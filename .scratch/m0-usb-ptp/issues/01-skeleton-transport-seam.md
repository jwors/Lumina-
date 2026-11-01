# 01: 工程骨架 + Transport 缝隙 + 探针空屏

**What to build:** 从用户视角：App 能编译、装上 Android 手机后启动显示一个 Compose 空屏，文案为"未连接 · 请插入相机"；协议迭代不依赖真机的第一块基石就位——Transport 缝隙 + JVM 单测可运行。

**Blocked by:** None (can start immediately)

**Status:** resolved

## Comments

- 实现 + code-review 修复两阶段完成。测试证据：`gradle :app:testDebugUnitTest` BUILD SUCCESSFUL（FakeTransportTest 5 例 + ManifestPrivacyTest 1 例）。
- code-review 两轴 findings 全部实质处理：缝隙四元组 open/read/write/close 补齐、TransportContract 死代码删除、FakeTransport 短 buffer 不再吞字节（余量保留语义）、隐私断言自动化、JDK 路径迁 per-machine 配置、kotlin-test 死依赖剔除、风格选择器挪 .scratch/。
- 挂起事项移交：Session.kt 骨架由 02 号补全；MainActivity 状态占位由 04 号替换。

- [ ] Gradle Kotlin 工程（按 ADR-0001：Kotlin + Jetpack Compose），含 app 单模块，代码分 transport / ptp / probe 三包骨架
- [ ] `Transport` 缝隙接口定义：open / read 块 / write 块 / close，与 Android USB API 零耦合
- [ ] `FakeTransport`（测试用可编程字节流伪实现）+ 至少一个冒烟 JVM 单测通过
- [ ] 探针 Activity 显示连接状态占位（当前恒为"未连接"）
- [ ] AndroidManifest 不声明 INTERNET 权限（M0 隐私断言之一）
