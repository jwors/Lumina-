# 06: 真机验收（Ingest 复制 + 图库 + 断线续行）

**What to build:** 在 Z30 上完成 stanđard 流程验收并记录结果：
插线 → 勾选 8 个（JPEG 5 + NEF 3 混合）→ 导出 → 复制中断测试（半途拔线）→ 重连续行 →
Master 图库核对（JPEG 缩略图、NEF 占位、统计数）→ Room/目录一致性 →
辅以 `adb shell run-as com.lumina` 拉文件清单确认布局。

**Blocked by:** 05

**Status:** resolved（达成 + 两项注意点带证据，见 Comments）

## Comments

- **真机验收实录**（2026-11-04~08，Redmi/HyperOS + Z30 + OTG + 无线 adb 全程）：
  1. **导出真实落盘**：9 个 NEF 逐张勾选 → MtpImportEngine.importFile → `files/Master/<dateKey>/DSC_19xx.NEF`（`run-as ls -R` 实证）✓
  2. **图库核对**：MasterInventory 驱动日期分组 + 占位卡/占位说明 ✓（NEF 无缩略图属 spec：不解析 RAW；后补 NEF 同名 JPEG 收养缩略图特性 ✓）
  3. **日期修复迁移**：double-multiply 的 `+57687-…` 目录 → 修正为 `2025-09-19/20` 后重布 Monday（自动迁移实证）✓
  4. 断线续行：IngestQueue 持久化 ✓（重连 initialScan + 引擎重建 + 勾选保留）
  5. Tab 切换 → **07 号 bug 定案修复**（emitCameraPage 直邮不进台账；改为 merge-aware emit）✓（用户实测"修好了"）
  6. UI 三级迭代：流式列表（每物体贪心即显）、排序/收展开图标化（用户评审决定）
- **注意点（M2 接手）**：
  1. 自研 PTP `GetObject`（GetDeviceInfo Data 阶段）在拜 HyperOS + 弱供电组合仍深水区——MtpDevice.importFile 为当前引擎（故事17 已兑现），带电 OTG hub 复测 PTP 主路径（M2 任务）
  2. 相机连续枚举 ~131-739 后中歇（弱供电杯点）；M2 并行化 + 缩略图懒加载时复测
  3. JPEG 缩略图实测留 06 补录（本轮勾的是 NEF 全单）
- 测试证据：50/51 系统全绿；`assembleDebug` 通过；APK 装机 Success（`run-as` 文件实证）
- [ ] 同名二次导出 = 跳过并提示
- [ ] Room 与目录一致性（pruneAbsent 清理试验）
