# UI · 按钮手感与 iOS 设计理念对照

> Status: code-complete（待真机视觉验收）
> 上游：用户 2026-11-15 提供的「6 种按钮手感」参考图 + Apple HIG 查证
> 规范：`docs/UI-FEEDBACK.md`

## Problem Statement

Lumina 目前大部分可点区域只是裸 `Modifier.clickable`：按下无视觉确认、松手无回弹、开关是 Material 默认样式、勾选是默认 Checkbox。徒步/戴手套场景下，点按是否被接收只能靠页面跳转来判断，手感"冷"。

## Solution

建立统一反馈层，落在 `com.lumina.ui.feedback`：

1. `luminaPressFeedback` / `luminaClickable`：按压缩放 + 松手 spring 回弹 + 触点光斑 + Material ripple + 可选磁吸 + 语义触感。
2. `LuminaSwitch`：spring 拇指 + 挤压/拉伸 + 颜色渐变。
3. `LuminaCheck`：圆形填充 + 对勾两段生长 + 图库选片粒子迸发。
4. `LuminaHaptic`：Tick / Tap / Confirm / Reject / Toggle，只在点击成立时触发。
5. `LuminaUiPrefs`：设置页「触感反馈」「减弱动效」，持久化并尊重系统动画时长。
6. 全 UI 接入：相机、图库、配方、设置、无线遥控、滤镜台。

## Decisions

- **不照搬 iOS 玻璃材质**：黑金深色 + 照片为主，玻璃会降低对比度并增加合成成本；只借"直接触摸响应"。
- **触感不在按下时触发**：列表滚动起手不误振；与 iOS selection/impact 的"动作成立才反馈"一致。
- **粒子只给图库选片**：相机批量勾选保持安静，避免 20 张连选时视觉/触觉噪音。
- **动效可关**：设置开关 + 系统 animator scale 双通道；关闭后瞬时到位，功能不变。
- **热区优先**：新组件 48dp；RoundIcon 视觉 34dp、外层 48dp。

## Acceptance

- [ ] `:app:testDebugUnitTest` 全绿（含 `FeedbackMathTest`）。
- [ ] `:app:assembleDebug` 成功。
- [ ] 真机/模拟器：主 CTA、开关、图库选片、相机勾选、删除确认五处反馈可见。
- [ ] 关闭触感 / 减弱动效后，功能与状态正确，无动画残留。
- [ ] 双轴 code-review（Standards / Spec）findings 闭环。

## Evidence（2026-11-15）

- `:app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL（20:18）；`FeedbackMathTest` 8/8。
- APK：`build-out/outputs/apk/debug/app-debug.apk`（20:18:27）。
- `adb devices` 为空，真机视觉/触感未验收；第 3 条保持待办。

## Review

- Spec 轴子代理复核：6/6 效果均有真实落点；findings 已闭环——相机列表勾选改 `hapticOnToggle = false`（批量不振动）、文档修正光斑可见范围/缩放区间/Liquid Glass 引用来源、触感关闭静音说明。
- Standards 轴：聚焦检查 modifier 顺序（`graphicsLayer` 包住背景/图标，`clickable` 在 `background` 前）、触点 state 读取、API guard、48dp 热区、纯函数单测；未发现 blocker。子代理未在时限内返回，按自检记录。
