package com.lumina.dav

/**
 * M3-04：WebDAV 轻客户端缝（PUT/MKCOL/PROPFIND 三操作——ADR-0003 唯一要求的覆盖面）。
 * 缝隙方法论延续：`WebDavClient` 接口 + `FakeDavClient`（JVM 真相）+ `OkHttpWebDavClient`（薄实装）。
 */
sealed class DavOutcome {
    data class Ok(val code: Int) : DavOutcome()
    data class Http(val code: Int) : DavOutcome()
    data class NetworkTimeout(val reason: String) : DavOutcome()
}

/** PROPFIND Depth:1 的结果：目录内的 (文件名, sizeBytes) 名单（目录本身解码后单独给）。 */
data class DavListing(val dirPath: String, val files: Map<String, Long>)

interface WebDavClient {
    fun put(remotePath: String, bytes: ByteArray): DavOutcome
    fun mkcol(remotePath: String): DavOutcome        // 405 = 已存在（语义 OK，调用方自行归一）
    fun propfind(dirPath: String): DavListing?       // null = 目录不可读/网络失败
}
