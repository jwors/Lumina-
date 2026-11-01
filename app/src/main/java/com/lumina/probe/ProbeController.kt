package com.lumina.probe

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import com.lumina.ptp.CatalogSummary
import com.lumina.ptp.ObjectCatalog
import com.lumina.ptp.PtpResponseException
import com.lumina.ptp.PtpSession
import com.lumina.usb.UsbCameraMonitor
import com.lumina.usb.UsbDeviceClassifier
import com.lumina.usb.UsbCameraStateMachine
import com.lumina.usb.UsbTransport
import java.util.concurrent.Executors

/**
 * 探针全链路控制器（工单 05）：连接监视 → 授权 → PTP 会话 → 按日期分组清单。
 * 单线程顺序驱动，UI 状态一律经主线程回调。
 * 人话状态：每种连接异常给出可读解释（相机休眠/拔线/权限/非 PTP 模式）。
 */
class ProbeController(
    context: Context,
    private val onState: (ProbeUiState) -> Unit,
) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainThread = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var session: PtpSession? = null

    init {
        UsbCameraMonitor(context, usbManager) { monitorState, kind, device ->
            io.execute { react(monitorState, kind, device) }
        }
        io.execute { emit(ProbeUiState("未连接 · 请插入相机")) }
    }

    private fun react(
        state: UsbCameraStateMachine.State,
        kind: UsbDeviceClassifier.DeviceKind?,
        device: UsbDevice?,
    ) {
        when {
            // 非 PTP 设备插进来：给相机模式引导（工单验收第 4 条）
            kind == UsbDeviceClassifier.DeviceKind.MTP_SUSPECT ->
                emit(
                    ProbeUiState(
                        "检测到疑似文件设备（MTP）。请在 Z30 菜单把 USB 连接设为 PTP 后重插",
                    ),
                )
            state == UsbCameraStateMachine.State.NOT_CONNECTED -> teardown()
            state == UsbCameraStateMachine.State.PENDING_PERMISSION ->
                emit(
                    ProbeUiState(
                        "相机已插入，等你在系统弹窗点\"允许\"。" +
                            "若没有弹窗：点按相机电源唤醒后再等几秒",
                    ),
                )
            state == UsbCameraStateMachine.State.READY -> {
                emit(ProbeUiState("清单读取中…", busy = true))
                readCatalog(device)
            }
        }
    }

    private fun readCatalog(device: UsbDevice?) {
        if (device == null) {
            emit(ProbeUiState("已授权但设备不可见，请拔下重插")); return
        }
        val transport = try {
            UsbTransport.from(usbManager, device)
        } catch (e: Exception) {
            emit(ProbeUiState("无法打开相机：${e.message}")); return
        }
        val ptp = PtpSession(transport)
        session = ptp
        try {
            val model = ptp.getModel()
            val summary = ptp.listStorage()
                .map { ObjectCatalog(ptp).traverse(it.id) }
                .reduce { acc, next -> CatalogSummary(acc.files + next.files, acc.folders + next.folders) }
            emit(
                ProbeUiState(
                    "$model · 清单就绪",
                    model = model,
                    summary = summary,
                    logTail = ptp.log.snapshot().takeLast(8),
                ),
            )
        } catch (e: Exception) {
            emit(
                ProbeUiState(
                    "读取失败：${humanize(e)}",
                    logTail = ptp.log.snapshot().takeLast(8),
                ),
            )
            runCatching { ptp.close() }
            session = null
        }
    }

    private fun teardown() {
        runCatching { session?.close() }
        session = null
        emit(ProbeUiState("未连接 · 请插入相机"))
    }

    /** 运行期异常 → 人话 */
    private fun humanize(e: Exception): String = when {
        e is PtpResponseException ->
            "相机返回错误 0x%04X（会话可能已失效，重插一次再试）".format(e.code)
        e.message?.contains("USB bulk read failed") == true -> "相机休眠或已拔出，唤醒或重插"
        e.message?.contains("USB bulk write failed") == true -> "相机没响应写入（休眠中？）"
        e.message?.contains("no Still-Image interface") == true -> "相机不是 PTP 模式：菜单切换 USB 连接方式"
        else -> e.message ?: e.toString()
    }

    private fun emit(state: ProbeUiState) = mainThread.post { onState(state) }
}

/** 探针界面的唯一数据包。 */
data class ProbeUiState(
    val connectionText: String,
    val busy: Boolean = false,
    val model: String? = null,
    val summary: CatalogSummary? = null,
    /** 环形日志尾部（从旧到新） */
    val logTail: List<String> = emptyList(),
)
