package com.lumina.work

import org.junit.Assert.assertTrue
import org.junit.Test

class HybridLayoutTest {

    @Test
    fun portraitPhotoStaysInsideCanvas() {
        val box = HybridLayout.photoBox(1279, 1706)
        assertTrue(box.left >= 0)
        assertTrue(box.top >= 0)
        assertTrue(box.left + box.width <= 1056)
        assertTrue(box.top + box.height <= 1760)
        assertTrue(box.height > box.width)
    }

    @Test
    fun landscapePhotoIsWidthLimited() {
        val box = HybridLayout.photoBox(3000, 2000)
        assertTrue(box.width <= 700)
        assertTrue(box.left + box.width <= 1056)
        assertTrue(box.height < 900)
    }

    @Test
    fun zeroSizeDoesNotCrash() {
        val box = HybridLayout.photoBox(0, 0)
        assertTrue(box.width == 0 && box.height == 0)
    }

    @Test
    fun fieldOnLeftPutsPhotoRight() {
        val leftField = HybridLayout.photoBox(1279, 1706, fieldSide = FieldSide.LEFT)
        val rightField = HybridLayout.photoBox(1279, 1706, fieldSide = FieldSide.RIGHT)
        assertTrue("photo should sit right of the field-on-right layout", leftField.left > rightField.left)
        assertTrue("photo must not intrude into the left 35% field", leftField.left >= 1056 * 0.35f)
        assertTrue(leftField.left + leftField.width <= 1056)
    }

    @Test
    fun fieldOnRightPutsPhotoLeft() {
        val box = HybridLayout.photoBox(1279, 1706, fieldSide = FieldSide.RIGHT)
        assertTrue("photo should sit on the left edge", box.left <= 60)
        assertTrue(box.left >= 0)
        assertTrue(box.top >= 0)
        assertTrue("photo must not intrude into the right 35% field", box.left + box.width <= 1056 * 0.65f)
        assertTrue(box.top + box.height <= 1760)
    }
}
