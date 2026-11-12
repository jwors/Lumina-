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
}
