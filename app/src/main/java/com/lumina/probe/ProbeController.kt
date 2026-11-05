package com.lumina.probe

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.Handler
import android.os.Looper
import com.lumina.ingest.IngestCallback
import com.lumina.ingest.IngestEngine
import com.lumina.ingest.IngestItem
import com.lumina.ingest.MtpImportEngine
import com.lumina.master.AppDatabase
import com.lumina.master.IngestQueueEntity
import com.lumina.master.MasterStore
import com.lumina.master.RoomInventoryStore
import com.lumina.ptp.CameraObject
import com.lumina.ptp.CatalogSummary
import com.lumina.ptp.ObjectCatalog
import com.lumina.ptp.ObjectFormat
import com.lumina.ptp.PtpResponseException
import com.lumina.ptp.PtpSession
import com.lumina.usb.UsbCameraMonitor
import com.lumina.usb.UsbCameraStateMachine
import com.lumina.usb.UsbDeviceClassifier
import com.lumina.usb.UsbTransport
import com.lumina.usb.UsbTransportException
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * M1 升级：探针控制器 = 连接监视 → 授权 → 会话 → 相机清单 → 勾选队列 → Ingest 复制。
 * 队列真相 = Room（IngestQueue 表）；引擎是会话内存镜像（重连时由表重建）。
 * 事件/失败仍落私有目录 m0-probe.log（spec story 20）。
 */
class ProbeController(
    context: Context,
    private val onState: (ProbeUiState) -> Unit,
) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainThread = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var session: PtpSession? = null

    private val db = AppDatabase.get(appContext)
    private val inventoryStore = RoomInventoryStore(db.masterInventoryDao())
    private val queueDao = db.ingestQueueDao()
    private val masterStore = MasterStore(appContext.filesDir, inventoryStore)

    /** Ingest 引擎：仅 MTP 兜底枚举成功时会就位（自研 PTP GetObject 引擎 M2 接管） */
    private var engine: IngestEngine? = null

    /** 相机页数据：勾选对象（文件；目录不可勾） */
    private var cameraItems: List<CameraItem> = emptyList()
    private var checkedNames: Set<String> = emptySet()

    /** 上一轮成功清单（切换队列状态时重发用） */
    private var lastTitle: String? = null
    private var lastSummary: CatalogSummary? = null
    private var lastLogTail: List<String> = emptyList()
    private var lastIngestStatus: String = ""
    private var lastGalleryItems: List<GalleryItem> = emptyList()
    private var lastGalleryStatus: String = ""

    private val monitor = UsbCameraMonitor(appContext, usbManager) { monitorState, kind, device ->
        io.execute { react(monitorState, kind, device) }
    }

    init {
        io.execute { emit(ProbeUiState("未连接 · 请插入相机")) }
    }

    // ---------- 公共动作（UI 入口，工单 03） ----------

    fun retryPermission() {
        io.execute { monitor.retryPermission() }
    }

    /** 勾选/取消一条：Room 是真相，引擎是镜像 */
    fun toggleCheck(item: CameraItem) {
        io.execute {
            val obj = ingestItemOf(item)
            val row = IngestQueueEntity(
                name = item.name, handle = item.handle, sizeBytes = item.sizeBytes,
                captureMs = item.captureMs, modifiedMs = item.modifiedMs,
                queuedAtMs = System.currentTimeMillis(),
            )
            if (queueDao.deleteByName(item.name) > 0) {
                engine?.dismiss(obj)
                logEvents(listOf("ingest queue: removed ${item.name}"))
            } else {
                queueDao.upsert(row)
                engine?.enqueue(obj)
                logEvents(listOf("ingest queue: queued ${item.name}"))
            }
            emitCameraPage()
        }
    }

    fun toggleDay(dateKey: String, checked: Boolean) {
        io.execute {
            for (item in cameraItems.filter { it.dateKey == dateKey }) {
                if (checked) {
                    queueDao.upsert(
                        IngestQueueEntity(
                            name = item.name, handle = item.handle, sizeBytes = item.sizeBytes,
                            captureMs = item.captureMs, modifiedMs = item.modifiedMs,
                            queuedAtMs = System.currentTimeMillis(),
                        ),
                    )
                    engine?.enqueue(ingestItemOf(item))
                } else {
                    queueDao.deleteByName(item.name)
                    engine?.dismiss(ingestItemOf(item))
                }
            }
            logEvents(listOf("ingest queue: ${if (checked) "queued" else "cleared"} dateKey=$dateKey"))
            emitCameraPage()
        }
    }

    /** Ingest 一轮；导出按钮走这里（弱供电自动重试循环保持） */
    fun startIngest() {
        io.execute {
            val ready = engine
            if (ready == null) {
                emit(ProbeUiState("没有可用的复制引擎（需要 MTP 会话）：请重插相线", engineReady = false))
            } else if (checkedNames.isEmpty()) {
                emit(ProbeUiState("队列是空的：先勾选对象", engineReady = true))
            } else {
                runIngestRound(ready)
            }
        }
    }

    fun retryFailed() {
        io.execute {
            if (engine != null && engine!!.queueSize() > 0) runIngestRound(engine!!)
            else emit(ProbeUiState("没有待重试的失败文件"))
        }
    }

    // ---------- 图库（工单 04） ----------

    /** 打开图库 Tab 时调用：pruneAbsent + 收养孤儿 + 快照上屏 */
    fun loadGallery() {
        io.execute {
            val pruned = masterStore.pruneAbsent()
            var adopted = 0
            // 收养：Master/ 子树里未登记的真实图像文件
            appContext.filesDir.walkTopDown()
                .filter { f -> f.isFile }
                .map { f -> f to f.relativeTo(appContext.filesDir).invariantSeparatorsPath }
                .filter { (_, rel) -> rel.startsWith("Master/") }
                .filter { (_, rel) ->
                    val upper = rel.uppercase()
                    upper.endsWith(".JPG") || upper.endsWith(".JPEG") || upper.endsWith(".NEF") || upper.endsWith(".MP4")
                }
                .forEach { (_, rel) ->
                    if (masterStore.adoptExisting(rel)) adopted++
                }
            val rows = masterStore.snapshot()
            val items = rows.map {
                GalleryItem(relPath = it.relPath, name = it.origFileName,
                    dateKey = it.dateKey, sizeBytes = it.sizeBytes)
            }
            lastGalleryItems = items
            lastGalleryStatus = if (pruned > 0 || adopted > 0)
                "对账：清理失效 $pruned · 收养新 $adopted" else ""
            emit(ProbeUiState(
                "Master 图库 · ${items.size} 个对象 · ${humanBytes(items.sumOf { it.sizeBytes })}",
                galleryItems = items,
                galleryStatus = lastGalleryStatus,
            ))
        }
    }

    /** 图库根目录（= filesDir；布局约束见 ADR-0004）——已并入 loadGallery 的 walkTopDown */

    // ---------- Ingest 进度（工单 05） ----------

    private var ingestRunning = false
    private var progressDone = 0
    private var progressTotal = 0
    private var progressCurrent = ""
    private val fileStatuses = linkedMapOf<String, String>()

    fun ingestRowsForUi(): Map<String, String> = fileStatuses.toMap()

    /** 每文件状态语义映射进 UI；失败文件带按钮。 */
    private fun registerStatus(name: String, text: String) {
        fileStatuses[name] = text
    }

    private fun resetProgress(total: Int) {
        ingestRunning = total > 0
        progressDone = 0
        progressTotal = total
        progressCurrent = ""
    }

    /** Ingest 一轮（真机弱供电自动重试循环已于 02 号实现）。 */
    private fun runIngestRound(engine: IngestEngine) {
        if (ingestRunning) return
        resetProgress(engine.queueSize())
        if (!ingestRunning) {
            emitCameraPage()
            return
        }
        engine.drain(object : IngestCallback {
            override fun onFileStart(item: IngestItem) {
                progressCurrent = item.name
                registerStatus(item.name, "复制中…")
                emitCameraPage()
            }
            override fun onFileSuccess(item: IngestItem, relPath: String) {
                queueDao.deleteByName(item.name)
                progressDone++
                registerStatus(item.name, "✓ 已入库")
                emitCameraPage()
            }
            override fun onFileSkipped(item: IngestItem, relPath: String) {
                queueDao.deleteByName(item.name)
                progressDone++
                registerStatus(item.name, "已在库（跳过）")
                emitCameraPage()
            }
            override fun onFileFail(item: IngestItem, reason: String) {
                failedFilesOfRound[item.name] = item
                registerStatus(item.name, "✗ $reason")
                emitCameraPage()
            }
            override fun onBatchDone(s: Int, sk: Int, f: Int) {
                ingestRunning = false
                lastIngestStatus = "Ingest：成功 $s · 跳过 $sk · 失败 $f"
                emitCameraPage()
            }
        })
    }

    /** 单文件重试（失败行上的按钮）。 */
    fun retryOne(item: CameraItem) {
        io.execute {
            val engineReady = engine
            if (engineReady == null) {
                emit(ProbeUiState("没有可用的复制引擎（需要 MTP 会话）"))
                return@execute
            }
            registerStatus(item.name, "重试中…")
            emitCameraPage()
            engineReady.retryOne(ingestItemOf(item), object : IngestCallback {
                override fun onFileStart(item: IngestItem) = Unit
                override fun onFileSuccess(i: IngestItem, relPath: String) {
                    queueDao.deleteByName(item.name)
                    registerStatus(item.name, "✓ 已入库")
                    lastIngestStatus = "重试成功：${item.name}"
                }
                override fun onFileSkipped(i: IngestItem, relPath: String) {
                    queueDao.deleteByName(item.name)
                    registerStatus(item.name, "已在库（跳过）")
                    lastIngestStatus = "已在库：${item.name}"
                }
                override fun onFileFail(i: IngestItem, reason: String) {
                    registerStatus(item.name, "✗ $reason")
                    lastIngestStatus = "重试失败：${item.name}（$reason）"
                }
                override fun onBatchDone(s: Int, sk: Int, f: Int) = Unit
            })
            emitCameraPage()
        }
    }

    private val failedFilesOfRound = linkedMapOf<String, IngestItem>()

    // ---------- 连接反应（照旧） ----------

    private fun react(
        state: UsbCameraStateMachine.State,
        kind: UsbDeviceClassifier.DeviceKind?,
        device: UsbDevice?,
    ) {
        logEvents(listOf("camera event: state=$state kind=$kind device=${device?.deviceName}"))
        when (state) {
            UsbCameraStateMachine.State.NOT_CONNECTED -> teardown()
            UsbCameraStateMachine.State.PENDING_PERMISSION ->
                emit(
                    ProbeUiState(
                        "相机已插入（kind=$kind），等你在系统弹窗点\"允许\"。" +
                            "若没有弹窗：唤醒相机屏幕后点\"重试授权\"",
                        pendingPermission = true,
                    ),
                )
            UsbCameraStateMachine.State.READY -> {
                emit(ProbeUiState("清单读取中…", busy = true))
                when (kind) {
                    UsbDeviceClassifier.DeviceKind.MTP_SUSPECT -> listViaMtp(device)
                    else -> listViaPtp(device)
                }
            }
        }
    }

    // ---------- 主路径：纯 PTP（清单；Ingest 引擎见 M2） ----------

    private fun listViaPtp(device: UsbDevice?) {
        if (device == null) {
            emit(ProbeUiState("已授权但设备不可见，请拔下重插")); return
        }
        try {
            val warm = UsbTransport.from(usbManager, device, diag = { line -> logEvents(listOf("warmup: $line")) })
            warm.open()
            warm.close()
            logEvents(listOf("warmup: done"))
        } catch (e: Exception) {
            logEvents(listOf("warmup skipped: ${e.javaClass.simpleName}: ${e.message}"))
        }
        var attempt = 0
        while (true) {
            val transport = try {
                UsbTransport.from(usbManager, device, diag = { line -> logEvents(listOf(line)) })
            } catch (e: Exception) {
                logEvents(listOf("transport open: ${e.javaClass.simpleName}: ${e.message}"))
                emit(ProbeUiState("无法打开相机：${humanize(e)}")); return
            }
            val ptp = PtpSession(transport)
            session = ptp
            try {
                ptp.open()
                val model = ptp.getModel()
                val summary = ObjectCatalog.merge(
                    ptp.listStorage().map { ObjectCatalog(ptp).traverse(it.id) },
                )
                publishListing(title = "$model · 清单就绪（PTP）", summary = summary,
                    logTail = ptp.log.snapshot().takeLast(LOG_TAIL))
                return
            } catch (e: Exception) {
                logEvents(listOf("PTP path fail: ${e.javaClass.simpleName}: ${e.message}") +
                    ptp.log.snapshot().takeLast(6))
                runCatching { ptp.close() }
                session = null
                attempt++
                if (attempt >= 1) {
                    // 本机实证：PTP Data 阶段深水区（tid=0/ZLP 修复后仍哑）——一次即放 MTP，
                    // 省掉无意义的 12s×N（见 .scratch/m1-ingest-gallery spec 遗留清单）
                    emit(ProbeUiState("读取失败：${humanize(e)}"))
                    listViaMtp(device)
                    return
                }
                logEvents(listOf("retrying PTP session (attempt $attempt)"))
                emit(ProbeUiState("正在重试连接相机…", busy = true))
            }
        }
    }

    // ---------- 兜底：android.mtp.MtpDevice（story 17；兼当 Ingest 引擎载体） ----------

    private fun listViaMtp(device: UsbDevice?) {
        if (device == null) {
            emit(ProbeUiState("已授权但设备不可见，请拔下重插")); return
        }
        try {
            val conn = usbManager.openDevice(device)
                ?: throw UsbTransportException("open", "MTP openDevice failed")
            val mtp = MtpDevice(device)
            if (!mtp.open(conn)) {
                throw UsbTransportException("open", "MTP openConnection failed")
            }
            // 引擎在会话 open 的瞬间就位（不等枚举）+ 从 Room 重建队列：
            // "引擎未就绪"永远不该出现在 MTP 会话存活期间
            engine = MtpImportEngine(mtp, masterStore)
            for (row in queueDao.all()) {
                engine?.enqueue(
                    IngestItem(row.handle, row.name, row.sizeBytes, row.captureMs, row.modifiedMs),
                )
            }
            val files = mutableListOf<CameraObject>()
            val folders = mutableListOf<CameraObject>()
            val infoExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
            for (storageId in mtp.getStorageIds() ?: IntArray(0)) {
                val seen = mutableSetOf<Int>()
                val handles = mutableListOf<Int>()
                outer@ for (format in intArrayOf(0, -1)) {
                    for (parent in intArrayOf(0, PtpSession.PARENT_ALL.toInt(), -1)) {
                        val altHandles = mtp.getObjectHandles(storageId, format, parent) ?: IntArray(0)
                        for (h in altHandles) if (seen.add(h)) handles.add(h)
                        if (handles.isNotEmpty()) break@outer
                    }
                }
                logEvents(
                    listOf("MTP storage=0x%08X handles=%d".format(storageId, handles.size)),
                )
                // 相机枚举期间先给 UI 一个"进行中"快照（引擎已就位，导出按钮当场可用）
                emit(ProbeUiState(
                    "MTP 枚举 ${handles.size} 个句柄…（弱供电相机可能提前沉默）",
                    busy = true,
                    engineReady = true,
                ))
                for (handle in handles) {
                    val info = try {
                        val fut = infoExecutor.submit<android.mtp.MtpObjectInfo?> { mtp.getObjectInfo(handle) }
                        fut.get(3, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: java.util.concurrent.TimeoutException) {
                        // 相机哑了：native 调用无限期阻塞——立即收摊，带着已有清单发货
                        logEvents(listOf("MTP scan stopped at $handle (${files.size} files after timeout)"))
                        break
                    } catch (e: Exception) {
                        null
                    }
                    if (info == null) {
                        // 相机中途哑（弱供电实证 ~131 后沉默）：不再按铃，带着已拿到的直接上屏
                        logEvents(listOf("MTP scan stopped at $handle (${files.size} files + ${folders.size} folders so far)"))
                        break
                    }
                    val obj = CameraObject(
                        handle = info.objectHandle.toLong(),
                        parent = if (info.parent == 0) ObjectCatalog.PARENT_ROOT else info.parent.toLong(),
                        isFolder = info.format == ObjectFormat.ASSOCIATION_FOLDER,
                        name = info.name,
                        sizeBytes = info.compressedSizeLong,
                        captureEpochMillis = mtpMillis(info),
                    )
                    if (obj.isFolder) folders.add(obj) else files.add(obj)
                }
            }
            val summary = CatalogSummary(files, folders)
            infoExecutor.shutdown()
            val fetched = files.size + folders.size
            if (fetched == 0) {
                logEvents(listOf("MTP enumeration empty"))
                emit(
                    ProbeUiState(
                        "MTP 兜底：相机此刻没应答（0 个对象）。请重新插拔 OTG 或稍等重试",
                    ),
                )
                return
            }
            // 就位 Ingest 引擎（真机路径）+ 从 Room 重建队列
            engine = MtpImportEngine(mtp, masterStore)
            for (row in queueDao.all()) {
                engine?.enqueue(
                    IngestItem(row.handle, row.name, row.sizeBytes, row.captureMs, row.modifiedMs),
                )
            }
            publishListing(
                title = "${device.productName ?: "MTP 设备"} · 清单就绪（MTP 兜底）",
                summary = summary,
                logTail = listOf(
                    "== MTP 兜底路径（story 17）==",
                    "handles=${files.size + folders.size}（files=${files.size}）",
                ),
                afterSetup = { refreshCheckedFromDb() },
            )
        } catch (e: Exception) {
            logEvents(listOf("MTP path fail: ${humanize(e)}"))
            emit(ProbeUiState("MTP 兜底也失败：${humanize(e)}"))
        }
    }

    /** MTP 日期是 epoch 秒（捕获优先，回退修改，0=无）。 */
    private fun mtpMillis(info: android.mtp.MtpObjectInfo): Long? {
        val seconds = info.dateCreated.takeIf { it != 0L } ?: info.dateModified.takeIf { it != 0L }
        return seconds?.times(1000L)
    }

    // ---------- 清单发布 + 相机页快照 ----------

    private fun publishListing(title: String, summary: CatalogSummary, logTail: List<String>, afterSetup: (() -> Unit)? = null) {
        lastTitle = title
        lastSummary = summary
        lastLogTail = logTail
        val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        cameraItems = summary.files.map { obj ->
            CameraItem(
                handle = obj.handle,
                name = obj.name,
                sizeBytes = obj.sizeBytes,
                dateKey = obj.captureEpochMillis?.let { DATE_FMT.format(Instant.ofEpochMilli(it)) } ?: "未知日期",
                captureMs = obj.captureEpochMillis,
                modifiedMs = null,
            )
        }
        afterSetup?.invoke()
        emitCameraPage()
    }

    /** checked 现算 + 全量重发（相机快照 min 依存：summary 可空 只发文字） */
    private fun refreshCheckedFromDb() {
        checkedNames = queueDao.all().map { it.name }.toSet()
    }

    private fun emitCameraPage() {
        refreshCheckedFromDb()
        val summaryOut = lastSummary
        val title = if (lastSummary != null) {
            "${lastTitle ?: ""}（勾 ${checkedNames.size}）"
        } else {
            lastTitle ?: "未连接"
        }
        val progress = if (progressTotal > 0) {
            IngestProgress(
                running = ingestRunning,
                done = progressDone,
                total = progressTotal,
                currentName = progressCurrent,
                statuses = fileStatuses.toMap(),
            )
        } else {
            null
        }
        mainThread.post {
            onState(
                ProbeUiState(
                    connectionText = title,
                    busy = false,
                    model = lastTitle?.substringBefore(" ·"),
                    summary = summaryOut,
                    logTail = lastLogTail.takeLast(LOG_TAIL),
                    cameraItems = cameraItems,
                    checkedNames = checkedNames,
                    engineReady = engine != null,
                    ingestProgress = progress,
                    ingestStatus = lastIngestStatus,
                    galleryItems = lastGalleryItems,
                    galleryStatus = lastGalleryStatus,
                ),
            )
        }
    }

    private fun ingestItemOf(item: CameraItem) =
        IngestItem(handle = item.handle, name = item.name, sizeBytes = item.sizeBytes,
            captureMs = item.captureMs, modifiedMs = item.modifiedMs)

    // ---------- 状态与日志 ----------

    private fun teardown() {
        runCatching { session?.close() }
        session = null
        engine = null
        lastTitle = null
        lastSummary = null
        lastLogTail = emptyList()
        lastIngestStatus = ""
        lastGalleryItems = emptyList()
        lastGalleryStatus = ""
        emit(ProbeUiState("未连接 · 请插入相机"))
    }

    /** 运行期异常 → 人话（类型化，不靠截字符串）。 */
    private fun humanize(e: Exception): String = when {
        e is PtpResponseException ->
            "相机返回错误 0x%04X（op=0x%04X，会话可能已失效，重插一次再试）".format(e.code, e.opCode)
        e is UsbTransportException && e.reason == "read" -> "相机休眠或已拔出，唤醒或重插"
        e is UsbTransportException && e.reason == "write" -> "相机没响应写入（休眠中？）"
        e is UsbTransportException && e.reason == "claim" -> "接口被系统占用：拔下重插一次，或重启手机再试"
        e.message?.contains("no Still-Image interface") == true -> "相机不是 PTP 模式：菜单切换 USB 连接方式"
        else -> e.message ?: e.toString()
    }

    /** story 20：设备事件/协议异常落应用私有目录（真机现场可回溯）。 */
    private fun logEvents(lines: List<String>) {
        if (lines.isEmpty()) return
        runCatching {
            val file = File(appContext.filesDir, "m0-probe.log")
            val stamp = Instant.now().toString()
            file.appendText(lines.joinToString("") { "[$stamp] $it\n" })
        }
    }

    private fun emit(state: ProbeUiState) = mainThread.post { onState(state) }

    private companion object {
        const val LOG_TAIL = 8
    }
}

/** 探针界面数据包（M1 增广：相机页数据 + 队列状态 + 引擎可用性） */
data class ProbeUiState(
    val connectionText: String,
    val busy: Boolean = false,
    val model: String? = null,
    val summary: CatalogSummary? = null,
    /** 环形日志尾部（从旧到新） */
    val logTail: List<String> = emptyList(),
    /** 待授权态：界面给出"重试授权"的交互入口 */
    val pendingPermission: Boolean = false,
    /** 相机页：可勾选对象（文件；目录不可选） */
    val cameraItems: List<CameraItem> = emptyList(),
    /** Room 队列的当前勾选 */
    val checkedNames: Set<String> = emptySet(),
    /** Ingest 引擎可用（= MTP 会话在手） */
    val engineReady: Boolean = false,
    /** Ingest 进度（工单 05）：非 null 才显示进度条/状态行 */
    val ingestProgress: IngestProgress? = null,
    /** 上一轮 Ingest 结果文本 */
    val ingestStatus: String = "",
    /** 图库（工单 04）：Room 驱动的 Master 全量快照 */
    val galleryItems: List<GalleryItem> = emptyList(),
    val galleryStatus: String = "",
)

/** Ingest 进度数据包。 */
data class IngestProgress(
    val running: Boolean,
    val done: Int,
    val total: Int,
    val currentName: String,
    /** 文件名 → 状态文本（✓ / 跳过 / ✗ 原因 / 复制中… / 待复制） */
    val statuses: Map<String, String>,
)

/** 图库条目。 */
data class GalleryItem(
    val relPath: String,
    val name: String,
    val dateKey: String,
    val sizeBytes: Long,
)

/** 相机页的可勾对象（UI 直接消费）。 */
data class CameraItem(
    val handle: Long,
    val name: String,
    val sizeBytes: Long,
    val dateKey: String,
    val captureMs: Long?,
    val modifiedMs: Long?,
)
