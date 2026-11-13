package com.lumina.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0x2004 凭据解密：97B 布局、定键 CBC 回环、可打印性闸门。 */
class WifiCredentialsTest {

    @Test
    fun `fixed nikon key decrypts a round tripped blob`() {
        val ssid = "NIKON_Z30_1234"
        val password = "12345678"
        val blob = buildBlob(ssid, password, security = 1)
        val creds = WifiCredentialsDecoder.decode(blob)
        assertNotNull(creds)
        assertEquals(ssid, creds!!.ssid)
        assertEquals(password, creds.password)
        assertEquals(1, creds.security)
        assertTrue(creds.keySource.startsWith("fixed-nikon-key"))
    }

    @Test
    fun `open network allows empty password`() {
        val blob = buildBlob("NIKON_Z30_OPEN", "", security = 0)
        val creds = WifiCredentialsDecoder.decode(blob)
        assertNotNull(creds)
        assertEquals("NIKON_Z30_OPEN", creds!!.ssid)
        assertEquals("", creds.password)
        assertEquals("开放", creds.securityLabel)
    }

    @Test
    fun `garbage blob is rejected instead of inventing plaintext`() {
        val blob = ByteArray(97) { 0xFF.toByte() }
        blob[96] = 1
        assertNull(WifiCredentialsDecoder.decode(blob))
    }

    @Test
    fun `short blob is rejected`() {
        assertNull(WifiCredentialsDecoder.decode(ByteArray(96)))
    }

    @Test
    fun `lss key candidates are eight well formed keys`() {
        val material = LssMaterial(
            key1 = ByteArray(8) { it.toByte() },
            key2 = ByteArray(8) { (it + 8).toByte() },
            saltIndex = 3,
            cameraNonce = ByteArray(8) { (it + 16).toByte() },
            phoneNonce = ByteArray(8) { (it + 24).toByte() },
        )
        val candidates = WifiCredentialsDecoder.lssSessionKeyCandidates(material)
        assertEquals(8, candidates.size)
        assertTrue(candidates.all { it.second.size == 8 })
        assertEquals(candidates.size, candidates.map { it.second.toList() }.toSet().size)
    }

    @Test
    fun `malformed lss material yields no candidates`() {
        val material = LssMaterial(ByteArray(4), ByteArray(8), 0, ByteArray(8), ByteArray(8))
        assertTrue(WifiCredentialsDecoder.lssSessionKeyCandidates(material).isEmpty())
    }

    private fun buildBlob(ssid: String, password: String, security: Int): ByteArray {
        val ssidPlain = ByteArray(32)
        ssid.toByteArray(Charsets.US_ASCII).copyInto(ssidPlain)
        val passPlain = ByteArray(64)
        password.toByteArray(Charsets.US_ASCII).copyInto(passPlain)
        val bf = Blowfish(Blowfish.NIKON_KEY)
        val blob = ByteArray(97)
        encryptCbc(bf, ssidPlain).copyInto(blob, 0)
        encryptCbc(bf, passPlain).copyInto(blob, 32)
        blob[96] = security.toByte()
        return blob
    }

    private fun encryptCbc(bf: Blowfish, plain: ByteArray): ByteArray {
        val out = ByteArray(plain.size)
        var prevL = 0
        var prevR = 0
        var i = 0
        while (i + 8 <= plain.size) {
            val l = u32Be(plain, i) xor prevL
            val r = u32Be(plain, i + 4) xor prevR
            val e = bf.encryptBlock(l, r)
            putBe32(out, i, e.l)
            putBe32(out, i + 4, e.r)
            prevL = e.l
            prevR = e.r
            i += 8
        }
        return out
    }

    private fun u32Be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun putBe32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }
}
