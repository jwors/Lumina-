# ADR-0006: 蓝牙只做配对唤醒，镜头参数与快门走 PTP

- **状态**: Accepted（M5 grilling 2026-11-14，用户拍板 A 方案）
- **日期**: 2026-11-14
- **上游**: `docs/SPEC-Lumina.md` §3.1/§7；`docs/adr/0003`（蓝牙批量导出拒绝）

## Context（背景）

用户要三个新功能：手机蓝牙连相机、读取并展示当前镜头参数、手机遥控快门。

尼康 Z 系的蓝牙（SnapBridge 的 LSS/GATT 方言）**只承载配对与状态**：四步握手、读加密的相机 Wi-Fi 凭据、写 `0x2005` 唤醒相机热点。它不提供镜头参数，也不是快门通道；ML-L7 遥控器走另一套 BLE 链路且只发快门/变焦，不回传参数。镜头参数与快门在 PTP 里：标准设备属性（F 值/焦距/快门/ISO/电量）与 `InitiateCapture`，可走 Wi-Fi PTP-IP（TCP 15740）或 USB PTP。

因此"纯蓝牙遥控"在 Z30 上不成立。真实链路 = **BLE 一键配对/唤醒 + Wi-Fi PTP 控制**。这与 ADR-0003 拒绝的"蓝牙批量导出"不冲突：本 ADR 不传输任何图像数据。

同时 ADR-0005 的隐私姿态是"除 INTERNET 外零危险权限"，BLE 与 Wi-Fi 直连必须突破它，需要一份新 ADR 明确边界。

## Decision（决策）

1. **BLE 仅作配对/唤醒通道**（包 `com.lumina.ble`）：扫描尼康 LSS 服务、四步配对握手、读 `0x2004` Wi-Fi 凭据（Blowfish 解密，LSS 会话密钥派生 + 定键兜底）、写 `0x2005=0x01` 唤起热点。**任何照片字节不得走 BLE。**
2. **镜头参数与快门走 PTP**：无线用 PTP-IP（`com.lumina.ptpip`，Nikon MTP/IP 方言：长度在前包头、双 TCP 通道、固定 Android GUID）；USB PTP 原路径保留。二者复用同一 `PtpSession`，靠 `Transport` 接缝切换链路（M0 的预留在此兑现）。
3. **权限白名单**（AndroidManifest）：
   - `BLUETOOTH_SCAN`（`neverForLocation`）、`BLUETOOTH_CONNECT`：API 31+ 的 BLE 扫描/连接。
   - `NEARBY_WIFI_DEVICES`（`neverForLocation`）：API 33+ 的 `WifiNetworkSpecifier` 自动加入相机热点。
   - `CHANGE_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`：`requestNetwork`/`bindProcessToNetwork` 的普通级权限（无运行时弹窗；Z30 真机实测缺 `CHANGE_NETWORK_STATE` 会直接拒绝入网）。
   - **不新增** `ACCESS_FINE_LOCATION`、存储、相机等任何其他危险权限。遥控功能要求 Android 12+（API 31）；31/32 上 Wi-Fi 加入降级为"打开系统 Wi-Fi 面板 + 展示 SSID/密码"的人工一步。
4. **隐私测试反转**：`ManifestPrivacyTest` 白名单扩为上述三项 + INTERNET/ACCESS_NETWORK_STATE，并继续断言**不存在定位/存储/相机权限**。日志、UI、异常文案不得输出 Wi-Fi 密码与凭据 blob 密文。
5. **凭据持久化**：SSID/密码只存应用私有 `SharedPreferences`，供下次直连；配对身份（deviceId/nonce/serial）同存，重连复用。

## Consequences（后果）

- 正面：三个功能可用且链路分工清晰；照片仍只走 USB/PTP，ADR-0003 的"蓝牙不搬 RAW"结论不变；权限叙事可审计（仅蓝牙配对 + 连相机热点，明确 `neverForLocation`）。
- 负面：首次使用多两次系统授权（附近设备/蓝牙）；BLE 方言是逆向所得（参考 MIT 项目 ZPill），Z30 固件若漂移需按日志修；API 31/32 的 Wi-Fi 加入不是全自动。
- 测试：BLE 字节层（配对帧/Blowfish/凭据布局）与 PTP-IP 编解码跑 JVM 单测；GATT 与真机链路由 `docs/DEVICE-TESTING.md` 的遥控验收单承担。

## 参考

- [ZPill](https://github.com/jaminxx-aha/ZPill)（MIT）：SnapBridge BLE 方言与 Nikon PTP-IP 方言的开源实现，本项目按其协议事实移植，不含其代码。
- `docs/SPEC-Lumina.md` §3.1、§6；`GLOSSARY.md`: 遥控（Remote）
