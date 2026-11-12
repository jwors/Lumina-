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
}
