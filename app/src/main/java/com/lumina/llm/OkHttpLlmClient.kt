package com.lumina.llm

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp 薄实现（M2 工单 02）：chat.completions 手搓请求体——
 * messages[0].content = [text, image_url(base64)]（OpenAI 兼容结构，GLM/Kimi/DeepSeek 全通）。
 * 每次调用独立连接（Pass 执行器串行驱动，无池化黑盒）。
 */
class OkHttpLlmClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)   // 视觉模型较慢，90s 余量
        .build(),
) : LlmClient {

    private val jsonType = "application/json; charset=utf-8".toMediaTypeOrNull()

    private companion object {
        /** 进程内稳定会话 ID：OpenCode Go 用它做路由/缓存，缺了会 400 MissingSessionID。 */
        val SESSION_ID: String = java.util.UUID.randomUUID().toString()
    }

    override fun complete(cfg: LlmConfigEntity, prompt: String, imageJpeg: ByteArray?): LlmOutcome {
        val content = JSONArray().put(
            JSONObject().put("type", "text").put("text", prompt),
        )
        if (imageJpeg != null) {
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put(
                            "url",
                            "data:image/jpeg;base64," +
                                Base64.encodeToString(imageJpeg, Base64.NO_WRAP),
                        ),
                    ),
            )
        }
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("max_tokens", 1024)

        val request = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer " + cfg.apiKey)
            .header("User-Agent", "Lumina/0.4 (Android)")
            .apply {
                // OpenCode Go 要求客户端带稳定会话头，否则 400 MissingSessionID
                if (cfg.baseUrl.contains("opencode.ai")) {
                    header("x-opencode-session", SESSION_ID)
                }
            }
            .post(body.toString().toRequestBody(jsonType))
            .build()

        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.code == 429 -> {
                        val retryAfter = resp.header("Retry-After")?.toIntOrNull()
                        LlmOutcome.RateLimited(retryAfter)
                    }
                    resp.code >= 400 -> LlmOutcome.Http(resp.code, text.take(300))
                    else -> {
                        val json = JSONObject(text)
                        val contentStr = json.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .optString("content", "")
                        val usage = json.optJSONObject("usage")
                        LlmOutcome.Text(
                            text = contentStr,
                            promptTokens = usage?.optInt("prompt_tokens")?.takeIf { it > 0 },
                            completionTokens = usage?.optInt("completion_tokens")?.takeIf { it > 0 },
                        )
                    }
                }
            }
        } catch (e: IOException) {
            LlmOutcome.NetworkTimeout(e.message ?: "io error")
        } catch (e: JSONException) {
            LlmOutcome.Http(-1, "响应不是合法 JSON：${e.message}")
        }
    }
}
