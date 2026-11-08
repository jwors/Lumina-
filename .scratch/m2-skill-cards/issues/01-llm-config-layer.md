# 01: LLM 配置层 + INTERNET 权限（ADR-0005）

**What to build:** Room v3 加 `llm_configs` 表（base_url/api_key/model/模板名/云处理总开关）；
预置模板初始化（DeepSeek/GLM/Kimi/通用，字段齐全，key 空）；
设置页 UI（三元组编辑 + 模板下拉 + 总开关）；
manifest 加 INTERNET；`ManifestPrivacyTest` V2（有 INTERNET、无其他危险权限）。

**Blocked by:** 无

**Status:** resolved

## Comments

- Room v3：`llm_configs` 单例行（id=1；三元组 + 模板名 + 总开关）；`LlmConfigDao`（active/upsert）。
- 模板：`LlmTemplates.presets` 四家（通用 OpenAI / DeepSeek v4-flash-vision-exp / 智谱 GLM-4v-flash / Kimi moonshot-vision）——选模板=填表，字段可改。
- 设置页（第三个 Tab）：模板单选 + 三元组 OutlinedTextField + 云处理总开关 Switch；隐私文案引 ADR-0005。
- Manifest：仅 `android.permission.INTERNET`；**ManifestPrivacyTest V2**：断言"有 INTERNET 且全集=INTERNET"（三断言，任何其他危险权限一票否决）。
- 切 Tab 快照刷新修整：相机/图库/设置三 Tab 均带 onTabSelected 式数据装载（emitCameraUiRefresh 复用）。
- 证据：50/52 系统全绿（ManifestPrivacyTest 1→3）；APK 装机 Success。

- [ ] Room v3 + llm_configs 表 + DAO
- [ ] 4 家模板预置（常量词典）
- [ ] 设置页（新 Tab）
- [ ] Manifest + ManifestPrivacyTest V2
