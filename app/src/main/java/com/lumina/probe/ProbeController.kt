package com.lumina.probe

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors

/**
 * 探针全链路控制器（工单 05）：连接监视 → 授权 → 会话/兜底 → 按日期分组清单。
 * 主路径 = 纯 PTP（interpret为 Still-Image 类）；系统若把相机识别为文件设备（MTP 类），
 * 走 android.mtp.MtpDevice 兜底列清单并把"走了哪条路径"写进屏显日志（spec 决策 4 / story 17）。
 * 设备/协议异常同步落应用私有目录 m0-probe.log（spec 决策 7 / story 20）。
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

    private val monitor = UsbCameraMonitor(appContext, usbManager) { monitorState, kind, device ->
        io.execute { react(monitorState, kind, device) }
    }

    /** 用户在界面上重试授权（工单 04 验收第 2 条的交互入口）。 */
    fun retryPermission() {
        io.execute { monitor.retryPermission() }
    }

    init {
        io.execute { emit(ProbeUiState("未连接 · 请插入相机")) }
    }

    private fun react(
        state: UsbCameraStateMachine.State,
        kind: UsbDeviceClassifier.DeviceKind?,
        device: UsbDevice?,
    ) {
        // 每次相机状态变化都落盘（真机离线排障：USB 口被相机占用时无法连 logcat）
        logEvents(listOf("camera event: state=$state kind=$kind device=${device?.deviceName}"))
        when (state) {
            UsbCameraStateMachine.State.NOT_CONNECTED -> teardown()
            UsbCameraStateMachine.State.PENDING_PERMISSION ->
                emit(
                    ProbeUiState(
                        "相机已插入（kind=$kind），等你在系统弹窗点\"允许\"。" +
                            "若没有弹窗：唤醒相机屏幕后点\"重试授权\"",
                        pendingPermission = true,
                        logTail = emptyList(),
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

    // ---------- 主路径：纯 PTP ----------

    private fun listViaPtp(device: UsbDevice?) {
        if (device == null) {
            emit(ProbeUiState("已授权但设备不可见，请拔下重插")); return
        }
        // 预热空转（真机实证：第一笔 claim 的 bulk 写必卡死，第二笔就通）：
        // 把"-必卡死的第一笔"拿来做空 claim。失败/成功都只是记录，不算在重试次数里。
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
                // 连接三步：transport.open + OpenSession → 型号 → 存储与清单
                ptp.open()
                val model = ptp.getModel()
                val summary = ObjectCatalog.merge(
                    ptp.listStorage().map { ObjectCatalog(ptp).traverse(it.id) },
                )
                emit(
                    ProbeUiState(
                        "$model · 清单就绪",
                        model = model,
                        summary = summary,
                        logTail = ptp.log.snapshot().takeLast(LOG_TAIL),
                    ),
                )
                return
            } catch (e: Exception) {
                // 原始异常 + 协议日志尾巴全量入库（屏上给人话，文件里留诊断数据）
                logEvents(listOf("PTP path fail: ${e.javaClass.simpleName}: ${e.message}") +
                    ptp.log.snapshot().takeLast(6))
                runCatching { ptp.close() }
                session = null
                attempt++
                if (attempt >= 3) {
                    emit(ProbeUiState("读取失败：${humanize(e)}"))
                    // PTP 多轮失败 → 立即转框架 MtpDevice 兜底（story 17；同时交叉验证管道层）
                    listViaMtp(device)
                    return
                }
                logEvents(listOf("retrying PTP session (attempt $attempt)"))
                emit(ProbeUiState("正在重试连接相机…", busy = true))
            }
        }
    }

    // ---------- 兜底：android.mtp.MtpDevice（story 17） ----------

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
            val files = mutableListOf<CameraObject>()
            val folders = mutableListOf<CameraObject>()
            for (storageId in mtp.getStorageIds() ?: IntArray(0)) {
                // MTP parent 语义两家口径（0=根 / -1 或 0xFFFFFFFF=全部）：
                // 先按 (format=0, parent=0) 取；空则穷举 (0/-1)×(0/-1) 的组合取并集
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
                for (handle in handles) {
                    val info = mtp.getObjectInfo(handle) ?: continue
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
            val fetched = files.size + folders.size
            if (fetched == 0) {
                // 相机此刻没在应答（grid 等 storages 空结果）——不冒充成功，给明确的重试指路
                logEvents(listOf("MTP enumeration empty"))
                emit(
                    ProbeUiState(
                        "MTP 兜底：相机此刻没应答（0 个对象）。请重新插拔 OTG 或稍等重试",
                    ),
                )
                return
            }
            val tail = listOf(
                "== MTP 兜底路径（story 17）==",
                "storages=${(mtp.getStorageIds() ?: IntArray(0)).size} objects=$fetched（files=${files.size} folders=${folders.size}）",
            )
            logEvents(tail.map { "MTP path: $it" })
            emit(
                ProbeUiState(
                    "${device.productName ?: "MTP 设备"} · 清单就绪（MTP 兜底）",
                    summary = summary,
                    logTail = tail,
                ),
            )
        } catch (e: Exception) {
            logFailure(listOf("MTP path fail: ${humanize(e)}"))
            emit(ProbeUiState("MTP 兜底也失败：${humanize(e)}"))
        }
    }

    /** MTP 日期是 epoch 秒（捕获优先，回退修改，0=无）。 */
    private fun mtpMillis(info: android.mtp.MtpObjectInfo): Long? {
        val seconds = info.dateCreated.takeIf { it != 0L } ?: info.dateModified.takeIf { it != 0L }
        return seconds?.times(1000L)
    }

    // ---------- 状态与日志 ----------

    private fun teardown() {
        runCatching { session?.close() }
        session = null
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

    /** 失败路径 = 事件日志 + 人话一行。 */
    private fun logFailure(lines: List<String>) = logEvents(lines)

    private fun emit(state: ProbeUiState) = mainThread.post { onState(state) }

    private companion object {
        const val LOG_TAIL = 8
    }
}

/** 探针界面的唯一数据包。 */
data class ProbeUiState(
    val connectionText: String,
    val busy: Boolean = false,
    val model: String? = null,
    val summary: CatalogSummary? = null,
    /** 环形日志尾部（从旧到新） */
    val logTail: List<String> = emptyList(),
    /** 待授权态：界面给出"重试授权"的交互入口 */
    val pendingPermission: Boolean = false,
)
