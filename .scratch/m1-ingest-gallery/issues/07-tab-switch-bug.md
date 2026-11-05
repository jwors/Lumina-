# 07: BUG 切图库再切回相机，对象列表为空

**What to fix:** 相机 Tab 流式列表正常显示（枚举中可见）；**切到图库 Tab 再切回相机，对象列表为空**（勾选框也消失）。

**Status:** ready-for-agent

## 已做的修复（未根治）
1. `emit()` 保留式合并（cameraItems/checkedNames/engineReady/galleryItems 空值承袭上次）——用户实测仍在
2. 枚举期"进行中"快照 emit + 引擎提前就位——用户实测仍在
3. 流式列表（每对象即时 publish）——枚举中能看到，切回仍空

## 待排查假设（下一轮优先）
1. **Compose 侧证据缺失**：给 `onState` 回调与 `publishListing` 各打 logcat（`android.util.Log.i("Lumina", ...)`），复现时直接对比
   - 假设 A：切回后 `state.cameraItems` 本身非空，是 **LazyColumn 没排 content**（key 冲突/`groupBy` 空 dateKey 分组回落），则问题在 UI 重组
   - 假设 B：某次 emission（图库对账/进度回写）在**切回后**又以空 cameraItems 覆盖——则问题在数据帧时序
2. 重点嫌疑：`loadGallery()` 的 reconcile（prune/adopt）在 io 线程跑完发出**图库专用 state**，随后的某个 emit 路径可能清理了 `lastSummary`/`lastTitle` 链——使 emitCameraPage 走"非 summary"分支渲染空列表
3. 复现路径固定（相机→图库→相机）， instrumentation 一次定案

**Blocked by:** 无（独立 bug）

## Acceptance
- [ ] 相机↔图库任意来回切换，相机列表/勾选状态**内容与数量不变**
- [ ] logcat/m0-probe.log 有可复现的时序证据并指明机制
