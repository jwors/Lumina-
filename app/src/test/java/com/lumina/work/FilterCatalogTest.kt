package com.lumina.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3-07：滤镜目录（内置 12 支按题材归组 + 用户导入合并）与一次选择的参数。
 * 锁的是 id 契约与分类归属——渲染层按 id 取 LUT；强度百分比只在这里取整。
 */
class FilterCatalogTest {

    private fun ids(entries: List<FilterEntry>) = entries.map { it.id }

    @Test
    fun `builtins mirror preset order and ids`() {
        val builtins = FilterCatalog.builtins()
        assertEquals(LutPresets.all.size, builtins.size)
        builtins.forEachIndexed { i, e ->
            assertEquals("builtin:$i", e.id)
            assertEquals(i, e.builtinIndex)
            assertTrue(e.isBuiltin)
            assertTrue(e.name.isNotBlank())
            assertNull(e.lutRelPath)
        }
    }

    @Test
    fun `preset names unique and categories known`() {
        val names = LutPresets.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it.isBlank() })
        LutPresets.all.forEach { assertTrue(it.category != FilterCategory.IMPORTED) }
    }

    @Test
    fun `every built-in category has at least one preset`() {
        val used = LutPresets.all.map { it.category }.toSet()
        FilterCategory.entries.filter { it != FilterCategory.IMPORTED }.forEach {
            assertTrue("category ${it.label} 没有预设", it in used)
        }
    }

    @Test
    fun `imported rows become imported entries keeping id and path`() {
        val rows = listOf(
            ImportedLut(id = 7, name = "Kodak 2383", relPath = "luts/k2383.cube"),
            ImportedLut(id = 9, name = "Fuji 3510", relPath = "luts/f3510.cube"),
        )
        val entries = FilterCatalog.imported(rows)
        assertEquals(listOf("local:7", "local:9"), ids(entries))
        assertEquals(FilterCategory.IMPORTED, entries[1].category)
        assertEquals("luts/f3510.cube", entries[1].lutRelPath)
        assertEquals(-1, entries[1].builtinIndex)
        assertFalse(entries[1].isBuiltin)
    }

    @Test
    fun `all keeps builtins first then imported`() {
        val rows = listOf(ImportedLut(1, "A", "luts/a.cube"))
        val all = FilterCatalog.all(rows)
        assertEquals(LutPresets.all.size + 1, all.size)
        assertEquals(LutPresets.all.size, all.indexOfFirst { it.id == "local:1" })
    }

    @Test
    fun `ids are unique across builtin and imported`() {
        val all = FilterCatalog.all(listOf(ImportedLut(1, "A", "luts/a.cube")))
        assertEquals(all.size, ids(all).toSet().size)
    }

    @Test
    fun `inCategory filters by category and null means all`() {
        val all = FilterCatalog.all(listOf(ImportedLut(1, "A", "luts/a.cube")))
        assertEquals(all, FilterCatalog.inCategory(all, null))
        val mono = FilterCatalog.inCategory(all, FilterCategory.MONO)
        assertEquals(LutPresets.all.count { it.category == FilterCategory.MONO }, mono.size)
        assertTrue(mono.all { it.category == FilterCategory.MONO })
        assertEquals(1, FilterCatalog.inCategory(all, FilterCategory.IMPORTED).size)
    }

    @Test
    fun `preset index round trips and rejects foreign presets`() {
        LutPresets.all.forEachIndexed { i, p -> assertEquals(i, LutPresets.indexOf(p)) }
        val foreign = LutPresets.Preset(
            name = "外部",
            category = FilterCategory.FILM,
            keys = listOf("x"),
            lut = CubeLut(size = 2, data = FloatArray(2 * 2 * 2 * 3)),
        )
        assertEquals(-1, LutPresets.indexOf(foreign))
    }

    @Test
    fun `selection clamps strength and rounds percent`() {
        val entry = FilterCatalog.builtins().first()
        assertEquals(1f, FilterSelection(entry, 2f).strength)
        assertEquals(0f, FilterSelection(entry, -1f).strength)
        assertEquals(100, FilterSelection(entry, 2f).percent)
        assertEquals(0, FilterSelection(entry, -1f).percent)
        assertEquals(50, FilterSelection(entry, 0.503f).percent)
        assertEquals(99, FilterSelection(entry, 0.994f).percent)
        assertEquals(100, FilterSelection(entry, 0.996f).percent)
    }

    @Test
    fun `selection equality keys on entry and raw strength`() {
        val a = FilterCatalog.builtins()[0]
        val b = FilterCatalog.builtins()[1]
        assertEquals(FilterSelection(a, 0.5f), FilterSelection(a, 0.5f))
        assertTrue(FilterSelection(a, 0.5f) != FilterSelection(a, 1f))
        assertTrue(FilterSelection(a, 0.5f) != FilterSelection(b, 0.5f))
        // 越界与夹紧后的值不等价——状态键要跟用户拖出来的原始值走
        assertTrue(FilterSelection(a, 2f) != FilterSelection(a, 1f))
    }
}
