# 原生版验证记录

## 1.1.0 更新验证

- 32 项纯 Java 策略检查通过（新增任务授权、持续自动与敏感动作优先级、确认工具、旧 work log 分离等）。
- 更新后设备侧 11 项原生检查再次通过。
- 用户确认更新后 `test` 会话明显更顺畅。
- 直接读取 App 当前数据库的诊断：当时打开的会话共 18 条、35021 字符，最大单条 14162 字符；实际首屏页加载 16 条。
- 该次后台历史查询约 1 ms，创建消息视图约 8 ms；不是整帧/滚动帧耗时，不据此声称所有场景不卡。
- 旧 Termux 同名 `test` 的 65 条/66902 字符是另一份历史数据，不与 App 当前会话混作一份。
- 实机确认默认 `phone_confirmation=task`，扩展自动 `extended_automation=false`；没有替用户开启高风险高级选项。
- 持续自动的风险对话框、通知栏批准/拒绝的完整用户交互仍待单独验收；本次不把编译通过或策略检查当作这些交互已经实测。
- 保留已有数据库及用户当前会话，没有重新导入或恢复用户已删除的旧会话。

以下为此前 1.0.1 的验证记录：

版本：Pocket Agent 1.0.1 / versionCode 11。
设备：HUAWEI MatePad Air，HarmonyOS 4.2，Android 兼容层 API 31。

## 已通过

- 官方 Android SDK 构建、APK v2/v3 签名验证。
- 电脑侧 17 项策略检查：HTTPS、URL 凭据/参数限制、路径越界、符号链接越界、修改权限分类、输出截断等。
- 设备侧 11 项原生检查：路径隔离、HTTPS、工具 schema 唯一性、文件写入/编号读取、精确编辑、快照撤销、grep、glob、App UID 系统 Shell 等。
- 修复了 Android `/data/user/0` 与 `/data/data` 私有目录别名造成的相对路径/撤销问题。
- 导入实时旧数据库的 89 条消息、1 份摘要、元数据与 4 个工作文件。没有修改旧 Termux 数据库。
- API 密钥使用 Keystore 加密；扫描解压后的所有 APK 条目，确认实际密钥未被打包。
- 从普通 App UID 调用迁移入口被拒绝；入口需要已授权 ADB shell，不能被一般 App 直接使用。
- 用户在 App 内首次发送、确认云端处理许可，并收到真实连接状态回复。
- 用户通过 App 下达“打开系统设置，进入显示和亮度，不修改任何设置”，完成操作确认并到达目标页面。
- 平板横屏深色模式界面截图检查：导航、会话、输入框、状态和触控区域可见，无终端或网页容器。
- 验证时 `com.termux` 进程没有运行，原生 App 进程在运行。原生服务不调用 Termux 或 Python 后端。
- 临时迁移文件已在验证后删除；共享存储不保留含 API 密钥的迁移包。

## 实现但未完整验收

- 文本输入、长按、其他第三方 App 的自绘控件、所有导航动作。
- 系统文件选择器的导入/会话导出完整交互。
- 小屏手机、最大系统字体、浅色主题、长期后台/重启恢复的实际设备矩阵。
- 网络中断/进程被杀的全部时序、Shell 孙进程退出和极大历史下的摘要压缩。

不把“已实现”当作“全部场景都测试过”。此版无 OCR、截图视觉理解、语音、Shizuku 或内嵌 Python。
后台存活受系统管理，取消不能回滚已发生的外部动作。

## 运行策略检查

```sh
javac -d /tmp/opencode/native-agent-policy-tests src/local/termux/agentbridge/AgentPolicy.java tests/PolicyTest.java
java -cp /tmp/opencode/native-agent-policy-tests local.termux.agentbridge.PolicyTest
```

设备原生自测仅在无任务执行时，由授权 ADB shell 调用 `MigrationProvider` 的 `selftest`。
自测只操作带 `__selftest_` 前缀的临时文件，不读取用户聊天内容。
