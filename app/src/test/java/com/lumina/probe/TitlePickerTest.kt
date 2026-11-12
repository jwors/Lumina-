package com.lumina.probe

import com.lumina.pass.SmartResultEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TitlePickerTest {

    private fun r(kind: String, text: String) = SmartResultEntity(0, "x.jpg", "c", kind, text, "m", 0)

    @Test
    fun prefersCaptionAndStripsPrefix() {
        val t = TitlePicker.pick(listOf(
            r("annotate", "山脊线上的风很大"),
            r("caption", "caption：山风正好\n小注"),
        ))
        assertEquals("山风正好", t)
    }

    @Test
    fun fallsBackToAnnotationFirstClause() {
        val t = TitlePicker.pick(listOf(r("annotate", "云层压得很低，远处的山脊若隐若现。")))
        assertEquals("云层压得很低", t)
    }

    @Test
    fun fallsBackToDefaultWhenEmpty() {
        assertEquals(TitlePicker.FALLBACK, TitlePicker.pick(emptyList()))
    }

    @Test
    fun stripsLabelAndMarkdownFromCaption() {
        val t = TitlePicker.pick(listOf(r("caption", "主标题：**把天空穿在身上**\n小注：2026-11-11")))
        assertEquals("把天空穿在身上", t)
    }

    @Test
    fun clampsLongTitles() {
        val t = TitlePicker.pick(listOf(r("caption", "caption：" + "很长的标题".repeat(10))))
        assertEquals(TitlePicker.MAX_CHARS, t.length)
    }
}
