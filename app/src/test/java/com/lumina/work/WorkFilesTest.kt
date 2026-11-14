package com.lumina.work

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M3-06 图库删除：Work 成片级联清理的命名契约与文件面（纯 JVM，临时目录真文件）。
 * 只认 `_g.jpg` / `_w.jpg` 两份契约；Master 子树 / 非 Work 路径 / 无后缀文件一律不碰。
 */
class WorkFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(relPath: String): File {
        val f = File(tmp.root, relPath)
        f.parentFile?.mkdirs()
        Files.write(f.toPath(), byteArrayOf(1, 2, 3))
        return f
    }

    @Test
    fun `derived base parses both suffixes and rejects others`() {
        assertEquals("DSC_3671", WorkFiles.derivedBase("DSC_3671_g.jpg"))
        assertEquals("DSC_3671", WorkFiles.derivedBase("DSC_3671_W.JPG"))
        assertNull(WorkFiles.derivedBase("DSC_3671.jpg"))
        assertNull(WorkFiles.derivedBase("DSC_3671_w.g.jpg")) // 历史事故名不归删除面管
    }

    @Test
    fun `work layout helpers are the single naming truth`() {
        assertEquals("DSC_3671", WorkFiles.baseOf("DSC_3671.JPG"))
        assertEquals("dsc_3671", WorkFiles.baseKey("DSC_3671.JPG"))
        assertEquals("Work/2026-11-01/DSC_3671_g.jpg", WorkFiles.generativeRelPath("2026-11-01", "DSC_3671.JPG"))
        assertEquals("Work/2026-11-01/DSC_3671_w.jpg", WorkFiles.lutRelPath("2026-11-01", "DSC_3671.JPG"))
    }

    @Test
    fun `delete removes only work files of matching masters`() {
        val g = touch("Work/2026-11-01/IMG_1_g.jpg")
        val w = touch("Work/2026-11-01/IMG_1_w.jpg")
        val other = touch("Work/2026-11-01/IMG_2_w.jpg")
        val masterLookalike = touch("Master/2026-11-01/IMG_1_w.jpg")
        val plain = touch("Work/2026-11-01/IMG_1.jpg")

        val deleted = mutableListOf<String>()
        val summary = WorkFiles.deleteDerived(tmp.root, listOf("IMG_1.JPG")) { deleted += it.name }

        assertEquals(WorkFiles.Summary(removed = 2, failed = 0), summary)
        assertEquals(setOf("IMG_1_g.jpg", "IMG_1_w.jpg"), deleted.toSet())
        assertEquals(false, g.exists())
        assertEquals(false, w.exists())
        assertEquals(true, other.exists())
        assertEquals(true, masterLookalike.exists())
        assertEquals(true, plain.exists())
    }

    @Test
    fun `base matching is case insensitive`() {
        val f = touch("Work/2026-11-01/DSC_3671_G.JPG")
        val summary = WorkFiles.deleteDerived(tmp.root, listOf("dsc_3671.jpg"))
        assertEquals(1, summary.removed)
        assertEquals(false, f.exists())
    }

    @Test
    fun `empty target list is a no-op`() {
        val f = touch("Work/2026-11-01/IMG_1_w.jpg")
        assertEquals(WorkFiles.Summary(0, 0), WorkFiles.deleteDerived(tmp.root, emptyList()))
        assertEquals(true, f.exists())
    }
}
