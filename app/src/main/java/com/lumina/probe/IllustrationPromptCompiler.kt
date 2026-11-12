package com.lumina.probe

/**
 * 混合渲染的「插画场」编译提示：只让模型产出底图（纸面 + 抽象场），
 * 明确排除照片/人物/文字，给本地合成留出位置。
 */
object IllustrationPromptCompiler {

    fun prompt(skillBrief: String?): String = buildString {
        append(
            "你在为纸刊海报生成「插画场」底图。先读照片，再只输出一段可直接给文生图模型的提示词：\n" +
                "竖 3:5（1056×1760）奶油手工纸面、平扫、纸纤维与墨粒；" +
                "从照片只提取「颜色关系」和 1-2 个抽象源形（轮廓/路径/色带），做成大面积平面色场；" +
                "55-85% 留白；画面必须保持抽象：不要照片、不要人物、不要衣物帽子、不要具体物体、不要文字、不要水印、不要 mockup 边框。\n" +
                "只输出提示词正文，不要解释、不要标题。",
        )
        if (!skillBrief.isNullOrBlank()) {
            append("\n\n【SKILL 风格规则（只取与插画场相关的部分）】\n")
            append(skillBrief.take(1800))
        }
    }

    /** 底图专用锚定：不含照片/撕边/文字，避免和本地合成打架。 */
    const val FIELD_ANCHOR =
        "Flat scanned cream paper, visible fibers and ink grain, one large abstract printed field, " +
            "one high-chroma accent hue, generous negative space, no photograph, no people, no text, no watermark."
}
