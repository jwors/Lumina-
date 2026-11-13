package com.lumina.probe

import com.lumina.work.FieldSide
import com.lumina.work.HybridComposition
import org.junit.Assert.assertTrue
import org.junit.Test

class IllustrationPromptCompilerTest {

    @Test
    fun promptPinsFieldToRequestedSide() {
        val page = ZinePlanner.plan(listOf("行山记"), HybridComposition.FIELD_RIGHT, "#3A3AD1").first()
        val prompt = IllustrationPromptCompiler.prompt("skill", page)
        assertTrue(prompt.contains("右侧（约 35% 宽）"))
        assertTrue(prompt.contains("左侧留作照片与标题的安静区"))
        assertTrue(prompt.contains("【整卷页序】"))
        assertTrue(prompt.contains("第 1 幕 / 共 1 幕"))
    }

    @Test
    fun defaultPromptKeepsLeftFieldWithoutPage() {
        val prompt = IllustrationPromptCompiler.prompt(null)
        assertTrue(prompt.contains("左侧（约 35% 宽）"))
        assertTrue(!prompt.contains("【整卷页序】"))
    }

    @Test
    fun fieldAnchorMentionsSameSide() {
        val page = ZinePlanner.plan(listOf("行山记"), HybridComposition.FIELD_RIGHT).first()
        val anchor = IllustrationPromptCompiler.fieldAnchor(page)
        assertTrue(anchor.contains("right third"))
        assertTrue(anchor.contains("left two-thirds"))
        assertTrue(IllustrationPromptCompiler.fieldAnchor().contains("left third"))
    }

    @Test
    fun fieldSideFallbackIsLeft() {
        val page = com.lumina.work.ZinePage(1, 1, "行山记", FieldSide.LEFT)
        assertTrue(IllustrationPromptCompiler.fieldAnchor(page).contains("left third"))
    }
}
