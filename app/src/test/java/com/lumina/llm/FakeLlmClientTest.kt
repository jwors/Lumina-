package com.lumina.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工单 02 验收：LlmClient 缝隙语义（fake 队列吐出式的确定性行为）。
 */
class FakeLlmClientTest {

    private fun cfg() = LlmConfigEntity(
        id = 1, baseUrl = "https://api.example.com/v1", apiKey = "sk-test",
        model = "test-model", templateName = "通用 OpenAI", cloudEnabled = true,
    )

    @Test
    fun `default outcome is fake text`() {
        val fake = FakeLlmClient()
        val out = fake.complete(cfg(), prompt = "描述这张图", imageJpeg = byteArrayOf(1, 2, 3))
        assertEquals("fake result", (out as LlmOutcome.Text).text)
        assertEquals(1, fake.calls.size)
        assertEquals("描述这张图", fake.calls[0].prompt)
        assertTrue(fake.calls[0].image != null)
    }

    @Test
    fun `scripted outcomes pop in order`() {
        val fake = FakeLlmClient(
            mutableListOf(
                LlmOutcome.RateLimited(retryAfterSec = 3),
                LlmOutcome.Text("second", 10, 20),
                LlmOutcome.Http(400, "bad"),
                LlmOutcome.NetworkTimeout("socket"),
            ),
        )
        assertTrue(fake.complete(cfg(), "a", null) is LlmOutcome.RateLimited)
        val second = fake.complete(cfg(), "b", null) as LlmOutcome.Text
        assertEquals("second", second.text)
        assertEquals(20, second.completionTokens)
    }

    @Test
    fun `rate limited carries retry after`() {
        val fake = FakeLlmClient(mutableListOf(LlmOutcome.RateLimited(7)))
        val out = fake.complete(cfg(), "p", null) as LlmOutcome.RateLimited
        assertEquals(7, out.retryAfterSec)
    }

    @Test
    fun `http outcome carries code and snippet`() {
        val fake = FakeLlmClient(mutableListOf(LlmOutcome.Http(400, "no image support")))
        val out = fake.complete(cfg(), "p", byteArrayOf(9)) as LlmOutcome.Http
        assertEquals(400, out.code)
        assertTrue(out.snippet.contains("no image"))
    }

    @Test
    fun `network timeout outcome passes reason`() {
        val fake = FakeLlmClient(mutableListOf(LlmOutcome.NetworkTimeout("connect refused")))
        val out = fake.complete(cfg(), "p", null) as LlmOutcome.NetworkTimeout
        assertEquals("connect refused", out.reason)
    }

    @Test
    fun `calls record cfg model for ledger`() {
        val fake = FakeLlmClient()
        fake.complete(cfg(), "hello", null)
        assertEquals("test-model", fake.calls[0].cfg.model)
    }
}
