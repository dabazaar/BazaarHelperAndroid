using BazaarGameShared.TempoNet.Enums;
using BazaarGameShared.TempoNet.Models;
using HarmonyLib;
using TheBazaar;
using TheBazaar.UI.EndOfRun;

namespace BazaarHelper;

internal static class SeasonRankCapture
{
    private static string? pendingRun;
    private static string? stamp;
    private static PlayerRankResponse? refreshedRank;
    private static bool enabled;
    private static Il2CppSystem.Action<PlayerRankResponse>? listener;

    internal static void Install(Harmony harmony)
    {
        var type = typeof(EndOfRunRankController);
        var init = AccessTools.Method(type, "Init") ?? throw new MissingMethodException(type.FullName, "Init");
        harmony.Patch(init, postfix: new HarmonyMethod(typeof(SeasonRankCapture), nameof(RankReady)));
        enabled = true;
    }

    internal static void Reset()
    {
        pendingRun = null;
        stamp = null;
        refreshedRank = null;
    }

    internal static void AwaitSettlement(string run)
    {
        if (!enabled || pendingRun == run) return;
        try
        {
            string session = NativeBridge.Session();
            if (session.Length == 0) return;
            if (listener == null)
                listener = (Action<PlayerRankResponse>)(value => {
                    if (pendingRun != null) refreshedRank = value;
                });
            // Account/cache resets can clear listeners. Refresh our registration for each run.
            ClientCache.Rank.Unsubscribe(listener);
            ClientCache.Rank.Subscribe(listener);
            pendingRun = run;
            stamp = session;
            refreshedRank = null;
        }
        catch (Exception error) { Recorder.Log.LogWarning("Rank listener unavailable: " + error.GetType().Name); }
    }

    private static void RankReady(EndOfRunRankController __instance, Il2CppSystem.Threading.Tasks.Task<bool> __result)
    {
        string? run = pendingRun;
        string? session = stamp;
        if (run == null || session == null || refreshedRank is not { } value) return;
        try
        {
            var awaiter = __result.GetAwaiter();
            void OnCompleted()
            {
                try
                {
                    if (awaiter.GetResult()) Persist(__instance, run, session, value);
                }
                catch (Exception error) { Recorder.Log.LogWarning("Rank capture unavailable: " + error.GetType().Name); }
            }
            if (awaiter.IsCompleted) OnCompleted();
            else awaiter.OnCompleted((Il2CppSystem.Action)(Action)OnCompleted);
        }
        catch (Exception error) { Recorder.Log.LogWarning("Rank completion listener unavailable: " + error.GetType().Name); }
    }

    private static void Persist(EndOfRunRankController controller, string run, string session, PlayerRankResponse value)
    {
        if (pendingRun != run || stamp != session || session != NativeBridge.Session() ||
            value.SeasonID <= 0 || value.Rank != controller.postRunRank ||
            value.Division != controller.postRunRankDivision || value.RankPoints != controller.postRunRankPoints) return;
        var season = ClientCache.CurrentSeason.Value;
        bool sameSeason = ClientCache.CurrentSeason.HasData && season.id == value.SeasonID;
        int position = controller.postRunLeaderboardPosition;
        NativeBridge.SaveRank(run, session, new {
            seasonId = value.SeasonID, rank = value.Rank.ToString(), division = value.Division,
            rankPoints = value.RankPoints, rating = value.Rating,
            leaderboardPosition = value.Rank == ERank.Legendary && position > 0 ? (int?)position : null,
            seasonName = sameSeason ? season.name : null,
            seasonTitle = sameSeason ? season.title : null,
            seasonStartedAt = sameSeason && season.start.Year > 2000 ? season.start.ToString("O") : null,
            observedAt = DateTimeOffset.UtcNow.ToString("O"),
        });
        Reset();
    }
}
