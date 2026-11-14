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
- [x] 真机：Z30 取景出现画面（2026-11-14 15:33，0x9203 帧 16KB，截图见 `ui-bug.jpg`）
- [ ] 真机：点按画面后相机对焦框移动并合焦
- [x] 看门狗：连续 12 帧失败提示、40 帧停止；新增 NotLiveView 自动重连（最多 2 次）
- [x] 离开相机页/断开自动 EndLiveView（日志 0x9202）

## 真机日志（2026-11-14 15:33）

- `0x9201` OK，`0x9203` 连续出帧（data≈16KB），约 23 秒后相机开始回 `0xA00B = NotLiveView`。
- 现已改为：连续 12 帧失败先 `EndLiveView + StartLiveView` 重连（最多 2 次），40 帧仍失败才停。
- 触控 AF 未触发：日志里没有 `0x9205/0x90C1`，待点按验证。
