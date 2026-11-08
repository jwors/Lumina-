package com.lumina.llm

/**
 * JVM 真相客户端：结局按调用顺序从队列吐出（空队列缺省 Text("fake")）。
 */
class FakeLlmClient(
    private val outcomes: MutableList<LlmOutcome> = mutableListOf(),
) : LlmClient {

    val calls = mutableListOf<LlmCall>()

    data class LlmCall(val cfg: LlmConfigEntity, val prompt: String, val image: ByteArray?)

    override fun complete(cfg: LlmConfigEntity, prompt: String, imageJpeg: ByteArray?): LlmOutcome {
        calls += LlmCall(cfg, prompt, imageJpeg)
        return if (outcomes.isEmpty()) {
            LlmOutcome.Text("fake result", null, null)
        } else {
            outcomes.removeAt(0)
        }
    }
}
