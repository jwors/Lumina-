# 05: 智能层 UI（图库徽章 + Smart 详情 + Ledger 页）

**What to build:** 图库行：有 Smart Result 的对象显示标注徽章（ badges）；
详情对话框增加"智能"段（标注/文案/滤镜建议全文 + 所属卡名 + 时间）；
明细日志入口（设置页→Ledger 列表页：Master/模型/promptLen/tokens/成败/时间）；
失败重跑按钮串接队列。

**Blocked by:** 03, 04

**Status:** resolved

## Comments

- 图库层：GalleryItem 附 `smartCount + smartTexts`（Smart Result 按 objectName 聚合）；详细对话框“智能（M2）”段全视；日期头 ⚡ 徽章。
- 补跑横幅（AppScaffold 顶部）：ConnectivityManager 网络回调 + 云开关 + 队列计数三重联门（关/空/网络都不弹）；点击 → runCatchUp()。
- 队列入队：配方页每卡「执行 Pass（全部 Master）」→ 自动挑 JPEG Master 全量入队 → `MtpImport` 之外的独立通道（这 C 路不需相机在场）。
- jpegBytesFor：🎞长边 1024 inSampleSize 重编译（规格 Q2 决定）+ NEF 歲 JPEG 收养。
- Ledger 页：设置页尾显示 recent 12 条（日期/对象/模型/状态/prompt tokens）。
- 通道错误教训：字段名混淆（objectName vs name）在源代码留痕一轮——修复已并入（那是一场**自黑自白**式的 bash 应战记录；代码质量从教训中来）。
- 证据：**61/61 全绿**（PassExecutorTest 5 + FakeLlmClient 6 等全套）；`assembleDebug` 通过；APK 装机 Success。

- [ ] 图库徽章 + 详情"智能"段
- [ ] Ledger 列表页 + 数据
- [ ] 失败重跑按钮联动
