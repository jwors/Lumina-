# M6 · 遥控进阶（参数/间隔/取景/回传/包围）

> Status: planning
> 上游：`docs/SPEC-Lumina.md` §3.1、`docs/adr/0006`、`.scratch/m5-remote/`
> 参考：ZRelay 3.0.87 真机 APK 静态盘点（只取协议事实与功能清单，不搬代码）

## Problem Statement

M5 已经打通「相机 Wi-Fi + PTP-IP」：镜头参数只读、手机按快门。但徒步/脚架场景还缺四类能力：

1. 手机上调光圈/快门/ISO/曝光补偿，不用凑到相机前；
2. 定时/间隔拍摄（自拍、延时、合影）；
3. 实时取景 + 触控对焦（构图、确认对焦）；
4. 拍完自动把这张 JPEG 回传到手机；进阶还有曝光/对焦包围、云创 Picture Control 导入。

## 参考事实（ZRelay 3.0.87 dex）

- 尼康私有操作码族存在：`StartLiveView`、`EndLiveView`、`GetLiveViewImage(Ex)`、`AfDrive`、`AfDriveCancel`、`ChangeAfArea`、`ChangeCameraMode`、`Capture`、`GetDevicePropValueEx`、`SetDevicePropValueEx`、`GetDevicePropDescEx`。
- 标准 `SetDevicePropValue(0x1017)` 可能被相机拒绝，需试尼康 `SetDevicePropValueEx`。
- 下载走 `GetObject` / `GetPartialObject(Ex)` / `GetObjectSize`，支持分段与高速。
- Wi-Fi 侧用 `WifiManager` + `WifiLock` + `FINE_LOCATION`；BLE 常量里**没有** `0x2004/0x2005`，它也不靠 BLE 开热点。
- 有 `retractableLens` 条件、Auto ISO、间隔拍摄、Bulb、曝光包围、对焦包围、定时提示音。

## 阶段与优先级

| 阶段 | 工单 | 内容 | 优先级 |
|---|---|---|---|
| P0 | 01 | 参数可写（光圈/快门/ISO/曝光补偿）+ 自动 ISO + 回读校验 | 最高 |
| P0 | 02 | 定时 / 间隔拍摄 | 高 |
| P1 | 03 | 实时取景 + 触控 AF | 高（工作量最大） |
| P1 | 04 | 拍后自动回传（JPEG） | 中 |
| P2 | 05 | 曝光包围 / 对焦包围 | 中 |
| P3 | 06 | 云创 Picture Control 导入 | 低（先做 spike） |
| — | 07 | 真机验收 + 回归 | 每阶段 |

## Implementation Decisions

- 继续复用 `PtpSession` / `PtpIpTransport`；新增 `NikonOpCode` 常量与 probe 日志。
- UI 全部并入相机页「无线相机」卡（不新增 Tab）：参数可点、工具行（定时/间隔/取景/回传）。
- **写参数必须回读校验**；相机拒绝/夹取值要在 UI 明说（参考 ZRelay 的 "Camera clamped shutter"）。
- 不支持的属性灰掉，不猜。
- 实时取景默认关；开启时保持屏幕常亮与前台，退出即 EndLiveView。
- 自动回传只回传本次快门产生的 JPEG（RAW 后续单独开关）。
- 每个尼康私有码先在真机 probe（`m6-remote.log` 记录请求/响应），未确认的不进生产路径。

## Testing Decisions

- JVM 单测：新操作码/参数编码、属性写入与夹取逻辑、间隔状态机、LiveView 帧头解析、包围计划器。
- 真机：Z30 逐项验收，日志 `files/m6-remote.log`（不含 Wi-Fi 密码）。

## Out of Scope

- 视频录制、B 门长曝（先不做）
- 云创配方服务端抓取/账号体系
- iOS、多相机并发
- 相机固件升级
