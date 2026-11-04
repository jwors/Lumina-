# 04: Master 图库页

**What to build:** 新 Tab：Room 驱动的已 Ingest 库浏览；
按拍摄日期分组（沿用 M0 的 DateGroup 方法论）；
JPEG 即时 `BitmapFactory` 解码缩略图（inSampleSize 按视图宽度算，LruCache）；
NEF 显示"RAW 胶片框"占位卡（点击→"仅存原片"信息页：名字/体积/日期）；
 CRUD 与 pruneAbsent reconcile 在页面加载时执行；顶部统计（N 个 / 体积总和）。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 图库 Tab（分组列表 + 顶部统计）
- [ ] JPEG 缩略图解码 + LruCache
- [ ] NEF 占位卡 + 详情页
- [ ] reconcile：目录在但 Room 不在 / Room 在但目录不在
