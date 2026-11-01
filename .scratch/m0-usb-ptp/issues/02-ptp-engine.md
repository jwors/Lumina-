# 02: PTP 协议引擎（对伪造字节流）

**What to build:** 从用户视角（M0 主叙事第 1~3 拍的前置）：协议层能把"打开会话 → 查询型号 → 列存储分区"的请求编码成字节、把相机响应解码成结构化对象；全部行为由 JVM 单测对 FakeTransport 验证，真机未知数为零。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] OpenSession 请求编码 + 成功/失败响应解码，单测覆盖
- [ ] GetDevicePropValue（型号名 DeviceProp 0x... per PTP 标准）往返解析，单测覆盖
- [ ] GetStorageIDs 解析为结构化存储列表，单测覆盖
- [ ] 会话状态机雏形（未连接 → 已打开 → 已查询），非法操作序有明确错误
- [ ] 全部相关单测在 `gradle test` 下绿
