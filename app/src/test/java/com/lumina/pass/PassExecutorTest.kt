package com.lumina.pass

import com.lumina.cards.CardEngine
import com.lumina.cards.SkillCard
import com.lumina.llm.FakeLlmClient
import com.lumina.llm.LlmConfigEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工单 04 验收：Pass 执行器语义（纯 JVM，缝隙全注入）。
 * 2026-11-15：标注/文案步骤停用，执行器只跑滤镜建议/生成式。
 */
class PassExecutorTest {

    private val card: SkillCard = CardEngine.parse(
        """{"name":"滤镜双步","version":1,"steps":[
            {"type":"filter_suggest","prompt":"选滤镜一"},
            {"type":"filter_suggest","prompt":"选滤镜二"}
        ]}""",
    ) { "card-1" }

    private val cfg = LlmConfigEntity(
        id = 1, baseUrl = "https://api.example.com/v1", apiKey = "sk",
        model = "test-model", templateName = "x", cloudEnabled = true,
    )

    private fun executor(fake: FakeLlmClient, sink: PassSink, retry: Long = 0): PassExecutor =
        PassExecutor({ fake }, { cfg }, sink, retry, now = { 0L })

    private fun rec(): RecordingCallback = RecordingCallback()

    private fun filterCard(id: String = "single"): SkillCard = CardEngine.parse(
        """{"name":"单步","version":1,"steps":[{"type":"filter_suggest","prompt":"选滤镜"}]}""",
    ) { id }

    @Test
    fun `two items two steps produce results and ledger in order`() {
        val sink = RecordingSink(cfg)
        sink.jpegs["A.JPG"] = byteArrayOf(1)
        sink.jpegs["B.JPG"] = byteArrayOf(2)
        val fake = FakeLlmClient()
        val cb = rec()
        executor(fake, sink).run(listOf("A.JPG", "B.JPG"), card, cb)

        assertEquals(4, sink.results.size)          // 2 物 × 2 步
        assertEquals(4, sink.ledgers.size)          // 每 LLM 调用一条
        assertTrue(sink.ledgers.all { it.outcome == "OK" })
        assertEquals(listOf("A.JPG", "B.JPG"), sink.removed)
        assertEquals(listOf(2), cb.started)
        assertEquals(listOf(2 to 0), cb.dones)
    }

    @Test
    fun `rate limited retried once then succeeds`() {
        val sink = RecordingSink(cfg)
        sink.jpegs["A.JPG"] = byteArrayOf(1)
        val fake = FakeLlmClient(
            mutableListOf(
                com.lumina.llm.LlmOutcome.RateLimited(3),            // 第一次：限流
                com.lumina.llm.LlmOutcome.Text("after retry", 5, 7), // 重试成功
            ),
        )
        val cb = rec()
        executor(fake, sink, retry = 0).run(listOf("A.JPG"), filterCard(), cb)
        assertEquals(2, sink.ledgers.size) // 限流一条 + 成功一条
        assertEquals("after retry", sink.results.single().text)
        assertTrue(cb.items.single().second)
    }

    @Test
    fun `network timeout twice leaves item failed and queued`() {
        val sink = RecordingSink(cfg)
        sink.jpegs["A.JPG"] = byteArrayOf(1)
        val fake = FakeLlmClient(
            mutableListOf(
                com.lumina.llm.LlmOutcome.NetworkTimeout("down"),
                com.lumina.llm.LlmOutcome.NetworkTimeout("down again"),
            ),
        )
        val cb = rec()
        executor(fake, sink, retry = 0).run(listOf("A.JPG"), filterCard(), cb)
        assertEquals(0, sink.results.size)
        assertEquals(2, sink.ledgers.size)   // 两次尝试各一条 Ledger
        assertTrue(sink.removed.isEmpty())   // 失败保留队列（伪）
        assertEquals(false, cb.items.single().second)
    }

    @Test
    fun `missing jpeg marks item failed without llm calls`() {
        val sink = RecordingSink(cfg)
        val fake = FakeLlmClient()
        val cb = rec()
        executor(fake, sink).run(listOf("A.JPG"), card, cb)
        assertEquals(0, fake.calls.size)
        assertEquals(0, sink.ledgers.size)
        assertEquals(false, cb.items.single().second)
    }

    @Test
    fun `generative step writes placeholder without llm call`() {
        val genCard = CardEngine.parse(
            """{"name":"x","version":1,"steps":[{"type":"generative","prompt":"zine"}]}""",
        ) { "id" }
        val sink = RecordingSink(cfg)
        sink.jpegs["A.JPG"] = byteArrayOf(1)
        val fake = FakeLlmClient()
        val cb = rec()
        PassExecutor({ fake }, { cfg }, sink, 0, now = { 0 }).run(listOf("A.JPG"), genCard, cb)
        assertTrue(sink.results.single().text.contains("M4"))
        assertEquals(0, fake.calls.size)
        assertEquals(0, sink.ledgers.size)
    }

    @Test
    fun `annotate and caption steps are skipped without llm calls`() {
        val legacy = CardEngine.parse(
            """{"name":"旧卡","version":1,"steps":[
                {"type":"annotate","prompt":"标注它"},
                {"type":"caption","prompt":"文案它"},
                {"type":"filter_suggest","prompt":"选滤镜"}
            ]}""",
        ) { "legacy" }
        val sink = RecordingSink(cfg)
        sink.jpegs["A.JPG"] = byteArrayOf(1)
        val fake = FakeLlmClient()
        val cb = rec()

        executor(fake, sink).run(listOf("A.JPG"), legacy, cb)

        assertEquals(1, fake.calls.size)                 // 只有滤镜建议真调
        assertEquals("filter_suggest", sink.results.single().kind)
        assertTrue(cb.items.single().second)
    }

    @Test
    fun `card with only disabled steps fails with a note`() {
        val legacy = CardEngine.parse(
            """{"name":"旧卡","version":1,"steps":[
                {"type":"annotate","prompt":"标注它"},
                {"type":"caption","prompt":"文案它"}
            ]}""",
        ) { "legacy" }
        val sink = RecordingSink(cfg)
        sink.jpegs["A.JPG"] = byteArrayOf(1)
        val fake = FakeLlmClient()
        val cb = rec()

        executor(fake, sink).run(listOf("A.JPG"), legacy, cb)

        assertEquals(0, fake.calls.size)
        assertEquals(0, sink.results.size)
        assertEquals(false, cb.items.single().second)
        assertTrue(sink.removed.isEmpty())
    }

    @Test
    fun `abort check stops between items and leaves rest queued`() {
        val sink = RecordingSink(cfg)
        sink.jpegs["A.JPG"] = byteArrayOf(1)
        sink.jpegs["B.JPG"] = byteArrayOf(2)
        val fake = FakeLlmClient()
        val cb = rec()
        // 取消缝：一张处理完成后即停（removed 非空 = 第一张已消费）
        val executor = PassExecutor(
            { fake }, { cfg }, sink, retryDelayMs = 0, now = { 0L },
            abortCheck = { sink.removed.isNotEmpty() },
        )
        executor.run(listOf("A.JPG", "B.JPG"), filterCard(), cb)

        assertEquals(listOf("A.JPG"), sink.removed)  // 第二张未被消费（队列仍含 B.JPG）
        assertEquals(1, sink.results.size)           // 单步卡：第一张 1 条结果
        assertEquals(listOf(1 to 0), cb.dones)       // 结算：仅统计已处理（成功 1 · 失败 0）
        assertEquals(1, cb.items.size)
    }
}
