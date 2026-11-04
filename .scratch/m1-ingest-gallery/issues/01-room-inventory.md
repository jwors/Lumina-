# 01: Room 盘点基架 + Master 目录布局

**What to build:** 从 ADR-0004：引入 Room（2.6.x，KSP 或手工 DAO 二选一，工单内评估后落锤）；
建 MasterInventory 表（id、相对路径、原文件名、拍摄日期、字节数、写入时间戳）；
建目录布局工具函数（`Master/<yyyy-MM-dd>/<原名>`；拍摄日期缺省回退修改日期）；
提供 `MasterStore` 门面：`register(file, orig)`、`findBy(name)`、`snapshotList()`、`pruneAbsent()`（文件不在即条目作废的 reconcile）。

**Blocked by:** 无

**Status:** ready-for-agent

- [ ] Room 依赖与 schema（KSP/kapt 二选一并注明理由）
- [ ] MasterInventory 表 + DAO（增/查/全列出）
- [ ] Master 目录布局与日期源规则（拍摄日期→修改日期回退）
- [ ] MasterStore 门面 + pruneAbsent reconcile
- [ ] JVM 测试：不加 Room 则 Robolectric 或 fake 数据先行的取舍（写进票内以便复用）
