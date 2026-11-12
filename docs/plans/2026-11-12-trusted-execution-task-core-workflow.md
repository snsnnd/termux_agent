---
intent: 在不改变包名、签名与安装方式的前提下，为 Android Agent 引入任务模型、能力白名单、审计日志、停止总结与进程重启恢复，作为 Mika 能力对标的第一阶段底座。
success_criteria:
  - 每次工具调用都写入审计记录，包含运行编号、工具、动作、目标、决策与结果码。
  - 任务级能力白名单与 App 白名单生效，白名单外的工具或目标无法执行，并记录 blocked 决策。
  - 用户停止任务后，对话中出现基于审计生成的停止总结，包含动作数与外发次数。
  - 进程被杀后重启，未结束的运行被标记为中断并只补告一次，不重复执行已完成步骤。
  - 纯 JVM 测试全部通过；有 Android SDK 时 build.py 构建成功并可安装。
risk_level: high
auto_approve: false
branch: trae/agent-AWjVlr
worktree: false
dirty_worktree: allow
---

## Steps

- [ ] **Step 1: 新增纯 Java 能力白名单 CapabilitySet**
action: 新建 `android-app/src/local/termux/agentbridge/CapabilitySet.java`。定义常量 `PHONE="phone"`、`VISION="vision"`、`FILES="files"`、`SHELL="shell"`；实现 `static CapabilitySet all()`、`static CapabilitySet parse(String csv)`（null 或空白返回 all）、`boolean allows(String capability)`、`static String capabilityOf(String toolName)`、`boolean toolAllowed(String toolName)`、`static CapabilitySet packages(String csv)`、`boolean packageAllowed(String pkg)`。映射规则固定为：文件工具 `read_file/list_files/write_file/edit_file/grep_files/glob_files/undo` 归 `files`；`shell/job` 归 `shell`；其余 `phone_*` 归 `phone`；`phone_vision` 必须同时启用 `phone` 与 `vision`；`confirm_action` 永不拦截。空 package 白名单表示不限制，`*` 也表示不限制。
loop: false
verify:
  type: artifact
  path: android-app/src/local/termux/agentbridge/CapabilitySet.java
  assert:
    kind: exists

- [ ] **Step 2: 新增纯 Java 任务描述 TaskSpec**
action: 新建 `android-app/src/local/termux/agentbridge/TaskSpec.java`。字段为 `runId`、`session`、`goal`、`capabilities`、`packages`；提供 `static TaskSpec create(String session,String goal,String capabilitiesCsv,String packagesCsv)`，runId 使用 `UUID.randomUUID().toString()`；提供 `static TaskSpec parseJson(JSONObject)` 与 `JSONObject toJson()` 用于持久化；`capabilities` 与 `packages` 类型为 `CapabilitySet`；goal 超过 32000 字符时抛出 `IllegalArgumentException`。
loop: false
verify:
  type: artifact
  path: android-app/src/local/termux/agentbridge/TaskSpec.java
  assert:
    kind: exists

- [ ] **Step 3: 新增纯 Java 审计记录 AuditRecord**
action: 新建 `android-app/src/local/termux/agentbridge/AuditRecord.java`。定义决策常量 `AUTO="auto"`、`APPROVED="approved"`、`DENIED="denied"`、`BLOCKED_CAPABILITY="blocked_capability"`、`BLOCKED_PACKAGE="blocked_package"`、`ERROR="error"`。实现 `static JSONObject of(String runId,String tool,String action,String target,String decision,String resultCode,long durationMs)`，其中 target 与 resultCode 用 `AgentPolicy.cap(value,200)` 截断；实现 `static boolean outward(String tool,String action)`，外发动作固定为 `phone_act` 的 `click/long_click/tap/set_text`、`shell`、`write_file`、`edit_file`、`undo`、`job` 的 `kill`、`confirm_action`。
loop: false
verify:
  type: artifact
  path: android-app/src/local/termux/agentbridge/AuditRecord.java
  assert:
    kind: exists

- [ ] **Step 4: 新增纯 Java 停止总结 StopSummary**
action: 新建 `android-app/src/local/termux/agentbridge/StopSummary.java`。实现 `static String plain(JSONArray auditRows,String liveText)`：统计总动作数、外发动作数、按工具计数；输出固定格式中文，例如 `已停止本次任务：共执行 N 个动作，其中外发 M 次（phone_act x 次、shell y 次、files z 次）。未完成的事项未知，请先检查当前界面状态再继续。`；若 liveText 非空则在开头附上 `停前已显示：` 加截断后的文本；审计为空时输出 `已停止本次任务，尚未执行任何动作。`。
loop: false
verify:
  type: artifact
  path: android-app/src/local/termux/agentbridge/StopSummary.java
  assert:
    kind: exists

- [ ] **Step 5: 新增纯 Java 运行状态机 AgentTask**
action: 新建 `android-app/src/local/termux/agentbridge/AgentTask.java`。定义枚举 `Phase{QUEUED,PREPARING,RUNNING,WAITING_USER,PAUSED,ENDING,ENDED,INTERRUPTED,CANCELLED}`；字段 `spec`、`phase`、`startedAt`、`endedAt`、`notified`；实现 `boolean canTransitionTo(Phase)`，允许 `QUEUED→PREPARING`、`PREPARING→RUNNING`、`RUNNING↔WAITING_USER`、`RUNNING→ENDING`、任意非终态→`CANCELLED`、任意非终态→`INTERRUPTED`、`ENDING→ENDED`，终态不可再转换；实现 `boolean terminal()`、`JSONObject toJson()`、`static AgentTask fromJson(JSONObject)`。
loop: false
verify:
  type: artifact
  path: android-app/src/local/termux/agentbridge/AgentTask.java
  assert:
    kind: exists

- [ ] **Step 6: 新增 Phase 1 测试与纯 JVM 测试 runner**
action: 新建 `android-app/tests/Phase1Test.java` 与 `android-app/tests/run_helpers.py`。Phase1Test 覆盖：CapabilitySet 的空白名单=全开、`phone` 白名单拒绝 shell、只开 `phone` 不开 `vision` 时拒绝 `phone_vision`、package 白名单拒绝未列包名、TaskSpec 的 CSV 解析与长度校验、AuditRecord 的外发分类、StopSummary 的计数与空审计文案、AgentTask 的合法与非法转换。run_helpers.py 复用 `run_efficiency.py` 的 org.json 下载与校验逻辑，只编译 `CapabilitySet/TaskSpec/AuditRecord/StopSummary/AgentTask/AgentPolicy` 与 `tests/Phase1Test.java` 到 `build/helper-classes`，然后运行 `local.termux.agentbridge.Phase1Test`，不需要 Android SDK。同时修改 `android-app/tests/run_efficiency.py`，把 `tests/Phase1Test.java` 加入 javac 源列表，并在测试循环中加入 `Phase1Test`。
loop: false
verify: python3 android-app/tests/run_helpers.py

- [ ] **Step 7: AgentStore 升级到 schema v2 并新增运行/审计表**
action: 修改 `android-app/src/local/termux/agentbridge/AgentStore.java`：构造器数据库版本从 1 改为 2；`onCreate` 与 `onUpgrade` 都执行幂等建表语句 `CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY,session TEXT,goal TEXT,phase TEXT,spec TEXT,started INTEGER,ended INTEGER,notified INTEGER DEFAULT 0)` 与 `CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,run TEXT,ts INTEGER,tool TEXT,action TEXT,target TEXT,decision TEXT,result TEXT,duration INTEGER)`，并建索引 `audit_run ON audit(run,id)`。新增方法：`startRun(AgentTask)`、`updateRunPhase(String id,String phase)`、`finishRun(String id,String phase)`、`markRunNotified(String id)`、`unfinishedRuns()`（返回 `ended IS NULL` 且 `notified=0` 的行）、`addAudit(JSONObject)`、`auditFor(String runId,int limit)`、`lastRunId()`。迁移只增表，不删除任何已有表或数据。
loop: false
verify:
  - type: shell
    command: grep -q "CREATE TABLE IF NOT EXISTS runs" android-app/src/local/termux/agentbridge/AgentStore.java
  - type: shell
    command: grep -q "CREATE TABLE IF NOT EXISTS audit" android-app/src/local/termux/agentbridge/AgentStore.java
gate: human

- [ ] **Step 8: 新增 AgentAudit 持久化门面**
action: 新建 `android-app/src/local/termux/agentbridge/AgentAudit.java`。构造器接收 `AgentStore`；实现 `void record(String runId,String tool,String action,String target,String decision,String resultCode,long durationMs)` 调用 `store.addAudit(AuditRecord.of(...))`；实现 `JSONArray forRun(String runId)`；实现 `JSONObject metrics(String runId)` 返回总调用数、各决策计数与外发次数。所有写库异常只记录到 `android.util.Log.e`，不让审计失败中断任务。
loop: false
verify:
  type: artifact
  path: android-app/src/local/termux/agentbridge/AgentAudit.java
  assert:
    kind: exists
gate: human

- [ ] **Step 9: 在 AgentPolicy 中补充外发与目标判定**
action: 修改 `android-app/src/local/termux/agentbridge/AgentPolicy.java`，新增 `static boolean outward(String name,String action)`，规则与 `AuditRecord.outward` 保持一致，并让 `AuditRecord.outward` 改为调用 `AgentPolicy.outward` 以避免两套规则；新增 `static boolean targetAllowed(String pkg,CapabilitySet packages)`，空包名放行，白名单为空或含 `*` 放行，否则按包名精确匹配。
loop: false
verify: python3 android-app/tests/run_helpers.py

- [ ] **Step 10: NativeTools 支持按任务过滤工具定义**
action: 修改 `android-app/src/local/termux/agentbridge/NativeTools.java`：保留现有 `static JSONArray definitions()` 作为全量入口，新增 `static JSONArray definitions(CapabilitySet allowed)`，用 `allowed.toolAllowed(name)` 过滤每个 schema；新增 `static void requireAllowed(CapabilitySet allowed,String tool)`，在工具不在白名单时抛出 `SecurityException("capability disabled for this task: "+tool)`。不改变任何现有工具的参数与返回格式。
loop: false
verify:
  - type: shell
    command: grep -q "definitions(CapabilitySet" android-app/src/local/termux/agentbridge/NativeTools.java
  - type: shell
    command: grep -q "requireAllowed" android-app/src/local/termux/agentbridge/NativeTools.java
gate: human

- [ ] **Step 11: AgentService 接入任务模型与过滤后的工具定义**
action: 修改 `android-app/src/local/termux/agentbridge/AgentService.java`：启动任务时从 Intent 读取 `capabilities` 与 `packages` 额外参数，缺省回落到 `store.prefs` 的 `task_capabilities` 与 `task_packages`，用 `TaskSpec.create` 生成 `AgentTask`，调用 `store.startRun(task)` 并把当前运行保存在实例字段 `currentTask`；把 `NativeTools.definitions()` 替换为 `NativeTools.definitions(task.spec.capabilities)`；结束时调用 `store.finishRun`。保留原有 `busy` 串行语义，并在已有任务时拒绝新任务。
loop: false
verify:
  - type: shell
    command: grep -q "NativeTools.definitions(task.spec.capabilities)" android-app/src/local/termux/agentbridge/AgentService.java
  - type: shell
    command: grep -q "startRun" android-app/src/local/termux/agentbridge/AgentService.java
gate: human

- [ ] **Step 12: 每次工具调用写入审计**
action: 修改 `android-app/src/local/termux/agentbridge/AgentService.java`：把 `approve(...)` 的返回值从 `boolean` 改为决策字符串（`AUTO`、`APPROVED`、`DENIED`），调用处据此判断是否执行并写审计。在工具分发处记录 `long auditStart=System.currentTimeMillis()`；调用 `NativeTools.requireAllowed` 前先判断，被拒绝时写 `BLOCKED_CAPABILITY` 审计并把 `capability disabled for this task` 作为工具结果；`DENIED` 写 `DENIED`；执行成功按 `approve` 返回值写 `AUTO` 或 `APPROVED`；执行抛异常写 `ERROR`。审计通过 `new AgentAudit(store).record(...)` 写入 runId、工具名、`args.optString("action")`、目标标签或包名、决策、结果码与耗时。结果码只取 `ok`、`denied`、`blocked`、`error` 四种，不写正文。
loop: false
verify:
  - type: shell
    command: grep -q "blocked_capability" android-app/src/local/termux/agentbridge/AgentService.java
  - type: shell
    command: grep -q "AgentAudit" android-app/src/local/termux/agentbridge/AgentService.java
gate: human

- [ ] **Step 13: App 级白名单拦截**
action: 修改 `android-app/src/local/termux/agentbridge/AgentService.java`：在 `tools.prepare` 得到 target 后，若 `target` 含 `package` 且 `AgentPolicy.targetAllowed(target.optString("package"),task.spec.packages)` 为 false，写 `BLOCKED_PACKAGE` 审计，并把工具结果设为 `blocked_package: target app is not allowed for this task`，不执行该动作；对 `phone_act` 的 `launch` 动作同样检查 `args.optString("package")`。
loop: false
verify:
  - type: shell
    command: grep -q "blocked_package" android-app/src/local/termux/agentbridge/AgentService.java
  - type: shell
    command: grep -q "targetAllowed" android-app/src/local/termux/agentbridge/AgentService.java
gate: human

- [ ] **Step 14: 停止总结接入**
action: 修改 `android-app/src/local/termux/agentbridge/AgentService.java`：在 `cancel()` 与异常收尾路径中，用 `store.auditFor(runId,200)` 调用 `StopSummary.plain` 生成总结，作为 `notice` 消息写入当前会话，并调用 `store.finishRun(runId,"CANCELLED")`；正常结束时写 `ENDED`。总结只在存在当前运行时写一次，避免重复。
loop: false
verify:
  - type: shell
    command: grep -q "StopSummary.plain" android-app/src/local/termux/agentbridge/AgentService.java
  - type: shell
    command: grep -q "finishRun" android-app/src/local/termux/agentbridge/AgentService.java
gate: human

- [ ] **Step 15: 进程重启恢复与旧偏好兼容**
action: 修改 `android-app/src/local/termux/agentbridge/AgentService.java`：`onCreate` 调用 `store.unfinishedRuns()`，对每条未结束运行标记 `INTERRUPTED`、向其 session 写入 `[上次任务因进程退出而中断。已执行的操作可能仍然生效，请先检查当前状态。]`、调用 `markRunNotified` 去重；`onDestroy` 把当前运行标记 `INTERRUPTED`。保留旧 `running_session` 偏好值的一次性兼容补告：仅当 runs 表没有未结束记录且该键非空时写一次 notice，随后清除该键。删除新代码对 `running_session` 的写入。
loop: false
verify:
  - type: shell
    command: grep -q "unfinishedRuns" android-app/src/local/termux/agentbridge/AgentService.java
  - type: shell
    command: grep -q "markRunNotified" android-app/src/local/termux/agentbridge/AgentService.java
gate: human

- [ ] **Step 16: 更新文档与验证记录**
action: 在 `android-app/CHANGELOG.md` 顶部新增 `# 1.3.0` 段落，列出任务模型、能力与包白名单、审计、停止总结、重启恢复；在 `android-app/VALIDATION.md` 末尾新增 `## Phase 1 验证` 段落，写明 `python3 android-app/tests/run_helpers.py` 的结果、是否执行 APK 构建、真机设备与未验证项；不要夸大已完成的验证。
loop: false
verify:
  - type: shell
    command: grep -q "1.3.0" android-app/CHANGELOG.md
  - type: shell
    command: grep -q "Phase 1 验证" android-app/VALIDATION.md

- [ ] **Step 17: 全量验证与构建**
action: 先运行 `python3 android-app/tests/run_helpers.py` 确认纯 JVM 测试通过；再设置 `BUILD_TOOLS` 与 `ANDROID_JAR` 环境变量（分别指向 Android build-tools 34 与 android.jar），运行 `python3 android-app/build.py --tools "$BUILD_TOOLS" --android "$ANDROID_JAR"`；若环境变量缺失，停止并请求用户提供路径，不得伪造构建结果。构建成功后运行 `python3 android-app/tests/run_efficiency.py --android "$ANDROID_JAR"`；最后在至少一台 Android 12+ 真机上安装，执行一次普通任务、一次越权工具调用、一次停止、一次杀进程重启，记录四条结果。
loop: false
max_iterations: 3
verify:
  - type: shell
    command: python3 android-app/tests/run_helpers.py
  - type: shell
    command: python3 android-app/tests/run_efficiency.py --android "$ANDROID_JAR"
  - type: human-review
    check: 真机四条验收（普通任务、越权拒绝、停止总结、重启补告）全部有记录
gate: human
