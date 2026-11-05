package com.lumina.ingest

import android.mtp.MtpDevice
import com.lumina.master.MasterPaths
import com.lumina.master.MasterStore
import java.io.File

/**
 * Android 薄实现（工单 02）：`MtpDevice.importFile(handle, destPath)` 为复制原语。
 * 会话生命周期内创建；drain 由调用方（相机页）在 io 线程驱动。
 *
 * 每文件语义：
 * - 相机侧 importFile 一次性整文件写（framework 走祖先路径）；
 * - 同名文件已在库/已在盘 → 跳过（收养登记，不覆盖内容）；
 * - 失败不阻塞后续，计入 failed 待重试。
 */
class MtpImportEngine(
    private val mtp: MtpDevice,
    private val store: MasterStore,
) : IngestEngine {

    private val queue = linkedMapOf<String, IngestItem>()
    private val consumed = mutableSetOf<String>()
    private val failedItems = linkedMapOf<String, IngestItem>()

    override fun enqueue(item: IngestItem) {
        queue.putIfAbsent(item.name, item)
    }

    override fun dismiss(item: IngestItem) {
        queue.remove(item.name)
        failedItems.remove(item.name)
    }

    override fun queueSize(): Int = queue.size - consumed.size - failedItems.size

    override fun pendingFails(): List<IngestItem> = failedItems.values.toList()

    override fun drain(callback: IngestCallback) {
        var success = 0
        var skipped = 0
        var fail = 0
        for ((_, item) in queue.toList()) {
            if (item.name in consumed) continue
            callback.onFileStart(item)
            val rel = MasterPaths.relativePath(item.captureMs, item.modifiedMs, item.name)
            val file = store.fileOf(rel)
            val existsOnDisk = file.exists()
            val alreadyRegistered = store.findByRelPath(rel) != null
            when {
                existsOnDisk || alreadyRegistered -> {
                    // 收养：已在盘=登记即可（不覆盖内容）；已在库=纯跳过
                    if (existsOnDisk && !alreadyRegistered) {
                        store.register(item.captureMs, item.modifiedMs, item.name, file.length())
                    }
                    consumed.add(item.name)
                    callback.onFileSkipped(item, rel)
                    skipped++
                }
                importOne(item, file) -> {
                    consumed.add(item.name)
                    callback.onFileSuccess(item, rel)
                    success++
                }
                else -> {
                    failedItems[item.name] = item
                    callback.onFileFail(item, lastReason)
                    fail++
                }
            }
        }
        callback.onBatchDone(success, skipped, fail)
    }

    private var lastReason = "camera refused"

    /** importFile 一发 + Store 登记；true = 全链成功，false 时 [lastReason] 给人话。 */
    private fun importOne(item: IngestItem, file: File): Boolean {
        lastReason = "camera refused the copy"
        return try {
            file.parentFile?.mkdirs()
            if (!mtp.importFile(item.handle.toInt(), file.absolutePath)) {
                false
            } else {
                lastReason = "copied but not registered"
                when (store.register(item.captureMs, item.modifiedMs, item.name, file.length().takeIf { it > 0 } ?: item.sizeBytes)) {
                    is MasterStore.RegisterResult.Registered -> true
                    is MasterStore.RegisterResult.AlreadyPresent -> true // 竞态而已：文件已在
                    is MasterStore.RegisterResult.MissingFile -> false
                }
            }
        } catch (e: Exception) {
            lastReason = e.message ?: e.javaClass.simpleName
            false
        }
    }
}
