package com.lumina.pass

/**
 * Pass 执行器侧数据外套：DB 入口、图片源一切由构造方（Android / 测试）注入。
 */
interface PassSink {
    /** Smart Result 落库（一张 Master × 一个步骤 = 一条） */
    fun saveResult(name: String, cardId: String, kind: String, text: String, model: String, createdAtMs: Long)

    /** Ledger 行落库（每次 LLM 调用一条） */
    fun saveLedger(name: String, model: String, promptChars: Int, withImage: Boolean,
                   outcome: String, promptTokens: Int?, completionTokens: Int?)

    /** Master 里为对象找 JPEG 源字节（优先同名 .JPG/.JPEG；对象本身是 JPEG 则读本体）；无 → null */
    fun jpegFor(name: String): ByteArray?

    /** 队列行移除（成功后调用；失败保留） */
    fun removeQueued(name: String)
}

/** Pass 回调： Started(name) / itemDone(name, success...) 由实现方定义。 */
interface PassCallback {
    fun onStart(totalItems: Int)
    fun onItemDone(name: String, ok: Boolean, note: String)
    fun onAllDone(successCount: Int, failCount: Int)
}
