# 04 · 真机验收（Z30）

> Status: resolved（Wi-Fi 路径全过；BLE 自动唤醒按固件限制关闭）
> 上游：`.scratch/m5-remote/spec.md`

## 前置

- 手机 Android 12+，蓝牙开；Z30 电量足、已退出 SnapBridge 连接（相机的蓝牙同一时刻只认一个控制器）。
- 装 debug 包：`gradle -p <repo> :app:assembleDebug` → `adb install -r`（必须先 assemble，M0 教训）。

## 步骤与判据

1. 相机：`网络菜单 → 连接至智能设备`，停在配对页。
2. 手机：相机页 → 遥控 → 扫描 → 点 `Z 30 …`。
3. 判据：状态走到「已配对」；日志出现 `stage1/stage2/stage3/stage4` 与 `0x2004 解密命中`（不得出现密码）。
4. 判据：Wi-Fi 自动/手动加入后，参数卡出现焦距/光圈/快门/ISO/电量；转焦后 ≤2s 刷新。
5. 判据：按快门，相机拍下一张；UI 出现「已拍摄」。
6. 判据：关相机 Wi-Fi 或走远，UI 显示断线原因；重连能回到参数页。
7. 回归：USB 导出仍可用（跑一次相机页清单）。

## 已知风险 / 现场处置

- **相机 BLE 已被 SnapBridge 占用**：Z30 同一时刻只认一个蓝牙控制器。先关掉 SnapBridge（或断开它的连接），再扫。
- **stage1 写入被拒（status=5/137）**：相机可能要求系统级 bonding。去 设置→蓝牙 手动配对 `Z 30 …`，回来重试；仍失败请把 `m5-remote.log` 发回。
- **握手校验失败（salt 未命中）**：固件方言漂移。日志里 stage2 原帧足够定位，先别反复重试（每次都会被踢）。
- **凭据解密未命中**：Z30 可能用 LSS 会话密钥变体。先用手动 Wi-Fi 填一次密码，把参数/快门跑通；BLE 凭据留作二期。
- **PTP 连不上**：确认手机确实在相机热点上（系统 Wi-Fi 显示已连接），且没有别的 App 占着 15740（SnapBridge 前台会抢单连接槽）。

## 取证

- `adb shell run-as com.lumina cat files/m5-remote.log`
- 失败时同时取 `adb logcat -v time | findstr /i "Lumina BlePlugin"`

## 结果

- 设备：Xiaomi 24122RKC7C（HyperOS，Android 14）
- **Wi-Fi 手动接入路径已通过**：相机开热点 → 手机加入 → PTP-IP `192.168.1.1:15740` → 型号 `Z 30` → 参数 f/4、1/10、ISO 400、电量 100% → `0x9207` 快门成功（相机实际出片）×2
- **BLE 自动唤醒未闭环**：配对/凭据/SSID 均正确，但 `0x2005` 后相机不广播热点；目前靠手动开相机 Wi-Fi。下一步候选：经典蓝牙 bonding。
- 未验：断线重连提示、USB 回归（待补）
