package com.lumina.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** M6-05：包围计划器纯逻辑。 */
class BracketPlanTest {

    @Test
    fun `exposure bracket walks start plus step`() {
        val plan = ExposureBracketPlan(startMilliEv = -2000, stepMilliEv = 1000, count = 3)
        assertEquals(listOf(-2000, -1000, 0), plan.values)
    }

    @Test
    fun `exposure bracket clamps to ptp range`() {
        val plan = ExposureBracketPlan(startMilliEv = 4500, stepMilliEv = 1000, count = 3)
        assertEquals(listOf(4500, 5000, 5000), plan.values)
    }

    @Test
    fun `exposure bracket rejects zero step for multiple shots`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExposureBracketPlan(startMilliEv = 0, stepMilliEv = 0, count = 3)
        }
    }

    @Test
    fun `focus bracket returns by opposite direction`() {
        val plan = FocusBracketPlan(step = 2, count = 3, direction = 1)
        assertEquals(0, plan.oppositeDirection)
        assertEquals(4, plan.returnStep)
    }

    @Test
    fun `focus bracket validates direction and step`() {
        assertThrows(IllegalArgumentException::class.java) { FocusBracketPlan(step = 0, count = 3, direction = 1) }
        assertThrows(IllegalArgumentException::class.java) { FocusBracketPlan(step = 2, count = 3, direction = 2) }
    }
}
