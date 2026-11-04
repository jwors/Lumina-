# 02: IngestEngine 缝隙 + MtpImportEngine 实装

**What to build:** M0 缝隙方法论在复制侧复刻：
`IngestEngine` 接口（`enqueue(handle/name/objectInfo)`、`drain(callback)`、进度回调）；
`FakeIngestEngine`（JVM 脚本化：给定对象清单和复制结果序列，断言调用顺序与汇总）；
`MtpImportEngine`（Android 薄实现：`MtpDevice.importFile(objectHandle, destPath)`，处理同名跳过、失败计数、每文件超时）。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] IngestEngine 接口 + Fake 实装（JVM 单测 4+ 例：顺序、失败混入、汇总）
- [ ] MtpImportEngine：importFile 调用 + 同名跳过 + 失败计数
- [ ] 与 ADR-0004 的 MasterStore 串接（复制成功 = 目录落文件 + Room 登记）
