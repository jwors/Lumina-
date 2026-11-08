package com.lumina.cards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工单 03 验收：Skill Card JSON schema v1 解析/校验/导出（纯 JVM）。
 */
class SkillCardParserTest {

    @Test
    fun `valid card parses with steps and name`() {
        val card = CardEngine.parse(
            """{"name":"徒步日记","version":1,"steps":[{"type":"annotate","prompt":"看图"}]}""",
        ) { "id-1" }
        assertEquals("徒步日记", card.name)
        assertEquals(1, card.version)
        assertEquals(StepKind.ANNOTATE, card.steps[0].kind)
        assertEquals("看图", card.steps[0].prompt)
    }

    @Test
    fun `unknown step type is rejected with readable reason`() {
        try {
            CardEngine.parse("""{"name":"x","version":1,"steps":[{"type":"warp_speed"}]}""") { "id" }
            throw AssertionError("应抛 SkillCardException")
        } catch (e: SkillCardException) {
            assertTrue(e.reason.contains("warp_speed"))
            assertTrue(e.reason.contains("未知步骤类型"))
        }
    }

    @Test
    fun `missing name rejected`() {
        try {
            CardEngine.parse("""{"version":1,"steps":[{"type":"annotate","prompt":"p"}]}""") { "id" }
            throw AssertionError("应抛 SkillCardException")
        } catch (e: SkillCardException) {
            assertTrue(e.reason.contains("name"))
        }
    }

    @Test
    fun `empty steps rejected`() {
        try {
            CardEngine.parse("""{"name":"x","version":1,"steps":[]}""") { "id" }
            throw AssertionError("应抛 SkillCardException")
        } catch (e: SkillCardException) {
            assertTrue(e.reason.contains("steps 为空"))
        }
    }

    @Test
    fun `broken json rejected as not legal`() {
        try {
            CardEngine.parse("""{"name":"x", """) { "id" }
            throw AssertionError("应抛 SkillCardException")
        } catch (e: SkillCardException) {
            assertTrue(e.reason.contains("不是合法 JSON"))
        }
    }

    @Test
    fun `export roundtrip preserves name steps and version`() {
        val card = CardEngine.parse(
            """{"name":"锐利风光","version":2,"steps":[{"type":"annotate","prompt":"四项"},{"type":"filter_suggest"}]}""",
        ) { "id-x" }
        val json = CardEngine.toJson(card)
        val reparsed = CardEngine.parse(json) { "id-y" }
        assertEquals("锐利风光", reparsed.name)
        assertEquals(2, reparsed.version)
        assertEquals(2, reparsed.steps.size)
        assertEquals(StepKind.FILTER_SUGGEST, reparsed.steps[1].kind)
        // 导出 JSON 不含 cardId（配方属于文件）
        assertTrue(!json.contains("id-x"))
    }
}
