# 07: BUG 切图库再切回相机，对象列表为空

**What to fix:** 相机 Tab 流式列表正常显示（枚举中可见）；**切到图库 Tab 再切回相机，对象列表为空**（勾选框也消失）。

**Status:** resolved

## Comments

- **根因（ instrumentation 定案）**：`onState` 帧序显示 `cameraItems=732` 发出后，切图库的下一帧将 `cameraItems` 清成 0——**emitCameraPage() 是直邮（不经 lastEmitted 台账）**，相机列表从未进入 merge 基线，任何文本类 emit 的合并都无从保留。
- **修复**：emitCameraPage 改走统一 merge-aware `emit()`（全量字段直供 + 台账登记）；发过即入账，后续合并基线里 cameraItems 永远是最新真实值。
- 验证：装机后用户实测「Tab 修复了」✓（2026-11-08）。
- 测试：50/50 绿；logcat 帧序证据入案（Lumina.UI 标签）。
