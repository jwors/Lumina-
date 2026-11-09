package com.lumina.work

/**
 * M3-03：zine 海报排版数学（纯函数，JVM 可测）。
 * 画布恒为 1080×1350（竖 4:5）；分区（自上而下）：标题带 → 照片区 → 底注带。
 * 纹理/字体绘制在设备侧 ZinePosterRenderer；本对象只出几何真相，保证双端不漂移。
 */
object PosterLayout {

    const val CANVAS_W = 1080
    const val CANVAS_H = 1350        // 4:5 竖档

    /** 分区矩形（设备 frustrum 无关，纯像素）。 */
    data class Zones(
        /** 标题带（字在此区内换行，≤2 行） */
        val titleTop: Int,
        val titleHeight: Int,
        /** 照片区（中心裁绘制） */
        val photoTop: Int,
        val photoHeight: Int,
        /** 底注带 */
        val noteTop: Int,
        val noteHeight: Int,
        val pad: Int,
    )

    private const val PAD_RATIO = 0.04f          // 边距 = 画布宽 4%（上下两个）
    private const val TITLE_H_RATIO = 0.13f      // 标题带高（≈2 行 64px 字）
    private const val NOTE_H_RATIO = 0.07f       // 底注带高

    fun zones(): Zones {
        val pad = (CANVAS_W * PAD_RATIO).toInt()
        val titleH = (CANVAS_H * TITLE_H_RATIO).toInt()
        val noteH = (CANVAS_H * NOTE_H_RATIO).toInt()
        // 结构：上 pad + 标题带 + 照片区（填满余量）+ 底注带 + 下 pad —— 两个 pad，吃满画布
        val photoH = CANVAS_H - pad * 2 - titleH - noteH
        return Zones(
            titleTop = pad,
            titleHeight = titleH,
            photoTop = pad + titleH,
            photoHeight = photoH,
            noteTop = pad + titleH + photoH,
            noteHeight = noteH,
            pad = pad,
        )
    }

    /** 中心裁正窗（纯数值，不用 android.graphics.Rect——JVM 可测）。 */
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    fun centerCrop(w: Int, h: Int, targetW: Int, targetH: Int): Crop {
        val scale = maxOf(targetW.toFloat() / w, targetH.toFloat() / h)
        val cropW = (targetW / scale).toInt().coerceAtMost(w)
        val cropH = (targetH / scale).toInt().coerceAtMost(h)
        val left = (w - cropW) / 2
        val top = (h - cropH) / 2
        return Crop(left, top, cropW, cropH)
    }

    /** 标题截断（大字排版上限；按全角≈2em 保守估算）。ellipsis=false 时只截不加省略号。 */
    fun clampTitle(title: String, maxChars: Int = 22, ellipsis: Boolean = true): String {
        val t = title.replace('\n', ' ').trim()
        if (t.length <= maxChars) return t
        return if (ellipsis) t.take(maxChars - 1) + "…" else t.take(maxChars)
    }

    /** LLM 文案 → 海报标题净化：剥 markdown 包装与键名前缀（**场景名**：/衔接语：/标题： 等）。 */
    fun sanitizeTitle(raw: String): String {
        var t = raw
            .replace(Regex("[*_`#>~]"), "")              // markdown 包装符号
            .replace(Regex("【.*?】"), "")               // 方括号族一并无视（结构标记）
            .trim()
        // 剥 "场景名：" / "衔接语：" / "标题：" / "文案：" 这类键名前缀（只剥出现于开头的组）
        t = t.replace(Regex("^(场景名|衔接语|标题|文案|主标题|小注|图注)\\s*[:：]\\s*"), "")
            .replace(Regex("\\s+(场景名|衔接语)\\s*[:：]\\s*"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
        return t
    }

    /** 颗粒噪声种子（确定性：同一张出片两次逐像素一致）。 */
    const val GRAIN_SEED = 20261109L
}
