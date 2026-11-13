package com.lumina.probe

import com.lumina.work.ZinePage

/**
 * 混合渲染的「插画场」编译提示：只让模型产出底图（纸面 + 抽象场），
 * 明确排除照片/人物/文字，给本地合成留出位置。
 *
 * 构图由 [ZinePage.fieldSide] 控制：色场占一侧约 35%，另一侧保持安静纸面，
 * 照片本地贴在安静区，不再让模型随机决定色场位置。
 */
object IllustrationPromptCompiler {

    fun prompt(skillBrief: String?, page: ZinePage? = null): String = buildString {
        val field = page?.fieldSide?.displayName ?: "左侧"
        val quiet = page?.fieldSide?.quietName ?: "右侧"
        append(
            "你在为纸刊海报生成「插画场」底图。先读照片，再只输出一段可直接给文生图模型的提示词：\n" +
                "竖 3:5（1056×1760）奶油手工纸面、平扫、纸纤维与墨粒。\n" +
                "只允许：平涂色场、色带、网点/颗粒、纸纤维；最多两种颜色（奶油纸 + 一种取自照片的高纯度色）。\n" +
                "构图：70% 以上纸面留白；一大块平涂色场偏置在画面$field（约 35% 宽），" +
                "${quiet}留作照片与标题的安静区；可把照片里的一条轮廓/路径做抽象延续。\n" +
                "禁止：任何可识别物体（人物、衣物、帽子、植物、山、云的具体形状）、照片感、文字、水印、mockup、3D、渐变光。\n" +
                "只输出提示词正文，不要解释、不要标题。",
        )
        page?.let {
            append("\n\n【整卷页序】\n")
            append(ZinePlanner.promptDirective(it))
        }
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

    /** 按页序把色场钉在指定一侧，另一侧留给照片与页脚。 */
    fun fieldAnchor(page: ZinePage? = null): String {
        val field = page?.fieldSide?.anchorField ?: "left third"
        val quiet = page?.fieldSide?.anchorQuiet ?: "right two-thirds"
        return "$FIELD_ANCHOR Put the color field on the $field only; keep the $quiet quiet cream paper " +
            "for a photograph and a small footer folio."
    }
}
