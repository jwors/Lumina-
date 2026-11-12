package com.lumina.probe

/**
 * 混合渲染的「插画场」编译提示：只让模型产出底图（纸面 + 抽象场），
 * 明确排除照片/人物/文字，给本地合成留出位置。
 */
object IllustrationPromptCompiler {

    fun prompt(skillBrief: String?): String = buildString {
        append(
            "你在为纸刊海报生成「插画场」底图。先读照片，再只输出一段可直接给文生图模型的提示词：\n" +
                "竖 3:5（1056×1760）奶油手工纸面、平扫、纸纤维与墨粒。\n" +
                "只允许：平涂色场、色带、网点/颗粒、纸纤维；最多两种颜色（奶油纸 + 一种取自照片的高纯度色）。\n" +
                "构图：70% 以上纸面留白；一大块平涂色场偏置在画面一侧；可把照片里的一条轮廓/路径做抽象延续。\n" +
                "禁止：任何可识别物体（人物、衣物、帽子、植物、山、云的具体形状）、照片感、文字、水印、mockup、3D、渐变光。\n" +
                "只输出提示词正文，不要解释、不要标题。",
        )
        if (!skillBrief.isNullOrBlank()) {
            append("\n\n【SKILL 风格规则（只取与插画场相关的部分）】\n")
            append(skillBrief.take(1800))
        }
    }

    /** 底图专用锚定：只允许平涂色场与纸纤维，避免和本地照片/文字打架。 */
    const val FIELD_ANCHOR =
        "Flat scanned cream paper with visible fibers and ink grain, one large flat color field in a single " +
            "high-chroma hue placed off-center, at least 70% empty paper, abstract color bands only, " +
            "no objects, no plants, no people, no clothing, no text, no watermark, no photograph."
}
