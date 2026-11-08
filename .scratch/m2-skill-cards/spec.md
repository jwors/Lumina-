# M2 · LLM 配置 + Skill Card 引擎

> Status: ready-for-agent
> 上游：`docs/SPEC-Lumina.md` §3.3/3.4/§5/§7、ADR-0005、词汇 `GLOSSARY.md`
> grilling 结论：用户 6 问全部拍板（2026-11-08），见 Decisions

## Problem Statement

M1 后 Master 已在手机里，但徒步征程拍完的照片仍是"哑"的——没有标注、没有文案、没有属于你的处理语言。回宿营地/酒店有网时，用户希望**一键让 LLM 把这批原片盘活**，而且这套"盘活"的配方要能用 Skill Card 固定下来复用（未来甚至分享）。

## Solution（M2 = 智能数据层）

- **① LLM 配置页**：OpenAI 兼容三元组（base_url + api_key + model）+ 预置模板（DeepSeek/GLM/Kimi/通用）+ 云处理总开关
- **② LLM 管线**：OkHttp → `chat.completions`，图片 **inline base64**（长边 1024、JPEG q80 压缩），串行 + 单次退避重试
- **③ Skill Card v1 引擎**：JSON 配方（步骤序列）+ 解析校验 + Room 卡片库 + SAF 导入导出 + 3 张内置示例卡
- **④ Processing Pass 执行器 + 补跑队列**：离线排队（Room）→ 回网横幅"检测到网络，N 项待处理 [补跑]"→ 串行执行 → 智能结果（Smart Result）落 Room
- **⑤ 图库加"智能"层**：NEF/JPEG 行显示标注/文案 badge；详情页展开全文；失败可重跑
- **⑥ 设置页**：云处理总开关 + 明细日志（Ledger）查看

## Decisions（grilling 拍板，2026-11-08）

1. Q1 范围：M2 产**智能结果数据**（标注/文案/滤镜建议）；图像渲染 Work → M3
2. Q2 上传：**inline base64** 统一；DeepSeek Files API 延后优化
3. Q3 补跑触发：网络监听→顶部横幅（点按补跑）+ 队列页手动补跑；**不做后台 Service**
4. Q4 Card 交互：内置 3 示例卡 + SAF 导入导出；**不做 JSON 编辑器 UI**
5. Q5 配置与隐私：INTERNET 权限进 manifest（ADR-0005：仅模型域，ManifestPrivacyTest V2 反转）；云处理总开关 + Ledger
6. Q6 执行策略：**串行** + 失败自动重试 1 次（2s 退避）+ 手动重跑；总开关挂起清横幅

非博弈默认值：
- prompt 模板文案随步骤类型内置中文（标注/文案/滤镜建议各一）
- 视觉模型输入：JPEG（NEF 先从 Master 对应同筐 JPEG 取——无 JPEG 的 NEF 标" 待 JPEG"并跳过）

## Implementation Decisions

1. **OkHttp 4.x**（规格 §6 摘明）；Retrofit 不上（v1 手搓请求体，OpenAI 兼容结构太规整）
2. **缝隙延续**：`LlmClient` 接口（`complete(prompt, image): Result`）+ `FakeLlmClient`（JVM 单测脚本）+ `OkHttpLlmClient`（薄实现）——M0 方法论第三次复刻
3. **Room v3**：`llm_configs`(三元组+模板+总开关)、`ingest_smart_queue`(Processing 队列)、`smart_results`、`skill_cards`、`ledger` 五表
4. UI：设置页（Tab 新增）+ 图库智能徽章 + 卡片页（配方 Tab 原型，M3 完整化）

## Testing Decisions

- FakeLlmClient 脚本化（成功/超时/429/400 全路径）JVM 全绿
- Card JSON 解析/schema 校验纯 JVM 测试（含坏 JSON/缺步骤/未知步骤类型）
- Pass 执行器与补跑：Fake + 手动顺序断言；真机（06）：GLM 免费渠道拿 10 张 JPEG 跑一张卡

## Out of Scope

- 图像渲染（LUT 应用/MediaStore）/ 生成式出图 → M3/M4
- Files API 上传通道、JSON 卡片编辑器、第三方卡片市场
- 后台常驻 Service、备用模型热切换

## Acceptance

真机：配 GLM（免费）→ 连 10 张 JPG 的 Matrix of Master → 执行一张卡（标注+文案+滤镜候选）→ 图库可见 10 条 Smart Result + Ledger 10 行；断网排队 → 回网横幅补跑验证一次。
