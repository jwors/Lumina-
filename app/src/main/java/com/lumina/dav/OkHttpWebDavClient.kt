package com.lumina.dav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/**
 * M3-04：OkHttp 薄实装（Basic 认证；15s/90s 超时，对齐 OkHttpLlmClient 惯例）。
 * 每次调用独立连接；PROPFIND 结果用容忍式正则解析（坚果云/Alist/NAS 返回的命名空间
 * 前缀 d:/D:/无前缀三种都认）。
 */
class OkHttpWebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) : WebDavClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun auth(): String = Credentials.basic(username, password)

    private fun urlFor(remotePath: String): String {
        val base = baseUrl.trimEnd('/')
        val path = remotePath.trimEnd('/')
        return if (path.removePrefix("/").startsWith(base.removePrefix("https://").removePrefix("http://").substringAfter('/'))) {
            "$base/${remotePath.trimStart('/')}"
        } else if (remotePath.startsWith("http")) {
            remotePath
        } else "$base/${path.trimStart('/')}"
    }

    override fun put(remotePath: String, bytes: ByteArray): DavOutcome = runCatching {
        val req = Request.Builder()
            .url(urlFor(remotePath))
            .header("Authorization", auth())
            .put(RequestBody.create("application/octet-stream".toMediaTypeOrNull(), bytes))
            .build()
        client.newCall(req).execute().use { r ->
            r.code
        }
    }.fold(
        { code -> if (code in 200..299) DavOutcome.Ok(code) else DavOutcome.Http(code) },
        { DavOutcome.NetworkTimeout(it.message ?: "network") },
    )

    override fun mkcol(remotePath: String): DavOutcome = runCatching {
        val req = Request.Builder()
            .url(urlFor(remotePath))
            .header("Authorization", auth())
            .header("Content-Type", "application/xml")
            .method("MKCOL", RequestBody.create(null, ByteArray(0)))
            .build()
        client.newCall(req).execute().use { it.code }
    }.fold(
        { code -> if (code in 200..299 || code == 405) DavOutcome.Ok(code) else DavOutcome.Http(code) },
        { DavOutcome.NetworkTimeout(it.message ?: "network") },
    )

    override fun propfind(dirPath: String): DavListing? = runCatching {
        val req = Request.Builder()
            .url(urlFor(dirPath))
            .header("Authorization", auth())
            .header("Depth", "1")
            .method("PROPFIND", RequestBody.create("application/xml".toMediaTypeOrNull(), ByteArray(0)))
            .build()
        client.newCall(req).execute().use { r ->
            if (r.code != 207) return null
            val body = r.body?.string() ?: return null
            parseListing(dirPath, body)
        }
    }.getOrNull()

    /** 容忍式解析：response 块内取 href 与 getcontentlength（大小写敏感放宽）。 */
    internal fun parseListing(dirPath: String, xml: String): DavListing {
        val files = LinkedHashMap<String, Long>()
        val responseBlocks = Regex("<(?:[A-Za-z]+:)?response[^>]*>(.*?)</(?:[A-Za-z]+:)?response>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
        val lastSeg = dirPath.trimEnd('/').substringAfterLast('/')
        for (block in responseBlocks) {
            val href = Regex("<(?:[A-Za-z]+:)?href[^>]*>(.*?)</(?:[A-Za-z]+:)?href>", RegexOption.DOT_MATCHES_ALL)
                .find(block.value)?.groupValues?.get(1)?.trim() ?: continue
            val decoded = decodeUrlPath(href)
            if (decoded.trimEnd('/') == dirPath.trimEnd('/')) continue   // 目录自身
            if (!decoded.contains(lastSeg)) continue                     // 他目录噪声
            val name = decoded.trimEnd('/').substringAfterLast('/')
            val size = Regex("<(?:[A-Za-z]+:)?getcontentlength[^>]*>(\\d+)</", RegexOption.DOT_MATCHES_ALL)
                .find(block.value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            files[name] = size
        }
        return DavListing(dirPath, files)
    }

    private fun decodeUrlPath(href: String): String =
        href.split("/").joinToString("/") { seg ->
            runCatching { java.net.URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
        }
}

