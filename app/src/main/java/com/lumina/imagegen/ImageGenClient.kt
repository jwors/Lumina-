package com.lumina.imagegen

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * 图像生成端点客户端（OpenAI 兼容形态）。
 *
 * 两种模式：
 *  - 文生图：POST .../images/generations（JSON；GLM-Image 仅支持这一种）
 *  - 参考图：POST .../images/edits（multipart；单图字段 `image`，多图 `image[]`）
 *
 * 是否走参考图由 [ImageGenProtocol.shouldUseEdit] 判定：端点含 `/images/edits`、
 * 模型名本身是编辑模型，或用户把 [ReferenceMode] 调成 FORCE。
 *
 * 返回兼容 `data[0].url` 与 `data[0].b64_json`。失败**不静默**——logger 落日志。
 */
class ImageGenClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val size: String,
    private val log: (String) -> Unit = {},
    /** 智谱生图默认加水印；false 需账户已在控制台签署去水印声明。 */
    private val watermarkEnabled: Boolean = true,
    private val referenceMode: ReferenceMode = ReferenceMode.AUTO,
) {

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 单参考图便捷入口（旧调用点）。 */
    fun generate(prompt: String, reference: ByteArray? = null): ByteArray? =
        generate(prompt, listOfNotNull(reference))

    /** 生成并返回图片字节（自拉 URL 转存或直接 b64）；失败 null。 */
    fun generate(prompt: String, references: List<ByteArray>): ByteArray? = runCatching {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val edit = ImageGenProtocol.shouldUseEdit(baseUrl, referenceMode, references.size)
        if (!edit && references.isNotEmpty() && referenceMode != ReferenceMode.OFF) {
            log("imagegen: 端点纯文生图，跳过参考图（${references.size} 张）")
        }
        val first = exec(buildRequest(prompt, references, edit), t0)
        // 去水印未签声明时接口会拒绝；回退带水印，别让整张图失败
        if (first == null && !edit && !watermarkEnabled && baseUrl.contains("bigmodel.cn")) {
            log("imagegen: 去水印请求失败 → 回退带水印重试")
            exec(textRequest(prompt, forceWatermark = true), t0)
        } else {
            first
        }
    }.getOrElse { e ->
        log("imagegen: FAILED ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** 供 JVM 测试构造请求（不发起网络），便于断言 multipart 字段与 URL 归一。 */
    fun buildRequest(
        prompt: String,
        references: List<ByteArray> = emptyList(),
        edit: Boolean = ImageGenProtocol.shouldUseEdit(baseUrl, referenceMode, references.size),
    ): Request = if (edit && references.isNotEmpty()) {
        editRequest(prompt, references)
    } else {
        textRequest(prompt)
    }

    private fun textRequest(prompt: String, forceWatermark: Boolean = false): Request {
        val url = ImageGenProtocol.endpoint(baseUrl, edit = false)
        val body = org.json.JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("size", size)
        if (baseUrl.contains("bigmodel.cn")) {
            body.put("watermark_enabled", watermarkEnabled || forceWatermark)
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
    }

    private fun editRequest(prompt: String, references: List<ByteArray>): Request {
        val url = ImageGenProtocol.endpoint(baseUrl, edit = true)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("size", size)
        // OpenAI gpt-image-1 多图字段是 image[]；单图用 image，兼容旧端点。
        val field = if (references.size == 1) "image" else "image[]"
        references.forEachIndexed { index, bytes ->
            val filename = if (references.size == 1) "source.jpg" else "source_${index + 1}.jpg"
            body.addFormDataPart(
                field, filename,
                bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()),
            )
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.build())
            .build()
    }

    private fun exec(req: Request, t0: Long): ByteArray? {
        return client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: return null.also { log("imagegen: empty body") }
            if (r.code != 200) {
                log("imagegen: HTTP ${r.code} ${text.take(300)}")
                return null
            }
            when (val reply = ImageGenResponse.parse(text)) {
                null -> {
                    log("imagegen: no data[0].url / b64_json — ${text.take(300)}")
                    null
                }
                is ImageReply.Bytes -> {
                    val ms = android.os.SystemClock.elapsedRealtime() - t0
                    log("imagegen: ok ${reply.bytes.size}B elapsed=${ms}ms (b64)")
                    reply.bytes
                }
                is ImageReply.Url -> download(reply.url, t0)
            }
        }
    }

    private fun download(imageUrl: String, t0: Long): ByteArray? {
        val dlReq = Request.Builder().url(imageUrl).build()
        return client.newCall(dlReq).execute().use { dr ->
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

    companion object {
        /** 字节 → Work 层文件（_g.jpg 由图库扫描契约收录）。 */
        fun saveToWork(bytes: ByteArray, workDir: File, baseName: String): File? = runCatching {
            workDir.mkdirs()
            val out = File(workDir, "${baseName}_g.jpg")
            out.writeBytes(bytes)
            out
        }.getOrNull()
    }
}
