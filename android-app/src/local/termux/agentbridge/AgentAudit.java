package local.termux.agentbridge;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** Thin persistence facade for per-tool audit rows. Audit failures never stop a task. */
final class AgentAudit {
    private static final String TAG = "AgentAudit";

    private final AgentStore store;

    AgentAudit(AgentStore store) {
        this.store = store;
    }

    void record(String runId, String tool, String action, String target,
                String decision, String resultCode, long durationMs) {
        try {
            store.addAudit(AuditRecord.of(runId, tool, action, target, decision, resultCode, durationMs));
        } catch (Exception error) {
            Log.e(TAG, "audit write failed", error);
        }
    }

    JSONArray forRun(String runId) {
        try {
            return store.auditFor(runId, 500);
        } catch (Exception error) {
            Log.e(TAG, "audit read failed", error);
            return new JSONArray();
        }
    }

    JSONObject metrics(String runId) {
        JSONObject out = new JSONObject();
        int auto = 0;
        int approved = 0;
        int denied = 0;
        int blocked = 0;
        int failed = 0;
        int outward = 0;
        JSONArray rows = forRun(runId);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String decision = row.optString("decision", "");
            if (decision.equals(AuditRecord.AUTO)) auto++;
            else if (decision.equals(AuditRecord.APPROVED)) approved++;
            else if (decision.equals(AuditRecord.DENIED)) denied++;
            else if (decision.equals(AuditRecord.BLOCKED_CAPABILITY) || decision.equals(AuditRecord.BLOCKED_PACKAGE)) blocked++;
            else if (decision.equals(AuditRecord.ERROR)) failed++;
            if (AuditRecord.outward(row.optString("tool", ""), row.optString("action", ""))) outward++;
        }
        try {
            out.put("total", rows.length()).put("auto", auto).put("approved", approved)
                    .put("denied", denied).put("blocked", blocked).put("failed", failed).put("outward", outward);
        } catch (Exception ignored) {}
        return out;
    }
}
