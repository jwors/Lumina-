package com.lumina.llm

/**
 * LLM 缝隙（M2 工单 02）：管线与协议物理隔离——
 * Pass 执行器只面对本接口，FakeLlmClient 是 JVM 真相，OkHttpLlmClient 是薄实现。
 */
sealed interface LlmOutcome {
    /** 正常文本结果（token 数透传给 Ledger，可空） */
    data class Text(val text: String, val promptTokens: Int?, val completionTokens: Int?) : LlmOutcome

    /** 429 / 官方限流（带 Retry-After 秒数可选） */
    data class RateLimited(val retryAfterSec: Int?) : LlmOutcome

    /** 其他 HTTP 错误（code + 截断 body） */
    data class Http(val code: Int, val snippet: String) : LlmOutcome

    /** 网络层失败（超时/DNS/断网） */
    data class NetworkTimeout(val reason: String) : LlmOutcome
}

interface LlmClient {
    /**
     * 单请求：prompt（文本）+ 可选 JPEG（视觉模型 inline base64）。
     * 同步调用（Pass 执行器在 io 线程驱动）；不出预取线程。
     */
    fun complete(cfg: LlmConfigEntity, prompt: String, imageJpeg: ByteArray?): LlmOutcome
}
