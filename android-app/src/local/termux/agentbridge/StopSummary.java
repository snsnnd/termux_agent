package local.termux.agentbridge;

import org.json.JSONArray;
import org.json.JSONObject;

/** Builds a deterministic stop report from audit rows; no model call needed. */
final class StopSummary {
    private StopSummary() {}

    static String plain(JSONArray auditRows, String liveText) throws Exception {
        int total = 0;
        int outward = 0;
        int phone = 0;
        int shell = 0;
        int files = 0;
        int other = 0;
        if (auditRows != null) {
            for (int i = 0; i < auditRows.length(); i++) {
                JSONObject row = auditRows.optJSONObject(i);
                if (row == null) continue;
                total++;
                String tool = row.optString("tool", "");
                String action = row.optString("action", "");
                if (AuditRecord.outward(tool, action)) outward++;
                String capability = CapabilitySet.capabilityOf(tool);
                if (tool.equals("phone_act")) phone++;
                else if (capability.equals(CapabilitySet.SHELL)) shell++;
                else if (capability.equals(CapabilitySet.FILES)) files++;
                else other++;
            }
        }
        if (total == 0) return "已停止本次任务，尚未执行任何动作。";

        StringBuilder out = new StringBuilder();
        if (liveText != null && !liveText.trim().isEmpty()) {
            out.append("停前已显示：").append(AgentPolicy.cap(liveText.trim(), 800)).append("\n\n");
        }
        out.append("已停止本次任务：共执行 ").append(total).append(" 个动作，其中外发 ").append(outward).append(" 次");
        out.append("（phone_act ").append(phone).append(" 次、shell ").append(shell)
                .append(" 次、files ").append(files).append(" 次、其他 ").append(other).append(" 次）。");
        out.append("未完成的事项未知，请先检查当前界面状态再继续。");
        return out.toString();
    }
}
