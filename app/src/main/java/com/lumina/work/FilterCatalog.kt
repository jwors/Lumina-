package com.lumina.work

import kotlin.math.roundToInt

/**
 * M3-07：滤镜题材分类。缩略图条按它分组；「全部」是入口 Tab，不是分类本身。
 */
enum class FilterCategory(val label: String) {
    FILM("胶片"),
    MONO("黑白"),
    CLASSIC("经典"),
    LANDSCAPE("风光"),
    IMPORTED("我的导入"),
}

/** 用户导入的 .cube 行（InventoryDb.lut_library 的投影；纯 Kotlin 便于 JVM 直测）。 */
data class ImportedLut(val id: Int, val name: String, val relPath: String)

/**
 * 滤镜台里的一支可选项。只带元信息——LUT 数据在渲染时按需取
 * （内置走 [LutPresets] 内存预设，导入读 .cube 再解析）。
 * id 契约："builtin:<LutPresets.all 下标>" / "local:<lut_library.id>"，稳定且可持久化。
 */
data class FilterEntry(
    val id: String,
    val name: String,
    val category: FilterCategory,
    /** 内置预设下标；-1 = 非内置 */
    val builtinIndex: Int = -1,
    /** 导入 .cube 的相对 filesDir 路径；null = 非导入 */
    val lutRelPath: String? = null,
) {
    val isBuiltin: Boolean get() = builtinIndex >= 0
}

/**
 * 一次滤镜选择 = 条目 + 强度。强度是滤镜的参数（GLOSSARY），所以两者一起走，
 * 不散成两个参数——渲染、保存、日志共用同一份，百分比口径也只有一处。
 */
data class FilterSelection(val entry: FilterEntry, val rawStrength: Float) {
    /** 0..1（越界值夹到边界，不抛） */
    val strength: Float get() = rawStrength.coerceIn(0f, 1f)

    /** 0..100 整数百分比：UI 读数与日志同源，别在两处各自取整。 */
    val percent: Int get() = (strength * 100f).roundToInt()
}

/**
 * 滤镜目录（M3-07）：把「内置预设」与「用户导入」合成一份可展示的清单。
 * 纯函数 + 稳定排序，不碰文件与 DB——渲染解析与数据读取在调用方。
 */
object FilterCatalog {

    /** 「全部」Tab 的标签（分类为 null 时用）。 */
    const val ALL_LABEL = "全部"

    /** 内置预设条目（顺序 = LutPresets.all 顺序，id 下标与之对齐）。 */
    fun builtins(): List<FilterEntry> = LutPresets.all.mapIndexed { i, p ->
        FilterEntry(
            id = "builtin:$i",
            name = p.name,
            category = p.category,
            builtinIndex = i,
        )
    }

    /** 导入条目（DB 已按 name 升序给出，这里保持原序）。 */
    fun imported(rows: List<ImportedLut>): List<FilterEntry> = rows.map {
        FilterEntry(
            id = "local:${it.id}",
            name = it.name,
            category = FilterCategory.IMPORTED,
            lutRelPath = it.relPath,
        )
    }

    /** 完整目录：内置在前、导入在后。 */
    fun all(rows: List<ImportedLut>): List<FilterEntry> = builtins() + imported(rows)

    /** 分类过滤；null = 全部。 */
    fun inCategory(entries: List<FilterEntry>, category: FilterCategory?): List<FilterEntry> =
        if (category == null) entries else entries.filter { it.category == category }
}
