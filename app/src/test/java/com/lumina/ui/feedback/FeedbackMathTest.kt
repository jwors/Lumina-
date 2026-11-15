package com.lumina.ui.feedback

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackMathTest {

    @Test
    fun centerPressHasNoPull() {
        val out = magneticOffset(Offset(50f, 20f), IntSize(100, 40), maxPullPx = 8f)
        assertEquals(Offset.Zero, out)
    }

    @Test
    fun edgePressPullsTowardTouchByMax() {
        val out = magneticOffset(Offset(100f, 20f), IntSize(100, 40), maxPullPx = 8f)
        assertEquals(8f, out.x, 0.001f)
        assertEquals(0f, out.y, 0.001f)
    }

    @Test
    fun cornerPressIsClampedToMaxLength() {
        val out = magneticOffset(Offset(100f, 40f), IntSize(100, 40), maxPullPx = 8f)
        assertEquals(8f, out.getDistance(), 0.001f)
        assertTrue(out.x > 0f && out.y > 0f)
    }

    @Test
    fun pressOutsideBoundsStillClampsToMax() {
        val out = magneticOffset(Offset(500f, -300f), IntSize(100, 40), maxPullPx = 8f)
        assertEquals(8f, out.getDistance(), 0.001f)
    }

    @Test
    fun degenerateSizeReturnsZero() {
        assertEquals(Offset.Zero, magneticOffset(Offset(10f, 10f), IntSize.Zero, 8f))
        assertEquals(Offset.Zero, magneticOffset(Offset(10f, 10f), IntSize(0, 40), 8f))
    }

    @Test
    fun nonPositivePullReturnsZero() {
        assertEquals(Offset.Zero, magneticOffset(Offset(100f, 20f), IntSize(100, 40), 0f))
        assertEquals(Offset.Zero, magneticOffset(Offset(100f, 20f), IntSize(100, 40), -4f))
    }

    @Test
    fun squashPeaksAtMidpointAndVanishesAtEnds() {
        assertEquals(0f, switchSquash(0f), 0.001f)
        assertEquals(1f, switchSquash(0.5f), 0.001f)
        assertEquals(0f, switchSquash(1f), 0.001f)
    }

    @Test
    fun squashClampsOvershootFromSpring() {
        assertEquals(0f, switchSquash(1.2f), 0.001f)
        assertEquals(0f, switchSquash(-0.2f), 0.001f)
    }
}
