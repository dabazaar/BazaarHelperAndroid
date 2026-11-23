package com.bazzarhelper.android.test;

import android.app.Instrumentation;
import android.os.Bundle;
import com.bazzarhelper.android.data.RecordDatabase;
import com.bazzarhelper.android.sync.SyncEngine;
import java.util.UUID;
import org.json.JSONObject;

/** Real Android SQLite/JSON with an isolated database and a fake sync transport. */
public final class RunCompletionTests extends Instrumentation {
  private int checks, uploads;
  private JSONObject uploaded;
  @Override public void onCreate(Bundle b) { super.onCreate(b); start(); }
  private void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }
  @Override public void onStart() {
    Bundle result = new Bundle();
    String name = "run-completion-" + UUID.randomUUID();
    RecordDatabase db = null;
    try {
      db = new RecordDatabase(getTargetContext(), name, "com.bazzarhelper.android.completion-tests");
      db.session(new JSONObject().put("owner", "test-owner").put("generation", "test-generation")
          .put("token", "test-token").put("checkedAt", System.currentTimeMillis())
          .put("rotatedAt", System.currentTimeMillis()));
      SyncEngine.Transport transport = (method, path, token, body) -> {
        if (!"/device/sync".equals(path)) throw new AssertionError("Unexpected request " + path);
        int runs = body.getJSONArray("runs").length(), battles = body.getJSONArray("battles").length();
        if (runs > 0) { uploaded = body.getJSONArray("runs").getJSONObject(0); uploads++; }
        return new JSONObject().put("acceptedRuns", runs).put("acceptedBattles", battles);
      };
      SyncEngine engine = new SyncEngine(db, transport);
      engine.beginRun(new JSONObject().put("runId", "test-run").put("gameMode", "Ranked")
          .put("victories", 3).put("losses", 2).put("finalDay", 5).put("prestige", 5));
      engine.sync();
      check(!uploaded.has("gameProgress"), "without end retain legacy inference");
      engine.completeRun("test-run", 5);
      engine.sync();
      check(uploaded.getJSONObject("gameProgress").getBoolean("settled"), "positive prestige completion");
      check("loss".equals(uploaded.getString("result")), "loss outcome");
      check(uploads == 2, "completion resends acknowledged active run");
      engine.beginRun(new JSONObject().put("runId", "test-run").put("victories", 2)
          .put("losses", 1).put("finalDay", 3).put("prestige", 20));
      engine.sync();
      check(uploads == 2, "late progress cannot overwrite settlement");
      db.close();
      db = new RecordDatabase(getTargetContext(), name, "com.bazzarhelper.android.completion-tests");
      engine = new SyncEngine(db, transport);
      engine.sync();
      check(uploads == 2, "settlement and receipt survive reopening");
      engine.beginRun(new JSONObject().put("runId", "test-zero").put("gameMode", "Ranked")
          .put("victories", 4).put("losses", 3).put("finalDay", 7).put("prestige", 8));
      engine.completeRun("test-zero", 0);
      engine.sync();
      check(uploaded.getInt("prestige") == 0, "zero prestige retained");
      check(uploaded.getJSONObject("gameProgress").getBoolean("settled"), "zero prestige settled");
      engine.beginRun(new JSONObject().put("runId", "test-unknown"));
      engine.sync();
      check(!uploaded.has("gameProgress"), "missing cumulative counts not invented");
      result.putString("stream", "PASS " + checks + " run completion checks\n");
      finish(-1, result);
    } catch (Throwable e) {
      result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(e));
      finish(0, result);
    } finally {
      if (db != null) db.close();
      for (String suffix : new String[] {".sqlite", ".sqlite-wal", ".sqlite-shm", ".sqlite.upload.lock"})
        new java.io.File(getTargetContext().getNoBackupFilesDir(), name + suffix).delete();
    }
  }
}
