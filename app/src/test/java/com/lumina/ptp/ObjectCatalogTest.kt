package com.lumina.ptp

import com.lumina.transport.FakeTransport
import java.io.ByteArrayOutputStream
import java.lang.Math.floorDiv
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 03 号工单验收：扁平枚举 + ObjectInfo 建树 + 日期分组 + 环形日志。
 * 伪相机脚本：OpenSession → GetObjectHandles(PARENT_ALL) → 逐句柄 GetObjectInfo。
 */
class ObjectCatalogTest {

    // ---------- 伪相机字节构建器 ----------

    private fun bb(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun u16(v: Int): ByteArray = bb(2).putShort(v.toShort()).array()
    private fun u32(v: Long): ByteArray = bb(4).putInt(v.toInt()).array()
    private fun container(type: Int, code: Int, tid: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        u32((12 + payload.size).toLong()) + u16(type) + u16(code) + u32(tid.toLong()) + payload
    private fun resp(code: Int, tid: Int): ByteArray = container(ContainerType.RESPONSE, code, tid)
    private fun dataPack(code: Int, tid: Int, payload: ByteArray): ByteArray =
        container(ContainerType.DATA, code, tid, payload)
    private fun mtpString(s: String): ByteArray {
        if (s.isEmpty()) return byteArrayOf(0)
        val chars = s.toByteArray(StandardCharsets.UTF_16LE)
        // PTP string：u8 长度含终结 NUL，字符 UTF-16LE，末尾补 NUL
        return byteArrayOf(((chars.size / 2) + 1).toByte()) + chars + byteArrayOf(0, 0)
    }

    /** ObjectInfo 数据集（gphoto2 ptp_unpack_OI 线格式） */
    private fun oiPack(
        name: String,
        format: Int = 0,
        size: Long = 0,
        parent: Long = 0,
        assocType: Int = 0,
        capture: String = "",
        modDate: String = "",
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32(0x00010001))            // StorageID
        out.write(u16(format))                // ObjectFormat
        out.write(u16(0))                     // ProtectionStatus
        out.write(u32(size))                  // ObjectSize
        out.write(u16(0))                     // ThumbFormat
        out.write(u32(0))                     // ThumbSize
        out.write(u32(0)); out.write(u32(0))  // ThumbPixWidth/Height
        out.write(u32(0)); out.write(u32(0))  // ImagePixWidth/Height
        out.write(u32(0))                     // ImageBitDepth
        out.write(u32(parent))                // ParentObject
        out.write(u16(assocType))             // AssociationType
        out.write(u32(0))                     // AssociationDesc
        out.write(u32(0))                     // SequenceNumber
        out.write(mtpString(name))            // Filename
        out.write(mtpString(capture))         // CaptureDate
        out.write(mtpString(modDate))         // ModificationDate
        return out.toByteArray()
    }

    private fun handlesPack(vararg handles: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32(handles.size.toLong()))
        for (h in handles) out.write(u32(h))
        return out.toByteArray()
    }

    private val STORAGE = 0x00010001L

    /** 标准脚本：3 对象（DCIM 目录、JPG、MP4） */
    private fun camera(): FakeTransport = FakeTransport(
        listOf(
            resp(RespCode.OK, 1),                                  // OpenSession
            // GetObjectHandles(parent=PARENT_ALL)：3 个句柄
            dataPack(OpCode.GET_OBJECT_HANDLES, 2, handlesPack(100, 200, 300)),
            resp(RespCode.OK, 2),
            // ObjectInfo #100：DCIM 目录
            dataPack(
                OpCode.GET_OBJECT_INFO, 3,
                oiPack("DCIM", assocType = ASSOC_TYPE_FOLDER, modDate = "20261031T100000"),
            ),
            resp(RespCode.OK, 3),
            // ObjectInfo #200：JPG（capture date）
            dataPack(
                OpCode.GET_OBJECT_INFO, 4,
                oiPack("DSC_N0379.JPG", format = ObjectFormat.EXIF_JPEG, size = 12_345,
                    parent = 100, capture = "20261030T081530"),
            ),
            resp(RespCode.OK, 4),
            // ObjectInfo #300：MP4（只有 mod date；体积用满 unsigned u32）
            dataPack(
                OpCode.GET_OBJECT_INFO, 5,
                oiPack("MOV_N0380.MP4", size = 0xFFFFFFF0, parent = 100,
                    modDate = "20261031T080000"),
            ),
            resp(RespCode.OK, 5),
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
        assertArrayEquals(
            encodeRequest(OpCode.GET_OBJECT_HANDLES, 2, longArrayOf(STORAGE, 0, PtpSession.PARENT_ALL)),
            t.observedWrites()[1],
        )
        // 逐句柄 GetObjectInfo：tid 3/4/5 三个事务，各带一句柄参数
        assertArrayEquals(
            encodeRequest(OpCode.GET_OBJECT_INFO, 3, longArrayOf(100)),
            t.observedWrites()[2],
        )
        assertArrayEquals(
            encodeRequest(OpCode.GET_OBJECT_INFO, 5, longArrayOf(300)),
            t.observedWrites()[4],
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
    fun `summary aggregates count bytes and date groups`() {
        val (s, _) = session()
        val summary = ObjectCatalog(s).traverse(STORAGE)
        assertEquals(3, summary.totalCount) // 2 文件 + 1 目录
        assertEquals(12_345L + 0xFFFFFFF0L, summary.totalBytes)
        val groups = summary.byDate
        assertEquals(2, groups.size) // Oct31（MP4）在前，Oct30（JPG）在后
        assertEquals(setOf("MOV_N0380.MP4"), groups[0].objects.map { it.name }.toSet())
        assertEquals(setOf("DSC_N0379.JPG"), groups[1].objects.map { it.name }.toSet())
        val jpg = summary.files.first { it.handle == 200L }
        assertEquals(
            floorDiv(jpg.captureEpochMillis!!, 86_400_000L),
            groups[1].day,
        )
    }

    @Test
    fun `zero file storage yields empty catalog`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),
                dataPack(OpCode.GET_OBJECT_HANDLES, 2, handlesPack()),
                resp(RespCode.OK, 2),
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
                // 相机谎报 count = 0x20000000（5.3 亿，超出上限 2^20）
                dataPack(OpCode.GET_OBJECT_HANDLES, 2, u32(0x20000000)),
                resp(RespCode.OK, 2),
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
                dataPack(OpCode.GET_OBJECT_HANDLES, 2, handlesPack(200, 200)),
                resp(RespCode.OK, 2),
                dataPack(
                    OpCode.GET_OBJECT_INFO, 3,
                    oiPack("DSC_N0379.JPG", format = ObjectFormat.EXIF_JPEG, size = 1),
                ),
                resp(RespCode.OK, 3),
                // 若不查重，这里需要第二个 info 响应 —— 不提供，脚本耗尽即失败
            ),
        )
        val s = PtpSession(t)
        s.open()
        val summary = ObjectCatalog(s).traverse(STORAGE)
        assertEquals(1, summary.files.size)
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
        // 容量 3 → 最后三条 = [上一次的响应, 本次请求, 本次响应]
        assertTrue(snap[1].contains("→ GetObjectInfo"))
        assertTrue(snap[2].contains("← GetObjectInfo"))
        assertTrue(snap[2].contains("OK"))
    }
}
