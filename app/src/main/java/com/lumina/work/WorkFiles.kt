package com.lumina.work

import java.io.File

/**
 * Work 成片的磁盘命名契约（与 `ProbeController.buildGalleryItems` 的扫描口径一致）：
 * `<原文件名去扩展>_g.jpg`（生成式真身）/ `<原文件名去扩展>_w.jpg`（LUT 成片）。
 *
 * 图库删除 Master 时按此契约级联清成片；只做文件侧动作（不碰 Room/UI），可 JVM 直测。
 * 命名契约见 M4-01 事故记录：只认这两份，历史事故名 `*_w.g.jpg` 由启动自愈改名，不在删除面内。
 */
object WorkFiles {

    private const val WORK_PREFIX = "Work/"
    private const val GENERATIVE_SUFFIX = "_g.jpg"
    private const val LUT_SUFFIX = "_w.jpg"

    /**
     * 删除 root（= filesDir）下 Work/ 子树里属于给定 Master 的成片；一次遍历。
     * 只在 `Work/` 下动手：Master 子树里同名的文件（正常不会出现）不碰。
     * [onDeleted] 在每个文件真正删掉后回调（控制器用它同步缩略图缓存账）。
     * 返回实际删除/失败计数（失败不抛——删不掉的文件不阻断其它条目）。
     */
    fun deleteDerived(
        root: File,
        origFileNames: Collection<String>,
        onDeleted: ((File) -> Unit)? = null,
    ): Summary {
        val bases = origFileNames.map { baseOf(it).lowercase() }.toSet()
        if (bases.isEmpty() || !root.isDirectory) return Summary(0, 0)
        var removed = 0
        var failed = 0
        for (file in root.walkTopDown()) {
            if (!file.isFile) continue
            val rel = file.relativeTo(root).invariantSeparatorsPath
            if (!rel.startsWith(WORK_PREFIX)) continue
            val base = derivedBase(file.name) ?: continue
            if (base.lowercase() !in bases) continue
            if (file.delete()) {
                removed++
                onDeleted?.invoke(file)
            } else if (file.exists()) {
                failed++
            }
        }
        return Summary(removed, failed)
    }

    /** Work 文件名 → Master base；不是两份契约之一返回 null。 */
    internal fun derivedBase(fileName: String): String? {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(GENERATIVE_SUFFIX) -> fileName.dropLast(GENERATIVE_SUFFIX.length)
            lower.endsWith(LUT_SUFFIX) -> fileName.dropLast(LUT_SUFFIX.length)
            else -> null
        }
    }

    /** 原文件名去扩展（Work 命名与 buildGalleryItems 扫描的公共口径）。 */
    fun baseOf(origFileName: String): String = origFileName.substringBeforeLast('.')

    /** 比较/共享判断用 key（大小写不敏感：Android 私有存储区分大小写，但相机命名只认大小写一致）。 */
    fun baseKey(origFileName: String): String = baseOf(origFileName).lowercase()

    fun generativeName(baseName: String): String = "$baseName$GENERATIVE_SUFFIX"

    fun lutName(baseName: String): String = "$baseName$LUT_SUFFIX"

    /** 生成式成片布局：Work/<dateKey>/<原名去扩展>_g.jpg。 */
    fun generativeRelPath(dateKey: String, origFileName: String): String =
        "Work/$dateKey/${generativeName(baseOf(origFileName))}"

    /** LUT 成片布局：Work/<dateKey>/<原名去扩展>_w.jpg。 */
    fun lutRelPath(dateKey: String, origFileName: String): String =
        "Work/$dateKey/${lutName(baseOf(origFileName))}"

    data class Summary(val removed: Int, val failed: Int)
}
