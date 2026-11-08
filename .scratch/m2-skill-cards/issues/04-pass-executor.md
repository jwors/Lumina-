# 04: Processing Pass 执行器 + 补跑队列

**What to build:** `ingest_smart_queue` 表（离线排队）；Pass 执行器 =
队列 × Card 步骤 × 对应 JPEG（长边 1024 压缩）→ LlmClient → Smart Result 落 `smart_results`；
串行 + 重试 1 次（2s 退避）；失败保留待手动重跑；
`ConnectivityManager` 监听 + 顶部横幅（网络可用+队列>0 → [补跑]）；云处理总开关联动挂起。

**Blocked by:** 01, 02, 03

**Status:** resolved

## Comments

- **PassExecutor**（纯 JVM 可测，全部依赖构造注入——缝隙方法论第四次复刻）：一张卡 × 一批 Master = 串行步骤事务；每步骤一次 LLM 调用，Text → SmartResult，RateLimited/NetworkTimeout **自动重试 1 次**，Http 4xx 不重试直接出；**Ledger 每调用一条**（试例 3 行含两条失败尝试的也全记录）；GENERATIVE 步骤写占位（M4 接管，无 LLM 调用）；无 JPEG 源记失败"待 JPEG"。
- **Room v6**：`smart_results`（name/cardId/kind/text/model/时间）+ `processing_queue`（name 主键/cardId/queuedAt）+ DAOs。
- 测试 5 例（全绿）：全序成功（4 结果/4 Ledger/顺序 removeQueued）、限流重试一次即成（2 Ledger：限流+成功）、断网双试失败保留队列（2 Ledger、0 结果）、无 JPEG 零调用失败、GENERATIVE 占位零调用。
- 该机注意点继续：弱供电下相机*/“哑掉”*由上层兜（断网通道/带电 hub——M2 后期再校）。

- [ ] 队列表 + 执行器（步骤→prompt→client→result 全链）
- [ ] 网络监听 + 横幅 + [补跑]
- [ ] 总开关挂起
- [ ] Fake 路径单测（离线排队/回网/重试/总开关关闭）
