# M5 · 遥控相机（BLE 配对 + Wi-Fi PTP）

> Status: in-progress — 代码与单测先行，真机验收见 issues/04
> 上游：`docs/SPEC-Lumina.md` §3.1/§7；`docs/adr/0006`（本里程碑的链路与权限决策）

## Problem Statement

徒步时相机常在脚架/远处，手机只用来取景与按快门。现有 Lumina 只有 USB 有线路径：相机插线后手机被"拴住"，且 USB 口被相机占用时没法同时充电/接电脑。用户要三件事：蓝牙连相机、看到当前镜头参数、手机遥控快门。

## Solution

相机页新增"遥控"入口，进入全屏遥控页：

1. **蓝牙配对**：扫描尼康 BLE 广播 → 四步 SnapBridge 握手 → 读相机 Wi-Fi 凭据 → 写 `0x2005` 唤醒热点。
2. **Wi-Fi 接入**：Android 13+ 用 `WifiNetworkSpecifier` 自动加入相机热点；12 用系统 Wi-Fi 面板人工一步。
3. **参数与快门**：连 PTP-IP → 轮询设备属性（焦距/光圈/快门/ISO/电量）→ 大快门按钮发 `InitiateCapture`。

BLE 不传输照片；照片仍走 USB/PTP（ADR-0003 不变）。

## User Stories

1. 作为徒步摄影者，我想在相机页点"遥控"，这样不用去找 USB 线。
2. 作为徒步摄影者，我想看到附近的尼康相机列表并点一台连接，这样不用记 MAC。
3. 作为徒步摄影者，我想连接时看到"已配对/正在唤醒 Wi-Fi/正在连相机"的分步状态，这样卡住时我知道卡在哪。
4. 作为徒步摄影者，我想在遥控页看到当前焦距、光圈、快门、ISO、电量，这样不用凑到相机屏幕前。
5. 作为徒步摄影者，我想按一个大按钮拍照，这样戴手套也能按。
6. 作为徒步摄影者，我想拍照后看到"已拍摄"反馈（快门计数/时间），这样确认真的拍了。
7. 作为徒步摄影者，我想参数每 2 秒自动刷新，这样转动变焦环后手机上能看到新焦距。
8. 作为徒步摄影者，我想在断线后看到原因与"重连"按钮，而不是白屏。
9. 作为徒步摄影者，我想第一次连接后记住相机，下次少走配对步骤。
10. 作为注重隐私的用户，我想知道 App 只把蓝牙用于配对、不用于定位，这样我敢授权。
11. 作为 Arc agent，我想 BLE 帧/Blowfish/凭据解密与 PTP-IP 编解码都有 JVM 单测，这样没相机也能回归。
12. 作为 Arc agent，我想协议异常落 `m5-remote.log`，这样真机现场的问题能回溯。

## Implementation Decisions

1. 链路分工按 ADR-0006：BLE 只配对/唤醒；参数与快门走 PTP-IP。
2. 复用 `PtpSession`：新增 `PtpIpTransport : Transport`（把 PTP 容器翻译成 PTP-IP 包），协议层零改动。
3. PTP 设备属性读 `GetDevicePropDesc`（自带数据类型与当前值），不硬编码类型；已知属性做单位换算（F 值 ×100、焦距 0.01mm、快门 1/10000s）。
4. 快门 = `InitiateCapture(0x100C)`，参数 `storageId=0xFFFFFFFF, format=0`；失败按响应码给人话。
5. 权限：`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`（31+，neverForLocation）、`NEARBY_WIFI_DEVICES`（33+，neverForLocation）；遥控要求 API 31+。见 ADR-0006。
6. 登录态：SSID/密码/配对身份存私有 `SharedPreferences`；密码不进日志。
7. UI：遥控页是全屏覆盖层（不新增第 5 个 Tab），下半屏放大快门；参数卡 2×3 网格。

## Testing Decisions

- 单测火力在字节层：配对帧编解码、Blowfish 已知向量、凭据 97B 布局解密、PTP-IP 包 golden、PTP-IP 对本地假相机（TCP loopback）的整事务、设备属性描述符解析与格式化。
- GATT/Wi-Fi 加入/真机快门由 `issues/04` 的真机验收单覆盖（日志 `files/m5-remote.log`）。
- 回归基线：动工前后各跑一次 `:app:testDebugUnitTest`，`ManifestPrivacyTest` 随权限白名单更新。

## Out of Scope

- BLE 传照片/RAW（永久拒绝，ADR-0003）
- 实时取景（Live View）串流
- iOS、相机入同一局域网（STA）模式、多相机同时连接
- 曝光参数写回（只读展示；改光圈/快门留后续）
- 后台常驻遥控
