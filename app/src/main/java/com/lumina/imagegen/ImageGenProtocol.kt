package com.lumina.imagegen

import java.util.Base64

/** 参考图模式（设置页可选）：自动按端点/模型判断、强制按图生图、关闭上传。 */
enum class ReferenceMode {
    AUTO,
    FORCE,
    OFF,
}

/**
 * 生图端点的协议判别（纯函数，JVM 可测）。
 *
 * 主流兼容形态：
 *  - 文生图：POST .../images/generations（JSON）
 *  - 参考图：POST .../images/edits（multipart；OpenAI gpt-image-1 用 image[] 多图）
 *
 * 用户经常只填 host 或 `/v1`，这里统一补全/纠偏，避免在 `/images/edits` 后面
 * 再拼 `/images/generations` 这类路径事故。
 */
object ImageGenProtocol {

    const val GENERATIONS_SUFFIX = "/images/generations"
    const val EDITS_SUFFIX = "/images/edits"

    fun isEditEndpoint(url: String): Boolean = url.contains(EDITS_SUFFIX)

    fun shouldUseEdit(
        baseUrl: String,
        mode: ReferenceMode,
        referenceCount: Int,
    ): Boolean {
        if (referenceCount <= 0) return false
        return when (mode) {
            ReferenceMode.OFF -> false
            ReferenceMode.FORCE -> true
            ReferenceMode.AUTO -> isEditEndpoint(baseUrl)
        }
    }

    fun endpoint(baseUrl: String, edit: Boolean): String {
        val root = baseUrl.trim().trimEnd('/')
        val base = when {
            root.contains(EDITS_SUFFIX) -> root.substringBefore(EDITS_SUFFIX)
            root.contains(GENERATIONS_SUFFIX) -> root.substringBefore(GENERATIONS_SUFFIX)
            else -> root
        }.trimEnd('/')
        return base + if (edit) EDITS_SUFFIX else GENERATIONS_SUFFIX
    }
}

/** 生图响应解析结果：URL 自取，或端点直接回 b64。 */
sealed interface ImageReply {
    data class Url(val url: String) : ImageReply
    data class Bytes(val bytes: ByteArray) : ImageReply
}

/**
 * 兼容两种返回：`data[0].url`（智谱/部分网关）与 `data[0].b64_json`（OpenAI gpt-image-1）。
 */
object ImageGenResponse {

    fun parse(text: String): ImageReply? = runCatching {
        val root = org.json.JSONObject(text)
        val item = root.optJSONArray("data")?.optJSONObject(0) ?: return@runCatching null
        val url = item.optString("url").takeIf { it.isNotBlank() }
        if (url != null) return@runCatching ImageReply.Url(url)
        val b64 = item.optString("b64_json").takeIf { it.isNotBlank() } ?: return@runCatching null
        val payload = b64.substringAfter("base64,", b64)
        val bytes = Base64.getDecoder().decode(payload)
        if (bytes.isEmpty()) null else ImageReply.Bytes(bytes)
    }.getOrNull()
}
