package com.lumina.probe

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.Handler
import android.os.Looper
import com.lumina.cards.CardEngine
import com.lumina.cards.CardEntity
import com.lumina.cards.SkillCard
import com.lumina.cards.SkillCardException
import com.lumina.ingest.IngestCallback
import com.lumina.ingest.IngestEngine
import com.lumina.ingest.IngestItem
import com.lumina.ingest.MtpImportEngine
import com.lumina.master.AppDatabase
import com.lumina.master.IngestQueueEntity
import com.lumina.master.MasterStore
import com.lumina.master.RoomInventoryStore
import com.lumina.pass.PassExecutor
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
    private val llmConfigDao = db.llmConfigDao()

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
    // M2：补跑队列/智能层
    private val passQueueDao = db.passQueueDao()
    private val webDavConfigDao = db.webDavConfigDao()
    private val imageGenConfigDao = db.imageGenConfigDao()
    private val resultsDao = db.smartResultsDao()
    private val ledgerDaoX = db.ledgerDao()
    private var catchupBanner: String? = null
    private var passStatus: String = ""
    /** M4-01：glm-image 资源包请求频次限制的冷却闸（elapsedRealtime 基准） */
    private var _imagegenCoolUntil = 0L
    private var lastLedgerLines: List<String> = emptyList()
    private var galleryLoading = false
    private var cardsLoading = false
    /** 图库→滤镜的勾选通道（用户 M1/M2 评审期望流：图库选中 ↘ 滤镜执行） */
    private val passSelection = linkedSetOf<String>()
    /** M3 交互：滤镜包卡片执行（半屏弹层 → 串行执行 → 保存收尾）的进行中状态 */
    private var activeCardRun: CardRunUi? = null
    private var abortRequested = false
    private var productNamePrefix = "MTP 设备"
    private var prevCount = 0

    /** 最近一次成功渲染的 state：切 Tab / 临时状态发射不得把别的 Tab 数据弄丢 */
    private val lastEmitted = java.util.concurrent.atomic.AtomicReference<ProbeUiState>(
        ProbeUiState("未连接 · 请插入相机"),
    )

    private val monitor = UsbCameraMonitor(appContext, usbManager) { monitorState, kind, device ->
        io.execute { react(monitorState, kind, device) }
    }

    init {
        io.execute { emit(ProbeUiState("未连接 · 请插入相机")) }
        watchNetwork()
        io.execute { updateCatchupBanner() }
        // M4-01 自愈：chat base_url 被 images/generations 污染的存量配置 → 启动时归位
        // （历史代码把生图 URL 粘进 LLM 的 base_url 曾令编译步全 404，成片"无任何效果"）
        io.execute {
            llmConfigDao.active()?.let { c ->
                if (c.baseUrl.contains("/images/generations")) {
                    llmConfigDao.upsert(c.copy(
                        baseUrl = c.baseUrl.substringBefore("/images/generations"),
                        model = "glm-4v-flash",
                    ))
                    logEvents(listOf("heal: chat base was imagegen-polluted → 归位 paas/v4 + glm-4v-flash"))
                    toastify("检测到 chat 配置被生图 URL 污染，已自动归位（重跑滤镜卡即可）")
                }
            }
            // M4-01 自愈 2：早期生成文件命名事故（*_w.g.jpg）→ 归位 *_g.jpg（扫描契约）
            appContext.filesDir.walkTopDown()
                .filter { f -> f.isFile && f.name.endsWith("_w.g.jpg") }
                .forEach { f ->
                    val fixed = File(f.parentFile, f.name.removeSuffix("_w.g.jpg") + "_g.jpg")
                    if (f.renameTo(fixed)) {
                        logEvents(listOf("heal: renamed ${f.name} → ${fixed.name}"))
                    }
                }
        }
    }

    // ---------- 公共动作（UI 入口，工单 03） ----------

    fun retryPermission() {
        io.execute { monitor.retryPermission() }
    }

    /** 勾选/取消一条：Room 是真相，引擎是镜像 */
    /** tab 切回相机时的快照重发（合并发射，无新增数据） */
    fun emitCameraUiRefresh() {
        io.execute { emitCameraPage() }
    }

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

    // ---------- M2 智能层（工单 05） ----------

    private val runningSingle = mutableSetOf<String>()

    /** 编辑界面单步执行：对一张 Master 即时跑某类步骤（标注/文案/滤镜建议） */
    fun runSingleStep(name: String, kind: com.lumina.cards.StepKind) {
        io.execute {
            val cfg = llmConfigDao.active()
            if (cfg == null) { emit(lastEmitted.get().copy(passStatus = "先配置 LLM")); return@execute }
            val jpeg = jpegBytesFor(name)
            if (jpeg == null) { emit(lastEmitted.get().copy(passStatus = "$name 无 JPEG 源")); return@execute }
            runningSingle.add(name)
            emit(lastEmitted.get().copy(passStatus = "$name ${kind.name.lowercase()} 执行中…"))
            // prompt：启用卡里同名步骤的 prompt，否则默认模板
            val prompt = firstCard()?.steps?.firstOrNull { it.kind == kind }?.prompt
                ?: com.lumina.pass.PassExecutor.Prompts.annotate.takeIf { kind == com.lumina.cards.StepKind.ANNOTATE }
                    ?: com.lumina.pass.PassExecutor.Prompts.caption.takeIf { kind == com.lumina.cards.StepKind.CAPTION }
                    ?: com.lumina.pass.PassExecutor.Prompts.filter
            val outcome = com.lumina.llm.OkHttpLlmClient().complete(cfg, prompt, jpeg)
            when (val o = outcome) {
                is com.lumina.llm.LlmOutcome.Text -> {
                    resultsDao.insert(com.lumina.pass.SmartResultEntity(
                        0, name, "_direct", kind.name.lowercase(), o.text, cfg.model, System.currentTimeMillis(),
                    ))
                    ledgerDaoX.insert(com.lumina.llm.LedgerEntity(
                        0, name, cfg.model, prompt.length, jpeg != null,
                        "OK", o.promptTokens, o.completionTokens, System.currentTimeMillis(),
                    ))
                }
                is com.lumina.llm.LlmOutcome.RateLimited -> ledgDaoSafe("RateLimited", cfg, prompt, jpeg != null)
                is com.lumina.llm.LlmOutcome.Http -> ledgDaoSafe("Http_${o.code}", cfg, prompt, jpeg != null)
                is com.lumina.llm.LlmOutcome.NetworkTimeout -> ledgDaoSafe("NetworkTimeout", cfg, prompt, jpeg != null)
            }
            runningSingle.remove(name)
            lastGalleryItems = buildGalleryItems()
            emit(lastEmitted.get().copy(
                galleryItems = lastGalleryItems,
                passStatus = if (outcome is com.lumina.llm.LlmOutcome.Text) "" else "执行失败",
                passSelectedNames = passSelection.toSet(),
                smartFeed = loadSmartFeedLines(),
            ))
        }
    }

    private fun firstCard(): com.lumina.cards.SkillCard? =
        cardDao().all().firstOrNull()?.let {
            CardEngine.parse(it.stepsJson) { it.cardId }
        }

    private fun loadSmartFeedLines(): List<com.lumina.probe.SmartFeedItem> =
        resultsDao.recent().map { r ->
            com.lumina.probe.SmartFeedItem(r.name, r.kind, r.text, r.model, r.createdAtMs)
        }

    private fun ledgDaoSafe(outcome: String, cfg: com.lumina.llm.LlmConfigEntity, prompt: String, withImage: Boolean) {
        ledgerDaoX.insert(com.lumina.llm.LedgerEntity(
            0, "_direct", cfg.model, prompt.length, withImage, outcome, null, null, System.currentTimeMillis(),
        ))
    }

    /** Work 静默预生成（按键版，供编辑工具栏「生成 Work」使用） */
    fun generateWorkFor(name: String) {
        io.execute {
            runningSingle.add(name)
            emit(lastEmitted.get().copy(passStatus = "$name 生成 Work 预览中…"))
            val rows = masterStore.snapshot()
            val row = rows.firstOrNull { it.origFileName == name } ?: run {
                emit(lastEmitted.get().copy(passStatus = "Master 中无 $name")); return@execute
            }
            val rel = renderWorkFor(row, name)
            if (rel == null) {
                emit(lastEmitted.get().copy(passStatus = "Work 生成失败"))
                logEvents(listOf("work fail: $name"))
                return@execute
            }
            logEvents(listOf("work: generated $rel"))
            lastGalleryItems = buildGalleryItems()
            emit(lastEmitted.get().copy(
                galleryItems = lastGalleryItems,
                passStatus = "Work 预览已生成：$rel",
            ))
        }
    }

    /** 为一张 Master 渲染成片并写入 Work 层；返回 Work 触达路径（失败 null，语义=不可保存）。
     *  选 LUT 优先级：建议文本命中家族 → 卡名兜底家族（v 提升，2026-11-09）→ 无家族走 CPU。 */
    private fun renderWorkFor(
        row: com.lumina.master.MasterInventoryEntity,
        name: String,
        cardName: String? = null,
    ): String? {
        val src = fileOfRowOrMaster(row.relPath) ?: return null
        val suggestion = resultsDao.recent()
            .lastOrNull { r -> r.name == name && r.kind == "filter_suggest" }?.text ?: ""
        val bytes = runCatching {
            val preset = com.lumina.work.LutPresets.pickFor(suggestion)
                ?: cardName?.let { com.lumina.work.LutPresets.presetForCard(it) }
                ?.also { logEvents(listOf("lut: 建议文本为空/未命中 → 按卡名兜底家族 ${it.keys.first()}")) }
            if (preset != null) {
                // M3-02 GL 路线：滤镜建议命中预设家族 → .cube LUT 真渲染；失败回退 CPU ColorMatrix
                // 2026-11-09 补灯：命中/回退都落日志（此前 GL 静默失败被误读为"滤镜没效果"）
                logEvents(listOf(
                    "lut: hit preset=${preset.keys.first()} for=${name.take(14)} " +
                        "suggest=${suggestion.take(40)}",
                ))
                com.lumina.work.WorkRendererGpu(
                    appContext,
                    log = { l ->
                        logEvents(listOf(l))
                        android.util.Log.i("Lumina.UI", l)
                    },
                ).bytes(src, preset.lut) ?: run {
                    logEvents(listOf("lut: GL 路线 null → 回退 CPU ColorMatrix"))
                    com.lumina.work.WorkRenderer(src, suggestion).bytes()
                }
            } else {
                logEvents(listOf("lut: miss (无命中家族) → CPU 路线，suggest=${suggestion.take(40)}"))
                com.lumina.work.WorkRenderer(src, suggestion).bytes()
            }
        }.getOrNull() ?: return null
        val rel = com.lumina.work.WorkRenderer.workRelPath(row.dateKey, name)
        val out = File(appContext.filesDir, rel)
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        return rel
    }

    /** 打开应用时的网络监听：回网 + 队列非空 → 补跑横幅 */
    fun watchNetwork() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                io.execute { updateCatchupBanner() }
            }
        })
    }

    private fun networkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 补跑横幅：云开关开 + 队列非空 + 网络可用 */
    fun updateCatchupBanner() {
        val enabled = llmConfigDao.active()?.cloudEnabled ?: false
        val count = passQueueDao.count()
        catchupBanner = when {
            !enabled || count == 0 || !networkAvailable() -> null
            else -> "检测到网络：$count 个 Master 待处理 [补跑]"
        }
        passStatus = catchupBanner ?: passStatus
        emitCameraPage()
    }

    /** 图库行勾选进/出智能处理队列（独立于 Ingest 的 IngestQueue） */
    fun togglePassSelection(name: String) {
        io.execute {
            if (!passSelection.remove(name)) passSelection.add(name)
            logEvents(listOf("sel: toggle $name → ${passSelection.size} in channel"))
            emitSelection()
        }
    }

    fun clearPassSelection() {
        io.execute {
            logEvents(listOf("sel: clear (was ${passSelection.size})"))
            passSelection.clear(); emitSelection()
        }
    }

    fun runCardPass(cardId: String) = startCardRun(cardId, selectedOnly = false)

    /** 滤镜包执行（M3 半屏弹层流）：确认范围 → 入队 → 串行执行（逐张反馈）→ AWAIT_SAVE 等收尾。 */
    fun startCardRun(cardId: String, selectedOnly: Boolean) {
        io.execute {
            logEvents(listOf("pass: startCardRun card=$cardId selectedOnly=$selectedOnly channel=${passSelection.size}"))
            val ent = cardDao().byId(cardId)
            if (ent == null) { emit(ProbeUiState("卡不存在")); return@execute }
            if (!(llmConfigDao.active()?.cloudEnabled ?: false)) {
                emit(ProbeUiState("云处理总开关是关的：先开它（设置 Tab）"))
                return@execute
            }
            val chosen = if (selectedOnly) {
                // 只收 JPEG 源（NEF 自动捎带其同名 JPG 的成片权益由图库显示层处理）
                passSelection.filter { isJpegName(it) }
            } else {
                masterStore.snapshot()
                    .filter { isJpegName(it.origFileName) }
                    .map { it.origFileName }
            }
            if (chosen.isEmpty()) {
                emit(ProbeUiState("Master 里没有可读的 JPEG。先从相机勾几张 .JPG 导出"))
                return@execute
            }
            val t = System.currentTimeMillis()
            chosen.forEach { name ->
                passQueueDao.upsert(
                    com.lumina.pass.PassQueueEntity(name = name, cardId = cardId, queuedAtMs = t),
                )
            }
            logEvents(listOf("pass: queued ${chosen.size} for card=$cardId (selectedOnly=$selectedOnly)"))
            passSelection.clear()
            emitSelection()   // 队列已消化：勾选通道明确清零（合并语义吞不得，见 emitSelection 注释）
            abortRequested = false
            activeCardRun = CardRunUi(
                cardId = cardId, cardName = ent.name, version = ent.version,
                targets = chosen, phase = com.lumina.probe.CardRunPhase.RUNNING,
                currentItem = chosen.first(),
            )
            emitCardRun(activeCardRun)

            // 串行执行（PassExecutor 驱动；逐张回调推进 cardRun 状态）
            val sink = passSink()
            val card = CardEngine.parse(ent.stepsJson) { ent.cardId }
            PassExecutor(
                clientProvider = { com.lumina.llm.OkHttpLlmClient() },
                cfgProvider = { llmConfigDao.active() },
                sink = sink, retryDelayMs = 2000, now = System::currentTimeMillis,
                abortCheck = { abortRequested },
            ).run(chosen, card, cardRunRecorder())

            // 执行完（或取消生效）：先逐张把成功条目的 Work 成片渲染出来
            //（AWAIT_SAVE 弹层/浮标宣称的"成片已暂存为 Work 预览"从此为真；"暂不保存"=成片留 Work 层）
            val finished = activeCardRun
            if (finished != null && finished.phase == com.lumina.probe.CardRunPhase.RUNNING) {
                val rowsByRoot = masterStore.snapshot().associateBy { it.origFileName }
                var workFail = 0
                finished.targets.filter { finished.itemStatus[it] == com.lumina.probe.ItemOutcome.OK }.forEach { name ->
                    val row = rowsByRoot[name]
                    if (row != null && renderWorkFor(row, name, cardName = finished.cardName) == null) workFail++
                }
                if (workFail > 0) logEvents(listOf("work: $workFail items failed to render at await-save"))
                // M4-01：生成式链（卡含 GENERATIVE 步才有意义；未配端点则静默占位+提示）
                // SKILL 全文优先：卡内 GENERATIVE 步的 prompt 若已 ≥800 字（导入的 Codex Skills 即正主规格），用之。
                val cardEnt = cardDao().byId(finished.cardId)
                val skillBody = cardEnt?.let { ent ->
                    runCatching {
                        CardEngine.parse(ent.stepsJson) { ent.cardId }.steps
                            .firstOrNull { it.kind == com.lumina.cards.StepKind.GENERATIVE }?.prompt
                    }.getOrNull()
                }
                runGenerativePass(
                    llmConfigDao.active(),
                    rowsByRoot,
                    finished.targets.filter { finished.itemStatus[it] == com.lumina.probe.ItemOutcome.OK },
                    skillBody = skillBody,
                    skillName = finished.cardName,
                )
                lastGalleryItems = buildGalleryItems()
                activeCardRun = finished.copy(
                    phase = com.lumina.probe.CardRunPhase.AWAIT_SAVE,
                    currentItem = null,
                    cancelRequested = abortRequested,
                )
                emitCardRun(activeCardRun)
                updateCatchupBanner()
            }
        }
    }

    /** 用户点了"取消执行"：在途条目跑完后停在收尾（未处理项留在队列 → 补跑横幅接管）。 */
    fun cancelCardRun() {
        io.execute {
            val cur = activeCardRun ?: return@execute
            if (cur.phase == com.lumina.probe.CardRunPhase.RUNNING) {
                abortRequested = true
                emitCardRun(cur.copy(cancelRequested = true))
                logEvents(listOf("pass: user requested cancel (${cur.doneOk} ok so far)"))
            }
        }
    }

    /** 保存收尾（AWAIT_SAVE 决策）：COPY_WORK=成片留在 Work 层；OVERWRITE=写回 JPEG Master 位置。 */
    fun finalizeCardRun(mode: SaveMode) {
        io.execute {
            val run = activeCardRun ?: return@execute
            if (run.phase != com.lumina.probe.CardRunPhase.AWAIT_SAVE) return@execute
            val okTargets = run.targets.filter { run.itemStatus[it] == com.lumina.probe.ItemOutcome.OK }
            val rowsByRoot = masterStore.snapshot().associateBy { it.origFileName }
            var saved = 0
            var skipped = 0
            for (name in okTargets) {
                val row = rowsByRoot[name]
                if (row == null) { skipped++; continue }
                // 成片在执行完成时已渲染进 Work 层；缺失则补一次渲染（防御兜底）
                val workRel = workExist(row, name) ?: renderWorkFor(row, name)
                if (workRel == null) { logEvents(listOf("save: work render fail, skip $name")); skipped++; continue }
                when (mode) {
                    SaveMode.COPY_WORK -> saved++ // 成片在 Work 层（_w.jpg），与 Master 并存
                    SaveMode.OVERWRITE -> {
                        // 覆盖只发生在 JPEG Master（入队层已滤；这里再按 ADR-0002 修订段守门）
                        if (!isJpegName(name)) { skipped++; continue }
                        val workFile = File(appContext.filesDir, workRel)
                        val master = masterStore.fileOf(row.relPath)
                        if (!workFile.exists()) { skipped++; continue }
                        runCatching {
                            workFile.copyTo(master, overwrite = true)
                            masterStore.updateSizeBytes(row.relPath, master.length())
                        }.onSuccess {
                            saved++
                            logEvents(listOf("save: overwrote ${row.relPath} (${master.length()}B)"))
                        }.onFailure {
                            skipped++
                            logEvents(listOf("save fail: ${it.message}"))
                        }
                    }
                }
            }
            val noun = if (mode == SaveMode.OVERWRITE) "已覆盖 Master" else "已另存 Work"
            val summary = "$noun × $saved" + if (skipped > 0) " · 跳过 $skipped" else ""
            logEvents(listOf("save: mode=$mode × $saved (skipped $skipped)"))
            toastify(summary)
            // 用户指认的缺口：成片要与"看得见"绑定。保存时把 Work 成片一并写入系统相册
            // （MediaStore Pictures/Lumina；无运行时权限需求则为 API29+）——ADR-0004 的库内真相不变，
            // 系统相册是"导出的影子"，M3 正式导出会扩展这一通道。
            var exported = 0
            if (saved > 0) {
                for (name in okTargets) {
                    if (run.itemStatus[name] != com.lumina.probe.ItemOutcome.OK) continue
                    val row = rowsByRoot[name] ?: continue
                    val rel = workExist(row, name) ?: continue
                    val f = File(appContext.filesDir, rel)
                    if (exportToSystemGallery(f, "${name.substringBeforeLast('.')}_w.jpg")) exported++
                }
                logEvents(listOf("save: exported to system gallery × $exported"))
            }
            val summaryFinal = summary + if (exported > 0) " · 相册 Pictures/Lumina ×$exported" else ""
            activeCardRun = null
            lastGalleryItems = buildGalleryItems()
            lastGalleryStatus = summaryFinal
            val next = lastEmitted.get().copy(
                cardRun = null,
                galleryItems = lastGalleryItems,
                galleryStatus = summaryFinal,
                passStatus = summaryFinal,
            )
            lastEmitted.set(next)
            mainThread.post { onState(next) }
        }
    }

    /** Work 成片入库到系统相册（MediaStore，Pictures/Lumina）。
     *  API29+ 无需任何运行时权限（应用创建自己的图像）；更低版本静默跳过（私有库照旧可用）。
     *  语义 = 导出影子：删 App 不会删相册里的这张，但不承诺反向来。 */
    internal fun exportToSystemGallery(src: File, displayName: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        return runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumina")
            }
            val uri = appContext.contentResolver.insert(
                android.provider.MediaStore.Images.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY,
                ),
                values,
            ) ?: return false
            appContext.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    /** 该 Master 的 Work 成片是否已在盘（相对路径命中即返回）。 */
    private fun workExist(
        row: com.lumina.master.MasterInventoryEntity,
        name: String,
    ): String? {
        val rel = com.lumina.work.WorkRenderer.workRelPath(row.dateKey, name)
        return if (File(appContext.filesDir, rel).exists()) rel else null
    }

    /** 不保存（成片留在 Work 层继续预览），收起弹层。 */
    fun dismissCardRun() {
        io.execute {
            activeCardRun = null
            emitCardRun(null)
        }
    }

    /** 卡片执行共用回调：逐张把成败写进 cardRun 状态（UI 弹层/浮标同源）。 */
    private fun cardRunRecorder() = object : com.lumina.pass.PassCallback {
        override fun onStart(total: Int) {}
        override fun onItemDone(name: String, ok: Boolean, note: String) {
            val cur = activeCardRun ?: return
            val st = cur.itemStatus + (name to (if (ok) com.lumina.probe.ItemOutcome.OK else com.lumina.probe.ItemOutcome.FAIL))
            val nextIdx = cur.targets.indexOfFirst { !st.containsKey(it) }
            val doneOk = st.values.count { it == com.lumina.probe.ItemOutcome.OK }
            val doneFail = st.size - doneOk
            passStatus = "处理中：$doneOk 成功 / $doneFail 失败"
            activeCardRun = cur.copy(
                itemStatus = st, doneOk = doneOk, doneFail = doneFail,
                currentItem = cur.targets.getOrNull(nextIdx),
            )
            emitCardRun(activeCardRun)
        }
        override fun onAllDone(s: Int, f: Int) {}
    }

    /** Pass 落库外套（与补跑路径同一套：结果/台账/队列删除）。 */
    private fun passSink() = object : com.lumina.pass.PassSink {
        override fun jpegFor(name: String): ByteArray? = jpegBytesFor(name)
        override fun saveResult(name: String, cardId: String, kind: String, text: String, model: String, createdAtMs: Long) {
            resultsDao.insert(
                com.lumina.pass.SmartResultEntity(
                    0, name, cardId, kind, text, model, createdAtMs,
                ),
            )
        }
        override fun saveLedger(name: String, model: String, promptChars: Int, withImage: Boolean, outcome: String, promptTokens: Int?, completionTokens: Int?) {
            ledgerDaoX.insert(
                com.lumina.llm.LedgerEntity(
                    0, name, model, promptChars, withImage, outcome, promptTokens, completionTokens,
                    System.currentTimeMillis(),
                ),
            )
        }
        override fun removeQueued(name: String) {
            passQueueDao.deleteByName(name)
        }
    }

    /** 横幅 [补跑]：串行执行队列 × 各卡 */
    fun runCatchUp() {
        io.execute { runCatchUpInternal() }
    }

    private fun runCatchUpInternal() {
        val rows = passQueueDao.all()
        if (rows.isEmpty()) {
            emit(ProbeUiState("处理队列为空", cameraItems = cameraItems, checkedNames = checkedNames))
            updateCatchupBanner()
            return
        }
        if (!(llmConfigDao.active()?.cloudEnabled ?: false)) {
            emit(ProbeUiState("云处理总开关关闭中：队列挂起"))
            return
        }
        if (!networkAvailable()) {
            emit(ProbeUiState("当前无网络：队列 ${rows.size} 项保留，回网自动补跑"))
            return
        }
        val sink = passSink()
        val cardIdGroups = rows.groupBy { it.cardId }
        var totalOK = 0
        var totalFail = 0
        for ((cardId, group) in cardIdGroups) {
            val ent = cardDao().byId(cardId) ?: continue
            val card = CardEngine.parse(ent.stepsJson) { ent.cardId }
            var roundOK = 0
            var roundFail = 0
            val recorder = object : com.lumina.pass.PassCallback {
                override fun onStart(total: Int) {}
                override fun onItemDone(name: String, ok: Boolean, note: String) {
                    if (ok) roundOK++ else roundFail++
                    // 状态行式发射：不覆盖 connectionText（滤镜/图库各自保有标题）
                    emit(lastEmitted.get().copy(passStatus = "处理中：$roundOK 成功 / $roundFail 失败"))
                }
                override fun onAllDone(s: Int, f: Int) {}
            }
            PassExecutor(
                clientProvider = { com.lumina.llm.OkHttpLlmClient() },
                cfgProvider = { llmConfigDao.active() },
                sink = sink, retryDelayMs = 2000, now = System::currentTimeMillis,
            ).run(group.map { it.name }, card, recorder)
            totalOK += roundOK
            totalFail += roundFail
        }
        lastIngestStatus = "Pass 完成：成功 $totalOK · 失败 $totalFail"
        updateCatchupBanner()
        // 结果并入库库快照（保留式：不改 connectionText，不冲掉任何 Tab 内容）
        lastGalleryItems = buildGalleryItems()
        emit(lastEmitted.get().copy(
            galleryItems = lastGalleryItems,
            ingestStatus = lastIngestStatus,
        ))
    }

    /** Master 行按名字找 JPEG 字节（长边 1024 重编码；仅设备层） */
    private fun jpegBytesFor(name: String): ByteArray? {
        val rows = masterStore.snapshot()
        val direct = rows.firstOrNull { it.origFileName.equals(name, true) }
            ?.takeIf { it.origFileName.uppercase().endsWith(".JPG") || it.origFileName.uppercase().endsWith(".JPEG") }
        val row = direct ?: rows.firstOrNull { e ->
            val base = name.substringBeforeLast('.')
            e.origFileName.substringBeforeLast('.').equals(base, true) &&
                (e.origFileName.uppercase().endsWith(".JPG") || e.origFileName.uppercase().endsWith(".JPEG"))
        } ?: return null
        val file = masterStore.fileOf(row.relPath)
        if (!file.exists()) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1024 && bounds.outHeight / (sample * 2) >= 1024) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, sample) }
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    /** 刷新图库快照（附智能结果行；不覆盖连接文本——任何 Tab 都不会被顶掉） */
    private fun publishGalleryWithResults() {
        lastGalleryItems = buildGalleryItems()
        emit(lastEmitted.get().copy(
            galleryItems = lastGalleryItems,
            galleryStatus = if (lastGalleryItems.any { it.smartCount > 0 }) "已有智能结果 ✓" else lastGalleryStatus,
        ))
    }

    // ---------- 图库（工单 04） ----------

    /** 智能结果全文流（配方执行后的所有 Smart Result） */
    fun loadSmartFeed() {
        io.execute {
            val rec = resultsDao.recent()
            val lines = rec.map { r ->
                SmartFeedItem(
                    name = r.name,
                    kind = r.kind,
                    text = r.text,
                    model = r.model,
                    createdAtMs = r.createdAtMs,
                )
            }
            emit(ProbeUiState(
                "智能结果 · ${lines.size} 条",
                smartFeed = lines,
            ))
        }
    }

    private fun buildGalleryItems(): List<GalleryItem> {
        val DATE_FMT_L = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        val rows = masterStore.snapshot()
        val recentResults = resultsDao.recent()
        val resultsByRow = HashMap<String, List<com.lumina.pass.SmartResultEntity>>()
        for (r in recentResults) {
            resultsByRow[r.name] = (resultsByRow[r.name] ?: emptyList()) + r
        }
        return rows.map {
            val mine = resultsByRow[it.origFileName] ?: emptyList<com.lumina.pass.SmartResultEntity>()
            val fallbackRel = if (it.origFileName.uppercase().endsWith(".NEF")) {
                val candidate = it.relPath.replaceAfterLast('.', "JPG")
                java.io.File(appContext.filesDir, candidate).takeIf { f -> f.exists() }?.let { _ -> candidate }
            } else null
            GalleryItem(
                relPath = it.relPath, name = it.origFileName,
                dateKey = it.dateKey, sizeBytes = it.sizeBytes,
                fallbackRelPath = fallbackRel,
                smartCount = mine.size,
                smartTexts = mine.map { r -> "${r.kind}：${r.text}" },
                // M4-01：Work 层双约定——_g.jpg（生成式真身）优先于 _w.jpg（LUT 对照）
                workRelPath = (appContext.filesDir.walkTopDown()
                    .firstOrNull { f -> f.isFile && f.name == "${it.origFileName.substringBeforeLast('.')}_g.jpg" }
                    ?: appContext.filesDir.walkTopDown()
                        .firstOrNull { f -> f.isFile && f.name == "${it.origFileName.substringBeforeLast('.')}_w.jpg" })
                    ?.let { f -> f.relativeTo(appContext.filesDir).invariantSeparatorsPath },
            )
        }
    }

    /** 打开图库 Tab 时调用：pruneAbsent + 收养孤儿 + 快照上屏 */
    fun loadGallery() {
        io.execute {
            if (galleryLoading) return@execute  // 防重入
            galleryLoading = true
            // 先发"加载中"快照（保留式合并：列表不闪空，只加个状态）
            emit(ProbeUiState("图库对账中…", galleryLoading = true, engineReady = engine != null))
            val pruned = masterStore.pruneAbsent()
            var adopted = 0
            var repaired = 0
            // 日期修复迁移（历史数据）：double-multiply 造成的 "+NNNNNN-MM-DD" 目录
            // → 真实毫秒 = 错误毫秒 / 1000 → 文件搬入真正日期目录 + Room 行修正
            masterStore.repairsForDateDoubleBind().forEach { (oldRel, newRel, newDateKey) ->
                runCatching {
                    val from = masterStore.fileOf(oldRel)
                    val to = masterStore.fileOf(newRel)
                    to.parentFile?.mkdirs()
                    if (from.renameTo(to) || (from.copyTo(to, overwrite = true).exists() && from.delete())) {
                        masterStore.repointDate(oldRel, newRel, newDateKey)
                        repaired++
                    }
                }
            }
            if (repaired > 0) logEvents(listOf("gallery repair: $repaired entries re-dated"))
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
                    if (masterStore.adoptExistingDiskFile(rel)) adopted++
                }
            lastGalleryItems = buildGalleryItems()
            lastGalleryStatus = if (pruned > 0 || adopted > 0)
                "对账：清理失效 $pruned · 收养新 $adopted" else ""
            galleryLoading = false
            updateCatchupBanner()
            // 发图库专用画布（保留式合并补全其它 Tab 字段）
            emit(ProbeUiState(
                "Master 图库 · ${lastGalleryItems.size} 个对象 · ${humanBytes(lastGalleryItems.sumOf { it.sizeBytes })}",
                galleryItems = lastGalleryItems,
                galleryStatus = lastGalleryStatus,
                cameraItems = cameraItems,
                checkedNames = checkedNames,
                engineReady = engine != null,
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

    // ---------- 卡库（M2 工单 03） ----------

    private var lastCards: List<CardUi> = emptyList()
    private var lastCardStatus: String = ""

    fun loadCards() {
        io.execute {
            if (cardsLoading) return@execute  // 防重入
            cardsLoading = true
            emit(ProbeUiState("配方库盘点中…", cardsLoading = true))
            cardDao().let { dao ->
                // M3 起点：内置卡播种升级为"缺哪补哪"——按名字对账，新版本内置卡也能补进旧设备库
                val existingNames = dao.all().map { it.name }.toSet()
                val missing = CardEngine.builtIns().filter { it.name !in existingNames }
                missing.forEach { card ->
                    dao.upsert(
                        CardEntity(
                            cardId = card.cardId, name = card.name, version = card.version,
                            stepsJson = CardEngine.toJson(card), imported = false,
                            createdAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
                if (missing.isNotEmpty()) {
                    logEvents(listOf("cards: seeded ${missing.size} missing built-ins (${missing.joinToString { it.name }})"))
                }
            }
            lastCards = loadCardRows()
            cardsLoading = false
            publishCards(lastCards, lastCardStatus)
        }
    }

    /** 卡片 UI 数据发布（合并式，相机/图库数据不冲掉） */
    private fun publishCards(cards: List<CardUi>, cardStatus: String) {
        // 滤镜确认弹层需要当页知道云开关状态（每次 loadCards 都新鲜读取——M3-02 闸门前置）
        val cloudEnabled = llmConfigDao.active()?.cloudEnabled ?: false
        lastCards = cards
        lastCardStatus = cardStatus
        emit(
            ProbeUiState(
                connectionText = "配方库 · ${cards.size} 张卡",
                cards = cards,
                cardStatus = cardStatus,
                cloudEnabled = cloudEnabled,
            ),
        )
    }

    /** SAF 导入：uri JSON → 校验解析 → 入库（cardId 撞库=替换，导入语义为"更新配方"） */
    fun importCard(uri: android.net.Uri) {
        io.execute {
            try {
                val json = appContext.contentResolver.openInputStream(uri)
                    ?.readBytes()?.toString(Charsets.UTF_8)
                    ?: throw SkillCardException("读不到文件内容")
                val card = CardEngine.parse(json) { CardEngine.newId() }
                cardDao().upsert(
                    CardEntity(
                        cardId = card.cardId, name = card.name, version = card.version,
                        stepsJson = CardEngine.toJson(card), imported = true,
                        createdAtMs = System.currentTimeMillis(),
                    ),
                )
                logEvents(listOf("cards: imported \"${card.name}\" (${card.steps.size} steps)"))
                publishCards(loadCardRows(), cardStatus = "已导入：${card.name}")
            } catch (e: SkillCardException) {
                publishCards(loadCardRows(), cardStatus = "导入失败：${e.reason}")
            } catch (e: Exception) {
                publishCards(loadCardRows(), cardStatus = "导入失败：${e.message}")
            }
        }
    }

    /** SAF 导出：cardId → 规整 JSON 写 uri。 */
    fun exportCardTo(uri: android.net.Uri, cardId: String) {
        io.execute {
            try {
                val ent = cardDao().byId(cardId) ?: throw SkillCardException("卡不存在")
                val card = CardEngine.parse(ent.stepsJson) { ent.cardId }
                appContext.contentResolver.openOutputStream(uri, "wt")
                    ?.write(CardEngine.toJson(card).toByteArray(Charsets.UTF_8))
                    ?: throw SkillCardException("输出流打开失败")
                logEvents(listOf("cards: exported \"${card.name}\""))
                publishCards(loadCardRows(), cardStatus = "已导出：${card.name}")
            } catch (e: Exception) {
                publishCards(loadCardRows(), cardStatus = "导出失败：${e.message}")
            }
        }
    }

    private fun cardDao() = db.cardDao()

    private fun loadCardRows(): List<CardUi> = cardDao().all().map {
        val steps = CardEngine.parse(it.stepsJson) { CardEngine.newId() }.steps
        CardUi(it.cardId, it.name, it.version, steps.size, it.imported, steps.map { s -> stepLabelZh(s.kind) })
    }

    /** 步骤类型 → 中文链路名（确认弹层的执行顺序展示）。 */
    internal fun stepLabelZh(kind: com.lumina.cards.StepKind): String = when (kind) {
        com.lumina.cards.StepKind.ANNOTATE -> "标注"
        com.lumina.cards.StepKind.CAPTION -> "文案"
        com.lumina.cards.StepKind.FILTER_SUGGEST -> "滤镜建议"
        com.lumina.cards.StepKind.GENERATIVE -> "生成"
    }

    // ---------- 设置页（M2 工单 01） ----------

    /** 连接测试：仅文本 ping 一次（记忆在 Ledger objectName="_test"） */
    fun testLlmConnection() {
        io.execute {
            val cfg = llmConfigDao.active()
            if (cfg == null) {
                emit(ProbeUiState("还没有保存的 LLM 配置：先填三元组保存", cameraItems = cameraItems))
                return@execute
            }
            if (!networkAvailable()) { emit(ProbeUiState("当前无网络")); return@execute }
            emit(ProbeUiState("LLM 连接测试中…"))
            val outcome = com.lumina.llm.OkHttpLlmClient().complete(
                cfg, "回复一个字：好", null,
            )
            val msg: String
            when (outcome) {
                is com.lumina.llm.LlmOutcome.Text -> {
                    msg = "✓ LLM 可用（模型 ${cfg.model}）：[回复=${outcome.text.take(12)}…]"
                    ledgerDaoX.insert(
                        com.lumina.llm.LedgerEntity(
                            0, "_test", cfg.model, 7, false, "OK",
                            outcome.promptTokens, outcome.completionTokens,
                            System.currentTimeMillis(),
                        ),
                    )
                }
                is com.lumina.llm.LlmOutcome.RateLimited ->
                    msg = "✗ 限流（429）——等 ${outcome.retryAfterSec ?: 60} 秒再试"
                is com.lumina.llm.LlmOutcome.Http ->
                    msg = "✗ HTTP ${outcome.code}（key/域名/模型名检查：${outcome.snippet.take(60)}）"
                is com.lumina.llm.LlmOutcome.NetworkTimeout ->
                    msg = "✗ 网络不可达：${outcome.reason}"
            }
            val stale = lastEmitted.get()
            emit(stale.copy(connectionText = "LLM 测试：$msg", testResult = msg, llmConfig = stale.llmConfig))
        }
    }

    fun loadSettings() {
        io.execute {
            val cfg = llmConfigDao.active()
            lastLedgerLines = ledgerDaoX.recent().take(12).map { e ->
                "${dateOf(e.createdAtMs)} · ${e.objectName.take(18)} · ${e.model} · ${e.outcome}" +
                    (e.promptTokens?.let { " · p=$it" } ?: "")
            }
            emit(
                ProbeUiState(
                    "LLM 设置",
                    llmConfig = cfg?.let {
                        LlmUiConfig(it.baseUrl, it.apiKey, it.model, it.templateName)
                    },
                    cloudEnabled = cfg?.cloudEnabled ?: false,
                    cameraItems = cameraItems,
                    checkedNames = checkedNames,
                    engineReady = engine != null,
                    galleryItems = lastGalleryItems,
                    ledgerRecent = lastLedgerLines,
                    catchupBanner = catchupBanner,
                    webdav = webDavConfigDao.active()?.let { WebDavUi(it.baseUrl, it.username) },
                    imagegen = imageGenConfigDao.active()?.let {
                        ImageGenUi(it.baseUrl, it.model, it.size)
                    },
                ),
            )
        }
    }

    /** WebDAV 配置可 publish 的小发射（新鲜读 + 主要字段保底合并发生在这之后的 emit） */
    private fun publishSettingsWebDav(status: String) {
        webDavConfigDao.active()?.let { cfg ->
            emit(lastEmitted.get().copy(
                webdav = WebDavUi(cfg.baseUrl, cfg.username),
                connectionText = status,
                testResult = status,
                ledgerRecent = lastLedgerLines,
            ))
        } ?: run { emit(ProbeUiState(status)) }
    }

    private fun dateOf(ms: Long): String =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(ms))

    fun saveLlmConfig(baseUrl: String, apiKey: String, model: String, templateName: String, cloudEnabled: Boolean) {
        io.execute {
            llmConfigDao.upsert(
                com.lumina.llm.LlmConfigEntity(
                    id = 1,
                    baseUrl = baseUrl.trim().trimEnd('/'),
                    apiKey = apiKey.trim(),
                    model = model.trim(),
                    templateName = templateName,
                    cloudEnabled = cloudEnabled,
                ),
            )
            logEvents(listOf("llm config saved: baseUrl=${baseUrl.trim()} model=${model.trim()} cloud=$cloudEnabled"))
            toastify("已保存 LLM 配置" +
                (com.lumina.llm.LlmTemplates.presets.firstOrNull { it.name == templateName }?.image?.let { " + 生图端点（${it.model}）" } ?: ""))
            // M4-01：平台模板自带生图端点时，一键把生图配置也并上（同 key 两用，size 固定 3:5）
            com.lumina.llm.LlmTemplates.presets.firstOrNull { it.name == templateName }?.image?.let { im ->
                imageGenConfigDao.upsert(
                    com.lumina.master.ImageGenConfigEntity(
                        id = 1,
                        baseUrl = im.baseUrl,
                        apiKey = apiKey.trim(),
                        model = im.model,
                        size = im.size,
                        savedAtMs = System.currentTimeMillis(),
                    ),
                )
                logEvents(listOf("imagegen: template-merged (${im.model} · ${im.size})"))
            }
            loadSettings()
        }
    }


    fun setCloudEnabled(enabled: Boolean) {
        io.execute {
            val cfg = llmConfigDao.active()
            if (cfg == null) {
                emit(ProbeUiState("先填 LLM 配置再开云端处理", cameraItems = cameraItems, checkedNames = checkedNames))
                return@execute
            }
            llmConfigDao.upsert(cfg.copy(cloudEnabled = enabled))
            toastify(if (enabled) "云处理已开启" else "云处理已暂停")
            logEvents(listOf("cloud processing ${if (enabled) "enabled" else "suspended"}"))
            loadSettings()
        }
    }

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
            productNamePrefix = device.productName ?: "MTP 设备"
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
                    // 流式：每读到一个对象就上屏一次（用户"不等全部加载"的要求）
                    if (obj.name.uppercase().endsWith(".JPG") || obj.name.uppercase().endsWith(".JPEG")) {
                        generateWorkQuiet(obj)
                    }
                    publishListing(
                        title = "$productNamePrefix · MTP 读取中（${files.size + folders.size}/${handles.size + prevCount}）",
                        summary = CatalogSummary(files.toList(), folders.toList()),
                        logTail = lastLogTail,
                    )
                }
            }
            val summary = CatalogSummary(files, folders)
            infoExecutor.shutdown()
            val fetched = files.size + folders.size
            if (fetched == 0) {
                logEvents(listOf("MTP enumeration empty (camera silent this round)"))
                emit(
                    ProbeUiState(
                        "相机静默（0 个新对象）：已列出的 ${cameraItems.size} 张保持可用。可重试或直接导出",
                        cameraItems = cameraItems,
                        checkedNames = checkedNames,
                        engineReady = true,
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

    /** MTP 日期：framework 已是 epoch 毫秒（捕获优先，回退修改，0=无）。 */
    private fun mtpMillis(info: android.mtp.MtpObjectInfo): Long? {
        val ms = info.dateCreated.takeIf { it != 0L } ?: info.dateModified.takeIf { it != 0L }
        return ms.takeIf { it != 0L }
    }

    /** Work 静默预生成：流式枚举期间调用·（转发新版 generateWorkFor）。 */
    private fun generateWorkQuiet(obj: com.lumina.ptp.CameraObject) {
        generateWorkFor(obj.name)
    }

    /** 由 relPath 找真实文件（兼容历史坏目录） */
    private fun fileOfRowOrMaster(relPath: String): File? {
        val f = File(appContext.filesDir, relPath)
        return f.takeIf { f.exists() } ?: runCatching {
            // 找坏目录同名搬家文件
            File(appContext.filesDir, "Master").walkTopDown()
                .firstOrNull { it.name == File(relPath).name }
        }.getOrNull()
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
        logEvents(listOf("ui emit: cameraItems=${cameraItems.size} checked=${checkedNames.size} engine=${engine != null} progress=${progressTotal > 0}"))
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
        // 经由统一 emit（合并 + 台账登记）——否则相机列表进不了 merge 台账，
        // 一条文本类 emit 就会把 cameraItems 清零（07 号 bug 实锤根因）
        emit(
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
                cards = lastCards,
                cardStatus = lastCardStatus,
                galleryLoading = galleryLoading,
                cardsLoading = cardsLoading,
                catchupBanner = catchupBanner,
                passStatus = passStatus,
                passSelectedNames = passSelection.toSet(),
            ),
        )
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
        emitReplace(ProbeUiState("未连接 · 请插入相机"))
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

    private fun toastify(msg: String) {
        mainThread.post {
            android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun emit(state: ProbeUiState) {
        // 保留式合并：文本/状态类发射不把相机页/图库/勾选/卡库数据冲掉——
        // 语义已提取为 StateMerge 纯函数（StateMergeTest 锁语义，M1-07 三例之后立的护栏）
        val merged = StateMerge.merge(lastEmitted.get(), state)
        lastEmitted.set(merged)
        mainThread.post { onState(merged) }
    }

    /** 卡片执行状态专用发射：不走 emit 合并语义（本轮明确"清空/翻转"要生效）。 */
    private fun emitCardRun(run: CardRunUi?) {
        val next = lastEmitted.get().copy(cardRun = run)
        lastEmitted.set(next)
        mainThread.post { onState(next) }
    }

    /** 图库多选专用发射：空集=明确清空，必须直接翻转（合并语义吞不得）。 */
    private fun emitSelection() {
        val next = lastEmitted.get().copy(passSelectedNames = passSelection.toSet())
        lastEmitted.set(next)
        mainThread.post { onState(next) }
    }

    /** WebDAV 备份专用发射（语义同 emitCardRun/emitSelection：清空/翻转合并吞不得）。 */
    private fun emitBackup(run: BackupUi?) {
        val next = lastEmitted.get().copy(backup = run)
        lastEmitted.set(next)
        mainThread.post { onState(next) }
    }

    // ---------- WebDAV Master 备份（M3-04） ----------

    fun saveWebDavConfig(baseUrl: String, username: String, password: String) {
        io.execute {
            if (baseUrl.isBlank() || username.isBlank() || password.isBlank()) {
                emit(ProbeUiState("WebDAV 配置三项不能留空"))
                return@execute
            }
            val cfg = com.lumina.master.WebDavConfigEntity(
                id = 1, baseUrl = baseUrl.trim(), username = username.trim(),
                password = password, savedAtMs = System.currentTimeMillis(),
            )
            webDavConfigDao.upsert(cfg)
            logEvents(listOf("dav: config saved (${cfg.baseUrl})"))
            publishSettingsWebDav("WebDAV 配置已保存 ✓")
        }
    }

    fun testWebDavConnection() {
        io.execute {
            val cfg = webDavConfigDao.active()
            if (cfg == null) { emit(ProbeUiState("先保存 WebDAV 配置")); return@execute }
            val ok = com.lumina.dav.OkHttpWebDavClient(cfg.baseUrl, cfg.username, cfg.password).propfind("/")
            emit(ProbeUiState(
                if (ok != null) "WebDAV 连接 ok（根目录可见 ${ok.files.size} 项）"
                else "连接失败：地址/账号/密码或网络",
            ))
        }
    }

    fun startWebDavBackup() {
        io.execute {
            val cfg = webDavConfigDao.active()
            if (cfg == null) { emit(ProbeUiState("先保存 WebDAV 配置")); return@execute }
            if (!networkAvailable()) { emit(ProbeUiState("当前无网络：备份挂起，回网再试")); return@execute }
            val masters = masterStore.snapshot()
                .filter { isJpegName(it.origFileName) || it.origFileName.uppercase().endsWith(".NEF") }
            if (masters.isEmpty()) { emit(ProbeUiState("Master 库是空的：无内容可备份")); return@execute }
            val client = com.lumina.dav.OkHttpWebDavClient(cfg.baseUrl, cfg.username, cfg.password)
            val dirBase = "/Lumina"
            val dirMaster = "$dirBase/Master"
            emitBackup(BackupUi(phase = BackupPhase.RUNNING, total = masters.size))
            // 目录链（405=已存在不算错，DavClient 归一为 Ok）
            client.mkcol(dirBase); client.mkcol(dirMaster)
            // 幂等计划：远端名单 → 需要 PUT 的
            val listing = client.propfind(dirMaster)
            val plan = com.lumina.dav.BackupPlanner.plan(
                masters.associate { it.origFileName to it.sizeBytes }, listing,
            )
            logEvents(listOf(
                "dav: plan total=${masters.size} verified=${plan.verified.size} " +
                    "mismatch=${plan.sizeMismatches.size} toUpload=${plan.toUpload.size}",
            ))
            var done = plan.verified.size
            var fail = 0
            for (name in plan.toUpload) {
                val row = masters.firstOrNull { it.origFileName == name } ?: continue
                emitBackup(BackupUi(
                    phase = BackupPhase.RUNNING, total = masters.size,
                    done = done, fail = fail, currentItem = name,
                ))
                val dateDir = "$dirMaster/${row.dateKey}"
                client.mkcol(dateDir)
                val bytes = runCatching {
                    masterStore.fileOf(row.relPath).readBytes()
                }.getOrNull()
                if (bytes == null) { fail++; continue }
                val remotePath = "$dateDir/${row.origFileName}"
                when (val o = client.put(remotePath, bytes)) {
                    is com.lumina.dav.DavOutcome.Ok -> { done++; logEvents(listOf("dav: put ${row.relPath} ${bytes.size}B ok(${o.code})")) }
                    else -> {
                        fail++
                        logEvents(listOf("dav: put $remotePath fail=${o}"))
                        emitBackup(BackupUi(
                            phase = BackupPhase.RUNNING, total = masters.size,
                            done = done, fail = fail, currentItem = name,
                        ))
                    }
                }
            }
            // 核验：重新 PROPFIND，逐项存在且字节相等才算 deletable（Q5 保守语义）
            val listing2 = client.propfind(dirMaster)
            val plan2 = com.lumina.dav.BackupPlanner.plan(
                masters.associate { it.origFileName to it.sizeBytes }, listing2,
            )
            val summary = if (fail > 0) "备份完成：出错 $fail（见日志）" else "备份完成 ✓"
            if (fail > 0) {
                emitBackup(null)
                updateCatchupBanner()
                lastIngestStatus = summary
                toastify("$summary · 失败文件看日志")
                emit(lastEmitted.get().copy(ingestStatus = summary))
                return@execute
            }
            toastify("备份完成：成功 $done · 可清出 ${plan2.verified.size}")
            emitBackup(BackupUi(
                phase = BackupPhase.VERIFIED,
                total = masters.size, done = done, fail = 0,
                deletableNames = plan2.verified,
                summary = summary,
            ))
            logEvents(listOf("dav: verified ${plan2.verified.size} deletable (awaiting user)"))
        }
    }

    /** M4-01：生成式链（在保存收尾弹层前跑）——对 OK 条目：
     *  ① 看图+SKILL 工作流编 prompt（chat vision，一次调用）② glm-image 出图 ③ 存 Work `_g.jpg`。
     *  SKILL 全文优先（导入的 Codex Skills 是正主规格）；未导入时退蒸馏版。
     *  未配端点 = 保持 M2 占位语义，提示语落 passStatus（设置→图像生成端点）。 */
    private fun runGenerativePass(
        chatCfg: com.lumina.llm.LlmConfigEntity?,
        rowsByRoot: Map<String, com.lumina.master.MasterInventoryEntity>,
        okNames: List<String>,
        skillBody: String? = null,
        skillName: String = "?",
    ) {
        val cfg = imageGenConfigDao.active()
        if (cfg == null) {
            logEvents(listOf("imagegen: 未配端点 → 生成式步保持占位"))
            passStatus = IMAGEGEN_HINT
            emit(lastEmitted.get().copy(passStatus = passStatus))
            return
        }
        if (chatCfg == null) { logEvents(listOf("imagegen: 无 chat 配置 → 编译步跳过")); return }
        // GLM-Image 资源包有请求频次限制（2026-11-09 实锤：429 频次码 1302）
        // 连续失败 → 进入 60s 冷却，避免连点连环 429；给用户明确 Toast。
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < _imagegenCoolUntil) {
            val remain = ((_imagegenCoolUntil - now) / 1000).toInt() + 1
            val msg = "GLM 生图限流冷却中：还剩约 ${remain}s（glm-image 有请求频次上限，稍等再跑）"
            passStatus = msg
            toastify(msg)
            emit(lastEmitted.get().copy(passStatus = passStatus))
            return
        }
        // 规则源优先级：卡内 SKILL 全文（忠实正主） > 蒸馏版（PosterPromptCompiler）
        val compilerPrompt = if (skillBody != null && skillBody.length >= 800) {
            "你在为本台生图管线工作。照片是参考。请严格按用户提供的 SKILL 语言与工作流读照片，" +
                "并以 SKILL 自带的最終生图 prompt 形态产出四段式指令（只输出提示词本身）：\n\n$skillBody"
        } else {
            PosterPromptCompiler.COMPILER_PROMPT
        }
        val rulesLabel = if (skillBody != null && skillBody.length >= 800) "SKILL 全文($skillName)" else "蒸馏版"
        val client = com.lumina.imagegen.ImageGenClient(
            cfg.baseUrl, cfg.apiKey, cfg.model, cfg.size,
            log = { l -> logEvents(listOf(l)); android.util.Log.i("Lumina.UI", l) },
        )
        okNames.forEachIndexed { idx, name ->
            passStatus = "生成中 ${idx + 1}/${okNames.size} —— $name"
            emit(lastEmitted.get().copy(passStatus = passStatus))
            val row = rowsByRoot[name] ?: return@forEachIndexed
            val jpeg = jpegBytesFor(name) ?: return@forEachIndexed
            // ① 读图 + 按工作流编译四段式 prompt（一次 vision 调用）
            val prompt = when (val v = com.lumina.llm.OkHttpLlmClient().complete(
                chatCfg, compilerPrompt, jpeg,
            )) {
                is com.lumina.llm.LlmOutcome.Text -> v.text.trim().take(2400)
                else -> {
                    val why = when (v) {
                        is com.lumina.llm.LlmOutcome.RateLimited -> "限流"
                        is com.lumina.llm.LlmOutcome.Http -> "HTTP ${(v as? com.lumina.llm.LlmOutcome.Http)?.code}"
                        is com.lumina.llm.LlmOutcome.NetworkTimeout -> "网络超时"
                        else -> "未知"
                    }
                    logEvents(listOf("imagegen: 编译步失败（$why）→ 跳过 $name；建议文本空是导致没有效果的根因"))
                    null
                }
            }
            if (prompt.isNullOrBlank()) {
                _imagegenCoolUntil = android.os.SystemClock.elapsedRealtime() + 45_000
                return@forEachIndexed
            }
            // ② 出图 + 转存 Work 层（429 → 20s 后重试 1 次；仍败进冷却）
            var bytes = client.generate(prompt)
            if (bytes == null) {
                Thread.sleep(20_000)
                bytes = client.generate(prompt)
            }
            if (bytes == null) {
                _imagegenCoolUntil = android.os.SystemClock.elapsedRealtime() + 60_000
                toastify("GLM 生图失败/限流：60s 冷却后再试")
                return@forEachIndexed
            }
            // 命名契约与 buildGalleryItems 扫描严格一致：<base>_g.jpg
            // （事故记录：曾用 replaceAfterLast('.', "g.jpg") 把 CHY_3671_w.jpg 变成
            //   CHY_3671_w.g.jpg，扫描找 CHY_3671_g.jpg 永远落空 → "生成的还是原图"）
            val rel = "Work/${row.dateKey}/${name.substringBeforeLast('.')}_g.jpg"
            File(appContext.filesDir, rel).parentFile?.mkdirs()
            File(appContext.filesDir, rel).writeBytes(bytes)
            resultsDao.insert(com.lumina.pass.SmartResultEntity(
                0, name, "_imagegen", "generative",
                "海报已生成（glm-image，key 见 $rel）",
                cfg.model, System.currentTimeMillis(),
            ))
            logEvents(listOf("imagegen: saved $rel (${bytes.size}B)"))
        }
        lastGalleryItems = buildGalleryItems()
    }

    /** M4-01 同款命名：生成式文件识别归 buildGalleryItems 扫描契约（_g.jpg）。 */

    /** 清出手机（ADR-0003）：用户在弹层确认后执行——只删已核验项，文件+盘点行一并对账。 */
    fun confirmWebDavCleanup() {
        io.execute {
            val run = lastEmitted.get().backup ?: return@execute
            if (run.phase != BackupPhase.VERIFIED) return@execute
            val rowsByRoot = masterStore.snapshot().associateBy { it.origFileName }
            var removed = 0
            for (name in run.deletableNames) {
                val row = rowsByRoot[name] ?: continue
                val file = masterStore.fileOf(row.relPath)
                val fileGone = runCatching { file.delete() }.getOrDefault(false) && !file.exists()
                if (fileGone) {
                    masterStore.deleteRow(row.relPath)
                    removed++
                }
            }
            logEvents(listOf("dav: cleanup removed $removed / ${run.deletableNames.size}"))
            lastGalleryItems = buildGalleryItems()
            masterStore.pruneAbsent()
            emitBackup(null)
            val summary = "已清出手机 × $removed（Remainder 见备份 log）"
            lastIngestStatus = summary
            lastGalleryStatus = summary
            val next = lastEmitted.get().copy(
                galleryItems = lastGalleryItems,
                galleryStatus = summary,
                passStatus = summary,
            )
            lastEmitted.set(next)
            mainThread.post { onState(next) }
        }
    }

    fun dismissWebDavCleanup() {
        io.execute { emitBackup(null) }
    }

    // ---------- 图像生成端点（M4-01） ----------

    fun saveImageGenConfig(baseUrl: String, apiKey: String, model: String, size: String) {
        io.execute {
            if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank() || size.isBlank()) {
                emit(ProbeUiState("生图端点四项不能留空"))
                return@execute
            }
            imageGenConfigDao.upsert(
                com.lumina.master.ImageGenConfigEntity(
                    id = 1, baseUrl = baseUrl.trim().trimEnd('/'),
                    apiKey = apiKey.trim(), model = model.trim(), size = size.trim(),
                    savedAtMs = System.currentTimeMillis(),
                ),
            )
            logEvents(listOf("imagegen: config saved (${model})"))
            emit(ProbeUiState("生图端点已保存 ✓"))
        }
    }

    /** M4-01 新增：从系统相册导入图片为 Master（Photo Picker/SAF 皆可）。
     *  名称取 DISPLAY_NAME；非 JPG 后缀归一为 .jpg（BitmapFactory 按内容解码）；
     *  同名冲突自动加时间戳；入库后立即可跑滤镜卡/生成链。 */
    fun importFromAlbum(uri: android.net.Uri) {
        io.execute {
            runCatching {
                val bytes = appContext.contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("读不到图片内容")
                if (bytes.isEmpty()) throw Exception("图片为空")
                val cursor = appContext.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
                )
                val rawName = cursor?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "album"
                cursor?.close()
                var name = (rawName.substringAfterLast('/').ifBlank { "album" })
                    .substringBeforeLast('.') + ".jpg"
                val now = System.currentTimeMillis()
                var rel = com.lumina.master.MasterPaths.relativePath(null, now, name)
                if (masterStore.findByRelPath(rel) != null) {
                    name = name.substringBeforeLast('.') + "_${now % 100000}.jpg"
                    rel = com.lumina.master.MasterPaths.relativePath(null, now, name)
                }
                val f = masterStore.fileOf(rel)
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                when (masterStore.register(null, now, name, bytes.size.toLong())) {
                    is com.lumina.master.MasterStore.RegisterResult.Registered -> {
                        logEvents(listOf("album import: $name (${bytes.size}B) → $rel"))
                        lastGalleryItems = buildGalleryItems()
                        toastify("已导入 Master：$name")
                        emit(lastEmitted.get().copy(
                            galleryItems = lastGalleryItems,
                            galleryStatus = "已导入相册图片：$name",
                        ))
                    }
                    else -> throw Exception("登记失败：$rel")
                }
            }.onFailure { e ->
                logEvents(listOf("album import fail: ${e.message}"))
                toastify("导入失败：${e.message}")
                emit(lastEmitted.get().copy(galleryStatus = "导入失败：${e.message}"))
            }
        }
    }

    /** M4-01：导入 Codex 生图 SKILL(.md) — frontmatter name/标题 → 卡名；全文 → GENERATIVE 步。 */
    fun importSkillCard(uri: android.net.Uri) {
        io.execute {
            try {
                val text = appContext.contentResolver.openInputStream(uri)
                    ?.readBytes()?.toString(Charsets.UTF_8)
                    ?: throw SkillCardException("读不到 SKILL 内容")
                if (text.length < 200) throw SkillCardException("内容太短，不像 SKILL 文档")
                // 卡名：frontmatter name → 首个 # 标题 → 文件名，依次回退
                val frontName = Regex("^name:\\s*(.+)$", RegexOption.MULTILINE)
                    .find(text)?.groupValues?.get(1)?.trim()
                val heading = Regex("^#\\s+(.+)$", RegexOption.MULTILINE)
                    .find(text)?.groupValues?.get(1)?.trim()
                val display = frontName ?: heading ?: "SKILL-导入"
                val card = com.lumina.cards.SkillCard(
                    cardId = com.lumina.cards.CardEngine.newId(),
                    name = display,
                    version = 1,
                    steps = listOf(
                        com.lumina.cards.CardStep(
                            kind = com.lumina.cards.StepKind.GENERATIVE,
                            prompt = text,
                        ),
                    ),
                )
                cardDao().upsert(
                    CardEntity(
                        cardId = card.cardId, name = card.name, version = card.version,
                        stepsJson = CardEngine.toJson(card), imported = true,
                        createdAtMs = System.currentTimeMillis(),
                    ),
                )
                logEvents(listOf("skills: imported \"$display\" (${text.length} chars, imagegen card)"))
                toastify("SKILL 已导入：$display")
                publishCards(loadCardRows(), cardStatus = "已导入 SKILL：$display（生成式卡，生图端点配置后生效）")
            } catch (e: SkillCardException) {
                publishCards(loadCardRows(), cardStatus = "SKILL 导入失败：${e.reason}")
                toastify("SKILL 导入失败：${e.reason}")
            } catch (e: Exception) {
                publishCards(loadCardRows(), cardStatus = "SKILL 导入失败：${e.message}")
                toastify("SKILL 导入失败：${e.message}")
            }
        }
    }

    fun testImageGenConfig() {
        io.execute {
            val cfg = imageGenConfigDao.active()
            if (cfg == null) { emit(ProbeUiState("先保存生图端点配置")); return@execute }
            val bytes = com.lumina.imagegen.ImageGenClient(
                cfg.baseUrl, cfg.apiKey, cfg.model, cfg.size,
                log = { l -> logEvents(listOf(l)); android.util.Log.i("Lumina.UI", l) },
            ).generate("一张安静的山间徒步测试图，奶油纸质，颗粒，留白")
            emit(ProbeUiState(
                if (bytes != null) "生图端点测试 ok（${bytes.size}B）" else "生图端点测试失败（看日志）",
            ))
        }
    }

    /** 全替换（断开等"清场"语义专用）。 */
    private fun emitReplace(state: ProbeUiState) {
        lastEmitted.set(state)
        mainThread.post { onState(state) }
    }

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
    /** LLM 连接测试结果（设置页） */
    val testResult: String = "",
    /** 图库（工单 04）：Room 驱动的 Master 全量快照 */
    val galleryItems: List<GalleryItem> = emptyList(),
    val galleryStatus: String = "",
    /** M2 智能层 */
    val catchupBanner: String? = null,
    val passStatus: String = "",
    /** Ledger 最近行（设置页展示） */
    val ledgerRecent: List<String> = emptyList(),
    /** 智能结果全文流 */
    val smartFeed: List<SmartFeedItem> = emptyList(),
    /** M2 设置页：LLM 三元组 + 云处理开关 */
    val llmConfig: LlmUiConfig? = null,
    val cloudEnabled: Boolean = false,
    /** 配方库（M2 工单 03） */
    val cards: List<CardUi> = emptyList(),
    val cardStatus: String = "",
    /** 页面加载指示（图库对账/滤镜库盘点为秒级 IO，无指示会像"空了"） */
    val galleryLoading: Boolean = false,
    val cardsLoading: Boolean = false,
    /** 图库→滤镜的勾选（M2 UX 流：图库选中 ↘ 滤镜执行） */
    val passSelectedNames: Set<String> = emptySet(),
    /** M3 交互：滤镜包卡片执行的进行中状态（RUNNING → AWAIT_SAVE → 用户收尾后 null） */
    val cardRun: CardRunUi? = null,
    /** M3-04：WebDAV 备份的进行中/待清出状态 */
    val backup: BackupUi? = null,
    /** M3-04：WebDAV 配置展示（设置页表单预填） */
    val webdav: WebDavUi? = null,
    /** M4-01：图像生成端点展示（设置页表单预填） */
    val imagegen: ImageGenUi? = null,
)

/** 设置页 WebDAV 表单数据（password 不回显，只回显 presence）。 */
data class WebDavUi(
    val baseUrl: String,
    val username: String,
)

/** 配方页卡条目。 */
data class CardUi(
    val cardId: String,
    val name: String,
    val version: Int,
    val stepsCount: Int,
    val imported: Boolean,
    /** 步骤链显示名（确认弹层用；顺序即执行顺序） */
    val steps: List<String> = emptyList(),
)

/** 保存方式（M3 收尾）：另存 Work 副本 / 覆盖 Master（仅 JPEG Master 允许覆盖） */
enum class SaveMode { COPY_WORK, OVERWRITE }

/** 滤镜包执行阶段：RUNNING（弹层执行视图） / AWAIT_SAVE（处理完，等用户决定保存方式） */
enum class CardRunPhase { RUNNING, AWAIT_SAVE }

/** 单条执行结果（M3-01 顺手项：淘汰"Map 缺键当第三态"评审记法——键在 = 已处理，键不在 = 未处理） */
enum class ItemOutcome { OK, FAIL }

/** 卡片执行进行中的 UI 数据（emit 全量快照式；phase 推进由 controller 驱动） */
data class CardRunUi(
    val cardId: String,
    val cardName: String,
    val version: Int,
    /** 执行目标（JPEG Master 文件名，执行顺序） */
    val targets: List<String>,
    val phase: CardRunPhase,
    val doneOk: Int = 0,
    val doneFail: Int = 0,
    /** name → 成败（false=失败可重试；未出现的 = 待处理/未处理） */
    val itemStatus: Map<String, ItemOutcome> = emptyMap(),
    /** 当前在跑的文件名（RUNNING 态高亮行） */
    val currentItem: String? = null,
    /** 用户点了"取消执行"：在途条目跑完后停在收尾 */
    val cancelRequested: Boolean = false,
)

/** WebDAV 备份阶段（M3-04）：RUNNING → VERIFIED（核验完，可清出弹层等用户） */

/** WebDAV 备份阶段（M3-04）：RUNNING → VERIFIED（核验完，可清出弹层等用户） */
enum class BackupPhase { RUNNING, VERIFIED }

/** WebDAV 备份（M3-04）进行中的 UI 数据 */
data class BackupUi(
    val phase: BackupPhase,
    val total: Int = 0,
    val done: Int = 0,
    val fail: Int = 0,
    val currentItem: String? = null,
    /** VERIFIED：远端存在且字节一致的可清出清单（删除动作仍逐项人工确认，ADR-0003） */
    val deletableNames: List<String> = emptyList(),
    val summary: String = "",
)

/** 设置页展示的生图端点三元组（password 不回显，只回显 presence）。 */
data class ImageGenUi(
    val baseUrl: String,
    val model: String,
    val size: String,
)

/** 保存弹层提示（未配生图端点时的语义字） */
const val IMAGEGEN_HINT = "未配生图端点：生成式步跳过。设置→图像生成端点 配好后重跑此卡。"

/** 智能结果全文流条目。 */
data class SmartFeedItem(
    val name: String,
    val kind: String,
    val text: String,
    val model: String,
    val createdAtMs: Long,
)

/** 设置页展示的 LLM 三元组（不含开关，开关单列）。 */
data class LlmUiConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val templateName: String,
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
    /** NEF 行的同名 JPEG（在盘）——用它出真缩略图；null = 真占位卡 */
    val fallbackRelPath: String? = null,
    /** 智能结果（M2）：条数 + 全文行 */
    val smartCount: Int = 0,
    val smartTexts: List<String> = emptyList(),
    /** Work 成片（智能滤镜渲染预览） */
    val workRelPath: String? = null,
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
