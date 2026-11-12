# 计划：让导入的 SKILL 真正跑出「示例图」的效果

> 对象：`scenes-gathered-zine-v1-3` 这类导入型生图 SKILL（38k 字 · 单 GENERATIVE 步）。
> 结论先行：当前不是“模型不行”，而是 **请求被网关 400 拒掉 + 管线丢了 SKILL 的四个硬前提**。
> 本文只做计划，不含代码改动。

## 1. 问题证据（2026-11-11 实机）

- `ledger` 最近 12 条全部 `Http_400`（22:24–23:10），说明标注/文案/编译步都没发出去。
- 用配置的端点做纯文本 ping，复现：
  `400 {"type":"error","error":{"type":"MissingSessionID","message":"Request is missing x-opencode-session ..."}}`
- App 的 `OkHttpLlmClient` 只发 `Authorization` + `Content-Type`，没有 `x-opencode-session`。
- 因此编译步没有 prompt → `runGenerativePass` 跳过 → 不产 `_g.jpg`，用户看到的只是占位文本。

## 2. 为什么“修好 400”也还不像示例

SKILL 的原始用法是「对话式 agent + 参考图 + 一次纠错」，四个硬前提 App 缺三个：

| SKILL 前提 | App 现状 |
|---|---|
| 以原照片为 reference 生成 | 纯文本 prompt 调 `images/generations`，参考图不上传 |
| 读图编译四段式 prompt | 38k 全文塞一次请求，输出 `take(2400)` |
| 生成后检查 + 最多一次定向重试 | 一次出图，无检查/重试 |
| 风格由 SKILL 决定 | 末尾强制拼 `STYLE_ANCHOR`，且声明“优先级最高” |

另外：卡片只有 1 个 GENERATIVE 步 → 标注/文案/场景序号不存在；逐张生成 → 无“第 N 幕”序列。

## 3. 目标与非目标

**目标（本期）**
1. 导入的 SKILL 能稳定跑通：编译出四段 prompt、生成 3:5 图、落 `Work/<日期>/<base>_g.jpg`。
2. 结果**可控地**接近 SKILL 示例：参考图、可关闭的风格锚定、蒸馏后的编译规则。
3. 不破坏内置卡与现有 Pass/队列语义。

**非目标（本期不做）**
- 多图拼贴 / 整卷排版（放 P3）
- 第三方模型市场、SKILL 编辑器
- 改变 Master 非破坏性语义

## 4. 工作包

### P0 · 解阻（0.5 天）
- `LlmConfig` 增加可选 `extraHeaders`（或专用 `sessionId` 字段）；`OkHttpLlmClient` 发送
  `x-opencode-session: <每次 Pass 的 UUID>` + `User-Agent: Lumina/0.4`。
- “测试连接”回显 HTTP 状态与 body 前 300 字（现在只落 ledger，界面看不到原因）。
- 备选：同一套配置支持切回 GLM 官方 `https://open.bigmodel.cn/api/paas/v4`。
- **验收**：ledger 出现 `OK`；测试连接 200；同照片编译出非空四段 prompt。

### P1 · 编译质量（1.5 天）
- 导入 SKILL 时解析章节：
  - `compilerBrief`：抽取 “Photo-Specific Prompt Compiler / Prompt Shape / Hard Avoids” 三节，压到 ≤2k 字；
  - `skillBody`：全文留档（不再直接进请求）。
- `CardEntity` 增加 `compilerBrief` 列（Room migration；`RegressionGuardTest` 要求挂 Migration）。
- 编译输入 = 卡内 brief（优先）> 蒸馏版 `PosterPromptCompiler`。
- `STYLE_ANCHOR` 改为按卡策略：SKILL 自带 anchor（含 `Hard Avoids`/`torn edge` 等）时**不追加**。
- 把前序结果带进编译：`标注/文案/滤镜建议` + 场景序号（若有）追加为“参考上下文”。
- **验收**：抽查生成 prompt 含 SKILL 关键词；输出与示例做 5 分制人工评分 ≥3。

### P2 · 参考图能力（2–3 天，依赖端点）
- 写探针：用同一张照片测三种形态——`images/edits`、`images/generations` 带 `image` 字段、多图消息。
- `ImageGenClient` 增加 `mode: TEXT2IMG | IMG2IMG`；配置项与保存逻辑。
- 若 GLM 不支持 image-to-image：接入支持参考图的兼容端点（作为可选模型配置）。
- **验收**：同一照片 + 参考图，构图/色关系保留；与纯文本 A/B 对比明显提升。

### P3 · 序列/拼贴（3–5 天，可选）
- 按日期/场景分组，生成“第 N 幕”编号与页序；多图拼贴渲染。
- **验收**：3 张同场景输出一套连续 zine 页。

## 5. 工单拆分（建议进 `.scratch/skills-pipeline/issues/`）

| # | 标题 | 依赖 |
|---|---|---|
| 01 | LLM 请求头 + 错误回显 | — |
| 02 | SKILL 导入解析 + compilerBrief + Room migration | — |
| 03 | 编译输入整合（brief + 前序结果 + 序号） | 02 |
| 04 | STYLE_ANCHOR 按卡策略 | 02 |
| 05 | 生图端点探针 + IMG2IMG 客户端 | — |
| 06 | 真机验收（与示例图对比） | 01–05 |

## 6. 验收与度量

- **功能**：ledger OK、`_g.jpg` 落盘、日志有四段 prompt。
- **质量**：5 张样片人工评分（构图/纸感/撕边/微文字），目标 ≥3/5；与示例图并排留档。
- **回归**：`:app:testDebugUnitTest` 100/100；内置 `gc-minimal` / `scenes-gathered-zine` 不回归。

## 7. 风险

| 风险 | 应对 |
|---|---|
| OpenCode Go 面向 coding agent，可能限制非编码流量 | 保留 GLM 官方端点作为主路径 |
| GLM 无参考图能力 | P2 探针先行；不支持则换模型/端点 |
| 38k SKILL 超上下文 | P1 蒸馏到 ≤2k；全文只留档 |
| 新增 DB 列触发 `RegressionGuardTest` | 按纪律挂 Migration，禁 destructive |
| 生图成本/限流 | 保留 180s 冷却；验收只跑 5 张 |

## 8. 待确认

1. 生图端点是否允许换模型（如支持参考图的 image2/GPT-image）？
2. 是否接受新增一列 + Migration？
3. P2 参考图与 P3 整卷，哪个优先？

## 9. 时间线

- 本周：P0 + P1（2 天）→ 能出图、prompt 可控。
- 下周：P2 探针与 IMG2IMG（视端点）。
- P3 视反馈再排。

## 10. P2 探测结果（2026-11-12 实测）

| 探测 | 结果 |
|---|---|
| GLM-Image 官方文档 | 输入模态**仅文本**（标称最大 1000 字符），不是图片编辑接口 |
| `POST /api/paas/v4/images/edits`（multipart） | **404 Not Found** |
| 1440 字长 prompt 走 `images/generations` | 200，出图（标称 1000 字上限未被硬拒） |

结论：

- **glm-image 无法做参考图**；P2 的“以原照片为 reference”在当前端点不可实现。
- App 侧已落地适配：`baseUrl` 含 `/images/edits` 时，`ImageGenClient` 自动切 multipart 上传源图；
  用户换成支持图生图的服务（OpenAI `images/edits`、WaveSpeed `glm-image/edit` 等）即可生效，无需改代码。
- 设置页会提示“当前是纯文生图端点，不会上传参考图”。
- 若要默认体验接近 SKILL 示例，需要选定一个支持参考图的端点/模型（待拍板）。

## 11. P3 进展（2026-11-12）

已落地（不依赖端点）：

- 生成编译时注入**执行序号**：“本张是本次执行的第 N 张，共 M 张；若 SKILL 需要拼贴序号，用「第 N 幕」”。
- 与 P1 的前序标注/文案/滤镜一起，由 `GenerativePrompt.assemble` 统一拼装（纯函数 + JVM 测试）。

未落地（与 P2 同阻塞）：

- 多图拼贴成“整卷”：需要支持**多张参考图**的端点；当前 glm-image 是纯文生图，做不了。
- 页序/版式合成：需要本地排版渲染器 + 多图输入，等端点或改用本地合成。

## 13. 混合渲染模式（2026-11-12 已落地）

纯文生图端点下保住“真景为锚”的折中方案，设置页开关「混合渲染（照片为锚）」，默认关：

1. 编译步只产出「插画场」底图 prompt（`IllustrationPromptCompiler`，明确排除照片/人物/衣物/文字）；
2. glm-image 只生成纸面底图；
3. `HybridZineRenderer` 本地合成：底图 cover 铺满 → 真实照片按比例贴回（左手撕边）→ 底部奶油纸带 + 标题/日期/LUMINA；
4. 失败自动回退纯文生图，不会让任务失败。

实测（模拟器，1000020752_79928.jpg）：

- 底图 `ok 156216B / 55.7s` → `hybrid saved Work/..._g.jpg (200343B, title=行山记)`；
- 照片 1:1 保留，文字本地排版可读，底图水印被纸带覆盖；
- 标题来源：卡片的 caption 结果；导入卡没有 caption 步时回退「行山记」。

### 13.2 标题清洗（2026-11-12）

`TitlePicker`：caption 优先，去掉 `caption：` / `主标题：` / `标题：` / Markdown `**` 前缀，只取首行并截 12 字；无 caption 时用 annotate 首个短句；仍无则「行山记」。

实测 `gc-minimal-zine-poster` 混合渲染：`hybrid saved ... title=把自己走成一道山脊线`，标题不再带「主标题：**」。

### 13.1 视觉收紧（2026-11-12）

- 插画场 prompt 只允许「平涂色场/色带/网点/纸纤维」，明确禁止可识别物体（人物/衣物/帽子/植物/山）；
- 撕纸边加三层：7px 半透明晕边 + 3px 主纤维线 + 单根毛刺（确定性种子，可复现）；
- 复跑：底图 `ok 147340B / 58.7s` → `hybrid saved (188597B)`；出图更接近纸刊留白语言。

## 12. P1 端到端验收（2026-11-12，模拟器）

- 环境：导入卡 `scenes-gathered-zine-v1-3` + 照片 `1000020752_79928.jpg`；OpenCode Go 编译，`glm-image` 1056x1760。
- 编译：**869 字四段式 prompt**（画布/场景忠实/插画场+撕纸边+微文字/质感与硬避免），不再是 38k 全文。
- 生成：`imagegen: ok 315066B elapsed=56208ms`，落 `Work/2026-11-11/1000020752_79928_g.jpg`。
- 观感：奶油纸纹、照片手撕边、从照片延伸的黄色块面、中文微文字「山风正好」——SKILL 的核心语言出现了。
- 遗留：
  - 右下角有 **「AI生成」水印**：设置页已加「关闭 AI 生成水印」（`watermark_enabled=false`），但**实测该账户仍带水印**——智谱对未签“去水印声明”的账户会静默忽略该字段；开关保留为尽力而为，界面文案已注明“以控制台为准”。
  - 编译写“竖幅 3:4”，实际按配置出 1056x1760（3:5）→ **已解决**：编译提示强制 3:5 + 输出归一化。
  - 模型回吐章节标签（`**Canvas and attention geometry.**`）被画进图里 → **已解决**：`GenerativePrompt.sanitize` 清 Markdown/章节标签/编号。
  - 仍无参考图（P2 阻塞），但纸刊语言已由 SKILL 规则+内置锚定带出。
