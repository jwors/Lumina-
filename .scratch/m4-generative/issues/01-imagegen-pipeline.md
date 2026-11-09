# M4-01: 生成式管线（SKILL 工作流 → glm-image 出图 → Work 层）

> 上游：`docs/SPEC-Lumina.md` §3.4/M4 行、词汇 `GLOSSARY.md`；正主规格 = 用户提供的
> Zeejay0《拾景纸刊》SKILL.md（scenes-gathered-zine-v1-3，个人非商业授权+署名 @Zeejay0）
> 原文存用户本机，Lumina 不分发其内容；生成作品公开分享时带 `Visual Skill by @Zeejay0`。

**What to build:**
- **生图端点配置**（Room v9 `imagegen_configs` 单行惯例：baseUrl/apiKey/model/size；默认预填智谱
  `https://open.bigmodel.cn/api/paas/v4` + `glm-image` + `1056×1760`（3:5））+ 设置页区
- **ImageGenExecutor**：POST /paas/v4/images/generations（Bearer）→ 解析 data[0].url → GET 字节
- **生成链**（卡含 GENERATIVE 步且端点已配时，AWAIT_SAVE 前逐张）：
  ① 看图（现有 chat/vision 白名单配置）出 Scene Card 精华 → ② 按 SKILL 工作流编译四段式 prompt
  → ③ glm-image 出图 → ④ 存 `Work/<date>/<base>_g.jpg`（图库扫描扩展识别 _g）
- 失败语义：未配端点 = 生成步维持 M2 占位文本并在保存弹层提示"待接入生图端点"；失败计入 cardRun 并落日志

**Blocked by:** 02（成片素材链）、03（分享出口已备）

**Status:** claimed（2026-11-09 动工前基线 100/100 全绿）

- [ ] glm-image 真出一张 3:5 zine 海报（用户视觉签收）
- [ ] 端点解密落 Room v9 正向迁移（护栏在册）
- [ ] 失败/未配置路径有清晰提示
