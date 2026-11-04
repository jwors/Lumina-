# ADR-0004: Master 库 = 目录布局 + Room 盘点

- **状态**: Accepted（M1 grilling 2026-11-04，用户拍板 Q4=A+B）
- **日期**: 2026-11-04

## Context（背景）

Master（已 Ingest 的原片文件）需要一个可浏览、可查询的本地收藏结构。备选路径：

- A. 只写文件目录（肉眼可查，但要图库检索/去重就得每次全目录扫描）
- B. 只建 Room 数据库（查询灵活，但用户脱离 App 走文件管理器时会失去物理对照）
- C. 混合（目录 + 数据库双轨）
- D. MediaStore/系统相册（Wizard：还会触发系统云相册自动备份，和 Master 语义冲突）

## Decision（决策）

**C = A + B 双轨**：

1. **目录布局（真相之一）**：`<私有目录>/Master/<yyyy-MM-dd>/<保留原文件名>`
   （拍摄日期优先；相机侧无拍摄日期时回退文件修改日期）
2. **Room 数据库（真相之二）**：一张 MasterInventory 表
   （id、相对路径、原文件名、拍摄日期、字节数、写入 时间戳、Association 摄影师说明（后续 M3））
   —— 作为图库查询 / 增量去重（同名跳过而非覆盖）/ Ingest 队列关联的索引。

两份都以"文件在即为准"（文件被手动清掉 = 盘点条目作废，下次扫描时 reconcile）。

## Consequences（后果）

- 正面：739+ 对象图库不需要每次磁盘扫描；去重 duck O(1)；
- 并发事项：Room 表是 **私有读写的真相**，目录是 **物理真相**——两者不一致时按"文件在即为准" reconcile。
- 测试影响：Room 需要 `androidx.room` + 测试端 Robolectric 或内存数据库测（M1 工单 01 内配套决定）。
- 若 M3 中 Master 移动到 WebDAV 备份扫出目录，会同步清理 Room（对账规则放 M3 spec）。

## 参考

- M1 grilling Q4 答案（A+B 均做）
- `GLOSSARY.md`: Master / Ingest Queue / 逐张独立勾选
