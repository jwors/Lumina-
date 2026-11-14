package com.lumina.master

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M3-06 删除决策：同名/同 base 保护是"级联清理不误伤"的核心，锁成纯 JVM 测试。
 */
class GalleryDeletePlanTest {

    private fun row(name: String, date: String = "2026-11-01") = MasterInventoryEntity(
        relPath = "Master/$date/$name",
        origFileName = name,
        dateKey = date,
        sizeBytes = 1,
        ingestedAtMs = 0,
    )

    @Test
    fun `unique target purges name-keyed data and work`() {
        val plan = GalleryDeletePlan.plan(
            targets = listOf(row("A.JPG")),
            remaining = listOf(row("B.JPG")),
        )
        assertEquals(setOf("A.JPG"), plan.purgeNames)
        assertEquals(listOf("A.JPG"), plan.workNames)
    }

    @Test
    fun `same name on another date shields results and work`() {
        val plan = GalleryDeletePlan.plan(
            targets = listOf(row("A.JPG", "2026-11-01")),
            remaining = listOf(row("A.JPG", "2026-11-02")),
        )
        assertEquals(emptySet<String>(), plan.purgeNames)
        assertEquals(emptyList<String>(), plan.workNames)
    }

    @Test
    fun `same base NEF keeps shared work but purges the JPEG name`() {
        val plan = GalleryDeletePlan.plan(
            targets = listOf(row("A.JPG")),
            remaining = listOf(row("A.NEF")),
        )
        assertEquals(setOf("A.JPG"), plan.purgeNames)
        assertEquals(emptyList<String>(), plan.workNames)
    }

    @Test
    fun `case-only name difference keeps work shared`() {
        // 名称键控（智能结果）区分大小写；Work 文件名按 base 共享 → 保守保留
        val plan = GalleryDeletePlan.plan(
            targets = listOf(row("A.JPG")),
            remaining = listOf(row("a.jpg")),
        )
        assertEquals(setOf("A.JPG"), plan.purgeNames)
        assertEquals(emptyList<String>(), plan.workNames)
    }

    @Test
    fun `multiple unique targets all purge`() {
        val plan = GalleryDeletePlan.plan(
            targets = listOf(row("A.JPG"), row("B.JPG")),
            remaining = emptyList(),
        )
        assertEquals(setOf("A.JPG", "B.JPG"), plan.purgeNames)
        assertEquals(listOf("A.JPG", "B.JPG"), plan.workNames)
    }
}
