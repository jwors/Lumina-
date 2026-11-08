# 03: Skill Card v1 引擎

**What to build:** Card JSON schema v1：
`{ id, name, version, steps: [{ type: "annotate"|"caption"|"filter_suggest"|"generative_placeholder", params? }] }`；
解析与校验（未知步骤类型/缺字段报错可读）；
Room `skill_cards` 表 + 卡片页 UI（列表 + 启用）；
SAF 导入导出（.json 文件）；
内置 3 张示例卡（徒步日记·文案 / 锐利风光·标注 / 极简海报·前夜）。

**Blocked by:** 无（引擎独立，可并行 02）

**Status:** ready-for-agent

- [ ] schema + 解析校验（纯 JVM）
- [ ] Room 表 + 卡片页
- [ ] SAF 导入导出
- [ ] 3 张内置卡
