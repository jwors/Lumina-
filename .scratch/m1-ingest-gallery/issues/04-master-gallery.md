# 04: Master 图库页

**What to build:** 新 Tab：Room 驱动的已 Ingest 库浏览；
按拍摄日期分组（沿用 M0 的 DateGroup 方法论）；
JPEG 即时 `BitmapFactory` 解码缩略图（inSampleSize 按视图宽度算，LruCache）；
NEF 显示"RAW 胶片框"占位卡（点击→"仅存原片"信息页：名字/体积/日期）；
 CRUD 与 pruneAbsent reconcile 在页面加载时执行；顶部统计（N 个 / 体积总和）。

**Blocked by:** 01

**Status:** resolved

## Comments

- 图库页面（F·Grain 延续）：Room 快照驱动 + 日期分组（groupBy dateKey）+ 顶部统计（N 个/体积）。
- 缩略图策略：JPEG 用 `BitmapFactory` + inSampleSize（target=128）+ `produceState` 后台解码；NEF/MP4 显示 "RAW 胶片框" 占位卡；点击行弹 material3 AlertDialog 详情（含"仅存原片"标注）。
- reconcile 双向都在：pruneAbsent（盘点失效）+ adoptExisting（磁盘孤儿收养，relPath 尊重磁盘物理位置；后缀白名单 JPG/JPEG/NEF/MP4 防误收 db/日志）。
- 无 LruCache（M1 简单版）——每次进 Tab 重解码，几十张规模可接受；M2 批量图库再上缓存。
- 真机：无线 adb 装机 Success（FCM..）；验证留工单 06。

- [ ] 图库 Tab（分组列表 + 顶部统计）
- [ ] JPEG 缩略图解码 + LruCache
- [ ] NEF 占位卡 + 详情页
- [ ] reconcile：目录在但 Room 不在 / Room 在但目录不在
