package com.lumina.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * M2-07 配方删除：内置卡播种过滤（已有名 / 删除墓碑都不补，新内置仍补）。
 */
class CardSeedTest {

    @Test
    fun `fresh install seeds every builtin`() {
        val expected = CardEngine.builtIns().map { it.name }
        val seeded = CardEngine.builtInsToSeed(emptySet(), emptySet()).map { it.name }
        assertEquals(expected, seeded)
    }

    @Test
    fun `existing names and tombstones are skipped`() {
        val all = CardEngine.builtIns()
        val existing = setOf(all[0].name)
        val deleted = setOf(all[1].name)

        val seeded = CardEngine.builtInsToSeed(existing, deleted)

        assertFalse("existing name must not re-seed", seeded.any { it.name == all[0].name })
        assertFalse("tombstoned builtin must not revive", seeded.any { it.name == all[1].name })
        assertEquals(all.size - 2, seeded.size)
    }

    @Test
    fun `deleting one builtin still seeds the others`() {
        val all = CardEngine.builtIns()
        val seeded = CardEngine.builtInsToSeed(emptySet(), setOf(all[2].name))
        assertEquals(all.size - 1, seeded.size)
        assertEquals(all.map { it.name } - all[2].name, seeded.map { it.name })
    }
}
