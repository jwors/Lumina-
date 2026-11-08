# 03: Skill Card v1 引擎

**What to build:** Card JSON schema v1：
`{ id, name, version, steps: [{ type: "annotate"|"caption"|"filter_suggest"|"generative_placeholder", params? }] }`；
解析与校验（未知步骤类型/缺字段报错可读）；
Room `skill_cards` 表 + 卡片页 UI（列表 + 启用）；
SAF 导入导出（.json 文件）；
内置 3 张示例卡（徒步日记·文案 / 锐利风光·标注 / 极简海报·前夜）。

**Blocked by:** 无（引擎独立，可并行 02）

**Status:** resolved

## Comments

- **Schema v1 + 解析校验（纯 JVM 6/6）**：缺 name / version<1 / steps 空 / 未知类型（带合法清单提示）/ 坏 JSON / export 幂等往返（id 不复用文件域）。
- 四类步骤：ANNOTATE / CAPTION / FILTER_SUGGEST / GENERATIVE（M4 占位）；ANNOTATE 与 CAPTION 必须带 prompt。
- **Room v5**：`skill_cards`（cardId PK + stepsJson 原文 + imported 标记）+ CardDao；**AppDatabase v5 破坏性迁移**（开发期惯例）。
- **配方 Tab（第四个）**：卡列表（名/vN/N 步/导入标记）+ SAF 导入（OpenDocument）与导出（CreateDocument 双段流程）+ 空/错误状态行。
- **内置 3 卡种子**（首启空库播种）：徒步日记·文案（annotate+caption）/ 锐利风光·标注 / 极简海报·前夜（filter_suggest+generative 占位）。
- 导出 JSON 不含 cardId（配方属于文件，id 归库）。
- 证据：56/62 系统全绿（+SkillCardParserTest 6）；assembleDebug 通过；APK 装机 Success。

- [ ] schema + 解析校验（纯 JVM）
- [ ] Room 表 + 卡片页
- [ ] SAF 导入导出
- [ ] 3 张内置卡
