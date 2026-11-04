# 06: 真机验收（Ingest 复制 + 图库 + 断线续行）

**What to build:** 在 Z30 上完成 stanđard 流程验收并记录结果：
插线 → 勾选 8 个（JPEG 5 + NEF 3 混合）→ 导出 → 复制中断测试（半途拔线）→ 重连续行 →
Master 图库核对（JPEG 缩略图、NEF 占位、统计数）→ Room/目录一致性 →
辅以 `adb shell run-as com.lumina` 拉文件清单确认布局。

**Blocked by:** 05

**Status:** ready-for-agent

- [ ] 8 文件导出全成功（JPEG 占多数）
- [ ] 半途拨出 → 重连续行（队列保留部分只是状态）
- [ ] 图库显示（缩略图 + NEF 占位 + 统计）
- [ ] 同名二次导出 = 跳过并提示
- [ ] Room 与目录一致性（pruneAbsent 清理试验）
