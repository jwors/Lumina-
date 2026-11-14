package com.lumina.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** M6-03：LiveView 帧解析（带头/纯 JPEG/坏包）。 */
class NikonLiveViewTest {

    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0xFF.toByte(), 0xD9.toByte(),
    )

    @Test
    fun `pure jpeg passes through unchanged`() {
        assertSame(jpeg, NikonLiveView.extractJpeg(jpeg))
    }

    @Test
    fun `nikon header is skipped at the first soi`() {
        val header = ByteArray(384) { 0x11 }
        val frame = header + jpeg
        assertArrayEquals(jpeg, NikonLiveView.extractJpeg(frame))
    }

    @Test
    fun `frame without soi is rejected`() {
        assertNull(NikonLiveView.extractJpeg(ByteArray(100) { 0x22 }))
    }

    @Test
    fun `too short frame is rejected`() {
        assertNull(NikonLiveView.extractJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }
}
