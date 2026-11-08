package com.lumina.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * M3-02：.cube 解析器全路径（好档/坏档/1D 拒收/维度/越界）。
 */
class CubeParserTest {

    private fun goodCube(n: Int = 2, prefix: String = ""): String = buildString {
        if (prefix.isNotEmpty()) append(prefix)
        appendLine("LUT_3D_SIZE $n")
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            appendLine("${r / (n - 1.0)} ${g / (n - 1.0)} ${b / (n - 1.0)}")
        }
    }

    @Test
    fun `parses minimal 3d cube and keeps r-fastest order`() {
        val lut = CubeParser.parse(goodCube(2))
        assertEquals(2, lut.size)
        // index = (b*N + g)*N + r → 每格输出 = (r/1, g/1, b/1)（本档即恒等映射）
        assertEquals(listOf(0f, 0f, 0f), lut.at(0, 0, 0).toList())
        assertEquals(listOf(1f, 0f, 0f), lut.at(0, 0, 1).toList())
        assertEquals(listOf(0f, 1f, 0f), lut.at(0, 1, 0).toList())
        assertEquals(listOf(0f, 1f, 1f), lut.at(1, 1, 0).toList())  // b 慢速轴在后
        assertEquals(listOf(1f, 1f, 1f), lut.at(1, 1, 1).toList())
    }

    @Test
    fun `comments blanks and title tolerated`() {
        val text = "# handmade\nTITLE \"x\"\n\nLUT_3D_SIZE 2\n# data below\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n"
        val lut = CubeParser.parse(text)
        assertEquals(2, lut.size)
    }

    @Test
    fun `input domain gets normalized to 0_1`() {
        val text = buildString {
            appendLine("LUT_3D_INPUT_MIN -1.0")
            appendLine("LUT_3D_INPUT_MAX 1.0")
            appendLine("LUT_3D_SIZE 2")
            // 格位输入上限=1 → 输出 = (in-min)/(max-min)：0→0.5, 1→1.0
            for (b in 0 until 2) for (g in 0 until 2) for (r in 0 until 2) {
                appendLine("0.0 0.5 1.0")
            }
        }
        val lut = CubeParser.parse(text)
        // 所有格位输出恒 (0, 0.5, 1) 原值 → 归一 (0.5, 0.75, 1.0)（span=2）
        assertEquals(0.5f, lut.at(1, 1, 0)[0], 1e-6f)
        assertEquals(0.75f, lut.at(1, 1, 0)[1], 1e-6f)
        assertEquals(1.0f, lut.at(1, 1, 1)[2], 1e-6f)
    }

    @Test
    fun `rejects 1d lut`() {
        assertThrows(CubeLutException::class.java) {
            CubeParser.parse("LUT_1D_SIZE 16\n0 0 0\n1 1 1\n")
        }
    }

    @Test
    fun `rejects missing header`() {
        assertThrows(CubeLutException::class.java) { CubeParser.parse("0 0 0\n1 1 1\n") }
    }

    @Test
    fun `rejects shortage of data values`() {
        val text = "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n"    // 期望 24 值，给 6
        assertThrows(CubeLutException::class.java) { CubeParser.parse(text) }
    }

    @Test
    fun `rejects non numeric data row`() {
        val text = "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 x\n1 1 1\n"
        assertThrows(CubeLutException::class.java) { CubeParser.parse(text) }
    }

    @Test
    fun `rejects value out of declared domain`() {
        val text = buildString {
            appendLine("LUT_3D_INPUT_MIN 0.0")
            appendLine("LUT_3D_INPUT_MAX 0.5")
            appendLine("LUT_3D_SIZE 2")
            for (b in 0 until 2) for (g in 0 until 2) for (r in 0 until 2) {
                appendLine("0.0 0.25 0.9")   // 0.9 越域
            }
        }
        assertThrows(CubeLutException::class.java) { CubeParser.parse(text) }
    }

    @Test
    fun `rejects absurd lattice size`() {
        val text = "LUT_3D_SIZE 1000\n"
        assertThrows(CubeLutException::class.java) { CubeParser.parse(text) }
    }
}
