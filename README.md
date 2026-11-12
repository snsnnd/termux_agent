# Termux Agent / Pocket Agent

本仓库保留原有 Termux Python 项目，并新增独立 Android 原生 App。

## Android App

- 源码：[`android-app/`](android-app/)
- 使用与构建：[`android-app/README.md`](android-app/README.md)
- 当前能力与后续架构：[`android-app/ARCHITECTURE.md`](android-app/ARCHITECTURE.md)
- 验证范围与限制：[`android-app/VALIDATION.md`](android-app/VALIDATION.md)
- 更新记录：[`android-app/CHANGELOG.md`](android-app/CHANGELOG.md)

当前 App 使用轻量 Java agent、外部模型 API 与 Android 原生执行工具，不依赖 Termux 服务。
尚未接入 Pi/DSH、内嵌 Linux/Node、本地 OCR 或全部系统数据接口；不能宣称可以操作手机所有内容。
1.2 新增精简无障碍与独立授权的云端视觉补盲，流程与隐私边界见 [`android-app/ACCESSIBILITY_VISION.md`](android-app/ACCESSIBILITY_VISION.md)。
后续建议复用成熟引擎，把重点放在 Android 能力、明确授权和个人工作流上。

## 旧 Termux 项目

根目录原有 `main.py`、`agent/`、`tools/` 等仍为历史 Python 项目，本次新增 App 不覆盖这些文件。
设备上对 QQBot 文件的归档不等于删除本仓库历史代码。

## 凭据与用户数据

不要提交 API 密钥、聊天数据库、迁移数据、工作区私人文件、备份或 APK 签名私钥。
Android 运行时密钥使用 Keystore 加密；构建输出及本地私密文件不随 App 源码提交。

旧项目的 `config.json` 已被 Git 跟踪，忽略规则不能清除已提交的凭据；如含真实密钥，请轮换并单独评估历史清理。
本次保留既有历史，不擅自改写远程提交。
