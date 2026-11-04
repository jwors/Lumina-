# M1 · Ingest 复制 + Master 图库

> Status: ready-for-agent
> 上游：`docs/SPEC-Lumina.md` §3.1/§3.2（含 2026-11-04 修订）、`docs/adr/0004`、词汇 `GLOSSARY.md`
> grilling 结论：用户 7 问已拍板（2026-11-04），见本文"Decisions"

## Problem Statement

M0 证明了"手机能读卡"，但目前只能"看清单"；徒步现场真正需要的是：**勾选想带走的照片，把它复制进手机（Master），离线翻图库**。

## Solution

两个页面 + 一个复制引擎 + 一个本地库（F·Grain 视觉延续）：

- **① 卡内容页**（相机页 Tab）：M0 探针的清单升级为正式版——支持逐张勾选、全选、日期分组；Ingest 队列持久化（断线不丢）
- **② Master 图库**（图库 Tab）：Room 驱动，按拍摄日期分组浏览已 Ingest 的文件；JPEG 缩略图就地解码；NEF 显示"RAW 胶片框"占位卡（点击进"仅存原片"信息页）
- **③ Ingest 复制引擎**：`android.mtp.MtpDevice.importFile(objectHandle, destPath)` 作为引擎（真机已能枚举 739 对象，现成通道）；引擎用缝隙接口抽象（`IngestEngine`），Fake 实现供 JVM 单测（套用 M0 的 Transport 缝隙方法论）
- **④ 队列/进度**：底部固定进度条 + 每文件状态行（成功/失败/重试）；失败不阻塞他人；最终汇总"N 成功 / M 失败"

## User Stories

1. 作为徒步摄影者，我在卡内容页逐张勾选照片，点"导出"开始复制，我不用懂任何协议。
2. 作为徒步摄影者，我的勾选被保存下来；即使相机中途睡了/线拔了，重连后选择仍在，我继续导出。
3. 作为徒步摄影者，复制过程每张的成败我一目了然，失败的还能单独重试。
4. 作为徒步摄影者，我能在 Master 图库里按日期翻已导出的照片（JPEG），不用猜文件在哪。
5. 作为徒步摄影者，NEF 没有缩略图没关系——我认那个"RAW 胶片框"占位卡，知道它还在但我没有临时解析它。
6. 作为徒步摄影者，重复导出同一个文件不会覆盖我的库（同名跳过，提示"已在库中"）。
7. 作为 Arc agent，我可以对 Ingest 引擎跑 JVM 单测（Fake 引擎给定脚本、断言调用与顺序），不需要相机在旁边。

## Decisions（grilling 拍板，2026-11-04）

1. **两个页面**：相机 Tab + 图库 Tab（Q1）
2. **Ingest 引擎 = `MtpDevice.importFile`**；自研 PTP `GetObject` 引擎留作 M2/Phase 2 性能升级（Q2）
3. **逐张独立勾选**：NEF 与 JPEG 各自可选，不强制成对（Q3 → 冲突性修订：覆盖 SPEC-SPEC §3.1 原"成对"表述；GLOSSARY 已加"逐张独立勾选"条目）
4. **Master 库 = 目录 + Room 双轨**（Q4；详见 ADR-0004）：
   布局 `<私有>/Master/<yyyy-MM-dd>/<原文件名>`；Room 表 MasterInventory
5. 图库缩略图：JPEG 用系统 `BitmapFactory`（inSampleSize 控内存），NEF 占位卡，无 RAW 库（Q5）
6. Ingest 队列本地持久化（Room 表）；断线不清空、重连续行（Q6）
7. 进度条 + 每文件状态 + 失败单独重试 + 全部结束后汇总（Q7）

非博弈默认值（如不同意随时改）：
- 目录日期源 = 相机侧拍摄日期（MtpObjectInfo.dateCreated），缺省回退文件修改日期
- 同名"已存在"= **跳过并标记**，永不覆盖
- 全选 = 当前日期分组范围内的对象全部入队（不做跨日期全选）

## Implementation Decisions

*（M0 纪律延续：Transport 缝隙方法论 + JVM 单测优先）*

1. **IngestEngine 缝隙**：接口 `void 把 handle/object 的复制动作排队（enqueue）`、`复制一批（drain）`、进度回调 `onFileDone/onFileFail`——MtpImportEngine 是 Android 薄实现，FakeIngestEngine 是 JVM 真相
2. **Room** 引入 `androidx.room`（2.6.1）+ KSP？M1 工单 01 内部再定。（KSP 或 kapt 均可；首次引入会拖 build 时间，备选 luad think 手写 DAO 评估）
3. UI 沿 M0 Compose + F·Grain 主题（奶油底/墨绿文字）
4. 相机扫描结果与 MTP engine 的绑定在 io 线程；UI 独立（同 M0 纪律）

**Testing Decisions**：
- 全部 JVM：FakeIngestEngine + FakeCamera 的开头组合（脚本化 objects/handles）
- Room DAO 层：如果上 Room 则用 Robolectric（备选——首个踩坑热点）或内存 map fake
- 真机（工单 06）：复制 8 个文件（JPEG+NEF 混合）+ 断线续行 + Room 完整性检查

## Out of Scope

- 文件对象 Resize/MediaStore 写入（Master 不入系统相册；Work 才入，M2/M3）
- NEF 解码（Phase 2 / LibRaw 评估）
- GetObject 自研复制引擎的性能版（M2 再接.Transport）
- 生成式/Skill Card/Filter/WebDAV（M2/M3）

## Further Notes

- M0 探针的 UI/清单代码保留生长，但"相机页"从此走 ingest 队列；Master 图库是全新页面
- 真机验收标准：插线 → 勾选 8 个 → 导出 → 断线 → 重新插入 → 图库列出 8 个（JPEG 有缩略图、NEF 有占位） → 失败文件可重建
