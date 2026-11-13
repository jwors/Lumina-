package com.lumina.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Blowfish 标准向量（Eric Young 测试集）：验证生成的 π 表与 Feistel/密钥编排正确。
 * 若表抄错或字节序反了，这里第一时间红。
 */
class BlowfishTest {

    @Test
    fun `zero key encrypts zero block to known vector`() {
        val bf = Blowfish(ByteArray(8))
        val out = bf.encryptBlock(0, 0)
        assertEquals(0x4EF99745, out.l)
        assertEquals(0x6198DD78, out.r)
    }

    @Test
    fun `all ones key and block match known vector`() {
        val bf = Blowfish(ByteArray(8) { 0xFF.toByte() })
        val input = -0x1 // 0xFFFFFFFF
        val out = bf.encryptBlock(input, input)
        assertEquals(0x51866FD5, out.l)
        assertEquals(0xB85ECB8A.toInt(), out.r)
    }

    @Test
    fun `ascii key vector and decrypt round trip`() {
        val key = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val bf = Blowfish(key)
        val out = bf.encryptBlock(0x11111111, 0x11111111)
        assertEquals(0x61F9C380, out.l)
        assertEquals(0x2281B096, out.r)
        val back = bf.decryptBlock(out.l, out.r)
        assertEquals(0x11111111, back.l)
        assertEquals(0x11111111, back.r)
    }

    @Test
    fun `hash is deterministic and pairs blocks`() {
        val h1 = Blowfish(Blowfish.NIKON_KEY).hash(intArrayOf(1, 2, 3, 4))
        val h2 = Blowfish(Blowfish.NIKON_KEY).hash(intArrayOf(1, 2, 3, 4))
        assertEquals(h1, h2)
        assertEquals(
            Blowfish(Blowfish.NIKON_KEY).hash(intArrayOf(1, 2, 3, 5)),
            Blowfish(Blowfish.NIKON_KEY).hash(intArrayOf(1, 2, 3, 5)),
        )
    }
}
