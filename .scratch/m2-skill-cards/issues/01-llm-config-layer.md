# 01: LLM 配置层 + INTERNET 权限（ADR-0005）

**What to build:** Room v3 加 `llm_configs` 表（base_url/api_key/model/模板名/云处理总开关）；
预置模板初始化（DeepSeek/GLM/Kimi/通用，字段齐全，key 空）；
设置页 UI（三元组编辑 + 模板下拉 + 总开关）；
manifest 加 INTERNET；`ManifestPrivacyTest` V2（有 INTERNET、无其他危险权限）。

**Blocked by:** 无

**Status:** ready-for-agent

- [ ] Room v3 + llm_configs 表 + DAO
- [ ] 4 家模板预置（常量词典）
- [ ] 设置页（新 Tab）
- [ ] Manifest + ManifestPrivacyTest V2
