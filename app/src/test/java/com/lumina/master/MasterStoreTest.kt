package com.lumina.master

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 工单 01 验收：Master 目录布局 + 盘点（纯 JVM，临时目录真文件）。
 */
class MasterStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val capture = 1793404800000L // 2026-10-31T00:00Z

    private fun store(): MasterStore = MasterStore(tmp.root, InMemoryInventoryStore())

    private fun touch(relPath: String): File {
        val f = File(tmp.root, relPath)
        f.parentFile.mkdirs()
        Files.write(f.toPath(), byteArrayOf(1, 2, 3))
        return f
    }

    @Test
    fun `relative path uses capture date with yyyy-MM-dd`() {
        assertEquals(
            "Master/2026-10-31/DSC_N0379.JPG",
            MasterPaths.relativePath(capture, 0L, "DSC_N0379.JPG"),
        )
    }

    @Test
    fun `fallback to modified date when capture is null`() {
        assertEquals(
            "Master/2026-01-02/IMG.MP4",
            MasterPaths.relativePath(null, 1767312000000L, "IMG.MP4"),
        )
    }

    @Test
    fun `register writes db entry when file exists`() {
        val s = store()
        Files.write(File(tmp.root, "Master/2026-10-31/DSC_N0379.JPG").apply { parentFile.mkdirs() }.toPath(), byteArrayOf(9))
        val result = s.register(capture, null, "DSC_N0379.JPG", sizeBytes = 3)
        assertEquals(MasterStore.RegisterResult.Registered::class, result::class)
        assertEquals(1, s.snapshot().size)
        assertEquals("DSC_N0379.JPG", s.snapshot()[0].origFileName)
    }

    @Test
    fun `register with missing file is refused and nothing recorded`() {
        val s = store()
        val result = s.register(capture, null, "ABSENT.JPG", sizeBytes = 0)
        assertEquals(MasterStore.RegisterResult.MissingFile::class, result::class)
        assertEquals(0, s.snapshot().size)
    }

    @Test
    fun `same relPath registers once and skips duplicate`() {
        val s = store()
        touch("Master/2026-10-31/DSC_N0379.JPG")
        val first = s.register(capture, null, "DSC_N0379.JPG", sizeBytes = 3)
        val second = s.register(capture, null, "DSC_N0379.JPG", sizeBytes = 3)
        assertTrue(first is MasterStore.RegisterResult.Registered)
        assertTrue(second is MasterStore.RegisterResult.AlreadyPresent)
        assertEquals(1, s.snapshot().size)
    }

    @Test
    fun `snapshot sorted by date desc then name asc`() {
        val s = store()
        touch("Master/2026-10-31/B.JPG"); touch("Master/2026-10-31/C.JPG"); touch("Master/2026-11-01/A.JPG")
        s.register(1793404800000L, null, "B.JPG", sizeBytes = 3)
        s.register(1793404800000L, null, "C.JPG", sizeBytes = 3)
        s.register(1793491200000L, null, "A.JPG", sizeBytes = 3) // 2026-11-01
        assertEquals(
            listOf("Master/2026-11-01/A.JPG", "Master/2026-10-31/B.JPG", "Master/2026-10-31/C.JPG"),
            s.snapshot().map { it.relPath },
        )
    }

    @Test
    fun `pruneAbsent removes entries whose file disappeared`() {
        val s = store()
        val f = touch("Master/2026-10-31/DSC_N0379.JPG")
        s.register(capture, null, "DSC_N0379.JPG", sizeBytes = 3)
        assertEquals(1, s.snapshot().size)
        f.delete()
        assertEquals(1, s.pruneAbsent())
        assertEquals(0, s.snapshot().size)
        assertEquals(0, s.pruneAbsent()) // 幂等
    }

    @Test
    fun `dateKey groups respected for gallery grouping`() {
        assertEquals("2026-10-31", MasterPaths.dateKeyOf(capture, 123L)) // 拍摄日期优先
        assertEquals("1970-01-01", MasterPaths.dateKeyOf(null, null))    // 双缺省占位
    }
}
