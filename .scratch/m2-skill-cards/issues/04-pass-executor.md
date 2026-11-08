# 04: Processing Pass 执行器 + 补跑队列

**What to build:** `ingest_smart_queue` 表（离线排队）；Pass 执行器 =
队列 × Card 步骤 × 对应 JPEG（长边 1024 压缩）→ LlmClient → Smart Result 落 `smart_results`；
串行 + 重试 1 次（2s 退避）；失败保留待手动重跑；
`ConnectivityManager` 监听 + 顶部横幅（网络可用+队列>0 → [补跑]）；云处理总开关联动挂起。

**Blocked by:** 01, 02, 03

**Status:** claimed

- [ ] 队列表 + 执行器（步骤→prompt→client→result 全链）
- [ ] 网络监听 + 横幅 + [补跑]
- [ ] 总开关挂起
- [ ] Fake 路径单测（离线排队/回网/重试/总开关关闭）
