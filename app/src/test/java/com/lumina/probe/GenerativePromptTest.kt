package com.lumina.probe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativePromptTest {

    @Test
    fun appendsPriorAndOrder() {
        val p = GenerativePrompt.assemble("BASE", "annotate：山脊", 0, 3)
        assertTrue(p.startsWith("BASE"))
        assertTrue(p.contains("annotate：山脊"))
        assertTrue(p.contains("第 1 张，共 3 张"))
        assertTrue(p.contains("第 1 幕"))
    }

    @Test
    fun omitsPriorBlockWhenBlank() {
        val p = GenerativePrompt.assemble("BASE", "", 2, 5)
        assertFalse(p.contains("前序结果"))
        assertTrue(p.contains("第 3 张，共 5 张"))
        assertTrue(p.contains("第 3 幕"))
    }

    @Test
    fun normalizesAspectTo3x5() {
        assertTrue(GenerativePrompt.normalizeAspect("竖幅 3:4 纸面").contains("3:5"))
        assertTrue(GenerativePrompt.normalizeAspect("横版 4:3").contains("3:5"))
        assertFalse(GenerativePrompt.normalizeAspect("竖幅 3:4").contains("3:4"))
    }

    @Test
    fun sanitizesSectionLabels() {
        val raw = """
            **Canvas and attention geometry.**
            Vertical 3:5 paper, torn edge.
            **1/4 · 画布与注意几何**
            大段正文保留。
            **Illustration field**
            插画正文。
        """.trimIndent()
        val clean = GenerativePrompt.sanitize(raw)
        assertFalse(clean.contains("Canvas and attention geometry"))
        assertFalse(clean.contains("1/4"))
        assertFalse(clean.contains("Illustration field"))
        assertTrue(clean.contains("Vertical 3:5 paper"))
        assertTrue(clean.contains("大段正文保留"))
    }
}
