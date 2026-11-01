package com.lumina.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.lumina.transport.Transport
import java.io.IOException
import java.nio.ByteBuffer

/** USB 层可读失败（reason ∈ claim/attach/open/read/write），供 UI 人话化，不走文案匹配。 */
class UsbTransportException(val reason: String, detailMessage: String) : IOException(detailMessage)

/**
 * Transport 的真实 USB 实现（工单 04）。
 * 协议层（ptp 包）只知道缝隙契约，这里的全部职责是把它落到 USB bulk 管道上：
 * - open：认领 PTP 接口（幂等性由状态机钉死，重复 open 抛错）
 * - write/read：一次 bulk 传输一个块
 * - close：释放接口 + 关 connection（终结性同 FakeTransport）
 * IO 失败一律 UsbTransportException，带可读原因（相机休眠 → 超时，拔线 → 设备不可用）。
 */
class UsbTransport(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    /** 单次 bulk 传输的超时毫秒：相机取景器待机不等于休眠断开，5s 足够 */
    private val timeoutMs: Int = 5_000,
) : Transport {

    private sealed interface Phase {
        data object Dormant : Phase
        data object Open : Phase
        data object Closed : Phase
    }

    private var phase: Phase = Phase.Dormant

    override fun open() {
        check(phase is Phase.Dormant) { "transport already $phase" }
        // force=true：相机可能残留上一次进程的认领；M0 探针独占使用
        if (!connection.claimInterface(iface, true)) {
            throw UsbTransportException(
                "claim",
                "claimInterface failed (device may be held by system service)",
            )
        }
        phase = Phase.Open
    }

    override fun write(chunk: ByteArray): Int {
        requireOpen()
        val sent = connection.bulkTransfer(bulkOut, chunk, 0, chunk.size, timeoutMs)
        if (sent < 0) {
            throw UsbTransportException(
                "write",
                "USB bulk write failed after ${timeoutMs}ms (camera asleep or unplugged?)",
            )
        }
        return sent
    }

    override fun read(buffer: ByteBuffer): Int {
        requireOpen()
        val remaining = buffer.remaining()
        if (remaining == 0) return 0
        // bulkTransfer 只认数组；一次最多读剩余容量，剩多少进多少
        val stage = ByteArray(remaining)
        val got = connection.bulkTransfer(bulkIn, stage, stage.size, timeoutMs)
        if (got < 0) {
            throw UsbTransportException(
                "read",
                "USB bulk read failed after ${timeoutMs}ms (camera asleep or unplugged?)",
            )
        }
        buffer.put(stage, 0, got)
        return got
    }

    private fun requireOpen() {
        check(phase is Phase.Open) {
            if (phase is Phase.Closed) "transport closed" else "io requires open transport"
        }
    }

    override fun close() {
        if (phase is Phase.Open) {
            runCatching { connection.releaseInterface(iface) }
        }
        runCatching { connection.close() }
        phase = Phase.Closed
    }

    companion object {
        /**
         * 从 UsbManager 视角打开一台已授权的 PTP 设备。
         * 只认 BULK 型号端点（影像接口可能附带中断 IN 端点，code-review 抓到的坑）；
         * 找不到时抛 IllegalStateException（含 MTP 疑似诊断）。
         */
        fun from(usbManager: UsbManager, device: UsbDevice): UsbTransport {
            val iface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbDeviceClassifier.CLASS_STILL_IMAGE }
                ?: throw IllegalStateException(
                    "no Still-Image interface on ${device.deviceName} " +
                        "(${describeInterfaces(device)}) — likely a file/MTP device",
                )
            val bulk = iface.bulkEndpoints()
            val bulkIn = bulk.first { it.direction == UsbConstants.USB_DIR_IN }
            val bulkOut = bulk.first { it.direction == UsbConstants.USB_DIR_OUT }
            val connection = usbManager.openDevice(device)
                ?: throw UsbTransportException("open", "openDevice failed (not granted or unplugged?)")
            return UsbTransport(connection, iface, bulkIn, bulkOut)
        }

        /** 只取 BULK 型端点（影像接口可能附带中断 IN 端点）。 */
        private fun UsbInterface.bulkEndpoints(): List<UsbEndpoint> =
            (0 until endpointCount)
                .map { getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }

        private fun describeInterfaces(device: UsbDevice): String =
            (0 until device.interfaceCount)
                .map { device.getInterface(it).interfaceClass }
                .joinToString(prefix = "interfaceClasses=[", postfix = "]") { "0x%02X".format(it) }
    }
}
