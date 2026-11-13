package com.lumina.ptpip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 工单 02：PTP-IP 尼康方言的字节 golden（长度在前、GUID、UTF-16LE 主机名、版本尾）。 */
class PtpIpCodecTest {

    @Test
    fun `init command request uses length-first header and utf16 hostname without length prefix`() {
        val bytes = PtpIp.initCommandRequest("Lumina")
        assertEquals(bytes.size.toLong(), PtpIp.u32(bytes, 0))
        assertEquals(PtpIp.INIT_COMMAND_REQUEST.toLong(), PtpIp.u32(bytes, 4))
        assertArrayEquals(PtpIp.ANDROID_GUID, bytes.copyOfRange(8, 24))
        val name = "Lumina".toByteArray(Charsets.UTF_16LE)
        assertArrayEquals(name, bytes.copyOfRange(24, 24 + name.size))
        assertEquals(0, PtpIp.u16(bytes, 24 + name.size))
        assertEquals(
            PtpIp.PROTOCOL_VERSION.toLong(),
            PtpIp.u32(bytes, 24 + name.size + 2),
        )
    }

    @Test
    fun `operation request packs phase opcode tid and params little endian`() {
        val bytes = PtpIp.operationRequest(PtpIp.PHASE_IN, 0x1015, 7, longArrayOf(0x5008, 0xFFFFFFFF))
        assertEquals(PtpIp.OPERATION_REQUEST.toLong(), PtpIp.u32(bytes, 4))
        assertEquals(PtpIp.PHASE_IN.toLong(), PtpIp.u32(bytes, 8))
        assertEquals(0x1015, PtpIp.u16(bytes, 12))
        assertEquals(7L, PtpIp.u32(bytes, 14))
        assertEquals(0x5008L, PtpIp.u32(bytes, 18))
        assertEquals(0xFFFFFFFFL, PtpIp.u32(bytes, 22))
    }

    @Test
    fun `init response decodes connection camera name and version`() {
        val name = "Z 30".toByteArray(Charsets.UTF_16LE)
        val body = ByteArray(4 + 16 + name.size + 2 + 4)
        PtpIp.putU32(body, 0, 3)
        name.copyInto(body, 20)
        PtpIp.putU32(body, 20 + name.size + 2, PtpIp.PROTOCOL_VERSION.toLong())
        val r = PtpIp.initResponse(body)
        assertEquals(3L, r.connectionNumber)
        assertEquals("Z 30", r.cameraName)
        assertEquals(PtpIp.PROTOCOL_VERSION, r.protocolVersion)
    }

    @Test
    fun `data phase table marks property reads as data-in`() {
        assertEquals(PtpIp.PHASE_IN, PtpIp.dataPhaseFor(0x1015))
        assertEquals(PtpIp.PHASE_IN, PtpIp.dataPhaseFor(0x1001))
        assertEquals(PtpIp.PHASE_NONE, PtpIp.dataPhaseFor(0x100C))
        assertEquals(PtpIp.PHASE_NONE, PtpIp.dataPhaseFor(0x1002))
    }

    @Test
    fun `request container round trips through ptp encoder`() {
        val container = com.lumina.ptp.encodeRequest(0x1015, 4, longArrayOf(0x5008))
        val req = PtpIp.parseRequestContainer(container)
        assertEquals(0x1015, req.opcode)
        assertEquals(4L, req.tid)
        assertArrayEquals(longArrayOf(0x5008), req.params)
        assertTrue(container.size == 16)
    }
}
