# 04: USB Transport 实现 + 连接状态机

**What to build:** 从用户视角：插上 OTG 线 → App 识别出"影像设备（PTP 类）"并显示已发现相机型号路径所需的真实传输层全部就绪；拔线界面归零、重插自动重连；若系统把它枚举为普通文件设备（MTP 类），记录实际路径供诊断。

**Blocked by:** 01

**Status:** resolved（代码 + 纯逻辑验收完成；真机插拔验证并入 06 号）

## Comments

- 实现四件 + 一接线：`UsbDeviceClassifier.kt`（纯逻辑：设备类/接口类 0x06=PTP 主路径；0xFF 厂商自定义=MTP 疑似诊断标记，不阻塞主路径）、`UsbCameraStateMachine.kt`（纯逻辑三态：NOT_CONNECTED → PENDING_PERMISSION → READY；denied 留在待授权供界面引导重试；合法序之外显式抛 IllegalStateException）、`UsbTransport.kt`（`Transport` 真实实现：claimInterface(force) → bulk 读写（5s 超时，IO 失败一律 IOException 带可读原因）→ 释放+close）、`UsbCameraMonitor.kt`（广播接线：ATTACHED 分类→PTP 才驱动状态机并弹运行时权限（Android 12+ FLAG_MUTABLE），DETACHED 状态归零、重插自动重走 attach；私有 permission action 显式 setPackage）。
- 验收 8 例全绿（分类 4 + 状态机 4；全套 33/33，`gradle :app:testDebugUnitTest` BUILD SUCCESSFUL）。
- 隐私不变：无 manifest 权限新增（requestPermission 为运行时机制），ManifestPrivacyTest 继续绿。
- 运行期 Android 胶水（claim/bulkTransfer 在 05 号接入后随 06 号真机验证）。
- Code Review (2026-11-02) 修复：bulk 端点只按 type=BULK 挑选（影像接口可能带中断端点）；`UsbTransportException(reason)` 类型化失败原因（替代 UI 字符串匹配，防文案漂移）；授权迟到（先拔线后回答）不再崩——permissionAnswer/attach 加状态守卫；新增 retryPermission()（denied 后界面可重拉弹窗）。

- [ ] UsbManager 设备发现：按设备类/接口过滤出相机类设备，暴露"未接入/已接入未授权/已授权"三态
- [ ] USB 权限请求流：未授权时触发系统弹窗并在探针界面给出引导文案
- [ ] `Transport` 的 USB 实现：bulk 传输读写 + 超时与重置（相机休眠导致 IO 异常有可读原因）
- [ ] 热插拔监听：拔线 → 状态归零；重插 → 自动尝试重连
- [ ] MTP 回退记录：若枚举为文件设备，明确标记诊断信息（不阻塞 PTP 主路径）
- [ ] 真机插拔一次可复现上面行为
