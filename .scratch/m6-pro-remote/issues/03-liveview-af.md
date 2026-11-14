# 03 · 实时取景 + 触控 AF

> Status: claimed（帧解析+取景循环+点按 AF 代码完成；Z30 真机待验收）
> 上游：`.scratch/m6-pro-remote/spec.md`

## 范围

- `StartLiveView(0x9201)` / `GetLiveViewImage(0x9203)` / `EndLiveView(0x9202)`；解析尼康帧头（找 JPEG SOI）
- 取景画面按比例显示；~6fps；点按 → `ChangeAfArea(0x9205)` + `AfDrive(0x90C1)`
- 退出/断开必须 EndLiveView
- 看门狗：连续 N 帧失败自动重启 / 提示

## 验收

- [x] 单测：帧头解析（有效/截断/非 JPEG）
- [ ] 真机：Z30 取景出现画面，手挡镜头画面变化
- [ ] 真机：点按画面后相机对焦框移动并合焦
- [ ] 真机：退出后相机退出 LiveView（屏幕恢复）

## 证据

- `NikonLiveView.extractJpeg` 找 `FF D8 FF`，头长度不写死；`NikonLiveViewTest` 4 例。
- 控制器 150ms ticker（单帧在途），Bitmap 存入 `RemoteUi.liveView`；点按归一化坐标 → 帧像素 → `ChangeAfArea` + `AfDrive`。
- 单测 186 全绿；APK 已装。
- **真机待验收**：StartLiveView 是否需先 ChangeCameraMode、0x9203 帧头格式、ChangeAfArea 坐标口径，均需 Z30 开 Wi-Fi 后按日志收口。
