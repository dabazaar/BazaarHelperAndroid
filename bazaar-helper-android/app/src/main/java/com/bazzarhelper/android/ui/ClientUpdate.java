package com.bazzarhelper.android.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.TextView;
import android.widget.Toast;
import com.bazzarhelper.android.Diag;
import com.bazzarhelper.android.sync.HttpApi;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class ClientUpdate {
  private static final long WEEK_MS = TimeUnit.DAYS.toMillis(7);
  private static final long HOUR_MS = TimeUnit.HOURS.toMillis(1);
  private static final String LAST_PROMPT = "last_prompt";
  private static final String DOWNLOAD_ROOT =
      "https://4ork.cn/bazzar-assets/bazaar/client-updates/android/";

  private final Activity host;
  private final SharedPreferences prefs;
  private final String currentVersion;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private long lastAttempt = -HOUR_MS;
  private boolean checking;
  private boolean closed;
  private TextView button;
  private Sheet prompt;

  ClientUpdate(Activity host, String currentVersion) {
    this.host = host;
    this.currentVersion = currentVersion;
    prefs = host.getSharedPreferences("client_update", Context.MODE_PRIVATE);
  }

  void bind(TextView button) {
    this.button = button;
    button.setOnClickListener(v -> check(true));
    renderButton();
  }

  void checkAutomatically() {
    check(false);
  }

  void close() {
    closed = true;
    if (prompt != null) prompt.dismiss();
    worker.shutdownNow();
  }

  private void renderButton() {
    if (button == null) return;
    button.setEnabled(!checking);
    button.setText(checking ? "检查中…" : "检查更新");
    button.setAlpha(checking ? 0.65f : 1f);
  }

  private boolean foreground() {
    return host.hasWindowFocus() && !host.isFinishing();
  }

  private boolean recentlyPrompted() {
    long now = System.currentTimeMillis();
    long promptedAt = prefs.getLong(LAST_PROMPT, 0);
    return promptedAt > 0 && promptedAt <= now && now - promptedAt < WEEK_MS;
  }

  private void check(boolean manual) {
    if (closed || checking || (prompt != null && prompt.isShowing())) return;
    if (!manual) {
      long now = SystemClock.elapsedRealtime();
      if (now - lastAttempt < HOUR_MS || !foreground() || recentlyPrompted()) return;
      lastAttempt = now;
    }

    checking = true;
    renderButton();
    worker.execute(
        () -> {
          try {
            String path = "/client-update?platform=android&version=" + Uri.encode(currentVersion);
            JSONObject response = new HttpApi().request("GET", path, null, null);
            String latest = response.getString("latestVersion");
            Object available = response.get("updateAvailable");
            if (!latest.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || !(available instanceof Boolean))
              throw new IllegalStateException("Invalid client update response");
            String download = response.getString("downloadUrl");
            if (!download.equals(DOWNLOAD_ROOT + latest + "/BazaarHelper-" + latest + ".apk"))
              throw new IllegalStateException("Invalid update download location");
            host.runOnUiThread(() -> finish(manual, (Boolean) available, latest, download));
          } catch (Exception e) {
            Diag.e("client-update", "version check failed", e);
            host.runOnUiThread(() -> finish(manual, false, null, null));
          }
        });
  }

  private void finish(boolean manual, boolean available, String latest, String download) {
    if (closed) return;
    checking = false;
    renderButton();
    if (!foreground()) {
      if (!manual && available) lastAttempt = -HOUR_MS;
      return;
    }
    if (!available) {
      if (manual) notifyUser(latest == null ? "暂时无法检查更新，请稍后重试" : "当前已是最新版本");
      return;
    }
    if (!manual && recentlyPrompted()) return;
    prompt =
        new Sheet(host, null, "发现新版本")
            .message("当前版本 v" + currentVersion + "\n最新版本 v" + latest)
            .primary("下载更新", () -> openDownload(download))
            .secondary("稍后", null);
    prompt.show();
    if (prompt.isShowing()) prefs.edit().putLong(LAST_PROMPT, System.currentTimeMillis()).apply();
  }

  private void openDownload(String download) {
    try {
      host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(download)));
    } catch (ActivityNotFoundException e) {
      notifyUser("无法打开浏览器，请重试");
    }
  }

  private void notifyUser(String message) {
    Toast.makeText(host, message, Toast.LENGTH_LONG).show();
  }
}
