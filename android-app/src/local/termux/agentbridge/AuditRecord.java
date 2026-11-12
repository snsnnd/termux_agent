package local.termux.agentbridge;

import org.json.JSONObject;

/** One normalized audit row per tool attempt. Never stores message bodies. */
final class AuditRecord {
    static final String AUTO = "auto";
    static final String APPROVED = "approved";
    static final String DENIED = "denied";
    static final String BLOCKED_CAPABILITY = "blocked_capability";
    static final String BLOCKED_PACKAGE = "blocked_package";
    static final String ERROR = "error";

    private AuditRecord() {}

    static JSONObject of(String runId, String tool, String action, String target,
                         String decision, String resultCode, long durationMs) throws Exception {
        String safeAction = action == null ? "" : action;
        return new JSONObject()
                .put("run", runId == null ? "" : runId)
                .put("tool", tool == null ? "" : tool)
                .put("action", AgentPolicy.cap(safeAction, 200))
                .put("target", AgentPolicy.cap(target == null ? "" : target, 200))
                .put("decision", decision == null ? "" : decision)
                .put("result", resultCode == null ? "" : resultCode)
                .put("ts", System.currentTimeMillis())
                .put("duration", Math.max(0, durationMs));
    }

    static boolean outward(String tool, String action) {
        return AgentPolicy.outward(tool, action);
    }
}
