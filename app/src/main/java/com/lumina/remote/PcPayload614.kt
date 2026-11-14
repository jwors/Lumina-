package com.lumina.remote

/**
 * 尼康自定义 Picture Control 的 614 字节 PTP 载荷（真机口径，见
 * `.scratch/m6-pro-remote/spikes/picture-control.md`）。
 *
 * 布局：
 *   +0x000  2   版本 02 00
 *   +0x002  20  ASCII 名称（NUL 填充）
 *   +0x019  1   控制点个数 0x08
 *   +0x02F  8   4 组 (x, y) 控制点
 *   +0x066  512 256 × u16 大端 LUT（0..32767）
 *
 * 只有 512B LUT 是成片真实生效的部分；控制点只给相机 LCD 画图。
 */
object PcPayload614 {
    const val SIZE = 614
    const val VERSION_OFFSET = 0x000
    const val NAME_OFFSET = 0x002
    const val NAME_LEN = 20
    const val POINT_COUNT_OFFSET = 0x019
    const val POINTS_OFFSET = 0x02F
    const val POINTS_LEN = 8
    const val LUT_OFFSET = 0x066
    const val LUT_COUNT = 256
    const val LUT_MAX = 32767

    /** 构造一份线性控制点 + 给定 256 点 LUT 的载荷。 */
    fun build(name: String, lut: IntArray, version: Int = 2): ByteArray {
        require(lut.size == LUT_COUNT) { "lut must have $LUT_COUNT points" }
        require(version in 1..2) { "unsupported version $version" }
        val bytes = ByteArray(SIZE)
        bytes[VERSION_OFFSET] = version.toByte()
        bytes[VERSION_OFFSET + 1] = 0
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        for (i in 0 until minOf(NAME_LEN, nameBytes.size)) {
            bytes[NAME_OFFSET + i] = nameBytes[i]
        }
        bytes[POINT_COUNT_OFFSET] = 8
        // 线性控制点 (0,0) (85,85) (170,170) (255,255)
        val points = intArrayOf(0, 0, 85, 85, 170, 170, 255, 255)
        for (i in points.indices) {
            bytes[POINTS_OFFSET + i] = points[i].toByte()
        }
        for (i in 0 until LUT_COUNT) {
            val v = lut[i].coerceIn(0, LUT_MAX)
            bytes[LUT_OFFSET + i * 2] = (v ushr 8).toByte()
            bytes[LUT_OFFSET + i * 2 + 1] = (v and 0xFF).toByte()
        }
        return bytes
    }

    fun isLikely(payload: ByteArray): Boolean =
        payload.size >= SIZE && payload[VERSION_OFFSET].toInt() in 1..2

    fun nameOf(payload: ByteArray): String {
        if (payload.size < NAME_OFFSET + NAME_LEN) return ""
        val slice = payload.copyOfRange(NAME_OFFSET, NAME_OFFSET + NAME_LEN)
        val end = slice.indexOfFirst { it == 0.toByte() }.let { if (it < 0) slice.size else it }
        return String(slice, 0, end, Charsets.US_ASCII)
    }

    /** 读 256 点 LUT；长度不足返回 null（不猜/不补）。 */
    fun lutOf(payload: ByteArray): IntArray? {
        if (payload.size < LUT_OFFSET + LUT_COUNT * 2) return null
        return IntArray(LUT_COUNT) { i ->
            val hi = payload[LUT_OFFSET + i * 2].toInt() and 0xFF
            val lo = payload[LUT_OFFSET + i * 2 + 1].toInt() and 0xFF
            (hi shl 8) or lo
        }
    }

    /** 线性 LUT，测试/预览用。 */
    fun linearLut(max: Int = LUT_MAX): IntArray =
        IntArray(LUT_COUNT) { i -> (i * max / (LUT_COUNT - 1)).coerceIn(0, max) }
}
