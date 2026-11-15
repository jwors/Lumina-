# UI 反馈规范 · 按钮手感（v1，2026-11-15）

> 缘起：用户给出「6 种让按钮更有手感的交互细节」参考图（按压回弹 / 弹性开关 / 波纹跟手 / 点赞反馈 / 复选框动画 / 磁吸按钮），并要求结合 Apple iOS UI 设计理念优化 Lumina。
>
> 本文是**事实基线**：后续新增按钮/开关/勾选，先查这里，再写代码。实现集中在 `app/src/main/java/com/lumina/ui/feedback/`。

## 0. 一句话结论

Lumina 是 Android 单手机、徒步场景、戴手套也可能操作的工具。反馈目标不是"炫"，而是**让每一次触摸都立刻被确认**：按下有形变、松手有回弹、状态变化有轻触感，同时允许用户一键关掉触感与动效。

## 1. 参考图的 6 个细节 → Lumina 落点

| # | 参考效果 | Lumina 实现 | 用在哪 |
|---|---|---|---|
| 01 | 按压回弹 Press & Spring Back | `Modifier.luminaClickable`：按下 90ms 缩到 0.86–0.99（按控件分级），松手用低阻尼 spring 回弹（轻微过冲） | 所有主按钮、标签栏、卡片、文字动作 |
| 02 | 弹性开关 Elastic Toggle | `LuminaSwitch`：拇指用 spring 过渡，移动中横向拉伸/纵向压扁，轨道与拇指颜色渐变 | 设置页所有开关（云处理、水印、混合渲染、触感、减弱动效） |
| 03 | 波纹跟手 Touch Ripple | Material ripple 从**真实触点**扩散；暗色表面再叠触点金色光斑，金色按钮改用深色波纹 + 白色按压高光 | 所有 `luminaClickable`；光斑中心跟随按下点，不是控件中心 |
| 04 | 点赞反馈 Like Burst | `LuminaCheck(burst = true)`：勾选时金色圆环 + 6 个粒子从圈心迸发 | 图库选片（正向动作）；相机列表勾选保持安静，避免批量选择时噪音 |
| 05 | 复选框动画 Animated Checkbox | `LuminaCheck`：圆形填充缩放 + 对勾两段生长（spring），深色描边底保证亮图上可辨 | 相机页逐张勾选（只动效、不振动）、图库选片圈 |
| 06 | 磁吸按钮 Magnetic Button | `luminaClickable(magnetic = true)`：按点相对中心归一化，最多吸 5dp，松手回中；斜向按角落也限幅 | 主 CTA（`PillButton(filled = true)`）、快门、圆形图标、小选择药丸 |

## 2. Apple HIG 查证与取舍

以下原则来自 Apple Human Interface Guidelines（2026-11-15 读取）：

### 2.1 Motion：动效要有目的、要短、要可关

- "Add motion purposefully, supporting the experience without overshadowing it." 不要为动而动；过度动画会让人分心甚至不适。
- "Aim for brevity and precision in feedback animations." 反馈动画要短、要准，才能轻量、不打断。
- "In apps, generally avoid adding motion to UI interactions that occur frequently." 高频交互不要每次都加戏。
- "Make motion optional." 动效不能是传达信息的唯一方式，要配合触感/声音等替代通道。

来源：[Apple HIG · Motion](https://developer.apple.com/design/human-interface-guidelines/motion)

**Lumina 取舍**：按压/回弹只在"按下—松开"的 100–300ms 内发生；列表滚动、Tab 切换不做整页动效；提供「减弱动效」开关，并尊重系统 `Animator duration scale = 0`。

### 2.2 Feedback：反馈强度要匹配信息重要性

- 反馈要让人知道"发生了什么、下一步能做什么、结果如何、如何避免错误"。
- "Make sure all feedback is accessible." 颜色之外还要有文字/触感等通道。
- 不要用 alert 讲不重要的事；重要完成才做确认。

来源：[Apple HIG · Feedback](https://developer.apple.com/design/human-interface-guidelines/feedback)

**Lumina 取舍**：删除等破坏性动作保留二次确认（已有），并配 `Reject` 触感；普通保存不弹窗，只用按钮内文案/轻触感确认；不用动画冒充"成功"。

### 2.3 Haptics：语义一致、少而准、可关闭

- 系统触感分 notification / impact / selection 三类，含义要稳定；同一触感不要一会儿表示成功、一会儿表示失败。
- "Avoid overusing haptics." 偶尔一次刚好，频繁就烦。
- "Make haptics optional." 允许关闭；关闭后 App 仍完整可用。
- 触感要与动画的强度和锐度匹配。

来源：[Apple HIG · Playing haptics](https://developer.apple.com/design/human-interface-guidelines/playing-haptics)

**Lumina 取舍**：映射成 5 个语义（Tick / Tap / Confirm / Reject / ToggleOn-Off），只在**点击成立**时触发一次（不在按下时触发，避免手指一碰列表就振）；设置页提供总开关。Android 侧用 `View.performHapticFeedback`，尊重系统触感设置。

### 2.4 Buttons：必须有 press state，热区至少 44×44pt

- "Always include a press state for a custom button. Without a press state, a button can feel unresponsive."
- 按钮热区至少 44×44pt（visionOS 60×60pt）。
- 一个视图里突出按钮只留 1–2 个；用样式而不是尺寸区分主次。

来源：[Apple HIG · Buttons](https://developer.apple.com/design/human-interface-guidelines/buttons)

**Lumina 取舍**：所有自定义可点元素统一走 `luminaClickable`，保证至少有缩放/波纹/光斑之一；主 CTA 仍是金色 filled 药丸，次按钮是深色药丸；不新增第三个"主按钮"。

### 2.5 Liquid Glass：直接触摸要有更强响应，但不照搬材质

Apple 2025 年的新设计语言强调直接触摸的响应感：HIG Motion 明确写到 Liquid Glass 的移动会响应直接触摸，用更强反馈强化"可触摸感"；系统组件还会自动适配无障碍与不同输入方式。Newsroom 的发布稿则交代了这套跨平台设计语言的整体目标（更聚焦内容、更有生命力，同时保持熟悉感）。

来源：[Apple HIG · Motion](https://developer.apple.com/design/human-interface-guidelines/motion)、[Apple Newsroom · Apple introduces a delightful and elegant new software design](https://www.apple.com/sg/newsroom/2025/06/apple-introduces-a-delightful-and-elegant-new-software-design/)

**Lumina 取舍**：**不**引入半透明玻璃材质。Lumina 是黑金深色、以照片内容为主角的工具，玻璃层会降低照片对比度、增加 GPU 合成成本，也不符合现有品牌。我们只借"直接触摸的响应感"——按压形变、触点光斑、磁吸位移——不借材质。

## 3. 动效与触感参数

| Token | 值 | 说明 |
|---|---|---|
| 按下缩放 | 0.86–0.99，按控件分级 | 勾选 0.86、圆形图标 0.88、主 CTA 0.94、次级 0.96、列表卡 0.985、整条横幅 0.99；1f = 只做波纹/高光 |
| 按下时长 | 90ms `FastOutSlowInEasing` | 立刻有反应，不拖沓 |
| 松手回弹 | `spring(dampingRatio = 0.38, stiffness = 620)` | 轻微过冲，形成"回弹" |
| 光斑 | 72dp 半径，金色 20% → 透明，110ms 进入 / 180ms 退出 | 暗色表面可见；金色/图片表面由波纹与按压高光承担 |
| 按压高光 | 白色 10% 覆盖，跟 glow 淡入淡出；图库缩略图用黑色 8% 避免洗白 | 金色/深色底都可见；勾选/开关不用（避免方框闪） |
| 波纹色 | 深色底金色 / 金色底深色；勾选与开关用白色 | 保证各种底色上的波纹对比 |
| 磁吸 | 最多 5dp，`spring(dampingRatio = 0.45, stiffness = 560)` | 斜向限幅，避免 1.41 倍 |
| 开关 | `spring(dampingRatio = 0.42, stiffness = 640)` | 拇指过冲 + 挤压 |
| 选片迸发 | 460ms，圆环 + 6 粒子 | 只在图库选片 |
| 触感 | Tick / Tap / Confirm / Reject / Toggle | 见 `LuminaHaptic` |

## 4. 无障碍与偏好

- 设置页新增「触感反馈」与「减弱动效」两个开关，持久化在 `lumina_ui_feedback` SharedPreferences。
- `减弱动效` 开启或系统动画时长为 0 时：缩放/光斑瞬时到位；磁吸与粒子直接关闭（不播放过程），功能不受影响。
- 触感关闭后：所有 `LuminaHaptic` 直接 return，不振动。
- 触控热区：`PillButton` ≥48dp 高，`LuminaCheck` 48dp，`LuminaSwitch` 48×48dp，`RoundIcon` 外层 48dp（视觉 34dp），`ShutterButton` 62dp。
- 状态不只靠动画表达：勾选有填充/对勾，开关有颜色/位置，按钮有文案变化。

## 5. 代码地图

- `ui/feedback/LuminaPress.kt`：`luminaPressFeedback` / `luminaClickable` / `LuminaFeedbackDefaults`
- `ui/feedback/LuminaCheck.kt`：圆形勾选 + 对勾生长 + 粒子迸发
- `ui/feedback/LuminaSwitch.kt`：弹性开关
- `ui/feedback/LuminaHaptics.kt`：语义触感
- `ui/feedback/LuminaUiPrefs.kt`：触感/动效偏好
- `ui/feedback/FeedbackMath.kt`：磁吸限幅、开关挤压（纯函数，JVM 单测）
- `probe/MainActivity.kt`、`probe/RemoteScreen.kt`、`probe/FilterStudio.kt`：接入点

## 6. 验收清单

- [ ] 连续快速点按主 CTA：每次都有缩放 + 回弹 + 轻触感，不累积卡顿。
- [ ] 设置页开关：拇指过冲/挤压可见，颜色渐变；打开「触感反馈」时有一次确认触感，关闭时静音（预期）。
- [ ] 图库选片：勾选有对勾生长 + 金色粒子；取消勾选无粒子；批量连点不卡。
- [ ] 列表滚动：起手不触发触感；滚动中不出现按钮缩放残留。
- [ ] 关闭「触感反馈」：全部按钮/开关/勾选静音。
- [ ] 开启「减弱动效」：无缩放/粒子/磁吸过程，但状态仍正确变化。
- [ ] 系统动画时长 0：与「减弱动效」同效。
- [ ] 删除确认：确认按钮触感为 Reject，取消为 Tap。
- [ ] 深色/亮色缩略图上，勾选圈与对勾均清晰。

## 7. 验证记录（2026-11-15）

- `:app:testDebugUnitTest`：BUILD SUCCESSFUL（20:18）；`FeedbackMathTest` 8/8（磁吸中心/边缘/斜向限幅、退化尺寸、非正吸力、开关挤压）。
- `:app:assembleDebug`：BUILD SUCCESSFUL（20:18）；APK 输出在 `build-out/outputs/apk/debug/app-debug.apk`。
- `:app:lintDebug`：新文件无 lint 告警；任务仍被既有的 `BleCameraLink.kt` 3 处 `MissingPermission` 卡住，与本次改动无关。
- 当前机器无 adb 设备（`adb devices` 为空），上述动效的**真机视觉/触感**仍需在 Z30 配套手机上过一遍；本文件第 6 节清单保留为真机验收项。
