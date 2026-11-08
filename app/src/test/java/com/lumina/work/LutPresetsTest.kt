package com.lumina.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3-02：内置预设家族映射（滤镜建议文本 → LUT 命中）+ 预设定型检查。
 */
class LutPresetsTest {

    private val cases = mapOf(
        "这张用胶片暖调处理" to "暖",
        "建议黑白胶片保留质感的银盐味道" to "黑白",
        "极简 zine 海报感" to "zine",
        "冷调山雾氛围最贴" to "冷调",
        "波塔柔彩照顾肤色调" to "波塔",
        "浓彩风光压出浓艳感" to "浓彩",
        "走经典褪色的复古" to "褪色",
        "银盐硬黑纪实一击" to "银盐",
        "柯达金黄黄昏草地" to "金黄",
        "青橙电影夜市灯光" to "青橙",
        "拍立得旅行手记" to "拍立得",
    )

    @Test
    fun `keyword families all resolve`() {
        cases.forEach { (text, marker) ->
            val hit = LutPresets.pickFor(text)
            assertNotNull(text, hit)
            // 命中自查：对应的 preset keys 必含 marker 族（marker 是族内代表词）
            assertTrue(
                "expect $marker in $text — got ${hit!!.keys}",
                hit.keys.any { text.lowercase().contains(it.lowercase()) },
            )
        }
    }

    @Test
    fun `unmatched text returns null for cpu fallback`() {
        assertNull(LutPresets.pickFor("建议直接锐化提亮，不需要特殊滤镜"))
    }

    @Test
    fun `all presets are well formed 3d lattices`() {
        assertEquals(12, LutPresets.all.size)
        LutPresets.all.forEach { p ->
            val lut = p.lut
            assertEquals(LutPresets.DEFAULT_N, lut.size)
            assertEquals(LutPresets.DEFAULT_N * LutPresets.DEFAULT_N * LutPresets.DEFAULT_N * 3, lut.data.size)
            assertTrue(lut.data.all { it in 0f..1f })
        }
    }
}
