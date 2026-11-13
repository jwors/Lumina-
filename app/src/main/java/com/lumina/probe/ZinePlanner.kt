package com.lumina.probe

import com.lumina.work.FieldSide
import com.lumina.work.HybridComposition
import com.lumina.work.ZinePage

/** 色场侧的中文/英文文案集中在这里，编译 prompt 与渲染页脚共用，避免三处各写一套。 */
val FieldSide.displayName: String get() = if (this == FieldSide.LEFT) "左侧" else "右侧"
val FieldSide.quietName: String get() = if (this == FieldSide.LEFT) "右侧" else "左侧"
val FieldSide.anchorField: String get() = if (this == FieldSide.LEFT) "left third" else "right third"
val FieldSide.anchorQuiet: String get() = if (this == FieldSide.LEFT) "right two-thirds" else "left two-thirds"

/**
 * 多图整卷的页序规划（纯函数，JVM 可测）。
 *
 * - 卷名：优先取本卷第一张非回退标题；全是「行山记」时保留回退名；
 * - 页序：第 N 幕 / 共 M 幕，按 `HybridComposition` 决定每页色场侧；
 * - 主色：由首张照片取色，整套共用（可选）。
 */
object ZinePlanner {

    fun issueTitle(titles: List<String>): String {
        val picked = titles
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it != TitlePicker.FALLBACK }
            ?: TitlePicker.FALLBACK
        return picked.take(TitlePicker.MAX_CHARS)
    }

    fun plan(
        titles: List<String>,
        composition: HybridComposition,
        accentHex: String? = null,
    ): List<ZinePage> {
        if (titles.isEmpty()) return emptyList()
        val issue = issueTitle(titles)
        val total = titles.size
        return titles.mapIndexed { index, _ ->
            ZinePage(
                number = index + 1,
                total = total,
                issueTitle = issue,
                fieldSide = composition.sideFor(index),
                accentHex = accentHex,
            )
        }
    }

    /** 给编译/生图 prompt 的整卷指令：页序、统一主色、色场侧与安静区。 */
    fun promptDirective(page: ZinePage): String {
        val accent = page.accentHex?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        return "整卷《${page.issueTitle}》${page.label}：全套共用同一奶油纸面、同一套纸纤维与同一主色$accent，" +
            "不要每页换色系；本页平涂色场位于画面${page.fieldSide.displayName}（约 35% 宽），" +
            "${page.fieldSide.quietName}留作照片与标题的安静区。"
    }
}
