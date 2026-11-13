package com.lumina.ble

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SnapBridge 四步握手的字节层验收：帧布局、salt 命中、stage3 生成、序列号提取。 */
class PairingTest {

    @Test
    fun `pairing frame is 17 bytes little endian`() {
        val msg = PairingMessage(0x01, 0x1122334455667788L, 0x0A0B0C0D, 0x01020304)
        val raw = msg.encode()
        assertEquals(17, raw.size)
        assertEquals(0x01, raw[0].toInt())
        assertEquals(0x88.toByte(), raw[1])
        assertEquals(0x0D.toByte(), raw[9])
        assertEquals(0x0A.toByte(), raw[12])
        assertEquals(0x04.toByte(), raw[13])
        assertEquals(0x01.toByte(), raw[16])
        assertEquals(msg, PairingMessage.decode(raw))
    }

    @Test
    fun `stage2 built from a real salt is accepted and yields stage3`() {
        val engine = NikonPairingEngine(Random(42))
        val s1 = engine.createStage1()
        val salt = NikonPairingEngine.SALTS[3]
        val camTs = 0x0102030405060708L
        val camLo = NikonPairingEngine.reverseU32((camTs).toInt())
        val camHi = NikonPairingEngine.reverseU32((camTs ushr 32).toInt())
        val ourLo = NikonPairingEngine.reverseU32(s1.timestamp.toInt())
        val ourHi = NikonPairingEngine.reverseU32((s1.timestamp ushr 32).toInt())
        val h = Blowfish(Blowfish.NIKON_KEY)
            .hash(intArrayOf(salt[0], salt[1], camLo, camHi, ourLo, ourHi))
        val s2 = PairingMessage(
            stage = 0x02,
            timestamp = camTs,
            device = NikonPairingEngine.reverseU32(h.l),
            nonce = NikonPairingEngine.reverseU32(h.r),
        )

        val result = engine.verifyStage2AndBuildStage3(s1, s2)
        assertNotNull(result)
        val (s3, saltIndex) = result!!
        assertEquals(3, saltIndex)
        assertEquals(0x03, s3.stage)
        assertEquals(s1.timestamp, s3.timestamp)
        assertTrue(s3.encode().size == 17)
    }

    @Test
    fun `random challenge without salt match is rejected`() {
        val engine = NikonPairingEngine(Random(7))
        val s1 = engine.createStage1()
        val bogus = PairingMessage(0x02, 123456789L, 0x11111111, 0x22222222)
        assertNull(engine.verifyStage2AndBuildStage3(s1, bogus))
    }

    @Test
    fun `saved identity is reused for reconnect`() {
        val engine = NikonPairingEngine(Random(1))
        val first = engine.createStage1()
        val again = engine.createStage1(first.device, first.nonce)
        assertEquals(first.device, again.device)
        assertEquals(first.nonce, again.nonce)
    }

    @Test
    fun `stage4 serial is ascii from bytes 9 to 16`() {
        val device = ('A'.code) or ('B'.code shl 8) or ('C'.code shl 16) or ('D'.code shl 24)
        val nonce = ('E'.code) or ('F'.code shl 8) or ('G'.code shl 16) or ('H'.code shl 24)
        val stage4 = PairingMessage(0x04, 0L, device, nonce)
        assertEquals("ABCDEFGH", NikonPairingEngine().extractSerial(stage4))
    }

    @Test
    fun `serial stops at nul`() {
        val device = ('A'.code) or ('B'.code shl 8) or (0 shl 16) or (0 shl 24)
        val stage4 = PairingMessage(0x04, 0L, device, 0)
        assertEquals("AB", NikonPairingEngine().extractSerial(stage4))
    }
}
