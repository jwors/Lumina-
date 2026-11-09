package com.lumina.dav

/**
 * M3-04：备份决策纯函数（JVM 可测）。
 * 输入：远端名单（PROPFIND），Master 清单 (origFileName, sizeBytes)，目标目录语义。
 * 输出：toUpload（需上传的）与 verified（远端存在且字节相等的——可进入"清出手机"确认清单）。
 * 幂等语义（拍板 Q4）：远端同名且同大小 = 跳过不再传；半途断传整文件重来（不分片）。
 * 清出语义（Q5/ADR-0003）：**只有 verified 的能被提出删除**，删除仍是逐项人工确认。
 */
object BackupPlanner {

    data class Plan(
        val toUpload: List<String>,
        val verified: List<String>,
        /** 远端同名但大小不同 → 语义冲突（云上版本不认），v1 也重传但单独提示 */
        val sizeMismatches: List<String>,
    )

    fun plan(
        masters: Map<String, Long>,          // origFileName -> sizeBytes
        listing: DavListing?,
    ): Plan {
        val listing2 = listing
            ?: return Plan(toUpload = masters.keys.toList(), verified = emptyList(), sizeMismatches = emptyList())
        val remote = listing2.files
        val toUpload = mutableListOf<String>()
        val verified = mutableListOf<String>()
        val mismatch = mutableListOf<String>()
        for ((name, size) in masters) {
            val remoteSize = remote[name]
            when {
                remoteSize == null -> toUpload.add(name)
                remoteSize == size -> verified.add(name)
                else -> { mismatch.add(name); toUpload.add(name) }
            }
        }
        return Plan(toUpload, verified, mismatch)
    }
}
