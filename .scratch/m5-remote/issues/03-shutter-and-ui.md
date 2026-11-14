# 03 · 遥控快门与遥控页 UI

> Status: claimed（代码完成；真机验收见 04）
> 上游：`.scratch/m5-remote/spec.md`、`docs/adr/0006`

## 范围

- `InitiateCapture(0x100C)`（storage=0xFFFFFFFF, format=0），失败码→人话
- 遥控页（全屏覆盖层）：连接分步状态、相机列表、参数 2×3 网格、下半屏大快门、拍照反馈、重连
- 控制器接线：`RemoteUi` 状态、`emitRemote()`、分步日志 `files/m5-remote.log`
- Wi-Fi 加入：33+ `WifiNetworkSpecifier` 自动加入并 `bindProcessToNetwork`；31/32 打开系统 Wi-Fi 面板

## 验收

- [x] 单测：快门操作码/参数编码、失败码人话
- [x] 真机：按快门 → 相机实际拍一张 → UI 出现"已拍摄"反馈（含时间/张数）
- [ ] 真机：拔相机电源/关热点 → UI 显示断线原因 + 重连可用
- [x] 权限拒绝路径：提示去设置，不崩（Compose 权限 launcher + Toast）

## 证据

`:app:testDebugUnitTest` 绿（快门参数在 `DevicePropsTest`，失败码在 `CameraParamsTest`）；
`:app:assembleDebug` 成功，APK 见 01 号单。

**真机（Z30）**：日志 `op=0x9207 rc=0x2001` + “快门已触发” ×2，相机实际出片。
真机修正：标准 `InitiateCapture(0x100C)` 在 Z30 上无响应（Read timed out）；改用尼康 `Capture(0x9207)` 后成功。
