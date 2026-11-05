package com.lumina.ingest

import com.lumina.master.MasterPaths
import com.lumina.master.MasterStore
import java.io.File

/**
 * JVM 真相引擎（工单 02 测试缝）：复制落成 MasterStore 里的真实小文件
 * （内容 = 文件名字节，TemporaryFolder 可观测），每文件结局由 [outcomes] 脚本指定。
 */
class FakeIngestEngine(
    private val store: MasterStore,
    var outcomes: Map<String, Outcome> = emptyMap(),
) : IngestEngine {

    sealed interface Outcome {
        data object Success : Outcome
        data object Skip : Outcome
        data class Fail(val reason: String) : Outcome
    }

    private val queue = linkedMapOf<String, IngestItem>()
    private val consumed = mutableSetOf<String>()   // 成功/跳过即出局
    private val failed = linkedMapOf<String, IngestItem>()
    private val writtenFiles = mutableListOf<Pair<IngestItem, File>>()

    override fun enqueue(item: IngestItem) {
        queue.putIfAbsent(item.name, item)
    }

    override fun dismiss(item: IngestItem) {
        queue.remove(item.name)
    }

    override fun queueSize(): Int = queue.size - consumed.size - failed.size

    override fun pendingFails(): List<IngestItem> = failed.values.toList()

    /** 工单 05：单文件重试 = 复制一支（忽略已消费标记，结局仍走脚本，可以先改 outcomes）。 */
    override fun retryOne(item: IngestItem, callback: IngestCallback) {
        callback.onFileStart(item)
        val rel = MasterPaths.relativePath(item.captureMs, item.modifiedMs, item.name)
        when (val outcome = outcomes[item.name] ?: Outcome.Success) {
            is Outcome.Fail -> {
                failed[item.name] = item
                callback.onFileFail(item, outcome.reason)
            }
            is Outcome.Skip -> {
                consumed.add(item.name)
                failed.remove(item.name)
                callback.onFileSkipped(item, rel)
            }
            is Outcome.Success -> {
                if (copyIntoStore(item, rel)) {
                    consumed.add(item.name)
                    failed.remove(item.name)
                    callback.onFileSuccess(item, rel)
                } else {
                    failed[item.name] = item
                    callback.onFileFail(item, "register failed")
                }
            }
        }
    }

    override fun drain(callback: IngestCallback) {
        var success = 0
        var skipped = 0
        var fail = 0
        for ((_, item) in queue.toList()) {
            if (item.name in consumed) continue
            callback.onFileStart(item)
            when (val outcome = outcomes[item.name] ?: Outcome.Success) {
                is Outcome.Fail -> {
                    failed[item.name] = item
                    callback.onFileFail(item, outcome.reason)
                    fail++
                }
                is Outcome.Skip -> {
                    consumed.add(item.name)
                    callback.onFileSkipped(item, MasterPaths.relativePath(item.captureMs, item.modifiedMs, item.name))
                    skipped++
                }
                is Outcome.Success -> {
                    val rel = MasterPaths.relativePath(item.captureMs, item.modifiedMs, item.name)
                    if (copyIntoStore(item, rel)) {
                        consumed.add(item.name)
                        callback.onFileSuccess(item, rel)
                        success++
                    } else {
                        failed[item.name] = item
                        callback.onFileFail(item, "register failed")
                        fail++
                    }
                }
            }
        }
        callback.onBatchDone(success, skipped, fail)
    }

    /** 真伪一致：写文件 → store.register（同名/已在盘走收养或跳过语义，与真机引擎同型）。 */
    private fun copyIntoStore(item: IngestItem, rel: String): Boolean {
        val file = store.fileOf(rel)
        file.parentFile?.mkdirs()
        if (file.exists()) {
            // 已在盘但未登记 → 收养：登记成功即算成功，不再写内容
            val adopt = store.register(item.captureMs, item.modifiedMs, item.name, file.length())
            return adopt is MasterStore.RegisterResult.Registered
        }
        file.writeBytes(item.name.toByteArray(Charsets.UTF_8))
        writtenFiles += item to file
        return when (store.register(item.captureMs, item.modifiedMs, item.name, file.length())) {
            is MasterStore.RegisterResult.Registered -> true
            is MasterStore.RegisterResult.AlreadyPresent -> false // 竞态：已被登记 → 跳过语义
            is MasterStore.RegisterResult.MissingFile -> false
        }
    }

    /** 测试观测口：真写过的文件（item→File，按写入顺序）。 */
    fun written(): List<Pair<IngestItem, File>> = writtenFiles.toList()
}
