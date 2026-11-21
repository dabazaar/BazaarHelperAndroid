package com.bazzarhelper.android.runtime;

import com.bazzarhelper.android.HelperApplication;
import org.json.JSONObject;

/** The only managed hook -> app boundary; no HTTP is performed on a game callback. */
public final class CaptureBridge {
  public static void timer(int elapsedMilliseconds) {
    BattleTimerOverlay.update(elapsedMilliseconds);
  }

  public static String session() {
    try {
      return HelperApplication.engine().stamp();
    } catch (Exception e) {
      return "";
    }
  }

  public static boolean capture(String stamp, String data) {
    try {
      HelperApplication.engine().capture(stamp, new JSONObject(data));
      HelperApplication.wake();
      return true;
    } catch (Exception e) {
      android.util.Log.e("BazaarHelper", "Capture rejected: " + e.getClass().getSimpleName());
      return false;
    }
  }

  public static boolean begin(String data) {
    try {
      HelperApplication.engine().beginRun(new JSONObject(data));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean complete(String data) {
    try {
      JSONObject payload = new JSONObject(data);
      String run = payload.getString("runId");
      JSONObject rank = payload.optJSONObject("seasonRank");
      if (rank != null) {
        HelperApplication.engine().saveSeasonRank(run, payload.getString("stamp"), rank);
        HelperApplication.wake();
        return true;
      }
      Integer prestige = payload.has("prestige") && !payload.isNull("prestige") ? payload.getInt("prestige") : null;
      HelperApplication.engine().completeRun(run, prestige);
      HelperApplication.wake();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static void status(String value) {
    HelperApplication.engine().db.put("hookStatus", value);
    android.util.Log.i("BazaarHelper", value);
  }
}
