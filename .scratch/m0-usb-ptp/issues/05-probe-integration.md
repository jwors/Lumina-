# 05: 探针全链路集成（清单屏）

**What to build:** 从用户视角（M0 主叙事完整版，真机 10 秒规则）：插线 → 授权 → 屏幕出现按拍摄日期分组的卡内照片清单（文件名+大小），顶部有总数/总体积统计，底部有滚动的最近会话日志；断开/休眠/权限缺失每种状态都有人话解释。

**Blocked by:** 02, 03, 04

**Status:** resolved（代码全链路完成，真机 10 秒规则复核并入 06 号）

## Comments

- 实现：`ProbeController.kt`（单线程顺序驱动：连接监视回调 → 状态机 → `UsbTransport.from` → `PtpSession`（getModel/listStorage/遍历所有存储分区合并清单）→ `ProbeUiState` 主线程回调；运行期异常全部人话化——相机休眠/拔线/PtpResponseException/非 PTP 模式）。`UsbCameraMonitor` 升级为回调携带 UsbDevice（授权后才能开传输）+ 广播异常兜底。
- `MainActivity.kt` 重写为集成屏：F·Grain 奶绿视觉（`F4F2EA` 底/`20251F` 墨/`767F72` 鼠尾草/`B9B3A6` 淡奶油），顶部总数/总体积统计、LazyColumn 按拍摄日期分组（无日期对象归"无拍摄日期"组），每行文件名+体积，底部常驻环形日志尾（最近 8 行，等宽字体）。
- 非 PTP 模式引导：探测到厂商自定义类（0xFF 疑似 MTP）时屏显"菜单切换 USB 连接方式"。
- 真机项（06 号复核）：10 秒规则、权限弹窗实弹、休眠恢复路径。
- 验证：`gradle :app:testDebugUnitTest` 33/33 绿；`gradle :app:assembleDebug` BUILD SUCCESSFUL 产出 `app-debug.apk`（8.7 MB）。
- 隐私不变：Manifest 无 INTERNET（ManifestPrivacyTest 绿）。
- Code Review (2026-11-02) 修复（两轴共同钉死 1 个致命 + 3 个次生）：
  1. **致命**：`readCatalog` 忘调 `ptp.open()`——首次真机连接必挂（IllegalStateException），已修：`ptp.open()` 前置于 getModel()。
  2. **spec 决策 4 / story 17 补实现**：MTP 类设备不再是只给提示——走 `android.mtp.MtpDevice` 兜底列清单（getStorageIds→getObjectHandles→getObjectInfo，日期 epoch 秒→毫秒），屏显并记日志"走了 MTP 兜底路径"。
  3. **spec 决策 7 / story 20 补实现**：设备/协议异常落私有目录 `filesDir/m0-probe.log`（时间戳行）。
  4. 授权弹窗错过时界面提供"重试授权"；存储合并抽到 `ObjectCatalog.merge`；`MAX_CONTAINER_BYTES` 收敛 256MiB→4MiB（M1 随需上调）。
  - MTP 父句柄语义（get 端 0 参数口径）真机校验证挂 06。

- [ ] 状态机驱动 UI：未连接 / 请求权限 / 已连接读取中 / 列表就绪 / 断开（含原因）
- [ ] 日期分组列表 + 头部总数/总体积统计
- [ ] 屏幕常驻最近若干行会话日志（环形缓冲的尾部）
- [ ] 相机端模式引导：若探测到 Z30 处于非 PTP 模式，屏幕提示切换
- [ ] 10 秒规则：冷插线到首屏清单 ≤ 10s（中低端机）
- [ ] Manifest 仍无 INTERNET 权限（验收时复核）
