package com.lumina.ptp

import com.lumina.transport.FakeTransport
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 02 号工单验收：协议引擎对"伪相机字节流"的行为钉死——
 * 请求字节形状、交易号递增、状态机推进、错误响应、容器分片重组。
 */
class PtpSessionTest {

    // ---------- 字节构建器（与主代码独立，互为对照） ----------

    private fun bb(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    private fun u16(v: Int): ByteArray = bb(2).putShort(v.toShort()).array()

    private fun u32(v: Long): ByteArray = bb(4).putInt(v.toInt()).array()

    private fun container(
        type: Int,
        code: Int,
        tid: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = u32((12 + payload.size).toLong()) +
        u16(type) + u16(code) + u32(tid.toLong()) + payload

    private fun resp(code: Int, tid: Int): ByteArray =
        container(ContainerType.RESPONSE, code, tid)

    private fun dataPack(code: Int, tid: Int, payload: ByteArray): ByteArray =
        container(ContainerType.DATA, code, tid, payload)

    private fun mtpString(s: String): ByteArray {
        if (s.isEmpty()) return byteArrayOf(0)
        val chars = s.toByteArray(StandardCharsets.UTF_16LE)
        return byteArrayOf((chars.size / 2).toByte()) + chars
    }

    private fun u16ArrayOf(vararg codes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32(codes.size.toLong()))
        for (c in codes) out.write(u16(c))
        return out.toByteArray()
    }

    /** DeviceInfo 数据集（M0 只关心 Model 字段） */
    private fun deviceInfo(model: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u16(0x0100))                  // StandardVersion
        out.write(u32(6L))                      // VendorExtensionID (MTP)
        out.write(u16(0x0100))                  // VendorExtensionVersion
        out.write(mtpString("microsoft.com/1.0")) // VendorExtensionDesc
        out.write(u16(0))                       // FunctionalMode
        out.write(u16ArrayOf(0x1001, 0x1002))   // Operationcodes
        out.write(u16ArrayOf())                 // Eventcodes
        out.write(u16ArrayOf())                 // DevicePropcodes
        out.write(u16ArrayOf())                 // CaptureFormats
        out.write(u16ArrayOf(0x3801))           // ImageFormats (EXIF/JPEG)
        out.write(mtpString("Nikon"))           // Manufacturer
        out.write(mtpString(model))             // Model
        out.write(mtpString("1.00"))            // DeviceVersion
        out.write(mtpString(""))                // SerialNumber
        return out.toByteArray()
    }

    // ---------- 行为测试 ----------

    @Test
    fun `open encodes OpenSession request and reaches Open`() {
        val t = FakeTransport(listOf(resp(RespCode.OK, tid = 1)))
        val s = PtpSession(t)
        s.open()
        assertEquals(PtpSession.State.Open, s.currentState)
        assertArrayEquals(
            encodeRequest(OpCode.OPEN_SESSION, 1, longArrayOf(1)),
            t.observedWrites()[0],
        )
    }

    @Test
    fun `open with error response throws and stays Closed`() {
        val t = FakeTransport(listOf(resp(0x2003, tid = 1)))
        val s = PtpSession(t)
        val ex = assertThrows(PtpResponseException::class.java) { s.open() }
        assertEquals(0x2003.toLong(), ex.code.toLong())
        assertEquals(PtpSession.State.Closed, s.currentState)
    }

    @Test
    fun `get model parses DeviceInfo and reaches Ready`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 1),
                dataPack(OpCode.GET_DEVICE_INFO, tid = 2, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, tid = 2),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(PtpSession.State.Ready, s.currentState)
        // 交易号递增：第二个事务 tid=2
        assertArrayEquals(
            encodeRequest(OpCode.GET_DEVICE_INFO, 2),
            t.observedWrites()[1],
        )
    }

    @Test
    fun `list storage returns storage ids`() {
        val storageIds = u32(2) + u32(1) + u32(0x00010001)
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 1),
                dataPack(OpCode.GET_STORAGE_IDS, tid = 2, storageIds),
                resp(RespCode.OK, tid = 2),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(listOf(1L, 0x00010001L), s.listStorage().map { it.id })
    }

    @Test
    fun `protocol calls before session open are rejected`() {
        val s = PtpSession(FakeTransport(listOf()))
        assertThrows(IllegalStateException::class.java) { s.getModel() }
        assertThrows(IllegalStateException::class.java) { s.listStorage() }
    }

    @Test
    fun `response tid mismatch is rejected`() {
        val t = FakeTransport(listOf(resp(RespCode.OK, tid = 99)))
        val s = PtpSession(t)
        assertThrows(IllegalStateException::class.java) { s.open() }
    }

    @Test
    fun `fragmented container across reads is reassembled`() {
        val whole = resp(RespCode.OK, tid = 1)
        val cut = 5 // 切在头中间
        val t = FakeTransport(listOf(whole.copyOfRange(0, cut), whole.copyOfRange(cut, whole.size)))
        PtpSession(t).open() // 必须无异常通过
    }

    @Test
    fun `close sends CloseSession and after close everything is dead`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 1),
                resp(RespCode.OK, tid = 2),
            ),
        )
        val s = PtpSession(t)
        s.open()
        s.close()
        assertArrayEquals(
            encodeRequest(OpCode.CLOSE_SESSION, 2),
            t.observedWrites()[1],
        )
        assertThrows(IllegalStateException::class.java) { s.open() }
    }
}
