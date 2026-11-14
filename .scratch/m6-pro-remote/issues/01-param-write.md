# 01 · 参数可写（光圈/快门/ISO/曝光补偿）

> Status: claimed（协议+UI 完成，单测绿；Z30 真机写参数待验收）
> 上游：`.scratch/m6-pro-remote/spec.md`

## 范围

- 读当前值（0x1015 GetDevicePropValue）+ 允许范围/枚举（0x1014 GetDevicePropDesc，含 Form）
- 写：标准 `SetDevicePropValue(0x1016)`（参数=属性码，数据出=值）；相机拒绝再试尼康 Ex
- 属性：光圈 `0x5007`、快门 `0x500D`、ISO `0x500F`；曝光补偿/自动 ISO 后续
- 写入后回读校验；相机夹取/拒绝时 UI 明示
- 镜头收起（焦距 0）时提示「请拧出镜头」（M6 后续小项）

## 验收

- [x] 单测：写入请求字节、数据出载荷、回读/拒绝路径
- [ ] 真机：光圈 f/4→f/5.6、快门 1/10→1/60、ISO 400→800 各写成功且回读一致
- [ ] 真机：相机拒绝的值（如 f/2.8 超出镜头范围）给出人话提示
- [ ] 日志出现 `SetDevicePropValue` 的请求/响应码

## 证据

- **操作码校正（2026-11-14）**：gphoto2 `ptp.h` 证实 `0x1014=GetDevicePropDesc`、`0x1015=GetDevicePropValue`、`0x1016=SetDevicePropValue`、`0x100E=InitiateCapture`；M5 当时按错位表解析，Z30 的 4/2/1 字节正是 0x1015 的“值”。
- 单测：179 全绿，含 `setDevicePropValue` 请求/数据出、PTP-IP 数据出 loopback。
- APK 已装（`lastUpdateTime=14:59:45`）。
- **真机待验收**：测试时手机 Wi-Fi 未连相机热点，相机热点未广播；待 Z30 开 Wi-Fi 后点参数卡验证。
