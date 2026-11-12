package local.termux.agentbridge;

import org.json.JSONObject;

import java.util.UUID;

/** Immutable description of one agent run and its allowlists. */
final class TaskSpec {
    static final int MAX_GOAL_CHARS = 32000;

    final String runId;
    final String session;
    final String goal;
    final CapabilitySet capabilities;
    final CapabilitySet packages;

    private TaskSpec(String runId, String session, String goal, CapabilitySet capabilities, CapabilitySet packages) {
        this.runId = runId;
        this.session = session;
        this.goal = goal;
        this.capabilities = capabilities;
        this.packages = packages;
    }

    static TaskSpec create(String session, String goal, String capabilitiesCsv, String packagesCsv) {
        if (goal != null && goal.length() > MAX_GOAL_CHARS) {
            throw new IllegalArgumentException("Task text exceeds " + MAX_GOAL_CHARS + " characters");
        }
        return new TaskSpec(UUID.randomUUID().toString(), session == null ? "main" : session,
                goal == null ? "" : goal, CapabilitySet.parse(capabilitiesCsv), CapabilitySet.packages(packagesCsv));
    }

    static TaskSpec parseJson(JSONObject row) throws Exception {
        String runId = row.optString("runId", "");
        if (runId.isEmpty()) runId = row.optString("id", "");
        if (runId.isEmpty()) throw new IllegalArgumentException("Run id is required");
        String session = row.optString("session", "main");
        String goal = row.optString("goal", "");
        CapabilitySet capabilities = CapabilitySet.parse(row.optString("capabilities", ""));
        CapabilitySet packages = CapabilitySet.packages(row.optString("packages", ""));
        return new TaskSpec(runId, session, goal, capabilities, packages);
    }

    JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("runId", runId)
                .put("session", session)
                .put("goal", goal)
                .put("capabilities", capabilities.capabilitiesCsv())
                .put("packages", packages.packagesCsv());
    }
}
