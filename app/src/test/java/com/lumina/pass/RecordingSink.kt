package com.lumina.pass

import com.lumina.llm.LedgerEntity
import com.lumina.llm.LlmConfigEntity

/** Pass 测试记录器（结果/账本/队列/回调三态）。 */
class RecordingSink(val cfg: LlmConfigEntity) : PassSink {
    val results = mutableListOf<SmartResultEntity>()
    val ledgers = mutableListOf<LedgerEntity>()
    val removed = mutableListOf<String>()
    val jpegs = mutableMapOf<String, ByteArray>()

    override fun jpegFor(name: String): ByteArray? = jpegs[name]

    override fun saveResult(
        name: String, cardId: String, kind: String, text: String,
        model: String, createdAtMs: Long,
    ) {
        results.add(
            SmartResultEntity(
                id = 0, name = name, cardId = cardId, kind = kind,
                text = text, model = model, createdAtMs = createdAtMs,
            ),
        )
    }

    override fun saveLedger(
        name: String, model: String, promptChars: Int, withImage: Boolean,
        outcome: String, promptTokens: Int?, completionTokens: Int?,
    ) {
        ledgers.add(
            LedgerEntity(
                id = 0, objectName = name, model = model, promptChars = promptChars,
                withImage = withImage, outcome = outcome,
                promptTokens = promptTokens, completionTokens = completionTokens,
                createdAtMs = 0,
            ),
        )
    }

    override fun removeQueued(name: String) { removed.add(name) }
}

class RecordingCallback : PassCallback {
    val started = mutableListOf<Int>()
    val items = mutableListOf<Pair<String, Boolean>>()
    val dones = mutableListOf<Pair<Int, Int>>()
    override fun onStart(total: Int) { started.add(total) }
    override fun onItemDone(name: String, ok: Boolean, note: String) { items.add(name to ok) }
    override fun onAllDone(successCount: Int, failCount: Int) { dones.add(successCount to failCount) }
}
