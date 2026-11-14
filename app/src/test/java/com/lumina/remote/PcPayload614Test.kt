package com.lumina.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M6-06 spike：614 字节 Picture Control 载荷的构造/解析。 */
class PcPayload614Test {

    @Test
    fun `build has documented size and header`() {
        val payload = PcPayload614.build("Z30_TEST", PcPayload614.linearLut())
        assertEquals(614, payload.size)
        assertEquals(2, payload[0].toInt())
        assertEquals(0, payload[1].toInt())
        assertEquals(8, payload[PcPayload614.POINT_COUNT_OFFSET].toInt())
        assertEquals("Z30_TEST", PcPayload614.nameOf(payload))
        assertTrue(PcPayload614.isLikely(payload))
    }

    @Test
    fun `lut round trips big endian`() {
        val lut = IntArray(256) { i -> (i * 128).coerceAtMost(PcPayload614.LUT_MAX) }
        val payload = PcPayload614.build("TEST", lut)
        assertArrayEquals(lut, PcPayload614.lutOf(payload))
        // 0x66 起第一个值 0，第二个值 128 → 00 00 00 80
        assertEquals(0, payload[PcPayload614.LUT_OFFSET].toInt())
        assertEquals(0, payload[PcPayload614.LUT_OFFSET + 1].toInt())
        assertEquals(0, payload[PcPayload614.LUT_OFFSET + 2].toInt())
        assertEquals(128, payload[PcPayload614.LUT_OFFSET + 3].toInt() and 0xFF)
    }

    @Test
    fun `values are clamped to the camera range`() {
        val lut = IntArray(256) { if (it == 0) -5 else 99_999 }
        val payload = PcPayload614.build("CLAMP", lut)
        val parsed = PcPayload614.lutOf(payload)!!
        assertEquals(0, parsed[0])
        assertEquals(PcPayload614.LUT_MAX, parsed[1])
    }

    @Test
    fun `name is truncated to 20 ascii bytes`() {
        val payload = PcPayload614.build("ABCDEFGHIJKLMNOPQRSTUVWXYZ", PcPayload614.linearLut())
        assertEquals("ABCDEFGHIJKLMNOPQRST", PcPayload614.nameOf(payload))
    }

    @Test
    fun `short payload is rejected`() {
        assertFalse(PcPayload614.isLikely(ByteArray(613)))
        assertNull(PcPayload614.lutOf(ByteArray(613)))
    }
}
