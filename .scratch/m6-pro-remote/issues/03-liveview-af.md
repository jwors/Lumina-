# 03 · 实时取景 + 触控 AF

> Status: ready-for-agent
> 上游：`.scratch/m6-pro-remote/spec.md`

## 范围

- `StartLiveView` / `GetLiveViewImage` / `EndLiveView`；解析尼康 JPEG 头（legacy 与 versioned 两种）
- 取景画面按比例显示；默认 10–15 fps，可切质量档
- 触控点按 → `ChangeAfArea`（或 `AfDrive` 带区域）→ 再按快门
- AF 状态/失败提示；退出页面必须 EndLiveView
- 看门狗：连续 N 帧失败自动重启 LiveView / 提示

## 验收

- [ ] 单测：帧头解析（有效/截断/非 JPEG）、取景几何换算、AF 区域映射
- [ ] 真机：Z30 取景出现画面，手挡镜头画面变化
- [ ] 真机：点按画面后相机对焦框移动并合焦
- [ ] 真机：退出后相机退出 LiveView（屏幕恢复）

## 风险

- 尼康 LiveView 帧头格式与 Z30 固件差异；先用原始字节落日志再解析
