# 02: IngestEngine 缝隙 + MtpImportEngine 实装

**What to build:** M0 缝隙方法论在复制侧复刻：
`IngestEngine` 接口（`enqueue(handle/name/objectInfo)`、`drain(callback)`、进度回调）；
`FakeIngestEngine`（JVM 脚本化：给定对象清单和复制结果序列，断言调用顺序与汇总）；
`MtpImportEngine`（Android 薄实现：`MtpDevice.importFile(objectHandle, destPath)`，处理同名跳过、失败计数、每文件超时）。

**Blocked by:** 01

**Status:** resolved

## Comments

- `IngestEngine` 缝隙（enqueue 名字去重 / dismiss / drain 分段回调：start/ok/skip/fail/done(成功,跳过,失败)/pendingFails 失败保留）。
- `FakeIngestEngine`：JVM 真相引擎——复制即真实文件落盘（Master 布局）+ MasterStore 登记；每文件结局可脚本化（Success/Skip/Fail）。7 例验收：顺序回调、失败不阻塞且进 pendingFails、enqueue 同名去重、dismiss 精准移除、空队列零回调、已在盘收养不覆盖内容、Skip 语义不写库。
- `MtpImportEngine`（主代码薄实现）：`MtpDevice.importFile(handle, dest)`；同名跳过管线（已库=纯跳过；已在盘未登记=收养登记）；失败给原始原因进回调并保留待重试；MasterStore.findByRelPath 门面转发补齐。
- test 证据：`gradle :app:testDebugUnitTest` BUILD SUCCESSFUL，全套 **50/50 绿**（新增 IngestEngineTest 7 例）。
- 真机 importFile 行为落 03/06 号（手机 Wi-Fi adb 断连，装包待重连统一推）。

- [ ] IngestEngine 接口 + Fake 实装（JVM 单测 4+ 例：顺序、失败混入、汇总）
- [ ] MtpImportEngine：importFile 调用 + 同名跳过 + 失败计数
- [ ] 与 ADR-0004 的 MasterStore 串接（复制成功 = 目录落文件 + Room 登记）
