package com.lumina.ingest

/**
 * Ingest 缝隙（M1 spec Implementation Decision 1）：
 * 复制动作与相机协议物理隔离——协议侧（相机页/勾选）只面对本接口，
 * FakeIngestEngine 是 JVM 真相，MtpImportEngine 是 Android 薄实现。
 */
data class IngestItem(
    /** 相机侧对象句柄（MtpImportEngine 用它 importFile；Fake 忽略） */
    val handle: Long,
    /** 相机侧原始文件名（保留原命名） */
    val name: String,
    val sizeBytes: Long,
    /** 拍摄日期（epoch ms）；缺省回退 modifiedMs（Master 目录布局规则，ADR-0004） */
    val captureMs: Long?,
    val modifiedMs: Long?,
)

/** 复制过程的回调（同步调用，drain 的调用方自己决定线程；UI 侧再切主线程）。 */
interface IngestCallback {
    fun onFileStart(item: IngestItem)
    fun onFileSuccess(item: IngestItem, relPath: String)
    /** 同名文件已在库/已在盘 → 跳过不复制（spec：同名跳过并提示，永不覆盖） */
    fun onFileSkipped(item: IngestItem, relPath: String)
    fun onFileFail(item: IngestItem, reason: String)
    fun onBatchDone(successCount: Int, skippedCount: Int, failCount: Int)
}

interface IngestEngine {
    /** 入队（按 name 去重：重复入队无副作用） */
    fun enqueue(item: IngestItem)

    /** 用户取消勾选 → 从队列移除（无此条目则无副作用） */
    fun dismiss(item: IngestItem)

    fun queueSize(): Int

    /** 按队列顺序复制；逐文件回调；一个失败不阻塞后续；结束报汇总。 */
    fun drain(callback: IngestCallback)

    /** 批次完成后队列清空（成功/跳过）；失败文件按 spec 保留供重试（MtpImport/Fake 一致语义）。 */
    fun pendingFails(): List<IngestItem>
}
