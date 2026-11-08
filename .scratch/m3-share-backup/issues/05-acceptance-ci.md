# 05: 真机验收 + M2-06 补验 + CI

**What to build:**
- CI：GitHub Actions 单测（gradle :app:testDebugUnitTest，runner 托管不涉本地代理）
- 真机全链：Master → 卡执行 → LUT Work 成片 → 分享两档 → 相册/云相册；WebDAV NEF 上云
- M2 票 06 降载两项补验：10 张 JPEG 批量一条链；断网排队 → 回网横幅 [补跑] 实录；失败重跑样例
- M3 retro.md 成账 + 里程碑状态头更新

**Blocked by:** 01–04

**Status:** ready-for-agent

- [ ] CI 绿（main push 触发）
- [ ] 全链真机实录 + 截图/log 取证
- [ ] 10 张批量 + 断网补跑两项闭环（M2 retro 遗留销账）
