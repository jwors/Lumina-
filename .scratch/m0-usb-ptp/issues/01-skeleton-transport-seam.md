# 01: 工程骨架 + Transport 缝隙 + 探针空屏

**What to build:** 从用户视角：App 能编译、装上 Android 手机后启动显示一个 Compose 空屏，文案为"未连接 · 请插入相机"；协议迭代不依赖真机的第一块基石就位——Transport 缝隙 + JVM 单测可运行。

**Blocked by:** None (can start immediately)

**Status:** claimed

- [ ] Gradle Kotlin 工程（按 ADR-0001：Kotlin + Jetpack Compose），含 app 单模块，代码分 transport / ptp / probe 三包骨架
- [ ] `Transport` 缝隙接口定义：open / read 块 / write 块 / close，与 Android USB API 零耦合
- [ ] `FakeTransport`（测试用可编程字节流伪实现）+ 至少一个冒烟 JVM 单测通过
- [ ] 探针 Activity 显示连接状态占位（当前恒为"未连接"）
- [ ] AndroidManifest 不声明 INTERNET 权限（M0 隐私断言之一）
