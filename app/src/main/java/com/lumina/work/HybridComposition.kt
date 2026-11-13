package com.lumina.work

/** AI 底图里平涂色场所在的一侧；照片始终落在另一侧，避免色场压住真景。 */
enum class FieldSide { LEFT, RIGHT }

/**
 * 底图构图策略（设置页可选）：
 *  - AUTO：按页序左右交替，整套整卷有节奏；
 *  - FIELD_LEFT / FIELD_RIGHT：固定色场侧，照片反位。
 */
enum class HybridComposition {
    AUTO,
    FIELD_LEFT,
    FIELD_RIGHT;

    fun sideFor(pageIndex: Int): FieldSide = when (this) {
        AUTO -> if (pageIndex % 2 == 0) FieldSide.LEFT else FieldSide.RIGHT
        FIELD_LEFT -> FieldSide.LEFT
        FIELD_RIGHT -> FieldSide.RIGHT
    }
}

/**
 * 整卷里的一页（纯数据）：第 N 幕 / 共 M 幕 + 卷名 + 该页色场侧。
 * `accentHex` 由首张照片取色，整套共用，避免每页各换一种主色。
 */
data class ZinePage(
    val number: Int,
    val total: Int,
    val issueTitle: String,
    val fieldSide: FieldSide,
    val accentHex: String? = null,
) {
    val label: String get() = "第 $number 幕 / 共 $total 幕"
}
