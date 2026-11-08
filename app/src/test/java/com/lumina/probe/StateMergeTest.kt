package com.lumina.probe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 发射台账回归基线（M3）：六个跨页字段的保底语义锁死。
 * 新增跨页字段时：加进 StateMerge + 本测试补一条断言——两处缺一即烂。
 */
class StateMergeTest {

    private val base = ProbeUiState(
        connectionText = "base",
        cameraItems = listOf(CameraItem(1, "A.JPG", 1L, "2026-11-08", null, null)),
        checkedNames = setOf("A.JPG"),
        engineReady = true,
        galleryItems = listOf(GalleryItem("p", "A.JPG", "2026-11-08", 1L)),
        cards = listOf(CardUi("c1", "山雾 zine", 3, 4, false)),
        cardRun = CardRunUi(
            cardId = "c1", cardName = "山雾 zine", version = 3,
            targets = listOf("A.JPG"), phase = CardRunPhase.RUNNING,
        ),
        passSelectedNames = setOf("A.JPG"),
    )

    @Test
    fun `partial emit preserves every whitelisted field`() {
        // 只带文字的最小状态包（= 历次 regression 的肇事形态）
        val merged = StateMerge.merge(base, ProbeUiState("提示文字"))
        assertEquals(base.cameraItems, merged.cameraItems)
        assertEquals(base.checkedNames, merged.checkedNames)
        assertEquals(true, merged.engineReady)
        assertEquals(base.galleryItems, merged.galleryItems)
        assertEquals(base.cards, merged.cards)
        assertEquals(base.cardRun, merged.cardRun)
        assertEquals(base.passSelectedNames, merged.passSelectedNames)
    }

    @Test
    fun `non empty payloads override`() {
        val next = base.copy(connectionText = "next", cameraItems = emptyList())
        val merged = StateMerge.merge(base, next.copy(cameraItems = listOf(CameraItem(2, "B.JPG", 2L, "d", null, null))))
        assertEquals("B.JPG", merged.cameraItems.single().name)
        assertEquals("next", merged.connectionText)
    }

    @Test
    fun `engineReady never regresses to false`() {
        val merged = StateMerge.merge(base, ProbeUiState("x", engineReady = false))
        assertEquals(true, merged.engineReady)
    }

    @Test
    fun `merge itself never clears cardRun - clearing is a dedicated emitter job`() {
        // 语义锁：透传"空集/null 不清值"——真正清空必须走 emitCardRun/emitSelection 专用发射器。
        // 若有人把清理语义塞回合并清单，此测试即红。
        val merged = StateMerge.merge(base, ProbeUiState("x"))
        assertEquals(base.cardRun, merged.cardRun)
        assertEquals(base.passSelectedNames, merged.passSelectedNames)
    }
}
