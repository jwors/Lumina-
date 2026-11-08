package com.lumina.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M3 工单 01：ThumbCache 缓存账（纯 JVM——淘汰策略是手写字节预算 LRU）。
 */
class ThumbCacheTest {

    @Before
    fun reset() { ThumbCache.clear() }

    @Test
    fun `put get and clear roundtrip`() {
        ThumbCache.put("a", "A", 100)
        assertEquals("A", ThumbCache.get<String>("a"))
        assertEquals(1, ThumbCache.sizeEntries())
        ThumbCache.clear()
        assertNull(ThumbCache.get<String>("a"))
        assertEquals(0, ThumbCache.sizeEntries())
    }

    @Test
    fun `lru evicts eldest when budget exceeded`() {
        ThumbCache.maxBudgetBytes = 250
        ThumbCache.put("a", "A", 100)
        ThumbCache.put("b", "B", 100)
        ThumbCache.put("c", "C", 100)   // a 被挤掉
        assertNull(ThumbCache.get<String>("a"))
        assertEquals("B", ThumbCache.get<String>("b"))
        assertEquals("C", ThumbCache.get<String>("c"))
        assertEquals(2, ThumbCache.sizeEntries())
    }

    @Test
    fun `recently used survives eviction`() {
        ThumbCache.maxBudgetBytes = 250
        ThumbCache.put("a", "A", 100)
        ThumbCache.put("b", "B", 100)
        assertTrue(ThumbCache.get<String>("a") != null)  // 访问 a → 让它变"新"
        ThumbCache.put("c", "C", 100)                    // 这次挤的是 b
        assertNull(ThumbCache.get<String>("b"))
        assertEquals("A", ThumbCache.get<String>("a"))
    }

    @Test
    fun `oversized single entry is kept`() {
        ThumbCache.maxBudgetBytes = 100
        ThumbCache.put("big", "B", 400)   // 单条超预算：硬收（查看器大图不能永不进账）
        assertEquals("B", ThumbCache.get<String>("big"))
        assertEquals(1, ThumbCache.sizeEntries())
        ThumbCache.put("small", "S", 10)  // 第二条进来 → 账必爆 → 挤 eldest(big)
        assertNull(ThumbCache.get<String>("big"))
        assertEquals("S", ThumbCache.get<String>("small"))
    }

    @Test
    fun `put same key replaces accounting`() {
        ThumbCache.put("a", "A1", 100)
        ThumbCache.put("a", "A2", 50)
        assertEquals("A2", ThumbCache.get<String>("a"))
        assertEquals(1, ThumbCache.sizeEntries())
        ThumbCache.clear()
        ThumbCache.maxBudgetBytes = 100
        ThumbCache.put("x", "X", 80)   // 若搬自账没清旧账，这里会误挤 x 自己
        assertTrue(ThumbCache.get<String>("x") != null)
        assertFalse(ThumbCache.get<String>("x") == null)
    }
}
