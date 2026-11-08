package com.lumina.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置卡播种契约（M3 起点）：全部可解析 + 用户点名的三张风格卡在册。
 */
class CardBuiltInsTest {

    @Test
    fun `all builtins parse with sane versions`() {
        CardEngine.builtIns().forEach { card ->
            assertTrue(card.name.isNotBlank())
            assertTrue("version ≥1: ${card.name}", card.version >= 1)
            assertTrue("steps ≥1: ${card.name}", card.steps.isNotEmpty())
        }
    }

    @Test
    fun `user-named skill cards are registered`() {
        val names = CardEngine.builtIns().map { it.name }
        assertTrue(names.contains("gc-minimal-zine-poster"))
        assertTrue(names.contains("pixel-style-poster-skill"))
        assertTrue(names.contains("scenes-gathered-zine"))
        assertEquals(3, CardEngine.builtIns().first { it.name == "gc-minimal-zine-poster" }.version)
        assertEquals(1, CardEngine.builtIns().first { it.name == "pixel-style-poster-skill" }.version)
        assertEquals(3, CardEngine.builtIns().first { it.name == "scenes-gathered-zine" }.version)
    }

    @Test
    fun `roundtrip export keeps name version and step chain`() {
        val card = CardEngine.builtIns().first { it.name == "gc-minimal-zine-poster" }
        val json = CardEngine.toJson(card)
        val back = CardEngine.parse(json) { "re" }
        assertEquals(card.name, back.name)
        assertEquals(card.version, back.version)
        assertEquals(card.steps.map { it.kind }, back.steps.map { it.kind })
    }
}
