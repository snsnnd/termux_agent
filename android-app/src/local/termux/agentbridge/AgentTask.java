package local.termux.agentbridge;

import org.json.JSONObject;

/** One run lifecycle. Pure state machine so transitions can be tested on the JVM. */
final class AgentTask {
    enum Phase { QUEUED, PREPARING, RUNNING, WAITING_USER, PAUSED, ENDING, ENDED, INTERRUPTED, CANCELLED }

    final TaskSpec spec;
    Phase phase;
    long startedAt;
    long endedAt;
    boolean notified;

    AgentTask(TaskSpec spec) {
        this.spec = spec;
        this.phase = Phase.QUEUED;
        this.startedAt = System.currentTimeMillis();
    }

    private AgentTask(TaskSpec spec, Phase phase, long startedAt, long endedAt, boolean notified) {
        this.spec = spec;
        this.phase = phase;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.notified = notified;
    }

    boolean terminal() {
        return phase == Phase.ENDED || phase == Phase.INTERRUPTED || phase == Phase.CANCELLED;
    }

    boolean canTransitionTo(Phase next) {
        if (next == null || terminal()) return false;
        if (next == Phase.CANCELLED || next == Phase.INTERRUPTED) return true;
        switch (phase) {
            case QUEUED:
                return next == Phase.PREPARING;
            case PREPARING:
                return next == Phase.RUNNING;
            case RUNNING:
                return next == Phase.WAITING_USER || next == Phase.PAUSED || next == Phase.ENDING;
            case WAITING_USER:
                return next == Phase.RUNNING || next == Phase.PAUSED || next == Phase.ENDING;
            case PAUSED:
                return next == Phase.RUNNING || next == Phase.ENDING;
            case ENDING:
                return next == Phase.ENDED;
            default:
                return false;
        }
    }

    void advanceTo(Phase next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("Invalid run transition: " + phase + " -> " + next);
        }
        phase = next;
        if (terminal()) endedAt = System.currentTimeMillis();
    }

    JSONObject toJson() throws Exception {
        return spec.toJson()
                .put("phase", phase.name())
                .put("startedAt", startedAt)
                .put("endedAt", endedAt)
                .put("notified", notified);
    }

    static AgentTask fromJson(JSONObject row) throws Exception {
        TaskSpec spec = TaskSpec.parseJson(row);
        Phase phase = Phase.valueOf(row.optString("phase", Phase.QUEUED.name()));
        return new AgentTask(spec, phase, row.optLong("startedAt", System.currentTimeMillis()),
                row.optLong("endedAt", 0L), row.optBoolean("notified", false));
    }
}
