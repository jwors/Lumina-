# 02 · PTP-IP 链路与镜头参数

> Status: claimed（代码完成，单测绿；真机验收见 04）
> 上游：`.scratch/m5-remote/spec.md`、`docs/adr/0006`

## 范围

- `com.lumina.ptpip`：Nikon MTP/IP 方言（长度在前包头、固定 Android GUID、双 TCP 通道、`DataPhaseInfo`）
- `PtpIpTransport : Transport`：把 `PtpSession` 的 PTP 容器翻译成 PTP-IP 包，复用现有会话/日志
- 设备属性：`GetDevicePropDesc(0x1015)` 解析（数据类型 + 当前值）、已知属性格式化
- 参数集：焦距 `0x5008`、光圈 `0x5007`、快门 `0x500D`、ISO `0x500F`、电量 `0x5001`

## 验收

- [x] `PtpIpCodecTest`：Init 包 golden（含 UTF-16LE 主机名与版本尾）、OperationRequest/Response、StartData/Data/EndData
- [x] `PtpIpTransportTest`：本地 TCP 假相机完成双通道握手 + OpenSession + GetDeviceInfo + GetDevicePropDesc
- [x] `CameraParamsTest`：UINT16/UINT32/STRING 描述符解析；f/5.6、50mm、1/250、ISO 400、电量 82% 的格式化
- [x] 真机：Wi-Fi 连上后 2 秒内出参数，转焦后刷新

## 证据

`gradle -p . :app:testDebugUnitTest --no-daemon` → BUILD SUCCESSFUL；
`PtpIpCodecTest` 5 / `PtpIpTransportTest` 1（loopback 假相机）/ `DevicePropsTest` 5 / `CameraParamsTest` 8 全绿。

**真机（Z30）**：`0x1015` 每 2 秒一轮、`rc=0x2001`、data=4/2/4/2/1 → 参数卡显示 f/4、1/10、ISO 400、电量 100%。
真机修正：Z30 对 `0x1016` 回 `0x201F`（取消），对 `0x1015` 只回值（非完整描述符）；改为短包按已知类型当值解。
另修：相机网关取到 `0.0.0.0` 导致白等 8 秒，已过滤并回退 `192.168.1.1`。
