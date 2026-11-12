package com.lumina.imagegen

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * 图像生成端点客户端（OpenAI 兼容形态）。
 *
 * 两种模式，按 baseUrl 自动判别：
 *  - 默认：POST .../images/generations（JSON，纯文生图；GLM-Image 仅支持这一种）
 *  - 参考图：baseUrl 含 "/images/edits" → multipart（image + prompt；需端点本身支持图生图）
 *
 * 失败**不再静默**——注入 logger 落日志。
 */
class ImageGenClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val size: String,
    private val log: (String) -> Unit = {},
) {

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 生成并返回图片字节（自拉 URL 转存）；失败 null。 */
    fun generate(prompt: String, reference: ByteArray? = null): ByteArray? = runCatching {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val req = if (reference != null && isEditEndpoint(baseUrl)) {
            editRequest(prompt, reference)
        } else {
            textRequest(prompt)
        }
        exec(req, t0)
    }.getOrElse { e ->
        log("imagegen: FAILED ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun textRequest(prompt: String): Request {
        val root = baseUrl.trimEnd('/')
        val url = if (root.endsWith("/images/generations")) root else "$root/images/generations"
        val body = org.json.JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("size", size)
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
    }

    private fun editRequest(prompt: String, reference: ByteArray): Request {
        val root = baseUrl.trimEnd('/')
        val url = if (root.endsWith("/images/edits")) root else "$root/images/edits"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("size", size)
            .addFormDataPart(
                "image", "source.jpg",
                reference.toRequestBody("image/jpeg".toMediaTypeOrNull()),
            )
            .build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
    }

    private fun exec(req: Request, t0: Long): ByteArray? {
        return client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: return null.also { log("imagegen: empty body") }
            if (r.code != 200) {
                log("imagegen: HTTP ${r.code} ${text.take(300)}")
                return null
            }
            val url2 = org.json.JSONObject(text)
                .optJSONArray("data")?.optJSONObject(0)?.optString("url")
            if (url2.isNullOrBlank()) {
                log("imagegen: no data[0].url — ${text.take(300)}")
                return null
            }
            val dlReq = Request.Builder().url(url2).build()
            client.newCall(dlReq).execute().use { dr ->
                val bytes = dr.body?.bytes()
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                if (dr.code == 200 && bytes != null) {
                    log("imagegen: ok ${bytes.size}B elapsed=${ms}ms")
                    bytes
                } else {
                    log("imagegen: download fail code=${dr.code}")
                    null
                }
            }
        }
    }

    companion object {
        /** 端点是图生图（images/edits）时，才把源照片作为参考图上传。 */
        fun isEditEndpoint(url: String): Boolean = url.contains("/images/edits")

        /** 字节 → Work 层文件（_g.jpg 由图库扫描契约收录）。 */
        fun saveToWork(bytes: ByteArray, workDir: File, baseName: String): File? = runCatching {
            workDir.mkdirs()
            val out = File(workDir, "${baseName}_g.jpg")
            out.writeBytes(bytes)
            out
        }.getOrNull()
    }
}
