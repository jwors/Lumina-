package com.lumina.probe

import com.lumina.pass.SmartResultEntity

/**
 * 混合渲染的标题选择（纯函数，JVM 可测）：
 * 优先 caption（去掉 "caption：" 前缀，取首行），其次 annotate 的首个短句，最后回退「行山记」。
 */
object TitlePicker {

    const val FALLBACK = "行山记"
    const val MAX_CHARS = 12

    fun pick(results: List<SmartResultEntity>): String {
        val caption = results.firstOrNull { it.kind == "caption" }?.text
        val cleanCaption = caption?.let { clean(it) }
        if (!cleanCaption.isNullOrBlank()) return clamp(cleanCaption)

        val annotate = results.firstOrNull { it.kind == "annotate" }?.text
            ?.trim()
            ?.lineSequence()?.firstOrNull()
            ?.substringBefore("，")?.substringBefore("。")?.trim()
        if (!annotate.isNullOrBlank() && annotate.length >= 2) return clamp(annotate)

        return FALLBACK
    }

    /** 去掉 "主标题："/"caption："/Markdown 加粗，只留首行正文。 */
    private fun clean(raw: String): String {
        var s = raw.substringAfter("caption：", raw)
        s = s.replace("**", "")
        s = s.lineSequence().firstOrNull()?.trim().orEmpty()
        s = s.replace(Regex("^(主标题|标题|caption|Caption)\\s*[:：]\\s*"), "")
        return s.trim(' ', '：', ':', '“', '”', '"', '。', '，', '-')
    }

    private fun clamp(s: String): String = s.take(MAX_CHARS)
}
