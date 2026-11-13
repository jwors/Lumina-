package com.lumina.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPaletteTest {

    @Test
    fun picksRedAsAccent() {
        val pixels = IntArray(64) { 0xFFFF0000.toInt() }
        assertEquals("#D13A3A", PhotoPalette.accentHex(pixels))
    }

    @Test
    fun grayFallsBackToDefaultHue() {
        val pixels = IntArray(64) { 0xFF808080.toInt() }
        assertEquals(PhotoPalette.FALLBACK_HEX, PhotoPalette.accentHex(pixels))
    }

    @Test
    fun emptyFallsBack() {
        assertEquals(PhotoPalette.FALLBACK_HEX, PhotoPalette.accentHex(IntArray(0)))
    }

    @Test
    fun hsvConversionStaysInRange() {
        val (r, g, b) = PhotoPalette.hsvToRgb(240.0, 1.0, 1.0)
        assertEquals(0, r)
        assertEquals(0, g)
        assertEquals(255, b)
        assertTrue(PhotoPalette.hsvToRgb(360.0, 0.5, 0.5).first in 0..255)
    }
}
