# M4-02: 多图整卷 + 底图构图控制 + 参考图端点

> 上游：`docs/PLAN-skills-pipeline.md` P2/P3、`.scratch/m4-generative/issues/01-imagegen-pipeline.md`。
> 2026-11-13 收尾时挂起的三件事，本次一并动工。

**What to build:**
- **多图整卷**：同一趟生成的所有可读 OK 条目共用「卷名 + 主色 + 连续性页序」，每页落
  `第 N 幕 / 共 M 幕` 页脚；主色由首张照片采样，避免每页换色系。
- **底图构图控制**：设置页可选「自动 / 色场在左 / 色场在右」。色场在哪侧，照片、手撕边
  与页脚文字就贴另一侧；`AUTO` 按页序左右交替。编译 prompt 与本地渲染共用同一侧。
- **参考图端点**：`/images/edits` 路径显式化；支持单图 `image`、多图 `image[]`；
  返回兼容 `data[0].url` 与 `data[0].b64_json`；设置页加「自动 / 强制 / 关闭」与参考图探针。

**What not to do:**
- 不改 Master 非破坏性语义，不新增 Room 列（偏好走 SharedPreferences）。
- 不做第三方异步任务端点（WaveSpeed 等 submit/poll 形态），当前只覆盖 OpenAI 兼容
  `images/edits`。
- 纯文生图模式不本地盖页脚：整卷页脚/主色条只落在混合渲染的 `_g.jpg`；文生图模式仍靠
  prompt 里的整卷指令。

**Acceptance:**
- [x] 3 张同场景混合渲染输出一套连续页：同卷名、同主色条、页脚 `第 N 幕 / 共 M 幕`。
- [x] 构图切到「色场在右」时，照片贴左、撕纸边朝右、文字贴左；「色场在左」时全部反向。
- [x] 参考图模式为 FORCE 时，请求 URL 归一到 `/images/edits`，2 张参考图用 `image[]`。
- [x] `b64_json` 返回可直接落盘；纯文生图端点仍回退 `url`。
- [x] 单测全绿 + `assembleDebug` 通过。
- [ ] 运行时多参考图仍不可达：每次生成只传当前照片 1 张；`image[]` 协议已备，
      等「一页多图」拼贴模式再接。纯文生图模式的页脚也未本地落盘。

**Status:** resolved（2026-11-14）

**Evidence:**
- 动工前基线：`:app:testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL（116 tests）。
- 收尾：`:app:testDebugUnitTest` → BUILD SUCCESSFUL，**175 tests / 0 failures**（含并行会话新增用例）。
- `:app:assembleDebug` → BUILD SUCCESSFUL。
- 新增：`ZinePlannerTest`、`PhotoPaletteTest`、`IllustrationPromptCompilerTest`；
  扩充 `HybridLayoutTest` / `ImageGenClientTest` / `GenerativePromptTest`。

**Review closure:**
- Standards：色场侧中英文案收口到 `FieldSide` 扩展；删除未用的 `ZinePage.isFirst`、
  响应别名、模型名猜测与 `ImageGenClient.isEditEndpoint` 中间人；`GLOSSARY.md` 补
  Zine Issue / Field Side / Reference Endpoint。
- Spec：页脚文字改到安静侧；照片最大宽度收到 0.60，避免压进 35% 色场；奶油垫只对整卷页生效。
  多参考图运行时不达、纯文生图页脚不落盘，已列入未完成项。
