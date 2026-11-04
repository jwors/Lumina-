# 01: Room 盘点基架 + Master 目录布局

**What to build:** 从 ADR-0004：引入 Room（2.6.x，KSP 或手工 DAO 二选一，工单内评估后落锤）；
建 MasterInventory 表（id、相对路径、原文件名、拍摄日期、字节数、写入时间戳）；
建目录布局工具函数（`Master/<yyyy-MM-dd>/<原名>`；拍摄日期缺省回退修改日期）；
提供 `MasterStore` 门面：`register(file, orig)`、`findBy(name)`、`snapshotList()`、`pruneAbsent()`（文件不在即条目作废的 reconcile）。

**Blocked by:** 无

**Status:** resolved

## Comments

- Room 2.6.1 + KSP（`2.0.20-1.0.25`，与 Kotlin 2.0.20 配对）入选：KSP 比 kapt 快且官方对齐 Kotlin 2.0。
- `master_inventory` 表 + DAO（insert/findByRelPath/all/deleteByRelPath/clear）+ AppDatabase 单例。
- 目录布局：`Master/<yyyy-MM-dd>/<原名>`；日期源 = 拍摄日期，缺省回退修改日期，双缺省 1970 占位（拍摄日期由调用方传，保持本层无 MTP 依赖）。
- 盘点存取走 `InventoryStore` 窄接口：Room DAO 实装 + `InMemoryInventoryStore`（JVM 测试用），`MasterStore` 门面（register 同名跳过 / MissingFile 拒绝 / snapshot 排序 / pruneAbsent 对账幂等）。
- 测试取舍：**不引 Robolectric**——纯逻辑（路径/排序/对账）用 `TemporaryFolder` 真文件 + 内存盘点即可 JVM 绿（8/8）；Room DAO 的真机行为留 06 号验收（`run-as` 查库）。
- 测试证据：`gradle :app:testDebugUnitTest` BUILD SUCCESSFUL，全套 **43/43 绿**（新增 MasterStoreTest 8 例）。

- [ ] Room 依赖与 schema（KSP/kapt 二选一并注明理由）
- [ ] MasterInventory 表 + DAO（增/查/全列出）
- [ ] Master 目录布局与日期源规则（拍摄日期→修改日期回退）
- [ ] MasterStore 门面 + pruneAbsent reconcile
- [ ] JVM 测试：不加 Room 则 Robolectric 或 fake 数据先行的取舍（写进票内以便复用）
