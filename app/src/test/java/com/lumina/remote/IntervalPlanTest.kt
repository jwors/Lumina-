package com.lumina.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** M6-02：间隔计划器的纯逻辑验收。 */
class IntervalPlanTest {

    private val plan = IntervalPlan(startAt = 1_000, intervalMs = 5_000, count = 3)

    @Test
    fun `shots are spaced by interval from startAt`() {
        assertEquals(1_000L, plan.shotAt(0))
        assertEquals(6_000L, plan.shotAt(1))
        assertEquals(11_000L, plan.shotAt(2))
        assertEquals(11_000L, plan.endAt)
    }

    @Test
    fun `due index counts the shot at its exact instant`() {
        assertEquals(0, plan.dueIndex(0))
        assertEquals(0, plan.dueIndex(999))
        assertEquals(1, plan.dueIndex(1_000))
        assertEquals(1, plan.dueIndex(5_999))
        assertEquals(2, plan.dueIndex(6_000))
        assertEquals(2, plan.dueIndex(10_999))
        assertEquals(3, plan.dueIndex(11_000))
        assertEquals(3, plan.dueIndex(99_999))
    }

    @Test
    fun `delayed start does not fire before startAt`() {
        val delayed = IntervalPlan(startAt = 10_000, intervalMs = 2_000, count = 2)
        assertEquals(0, delayed.dueIndex(9_999))
        assertEquals(1, delayed.dueIndex(10_000))
        assertEquals(2, delayed.dueIndex(12_000))
    }
}
