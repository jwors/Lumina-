package com.lumina.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2-07 / 2026-11-15 步骤停用：内置卡与库的同步计划（播种 / 下架 / 刷新，墓碑不复活）。
 */
class CardSeedTest {

    private fun row(
        card: SkillCard,
        cardId: String = card.cardId,
        imported: Boolean = false,
        stepsJson: String = CardEngine.toJson(card),
        version: Int = card.version,
    ) = CardEntity(
        cardId = cardId, name = card.name, version = version,
        stepsJson = stepsJson, imported = imported, createdAtMs = 0,
    )

    @Test
    fun `fresh db seeds every builtin`() {
        val plan = CardEngine.syncPlan(emptyList(), emptySet())
        assertEquals(CardEngine.builtIns().map { it.name }, plan.seed.map { it.name })
        assertTrue(plan.retireCardIds.isEmpty())
        assertTrue(plan.refresh.isEmpty())
    }

    @Test
    fun `legacy annotate-only builtin is retired`() {
        val legacy = CardEngine.parse(
            """{"name":"徒步日记 · 文案","version":1,"steps":[{"type":"annotate","prompt":"x"}]}""",
        ) { "legacy-id" }
        val plan = CardEngine.syncPlan(listOf(row(legacy, imported = false)), emptySet())

        assertEquals(listOf("legacy-id"), plan.retireCardIds)
        assertFalse(plan.seed.any { it.name == legacy.name })
    }

    @Test
    fun `stale builtin definition is refreshed with its existing cardId`() {
        val gc = CardEngine.builtIns().first { it.name == "gc-minimal-zine-poster" }
        val old = CardEngine.parse(
            """{"name":"${gc.name}","version":1,"steps":[{"type":"annotate","prompt":"old"}]}""",
        ) { "stable-id" }
        val plan = CardEngine.syncPlan(listOf(row(old, cardId = "stable-id", version = 1)), emptySet())

        assertEquals("stable-id", plan.refresh.single().cardId)
        assertEquals(gc.version, plan.refresh.single().card.version)
        assertFalse(plan.seed.any { it.name == gc.name }) // 同名在库：刷新而非重播种
    }

    @Test
    fun `tombstoned builtin is not seeded`() {
        val deleted = CardEngine.builtIns().first().name
        val plan = CardEngine.syncPlan(emptyList(), setOf(deleted))
        assertEquals(CardEngine.builtIns().size - 1, plan.seed.size)
        assertFalse(plan.seed.any { it.name == deleted })
    }

    @Test
    fun `imported card with a builtin name blocks seeding`() {
        val gc = CardEngine.builtIns().first { it.name == "gc-minimal-zine-poster" }
        val plan = CardEngine.syncPlan(listOf(row(gc, imported = true, cardId = "imported-id")), emptySet())

        assertFalse(plan.seed.any { it.name == gc.name })
        assertTrue(plan.refresh.isEmpty())
        assertTrue(plan.retireCardIds.isEmpty())
    }
}
