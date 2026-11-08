package com.lumina.work

/**
 * M3-02：3D LUT → 2D 图集（纯 Kotlin，JVM 可测）。
 *
 * 排布契约：x = g*N + r，y = b（即像素 = (b, g*N + r) 的 N²宽 × N 高 RGBA 位图）。
 * shader 采样端以 (c.r→x 内速、c.g→x 慢速、c.b→y) 三线性插值回查——坐标变换在
 * `Lut3dFragmentShader.frag`（内嵌字符串）与图集编码两端共用同一契约，双向互证。
 */
object LutAtlas {

    /** 产出 RGBA_8888 排布的字节序（每像素 4 字节，未 premultiplied 预乘）。 */
    fun rgbaBytes(lut: CubeLut): ByteArray {
        val n = lut.size
        val out = ByteArray(n * n * n * 4)
        var o = 0
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    val v = lut.at(b = b, g = g, r = r)
                    out[o] = (v[0] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
                    out[o + 1] = (v[1] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
                    out[o + 2] = (v[2] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
                    out[o + 3] = 0xFF.toByte()
                    o += 4
                }
            }
        }
        return out
    }
}
