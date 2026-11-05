# 03: 相机页正式化（勾选/全选/队列持久化）

**What to build:** M0 探针的相机清单页升级：F·Grain 主题下按日期分组的对象列表（文件名+体积+格式徽标）；
逐张勾选（复选框态由 Room 的 IngestQueue 表持久化）；
"全选本日 / 清空本日"；连接断开时队列不清，重连后表头提示"上次勾选还在"；
"导出 N 个" 按钮接 IngestEngine（跳工单 05 的进度 UI）。

**Blocked by:** 01, 02

**Status:** resolved

## Comments

- 双 Tab 骨架（相机/图库）+ 相机页 F·Grain 主题升级：按 dateKey 分组 + 每行勾选（material3 Checkbox）+ **全选本日/清空本日**（必达）。
- 队列持久化：`ingest_queue` 表（name 主键 + handle/size/日期/queuedAt）+ DAO（upsert/deleteByName/all/clear）；AppDatabase v2 + 破坏性迁移（开发期）。
- ProbeController 扩展：toggleCheck/toggleDay/startIngest/retryFailed；MTP 枚举成功即就位 `MtpImportEngine` 并**从 Room 重建 queue**（断线不清勾选 ✓）；engine 不在（纯 PTP 路径）时导出按钮禁用并提示"引擎未就绪（需 MTP 会话）"。
- 断线/重连文案：teardown 清会话与引擎；重连 initialScan 自扫描 + 引擎重建 + checkedNames 重新画框。
- 测试证据：50/50 绿（无新建测：UI 无 JVM 测试，Room 语义同 01/02 的 fake，真机留 06）；`assembleDebug` 通过；**已装上手机**（无线 adb Success）。
- 遗留：队列在 Room 而引擎是"内存镜像"——后续工单 05 若引入"重启 App 也能续传"需保证重连重建（已实现）。

- [ ] 对象列表 UI（日期分组、体积、JPEG/RAW 徽标）
- [ ] 勾选 → IngestQueue 表（持久化）
- [ ] 全选本日 / 清空本日
- [ ] 断线提示与续行文案
