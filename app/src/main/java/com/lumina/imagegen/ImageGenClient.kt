package com.lumina.imagegen

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File

/**
 * M4-01：图像生成端点客户端（OpenAI 兼容 images/generations 形态；默认智谱 glm-image）。
 * 设置三元组（baseUrl/apiKey/model/size）由用户配置；失败**不再静默**——注入 logger 落日志。
 * baseUrl 两种容忍写法（2026-11-09 对齐官网示例）：
 *  - 完整端点：https://open.bigmodel.cn/api/paas/v4/images/generations
 *  - 根前缀： https://open.bigmodel.cn/api/paas/v4（会自动补 /images/generations）
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
    fun generate(prompt: String): ByteArray? = runCatching {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val root = baseUrl.trimEnd('/')
        val url = if (root.endsWith("/images/generations")) root else "$root/images/generations"
        val body = org.json.JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("size", size)
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(), body.toString(),
            ))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: return@runCatching null.also { log("imagegen: empty body") }
            if (r.code != 200) {
                log("imagegen: HTTP ${r.code} ${text.take(300)}")
                return@runCatching null
            }
            val url2 = org.json.JSONObject(text)
                .optJSONArray("data")?.optJSONObject(0)?.optString("url")
            if (url2.isNullOrBlank()) {
                log("imagegen: no data[0].url — ${text.take(300)}")
                return@runCatching null
            }
            val dlReq = okhttp3.Request.Builder().url(url2).build()
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
    }.getOrElse { e ->
        log("imagegen: FAILED ${e.javaClass.simpleName}: ${e.message}")
        null
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


