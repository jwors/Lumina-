package com.lumina.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3-03：海报排版几何回归基线（分区比例 + 中心裁窗）。纯 JVM（无 android.graphics）。
 */
class PosterLayoutTest {

    @Test
    fun `zones fill the 4x5 canvas without gaps`() {
        val z = PosterLayout.zones()
        assertEquals(1080, PosterLayout.CANVAS_W)
        assertEquals(1350, PosterLayout.CANVAS_H)
        val consumed = z.pad + z.titleHeight + z.photoHeight + z.noteHeight + z.pad
        assertEquals("分区必须吃满画布高", PosterLayout.CANVAS_H, consumed)
        // 顺序与相邻性
        assertEquals(z.titleTop + z.titleHeight, z.photoTop)
        assertEquals(z.photoTop + z.photoHeight, z.noteTop)
        // 照片区是主导（>70%）
        assertTrue(z.photoHeight > PosterLayout.CANVAS_H * 0.7f)
    }

    @Test
    fun `center crop fills target window from the center`() {
        val z = PosterLayout.zones()
        val targetW = PosterLayout.CANVAS_W - z.pad * 2
        val crop = PosterLayout.centerCrop(w = 5568, h = 3712, targetW = targetW, targetH = z.photoHeight)
        // 短边约束主导：目标高 < 源高 → scale = targetH/h → 高度吃满源高
        assertEquals(3712, crop.height)
        // 宽度按同 scale 收缩（≈ targetW * h/targetH），垂直居中
        val expectedW = (targetW * 3712.0 / z.photoHeight).toInt()
        assertTrue("crop.width=$crop", kotlin.math.abs(crop.width - expectedW) <= 1)
        assertEquals((5568 - crop.width) / 2, crop.left)
        assertEquals(0, crop.top)
    }

    @Test
    fun `title clamp keeps text short`() {
        val long = "用" + "字".repeat(30)
        assertEquals(22, PosterLayout.clampTitle(long).length)
        assertTrue(PosterLayout.clampTitle("短标题").endsWith("标题"))
    }

    @Test
    fun `sanitize strips markdown wrappers and key prefixes`() {
        assertEquals(
            "红案一角 衔接三两句",
            PosterLayout.sanitizeTitle("**场景名**：红案一角  **衔接语**：衔接三两句"),
        )
        // 键名开头剥整段前缀（末尾键名型残留由上一步空格替换处理）
        assertEquals("红案一角", PosterLayout.sanitizeTitle("场景名：红案一角"))
        assertEquals("雾没进门 栈道让了路", PosterLayout.sanitizeTitle("#标题：雾没进门\n栈道让了路"))
    }
}
