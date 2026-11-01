# 02: PTP 协议引擎（对伪造字节流）

**What to build:** 从用户视角（M0 主叙事第 1~3 拍的前置）：协议层能把"打开会话 → 查询型号 → 列存储分区"的请求编码成字节、把相机响应解码成结构化对象；全部行为由 JVM 单测对 FakeTransport 验证，真机未知数为零。

**Blocked by:** 01

**Status:** resolved

## Comments

- 实现四件套：`Ptp.kt`（操作码/响应码常量 + PtpResponseException）、`Packets.kt`（容器编解码，头部 = u32 length + **u16 type** + u16 code + u32 tid，PIMA 15740）、`MtpReader.kt`（DeviceInfo 数据集游标）、`PtpSession.kt`（状态机 Closed→Open→Ready→Closed + 事务引擎 + 分片重组）。
- 验收测试 8 例全绿（`gradle :app:testDebugUnitTest` BUILD SUCCESSFUL，14/14 含缝隙与隐私断言）：请求字节形状对照（encodeRequest vs 独立构建器）、错误响应抛 PtpResponseException 且状态保持 Closed、DeviceInfo 解析出 "NIKON Z 30" 且进入 Ready、StorageIDs 解析、未开会话拒绝调用、响应 tid 错位拒绝、跨 read 分片重组、CloseSession 请求字节 + close 后一切封死。
- 过程中抓到并修复 4 个真实 bug：容器 type 字段曾按 u32 编码（正确为 u16，解析端同步修正）、装配容器时第二次 `flip()` 把 limit 错设为 4（改 `rewind()`）、`body` 填满后未 `flip()` 导致 payload 丢失、`open()` 在 tid 递增后才校验响应 tid（校验移入 `send()` 内部、tid 递增之前）。
- 附带闭环：`PtpSession.open()` 负责调用 `transport.open()`，兑现 ticket 01 引入的缝隙契约。
- 真机验证依赖不涉及本票（纯 JVM）；USB transport 属 01 号安全边界，真实 transport 在 05/06 号。

- [x] OpenSession 请求编码 + 成功/失败响应解码，单测覆盖
- [x] 型号名查询解析（GetDeviceInfo 数据集 → Model 字段；M0 主叙事用 GetDeviceInfo 而非 DeviceProp，见 spec 决策），单测覆盖
- [x] GetStorageIDs 解析为结构化存储列表，单测覆盖
- [x] 会话状态机雏形（未连接 → 已打开 → 已查询），非法操作序有明确错误
- [x] 全部相关单测在 `gradle test` 下绿
