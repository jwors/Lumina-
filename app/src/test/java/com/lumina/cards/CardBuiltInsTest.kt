package com.lumina.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置卡契约：全部可解析 + 用户点名的三张风格卡在册 + 标注/文案步骤已停用。
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
        val cards = CardEngine.builtIns()
        val names = cards.map { it.name }
        assertTrue(names.contains("gc-minimal-zine-poster"))
        assertTrue(names.contains("pixel-style-poster-skill"))
        assertTrue(names.contains("scenes-gathered-zine"))
        assertEquals(4, cards.first { it.name == "gc-minimal-zine-poster" }.version)
        assertEquals(2, cards.first { it.name == "pixel-style-poster-skill" }.version)
        assertEquals(4, cards.first { it.name == "scenes-gathered-zine" }.version)
    }

    @Test
    fun `builtins carry no disabled annotate or caption steps`() {
        CardEngine.builtIns().forEach { card ->
            assertTrue(
                "step disabled: ${card.name}",
                card.steps.all { it.kind == StepKind.FILTER_SUGGEST || it.kind == StepKind.GENERATIVE },
            )
        }
    }

    @Test
    fun `annotate-only legacy builtins are retired`() {
        val names = CardEngine.builtIns().map { it.name }
        assertFalse(names.contains("徒步日记 · 文案"))
        assertFalse(names.contains("锐利风光 · 标注"))
        assertEquals(4, names.size)
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
