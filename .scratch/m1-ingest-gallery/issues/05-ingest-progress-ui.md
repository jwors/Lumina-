# 05: Ingest 进度 UI + 失败重试

**What to build:** 导出执行时的旅程：底部固定进度条 + 当前文件名滚动 +
队列里每文件的状态行（待复制/成功/失败）+ 失败可单独重试按钮；
全部完成后顶部汇总"N 成功 / M 失败"；失败文件保留队列状态可手动重跑；
IngestEngine 的进度回调经主线程回调进 Compose 状态。

**Blocked by:** 03, 02（引擎依赖）

**Status:** resolved

## Comments

- 进度数据包 `IngestProgress`（running/done/total/currentName/statuses map）进 ProbeUiState；emitCameraPage 全量快照每事件上屏。
- UI：底部固定进度条（material3 lambda progress）+ "正在复制：<文件>"当前行 + 每文件行状态文本：`复制中…` / `✓ 已入库` / `已在库（跳过）` / `✗ 原因`；失败行内嵌"重试"小按钮 → `engine.retryOne(item)`（单文件引擎重试，不走整轮）。
- 重试语义：MtpImportEngine.retryOne / FakeIngestEngine.retryOne 都实现——已在盘走收养跳过、失败保留 pendingFails；Fake 的 outcomes 改为 `var`，测试可中途改脚本验证"第二次成功"。
- 断开 UI 降级：相机跑了/线拔了时 retryOne 返回"camera refused"进入状态行（重试按钮仍在）。
- 测试证据：50/50 绿（IngestEngineTest含 retry 语义验证 7 例中已有覆盖）。
- 真机（06）：导出/重试/汇总的整机流程待线。

- [ ] 底部固定进度条 + 当前行
- [ ] 每文件状态行 + 单独重试
- [ ] 结束汇总
- [ ] 真机断开中途的 UI 降级（相机跑了/线拔了时给"重试"按钮而非空错误）
