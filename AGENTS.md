# AGENTS.md — Lumina

> 本文件只做导航指针，不在文件里重复内容：每份真相只写一处。

## 项目

Lumina：徒步摄影者用 Android 应用（尼康 Z30 → 手机导出）。**仅 Android、仅 Kotlin、Jetpack Compose**（见 `docs/adr/0001*`）。

## 术语与规格

- 词汇表：`GLOSSARY.md`（Ingest / Master 等，写代码前先对齐词义）
- 产品规格：`docs/SPEC-Lumina.md`（里程碑 M0..M4 边界）
- 原则性 ADR：`docs/adr/`（0001 平台选型、0003 拒绝蓝牙）

## 构建与测试（本机环境缓存）

- 工程不配 wrapper：Gradle 在 `D:\Android\tools\gradle-8.9\bin\gradle.bat`；JDK 21 在 `D:\Android\jdk\jdk-21`（`%USERPROFILE%\.gradle\gradle.properties` 已配 `org.gradle.java.home`）
- 单测：`gradle -p <repo> :app:testDebugUnitTest --no-daemon`（结果 XML 在 `app\build\test-results\`）
- 回归基线（2026-11-09 拍板）：**功能动工前跑一遍全绿基线，收尾提交前再跑一遍对照**；`StateMergeTest` 锁发射台账（部分状态包不得冲掉跨页字段），`RegressionGuardTest` 锁 DB 纪律（bump version 必挂 Migration、`fallbackToDestructiveMigration` 永久禁用——它清过全库）
- 手机 adb：`D:\Android\Sdk\platform-tools\adb.exe`
- GitHub 网络被墙：**已配仓库级 `http.proxy=127.0.0.1:7890`**，push 不需要再带 `-c` 参数

## 真机调试流程（工单 06 类工作的标准姿势）

见 `docs/DEVICE-TESTING.md`：USB 口被相机占用时的 Wi-Fi 无线 adb + App 内 `m0-probe.log` 取回。

## 工单跟踪

- 当前里程碑工单在 `.scratch/<milestone-slug>/issues/`（spec.md 含状态与验收）。开工前先把票从 `ready-for-agent` 改 `claimed`，收尾时改 `resolved` 并附证据（测试命令 + 结果）。
- 每票成环顺序：implement → 双轴 code-review（Standards / Spec 并行子代理）→ findings 闭环 → 提交（信息引用票号）→ 更新 tracker。
