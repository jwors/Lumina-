package com.lumina.ble

import java.security.SecureRandom

/** 尼康智能设备模式（LSS）的 GATT UUID。 */
object NikonBleUuids {
    const val SERVICE = "0000de00-3dd4-4255-8d62-6dc7b9bd5561"
    const val PAIR = "00002000-3dd4-4255-8d62-6dc7b9bd5561"
    const val NAME = "00002002-3dd4-4255-8d62-6dc7b9bd5561"
    const val CONFIG = "00002004-3dd4-4255-8d62-6dc7b9bd5561"
    const val WIFI = "00002005-3dd4-4255-8d62-6dc7b9bd5561"
    const val TIME = "00002006-3dd4-4255-8d62-6dc7b9bd5561"
    const val GEO = "00002007-3dd4-4255-8d62-6dc7b9bd5561"
    const val NOT1 = "00002008-3dd4-4255-8d62-6dc7b9bd5561"
    const val NOT2 = "0000200a-3dd4-4255-8d62-6dc7b9bd5561"
    const val STATUS_FOR_CONTROL = "00002020-3dd4-4255-8d62-6dc7b9bd5561"
    const val CONTROL_POINT = "00002021-3dd4-4255-8d62-6dc7b9bd5561"
    const val BATTERY = "00002a19-3dd4-4255-8d62-6dc7b9bd5561"

    /** 尼康 BLE 厂商数据公司 ID（广播里用于识别已配对相机）。 */
    const val COMPANY_ID = 0x0399
}

/**
 * 17 字节配对帧（小端）：stage(u8) + timestamp(u64) + device(u32) + nonce(u32)。
 * 与 SnapBridge 方言逐字节一致。
 */
data class PairingMessage(
    val stage: Int,
    val timestamp: Long,
    val device: Int,
    val nonce: Int,
) {
    fun encode(): ByteArray {
        val b = ByteArray(LENGTH)
        b[0] = stage.toByte()
        for (i in 0 until 8) b[1 + i] = ((timestamp ushr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 4) b[9 + i] = ((device ushr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 4) b[13 + i] = ((nonce ushr (8 * i)) and 0xFF).toByte()
        return b
    }

    companion object {
        const val LENGTH = 17

        fun decode(raw: ByteArray): PairingMessage {
            require(raw.size == LENGTH) { "配对帧须为 $LENGTH 字节，实得 ${raw.size}" }
            var ts = 0L
            for (i in 7 downTo 0) ts = (ts shl 8) or (raw[1 + i].toLong() and 0xFF)
            var dev = 0
            for (i in 3 downTo 0) dev = (dev shl 8) or (raw[9 + i].toInt() and 0xFF)
            var nonce = 0
            for (i in 3 downTo 0) nonce = (nonce shl 8) or (raw[13 + i].toInt() and 0xFF)
            return PairingMessage(raw[0].toInt() and 0xFF, ts, dev, nonce)
        }
    }
}

/**
 * SnapBridge 四步配对握手：
 * stage1 我方发随机身份 → stage2 相机挑战 → stage3 用命中的 salt 回应 → stage4 相机回序列号。
 * stage2/stage3 的 hash 是定键 Blowfish 的 CBC-MAC。
 */
class NikonPairingEngine(private val random: java.util.Random = SecureRandom()) {

    fun createStage1(savedDevice: Int? = null, savedNonce: Int? = null): PairingMessage {
        val device = savedDevice ?: ((random.nextInt() and 0xFFFFFF00.toInt()) or 0x01)
        val nonce = savedNonce ?: random.nextInt()
        return PairingMessage(0x01, random.nextLong(), device, nonce)
    }

    /** 校验 stage2 并构建 stage3；无 salt 命中返回 null（握手失败）。 */
    fun verifyStage2AndBuildStage3(stage1: PairingMessage, stage2: PairingMessage): Pair<PairingMessage, Int>? {
        val ourLo = tsLowBe(stage1.timestamp)
        val ourHi = tsHighBe(stage1.timestamp)
        val camLo = tsLowBe(stage2.timestamp)
        val camHi = tsHighBe(stage2.timestamp)
        val expectDevice = reverseU32(stage2.device)
        val expectNonce = reverseU32(stage2.nonce)
        val hasher = Blowfish(Blowfish.NIKON_KEY)
        for ((idx, salt) in SALTS.withIndex()) {
            val h = hasher.hash(intArrayOf(salt[0], salt[1], camLo, camHi, ourLo, ourHi))
            if (h.l == expectDevice && h.r == expectNonce) {
                val back = hasher.hash(intArrayOf(salt[0], salt[1], ourLo, ourHi, camLo, camHi))
                return PairingMessage(
                    stage = 0x03,
                    timestamp = stage1.timestamp,
                    device = reverseU32(back.l),
                    nonce = reverseU32(back.r),
                ) to idx
            }
        }
        return null
    }

    /** stage4 帧字节 9..16 = 相机 BLE 侧序列号（ASCII，NUL 截止）。 */
    fun extractSerial(stage4: PairingMessage): String {
        val raw = stage4.encode()
        val sb = StringBuilder()
        for (i in 9 until 17) {
            if (raw[i].toInt() == 0) break
            sb.append(raw[i].toInt().toChar())
        }
        return sb.toString()
    }

    companion object {
        /** 8 组固定挑战 salt（协议方言，顺序即 ctx 的 idx 位）。 */
        val SALTS = arrayOf(
            intArrayOf(0x704066E4, 0x0433D552),
            intArrayOf(0xED4B8FAC.toInt(), 0x15F7E47B),
            intArrayOf(0x24471F11, 0x8B5EA1FC.toInt()),
            intArrayOf(0x05960C31, 0x2B8C7F41),
            intArrayOf(0xFDA588C1.toInt(), 0xEBA8B1F3.toInt()),
            intArrayOf(0x99166056.toInt(), 0x1BD3D550),
            intArrayOf(0xCD32687F.toInt(), 0xA9E28A30.toInt()),
            intArrayOf(0x2A8FE834, 0xDEC7EBF4.toInt()),
        )

        fun reverseU32(v: Int): Int =
            ((v and 0xFF) shl 24) or
                ((v and 0xFF00) shl 8) or
                ((v ushr 8) and 0xFF00) or
                ((v ushr 24) and 0xFF)

        private fun tsLowBe(ts: Long): Int = reverseU32(ts.toInt())
        private fun tsHighBe(ts: Long): Int = reverseU32((ts ushr 32).toInt())
    }
}

/** 0x2004 凭据解密的握手材料（字段一律线上原样字节）。 */
data class LssMaterial(
    val key1: ByteArray,
    val key2: ByteArray,
    val saltIndex: Int,
    val cameraNonce: ByteArray,
    val phoneNonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is LssMaterial &&
        key1.contentEquals(other.key1) && key2.contentEquals(other.key2) &&
        saltIndex == other.saltIndex &&
        cameraNonce.contentEquals(other.cameraNonce) &&
        phoneNonce.contentEquals(other.phoneNonce)

    override fun hashCode(): Int = listOf(
        key1.contentHashCode(), key2.contentHashCode(), saltIndex,
        cameraNonce.contentHashCode(), phoneNonce.contentHashCode(),
    ).hashCode()
}

/** 相机热点凭据。security：0=开放，1=WPA2，2=WPA3，3=WPA2+WPA3，0xFF=未定义。 */
data class WifiCredentials(
    val ssid: String,
    val password: String,
    val security: Int,
    val keySource: String,
) {
    val securityLabel: String
        get() = when (security) {
            0x00 -> "开放"
            0x01 -> "WPA2"
            0x02 -> "WPA3"
            0x03 -> "WPA2/WPA3"
            0xFF -> "未定义"
            else -> "未知($security)"
        }
}

/**
 * 解 0x2004 的 97B/98B 凭据：32B 密文 SSID + 64B 密文密码 + 1B 安全模式。
 * 主路是 LSS 会话密钥派生（八组候选，可打印性 oracle 裁决），兜底是尼康定键直接解。
 * 全部候选失败返回 null——不臆造明文。
 */
object WifiCredentialsDecoder {

    private data class Layout(val name: String, val ssidOff: Int, val passOff: Int, val secOff: Int)

    fun decode(blob: ByteArray, lss: LssMaterial? = null): WifiCredentials? {
        if (blob.size < 97) return null
        val layouts = buildList {
            add(Layout("l97", 0, 32, 96))
            if (blob.size >= 98) add(Layout("l98", 1, 33, 97))
        }
        for (lay in layouts) {
            val security = blob[lay.secOff].toInt() and 0xFF
            if (security > 0x04 && security != 0xFF) continue
            val cipherSsid = blob.copyOfRange(lay.ssidOff, lay.ssidOff + 32)
            val cipherPassword = blob.copyOfRange(lay.passOff, lay.passOff + 64)
            if (lss != null) {
                for ((tag, key) in lssSessionKeyCandidates(lss)) {
                    val hasher = Blowfish(key)
                    val ssid = decryptCbc(hasher, cipherSsid)
                    if (!isPaddedPrintable(ssid, allowEmpty = false)) continue
                    val password = decryptCbc(hasher, cipherPassword)
                    if (!isPaddedPrintable(password, allowEmpty = security == 0x00 || security == 0x01)) continue
                    return WifiCredentials(
                        normalizeSsid(trimNul(ssid)), trimNul(password), security,
                        "lss-kdf $tag lay=${lay.name}",
                    )
                }
            }
            if (lay.name == "l97") {
                for (cbc in listOf(false, true)) {
                    val hasher = Blowfish(Blowfish.NIKON_KEY)
                    val ssid = decrypt(hasher, cipherSsid, cbc)
                    if (!isPaddedPrintable(ssid, allowEmpty = false)) continue
                    val password = decrypt(hasher, cipherPassword, cbc)
                    if (!isPaddedPrintable(password, allowEmpty = security == 0x00 || security == 0x01)) continue
                    return WifiCredentials(
                        normalizeSsid(trimNul(ssid)), trimNul(password), security,
                        "fixed-nikon-key${if (cbc) "+cbc-iv0" else "+ecb"}",
                    )
                }
            }
        }
        return null
    }

    /**
     * LSS 会话密钥候选：`ctx = idx‖N_A[1..3]‖X[0..3]`，`K = MAC8(key1‖key2‖ctx)`。
     * 字节序与 key1 语义留候选变体，由可打印性 oracle 裁决。
     */
    fun lssSessionKeyCandidates(m: LssMaterial): List<Pair<String, ByteArray>> {
        if (m.key1.size != 8 || m.key2.size != 8 ||
            m.cameraNonce.size != 8 || m.phoneNonce.size != 8
        ) {
            return emptyList()
        }
        val salt = NikonPairingEngine.SALTS[m.saltIndex.coerceIn(0, 7)]
        val key1s = listOf(
            "k1=stage4" to m.key1,
            "k1=salt" to (be32(salt[0]) + be32(salt[1])),
        )
        val ctxShapes = listOf(
            Triple("na1-3,x0-3", 1, 0),
            Triple("na0-2,x0-3", 0, 0),
            Triple("na1-3,x4-7", 1, 4),
            Triple("na0-2,x4-7", 0, 4),
        )
        val mac = Blowfish(Blowfish.NIKON_KEY)
        val out = ArrayList<Pair<String, ByteArray>>(8)
        for ((k1Tag, key1) in key1s) {
            for ((ctxTag, naOff, xOff) in ctxShapes) {
                val nA = m.cameraNonce
                val x = m.phoneNonce
                val ctx = byteArrayOf(
                    m.saltIndex.toByte(),
                    nA[naOff], nA[naOff + 1], nA[naOff + 2],
                    x[xOff], x[xOff + 1], x[xOff + 2], x[xOff + 3],
                )
                val h = mac.hash(u32BePair(key1) + u32BePair(m.key2) + u32BePair(ctx))
                out.add("ctx=$ctxTag $k1Tag" to (be32(h.l) + be32(h.r)))
            }
        }
        return out
    }

    private fun decrypt(hasher: Blowfish, cipher: ByteArray, cbc: Boolean): ByteArray {
        val out = ByteArray(cipher.size)
        var prevL = 0
        var prevR = 0
        var i = 0
        while (i + 8 <= cipher.size) {
            val cl = u32Be(cipher, i)
            val cr = u32Be(cipher, i + 4)
            val d = hasher.decryptBlock(cl, cr)
            val l = if (cbc) d.l xor prevL else d.l
            val r = if (cbc) d.r xor prevR else d.r
            putBe32(out, i, l)
            putBe32(out, i + 4, r)
            prevL = cl
            prevR = cr
            i += 8
        }
        return out
    }

    private fun decryptCbc(hasher: Blowfish, cipher: ByteArray): ByteArray = decrypt(hasher, cipher, true)

    private fun u32Be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun be32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun putBe32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun u32BePair(b: ByteArray): IntArray = intArrayOf(u32Be(b, 0), u32Be(b, 4))

    private fun isPaddedPrintable(bytes: ByteArray, allowEmpty: Boolean): Boolean {
        var printable = 0
        var seenZero = false
        for (raw in bytes) {
            val b = raw.toInt() and 0xFF
            if (b == 0) {
                seenZero = true
                continue
            }
            if (seenZero) return false
            if (b < 0x20 || b > 0x7E) return false
            printable++
        }
        return printable > 0 || allowEmpty
    }

    private fun trimNul(bytes: ByteArray): String {
        var end = bytes.size
        while (end > 0 && bytes[end - 1].toInt() == 0) end--
        return String(bytes, 0, end, Charsets.US_ASCII)
    }

    /**
     * Z30 真机（2026-11-14）：0x2004 里解出的 SSID 带 `_SnapBridge` 后缀，
     * 但相机实际广播的是去掉后缀的名字（扫描实测 `NIKON_Z_30_82949` vs
     * 解密值 `NIKON_Z_30_82949_SnapBridge`）。不剥掉就会一直 NO AP FOUND。
     */
    private fun normalizeSsid(ssid: String): String = ssid.removeSuffix("_SnapBridge")
}
