package local.termux.agentbridge;

import org.json.JSONArray;
import org.json.JSONObject;

/** JVM-only tests for Phase 1 pure logic. No Android SDK or device needed. */
public final class Phase1Test {
    static int passed;

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }

    interface Checked { void run() throws Exception; }

    static void rejects(Checked action, String label) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            passed++;
            return;
        }
        throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        capabilityTests();
        taskSpecTests();
        auditTests();
        summaryTests();
        stateMachineTests();
        System.out.println(passed + " phase 1 helper checks passed");
    }

    static void capabilityTests() {
        check(CapabilitySet.parse("").toolAllowed("shell"), "blank capability list allows all");
        check(CapabilitySet.parse("*").toolAllowed("phone_act"), "star allows all");
        CapabilitySet phoneOnly = CapabilitySet.parse("phone");
        check(phoneOnly.toolAllowed("phone_act"), "phone capability allows phone_act");
        check(phoneOnly.toolAllowed("phone_observe"), "phone capability allows observation");
        check(!phoneOnly.toolAllowed("shell"), "phone capability blocks shell");
        check(!phoneOnly.toolAllowed("read_file"), "phone capability blocks files");
        check(!phoneOnly.toolAllowed("phone_vision"), "vision requires both phone and vision");
        check(CapabilitySet.parse("phone,vision").toolAllowed("phone_vision"), "vision enabled with both");
        check(CapabilitySet.parse("files").toolAllowed("write_file"), "files capability allows writes");
        check(!CapabilitySet.parse("files").toolAllowed("phone_act"), "files capability blocks phone");
        check(CapabilitySet.parse("files").toolAllowed("confirm_action"), "confirmation is never blocked");
        check(!CapabilitySet.parse("phone").toolAllowed("unknown_tool"), "unknown tools are denied");
        check(CapabilitySet.packages("").packageAllowed("com.example.any"), "blank package list allows all");
        check(CapabilitySet.packages("*").packageAllowed("com.example.any"), "star allows every package");
        check(!CapabilitySet.packages("com.example.one").packageAllowed("com.example.two"), "unlisted package blocked");
        check(CapabilitySet.packages("com.example.one").packageAllowed("com.example.one"), "listed package allowed");
        check(CapabilitySet.packages("com.example.one").packageAllowed(""), "empty package always allowed");
        check(phoneOnly.capabilitiesCsv().equals("phone"), "capabilities serialize back to csv");
        check(CapabilitySet.packages("com.example.one").packagesCsv().equals("com.example.one"), "packages serialize back");
        try {
            CapabilitySet.parse("phone,telepathy");
            throw new AssertionError("unknown capability must be rejected");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
    }

    static void taskSpecTests() throws Exception {
        TaskSpec spec = TaskSpec.create("main", "打开设置", "phone", "com.android.settings");
        check(spec.runId != null && !spec.runId.isEmpty(), "run id generated");
        check(spec.capabilities.toolAllowed("phone_act"), "task capabilities parsed");
        check(!spec.capabilities.toolAllowed("shell"), "task capabilities narrow");
        check(spec.packages.packageAllowed("com.android.settings"), "task package allowed");
        check(!spec.packages.packageAllowed("com.example.other"), "task package blocked");
        JSONObject json = spec.toJson();
        TaskSpec roundTrip = TaskSpec.parseJson(json);
        check(roundTrip.runId.equals(spec.runId), "run id round trip");
        check(roundTrip.goal.equals(spec.goal), "goal round trip");
        check(roundTrip.capabilities.toolAllowed("phone_act") && !roundTrip.capabilities.toolAllowed("shell"), "capabilities round trip");
        check(!roundTrip.packages.packageAllowed("com.example.other"), "packages round trip");
        rejects(() -> TaskSpec.create("main", new String(new char[32001]).replace('\0', 'a'), "", ""), "oversized goal rejected");
    }

    static void auditTests() throws Exception {
        check(AuditRecord.outward("phone_act", "click"), "click is outward");
        check(AuditRecord.outward("phone_act", "tap"), "tap is outward");
        check(!AuditRecord.outward("phone_observe", ""), "observation is not outward");
        check(!AuditRecord.outward("phone_act", "back"), "navigation is not outward");
        check(AuditRecord.outward("shell", ""), "shell is outward");
        check(AuditRecord.outward("job", "kill"), "job kill is outward");
        check(!AuditRecord.outward("job", "tail"), "job tail is not outward");
        JSONObject row = AuditRecord.of("run-1", "phone_act", "click", new String(new char[1000]).replace('\0', 'x'), AuditRecord.APPROVED, "ok", 12);
        check(row.getString("run").equals("run-1"), "audit run id");
        check(row.getString("decision").equals(AuditRecord.APPROVED), "audit decision");
        check(row.getString("target").length() < 300, "audit target capped");
        check(row.getLong("duration") == 12, "audit duration");
    }

    static void summaryTests() throws Exception {
        check(StopSummary.plain(new JSONArray(), "").equals("已停止本次任务，尚未执行任何动作。"), "empty audit summary");
        JSONArray rows = new JSONArray()
                .put(AuditRecord.of("run-1", "phone_act", "click", "发送", AuditRecord.APPROVED, "ok", 5))
                .put(AuditRecord.of("run-1", "shell", "", "ls", AuditRecord.AUTO, "ok", 8))
                .put(AuditRecord.of("run-1", "read_file", "", "notes.md", AuditRecord.AUTO, "ok", 1));
        String summary = StopSummary.plain(rows, "正在读取");
        check(summary.contains("共执行 3 个动作"), "summary counts actions");
        check(summary.contains("外发 2 次"), "summary counts outward actions");
        check(summary.contains("phone_act 1 次"), "summary counts phone");
        check(summary.contains("shell 1 次"), "summary counts shell");
        check(summary.contains("files 1 次"), "summary counts files");
        check(summary.startsWith("停前已显示："), "summary keeps visible draft");
        check(summary.contains("请先检查当前界面状态"), "summary warns about state");
    }

    static void stateMachineTests() throws Exception {
        AgentTask task = new AgentTask(TaskSpec.create("main", "task", "", ""));
        check(task.phase == AgentTask.Phase.QUEUED, "new task queued");
        check(task.canTransitionTo(AgentTask.Phase.PREPARING), "queued to preparing");
        check(!task.canTransitionTo(AgentTask.Phase.ENDED), "queued cannot end directly");
        task.advanceTo(AgentTask.Phase.PREPARING);
        task.advanceTo(AgentTask.Phase.RUNNING);
        check(task.canTransitionTo(AgentTask.Phase.WAITING_USER), "running can wait");
        task.advanceTo(AgentTask.Phase.WAITING_USER);
        task.advanceTo(AgentTask.Phase.RUNNING);
        task.advanceTo(AgentTask.Phase.CANCELLED);
        check(task.terminal(), "cancelled is terminal");
        check(!task.canTransitionTo(AgentTask.Phase.RUNNING), "terminal cannot transition");
        rejects(() -> task.advanceTo(AgentTask.Phase.RUNNING), "terminal advance rejected");
        AgentTask fresh = new AgentTask(TaskSpec.create("main", "task", "", ""));
        fresh.advanceTo(AgentTask.Phase.PREPARING);
        fresh.advanceTo(AgentTask.Phase.RUNNING);
        fresh.advanceTo(AgentTask.Phase.ENDING);
        fresh.advanceTo(AgentTask.Phase.ENDED);
        check(fresh.terminal(), "ended is terminal");
    }
}
