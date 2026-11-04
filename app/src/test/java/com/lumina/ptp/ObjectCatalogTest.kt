package com.lumina.ptp

import com.lumina.ptp.FakeCamera.dataPack
import com.lumina.ptp.FakeCamera.resp
import com.lumina.ptp.FakeCamera.u16
import com.lumina.ptp.FakeCamera.u32
import com.lumina.transport.FakeTransport
import java.lang.Math.floorDiv
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 03 号工单验收（探活先行的新协议序）：
 * OpenSession 前多一笔 GetDeviceInfo 探活，之后 handles/infos 顺序不变。
 * 字节构建器统一来自 FakeCamera。
 */
class ObjectCatalogTest {

    private val STORAGE = 0x00010001L

    private fun handlesPack(vararg handles: Long) = FakeCamera.u32Array(*handles)

    /** 标准脚本：pre-close + open，随后正是 3 对象枚举 */
    private fun camera(): FakeTransport = FakeTransport(
        listOf(
            resp(RespCode.OK, 1),                                   // pre-close
            resp(RespCode.OK, 2),                                   // OpenSession
            dataPack(OpCode.GET_OBJECT_HANDLES, 3, handlesPack(100, 200, 300)),
            resp(RespCode.OK, 3),
            dataPack(                                               // #100：DCIM 目录
                OpCode.GET_OBJECT_INFO, 4,
                FakeCamera.objectInfo("DCIM", assocType = ASSOC_TYPE_FOLDER,
                    modificationDate = "20261031T100000"),
            ),
            resp(RespCode.OK, 4),
            dataPack(                                               // #200：JPG
                OpCode.GET_OBJECT_INFO, 5,
                FakeCamera.objectInfo("DSC_N0379.JPG", format = ObjectFormat.EXIF_JPEG,
                    size = 12_345, parent = 100, captureDate = "20261030T081530"),
            ),
            resp(RespCode.OK, 5),
            dataPack(                                               // #300：MP4（体积满 u32）
                OpCode.GET_OBJECT_INFO, 6,
                FakeCamera.objectInfo("MOV_N0380.MP4", size = 0xFFFFFFF0, parent = 100,
                    modificationDate = "20261031T080000"),
            ),
            resp(RespCode.OK, 6),
        ),
    )

    private fun session(): Pair<PtpSession, FakeTransport> {
        val t = camera()
        val s = PtpSession(t)
        s.open()
        return s to t
    }

    // ---------- 行为测试 ----------

    @Test
    fun `traverse requests all handles with PARENT_ALL then infos in order`() {
        val (s, t) = session()
        ObjectCatalog(s).traverse(STORAGE)
        // 写序：0=pre-close, 1=OpenSession, 2=handles, 3..5=infos
        assertArrayEquals(
            encodeRequest(OpCode.GET_OBJECT_HANDLES, 3, longArrayOf(STORAGE, 0, PtpSession.PARENT_ALL)),
            t.observedWrites()[2],
        )
        assertArrayEquals(
            encodeRequest(OpCode.GET_OBJECT_INFO, 4, longArrayOf(100)),
            t.observedWrites()[3],
        )
        assertArrayEquals(
            encodeRequest(OpCode.GET_OBJECT_INFO, 6, longArrayOf(300)),
            t.observedWrites()[5],
        )
    }

    @Test
    fun `traverse builds catalog with folder file sizes and dates`() {
        val (s, _) = session()
        val summary = ObjectCatalog(s).traverse(STORAGE)
        assertEquals(2, summary.files.size)
        assertEquals(1, summary.folders.size)
        assertEquals("DCIM", summary.folders[0].name)
        val jpg = summary.files.first { it.handle == 200L }
        assertEquals("DSC_N0379.JPG", jpg.name)
        assertEquals(12_345L, jpg.sizeBytes)
        assertEquals(100L, jpg.parent)
        assertEquals(
            LocalDateTime.of(2026, 10, 30, 8, 15, 30).toInstant(ZoneOffset.UTC).toEpochMilli(),
            jpg.captureEpochMillis,
        )
        val mp4 = summary.files.first { it.handle == 300L }
        // u32 大小必须无符号读出（>4GB 的视频文件）
        assertEquals(0xFFFFFFF0L, mp4.sizeBytes)
        // capture 缺失 → 回退 modification date
        assertEquals(
            LocalDateTime.of(2026, 10, 31, 8, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            mp4.captureEpochMillis,
        )
    }

    @Test
    fun `summary aggregates count bytes and date groups newest first`() {
        val (s, _) = session()
        val summary = ObjectCatalog(s).traverse(STORAGE)
        assertEquals(3, summary.totalCount)
        assertEquals(12_345L + 0xFFFFFFF0L, summary.totalBytes)
        val groups = summary.byDate
        assertEquals(2, groups.size) // Oct31（MP4）在前（新），Oct30（JPG）在后
        assertEquals(setOf("MOV_N0380.MP4"), groups[0].objects.map { it.name }.toSet())
        assertEquals(setOf("DSC_N0379.JPG"), groups[1].objects.map { it.name }.toSet())
        val jpg = summary.files.first { it.handle == 200L }
        assertEquals(floorDiv(jpg.captureEpochMillis!!, 86_400_000L), groups[1].day)
    }

    @Test
    fun `zero file storage yields empty catalog`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),
                dataPack(OpCode.GET_OBJECT_HANDLES, 3, handlesPack()),
                resp(RespCode.OK, 3),
            ),
        )
        val s = PtpSession(t)
        s.open()
        val summary = ObjectCatalog(s).traverse(STORAGE)
        assertEquals(0, summary.totalCount)
        assertEquals(0, summary.byDate.size)
    }

    @Test
    fun `absurd object count from camera is rejected without allocation`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),
                // 相机谎报 count = 0x20000000（5.3 亿，超出上限 2^20）
                dataPack(OpCode.GET_OBJECT_HANDLES, 3, u32(0x20000000)),
                resp(RespCode.OK, 3),
            ),
        )
        val s = PtpSession(t)
        s.open()
        val ex = assertThrows(IllegalStateException::class.java) { s.getObjectHandles(STORAGE) }
        assertTrue(ex.message!!.contains("sane range"))
    }

    @Test
    fun `duplicate handles are de-duplicated by visited set`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),
                dataPack(OpCode.GET_OBJECT_HANDLES, 3, handlesPack(200, 200)),
                resp(RespCode.OK, 3),
                dataPack(
                    OpCode.GET_OBJECT_INFO, 4,
                    FakeCamera.objectInfo("DSC_N0379.JPG", format = ObjectFormat.EXIF_JPEG, size = 1),
                ),
                resp(RespCode.OK, 4),
                // 若不查重，这里需要第二个 info 响应 —— 不提供，脚本耗尽即失败
            ),
        )
        val s = PtpSession(t)
        s.open()
        val summary = ObjectCatalog(s).traverse(STORAGE)
        assertEquals(1, summary.files.size)
    }

    @Test
    fun `long filename and non-standard format pass through unharmed`() {
        val longName = "DSC_N0379_20261031_ levy-onthe-sky-超长文件名" + "x".repeat(200) + ".NEF"
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),
                dataPack(OpCode.GET_OBJECT_HANDLES, 3, handlesPack(400)),
                resp(RespCode.OK, 3),
                dataPack(
                    OpCode.GET_OBJECT_INFO, 4,
                    FakeCamera.objectInfo(longName, format = 0x3000, size = 45_000_000),
                ),
                resp(RespCode.OK, 4),
            ),
        )
        val s = PtpSession(t)
        s.open()
        val summary = ObjectCatalog(s).traverse(STORAGE)
        assertEquals(1, summary.files.size)
        val nef = summary.files[0]
        assertEquals(longName, nef.name)
        assertEquals(45_000_000L, nef.sizeBytes)
    }

    @Test
    fun `session log ring keeps most recent entries and marks requests responses`() {
        val log = RingLog(3)
        val t = camera()
        val s = PtpSession(t, log)
        s.open()
        ObjectCatalog(s).traverse(STORAGE)
        val snap = log.snapshot()
        assertEquals(3, snap.size)
        assertTrue(snap[1].contains("→ GetObjectInfo"))
        assertTrue(snap[2].contains("← GetObjectInfo"))
        assertTrue(snap[2].contains("OK"))
    }
}
