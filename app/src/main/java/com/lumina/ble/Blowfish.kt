package com.lumina.ble

/**
 * 标准 Blowfish（公开算法）+ 尼康 SnapBridge 方言用的定键 CBC-MAC 式 hash。
 *
 * 相机在配对握手（stage2/stage3）与 Wi-Fi 凭据解密时用的都是这一套：
 * 定键 8 字节 `FF FF AA 55 11 22 33 00`，输入按 u32 对分组做 CBC-MAC，
 * 初值 0x01020304 / 0x05060708。
 */
internal class Blowfish private constructor(
    private val p: IntArray,
    private val s: Array<IntArray>,
) {

    constructor(key: ByteArray) : this(
        BlowfishTables.P.copyOf(),
        Array(4) { i ->
            when (i) {
                0 -> BlowfishTables.S0.copyOf()
                1 -> BlowfishTables.S1.copyOf()
                2 -> BlowfishTables.S2.copyOf()
                else -> BlowfishTables.S3.copyOf()
            }
        },
    ) {
        expandKey(key)
    }

    data class Block(val l: Int, val r: Int)

    fun encryptBlock(l: Int, r: Int): Block {
        var xl = l
        var xr = r
        for (i in 0 until 16) {
            xl = xl xor p[i]
            xr = xr xor f(xl)
            val t = xl; xl = xr; xr = t
        }
        val t = xl; xl = xr; xr = t
        xr = xr xor p[16]
        xl = xl xor p[17]
        return Block(xl, xr)
    }

    fun decryptBlock(l: Int, r: Int): Block {
        var xl = l
        var xr = r
        for (i in 17 downTo 2) {
            xl = xl xor p[i]
            xr = xr xor f(xl)
            val t = xl; xl = xr; xr = t
        }
        val t = xl; xl = xr; xr = t
        xr = xr xor p[1]
        xl = xl xor p[0]
        return Block(xl, xr)
    }

    /** CBC-MAC 式 hash：u32 块数须为偶数，返回最后一对密文。 */
    fun hash(blocks: IntArray): Block {
        require(blocks.size % 2 == 0) { "hash block count must be even: ${blocks.size}" }
        var left = 0x01020304
        var right = 0x05060708
        var i = 0
        while (i < blocks.size) {
            val b = encryptBlock(blocks[i] xor left, blocks[i + 1] xor right)
            left = b.l
            right = b.r
            i += 2
        }
        return Block(left, right)
    }

    private fun f(x: Int): Int {
        val a = s[0][(x ushr 24) and 0xFF]
        val b = s[1][(x ushr 16) and 0xFF]
        val c = s[2][(x ushr 8) and 0xFF]
        val d = s[3][x and 0xFF]
        return ((a + b) xor c) + d
    }

    private fun expandKey(key: ByteArray) {
        var k = 0
        for (i in 0 until 18) {
            var word = 0
            for (j in 0 until 4) {
                word = (word shl 8) or (key[k].toInt() and 0xFF)
                k = (k + 1) % key.size
            }
            p[i] = p[i] xor word
        }
        var bl = 0
        var br = 0
        var i = 0
        while (i < 18) {
            val b = encryptBlock(bl, br); bl = b.l; br = b.r
            p[i] = bl
            p[i + 1] = br
            i += 2
        }
        for (box in 0 until 4) {
            var j = 0
            while (j < 256) {
                val b = encryptBlock(bl, br); bl = b.l; br = b.r
                s[box][j] = bl
                s[box][j + 1] = br
                j += 2
            }
        }
    }

    companion object {
        /** SnapBridge 方言的固定 Blowfish 键（配对 hash 与旧款凭据解密共用）。 */
        val NIKON_KEY = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xAA.toByte(), 0x55,
            0x11, 0x22, 0x33, 0x00,
        )

        /** 便于单测构造已知向量的入口。 */
        fun withKey(key: ByteArray): Blowfish = Blowfish(key)
    }
}
