package com.lumina.dav

/**
 * M3-04：JVM 真相客户端（脚本化结局 + 调用录制）——FakeTransport/FakeLlmClient 同族第五复刻。
 */
class FakeDavClient(
    /** 预设远端目录名单；put 成功后自动登记进名单（模拟服务端收敛）。 */
    var files: MutableMap<String, Long> = mutableMapOf(),
    var putOutcome: DavOutcome = DavOutcome.Ok(201),
    var listing: DavListing? = null,
) : WebDavClient {

    val calls = mutableListOf<String>()

    override fun put(remotePath: String, bytes: ByteArray): DavOutcome {
        calls += "put $remotePath ${bytes.size}"
        val o = putOutcome
        if (o is DavOutcome.Ok) files[normalizeName(remotePath)] = bytes.size.toLong()
        return o
    }

    override fun mkcol(remotePath: String): DavOutcome {
        calls += "mkcol $remotePath"
        return DavOutcome.Ok(201)
    }

    override fun propfind(dirPath: String): DavListing? {
        calls += "propfind $dirPath"
        if (listing != null) return listing
        // 默认按内部名单收敛一个 listing（文件名匹配 dirPath 前缀的归该目录）
        val stripped = dirPath.trimEnd('/')
        val prefix = "${stripped.substringBeforeLast('/')}/"
        val sub = files.filterKeys { it.startsWith("$stripped/") }
            .mapKeys { it.key.removePrefix("$stripped/") }
        if (sub.isEmpty() && listing == null) return DavListing(dirPath, emptyMap())
        return DavListing(dirPath, sub)
    }

    private fun normalizeName(remotePath: String): String = remotePath.trimEnd('/')
}
