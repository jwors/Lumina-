package com.lumina.work

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M3-02：图集编码契约测试 —— 与 Lut3DFilter 的 shader 坐标算式（x = g*N + r, y = b）双向互证。
 */
class LutAtlasTest {

    @Test
    fun `atlas layout is x = g*n plus r and y = b`() {
        val n = 3
        // 步进恒等格：out.rgb = (r/(n-1), g/(n-1), b/(n-1))
        val data = FloatArray(n * n * n * 3)
        var o = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            data[o] = r / (n - 1f); data[o + 1] = g / (n - 1f); data[o + 2] = b / (n - 1f); o += 3
        }
        val lut = CubeLut(size = n, data = data)
        val bytes = LutAtlas.rgbaBytes(lut)

        // 像素 (x= g*N + r, y=b)：R 通道应等于 r/(n-1)。抽验四角与一个中位像素。
        fun pixelR(y: Int, g: Int, r: Int): Int =
            bytes[((y * (n * n)) + (g * n + r)) * 4].toInt() and 0xFF

        assertEquals(0, pixelR(0, 0, 0))                   // r=0 → 0
        assertEquals(255, pixelR(0, 0, n - 1))             // r=2 → 255
        assertEquals(0, pixelR(0, 1, 0))                   // 内层 x 仍由 r 决定 → 0
        assertEquals(0, pixelR(n / 2, n / 2, 0))           // g 变化不改 R → 0
        val mid = bytes[(2 * (n * n) + (1 * n + 1)) * 4 + 2].toInt() and 0xFF   // (y=2, g1r1) 的 B 通道 = b=2 → 255
        assertEquals(255, mid)
    }
}
