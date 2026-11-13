package com.lumina.probe

import com.lumina.work.FieldSide
import com.lumina.work.HybridComposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZinePlannerTest {

    @Test
    fun autoAlternatesFieldSideByPage() {
        val pages = ZinePlanner.plan(listOf("山脊", "云海", "归途"), HybridComposition.AUTO)
        assertEquals(3, pages.size)
        assertEquals(listOf(FieldSide.LEFT, FieldSide.RIGHT, FieldSide.LEFT), pages.map { it.fieldSide })
        assertEquals(listOf(1, 2, 3), pages.map { it.number })
        assertTrue(pages.all { it.total == 3 })
        assertTrue(pages.all { it.issueTitle == "山脊" })
    }

    @Test
    fun explicitCompositionPinsEveryPage() {
        val left = ZinePlanner.plan(listOf("A", "B"), HybridComposition.FIELD_LEFT)
        assertTrue(left.all { it.fieldSide == FieldSide.LEFT })
        val right = ZinePlanner.plan(listOf("A", "B"), HybridComposition.FIELD_RIGHT)
        assertTrue(right.all { it.fieldSide == FieldSide.RIGHT })
    }

    @Test
    fun issueTitleSkipsFallbackTitle() {
        assertEquals("山风正好", ZinePlanner.issueTitle(listOf("行山记", "山风正好")))
        assertEquals("行山记", ZinePlanner.issueTitle(listOf("行山记", "行山记")))
        assertEquals("行山记", ZinePlanner.issueTitle(emptyList()))
    }

    @Test
    fun directiveCarriesFolioAccentAndQuietSide() {
        val page = ZinePlanner.plan(listOf("行山记", "云海"), HybridComposition.FIELD_RIGHT, "#3A3AD1")[1]
        val text = ZinePlanner.promptDirective(page)
        assertTrue(text.contains("《云海》"))
        assertTrue(text.contains("第 2 幕 / 共 2 幕"))
        assertTrue(text.contains("#3A3AD1"))
        assertTrue(text.contains("右侧"))
        assertTrue(text.contains("左侧留作照片与标题的安静区"))
    }
}
