package com.lumina.ptpip

import com.lumina.transport.DataOutTransport
import com.lumina.transport.Transport
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * PTP-IP 传输：把 `PtpSession` 的 PTP 容器字节流翻译成 15740/TCP 的包事务。
 *
 * 这是 M0 预留的 `Transport` 接缝在无线侧的实现——`PtpSession` 完全不知道
 * 底下是 USB 还是 Wi-Fi。PTP-IP 有自己的事务号（与容器 tid 独立）与双通道：
 * command 承载 operation/data/response，event 承载相机事件。
 *
 * 单飞前提：`PtpSession` 严格串行（发一笔、收完再发下一笔），所以这里用
 * "write 内完成整笔事务、把 Data/Response 容器排进队列，read 依次吐出"的
 * 简单模型；不实现并发事务路由。
 */
class PtpIpTransport(
    private val host: String,
    private val port: Int = PtpIp.PORT,
    private val connectTimeoutMs: Int = 8_000,
    private val ioTimeoutMs: Int = 15_000,
    private val hostname: String = "Lumina",
    private val diag: (String) -> Unit = {},
) : DataOutTransport {

    private var command: Socket? = null
    private var event: Socket? = null
    private var eventThread: Thread? = null
    @Volatile private var closed = false
    @Volatile private var poisoned: String? = null

    /** 下一笔 PTP-IP 事务号；OpenSession 归零，之后从 1 起（尼康实测约定）。 */
    private var nextTid = 0L

    private val outQueue = ArrayDeque<ByteArray>()
    private var partial: ByteArray? = null
    private var partialOffset = 0

    /** 数据出阶段在途请求（SetDevicePropValue）：请求先发，等主机发完数据再收响应。 */
    private data class PendingDataOut(val opcode: Int, val ptpTid: Long, val ipTid: Long)
    private var pendingDataOut: PendingDataOut? = null

    private val dataOutOps = setOf(
        0x1016, // SetDevicePropValue
        0x90CD, // Nikon SetPictCtrlData
    )

    val cameraName: String get() = cameraNameField
    private var cameraNameField = ""

    override fun open() {
        check(command == null) { "transport already open" }
        closed = false
        poisoned = null
        val cmd = connect()
        command = cmd
        try {
            sendFrame(cmd, PtpIp.initCommandRequest(hostname))
            var init = readFrame(cmd)
            while (init.type == PtpIp.EVENT) init = readFrame(cmd)
            if (init.type == PtpIp.INIT_FAIL) {
                val reason = if (init.body.size >= 4) PtpIp.u32(init.body, 0) else 0
                throw IOException("相机拒绝连接（InitFail reason=0x%X）".format(reason))
            }
            require(init.type == PtpIp.INIT_COMMAND_RESPONSE) {
                "expected InitCommandResponse, got ${init.type}"
            }
            val resp = PtpIp.initResponse(init.body)
            cameraNameField = resp.cameraName
            diag("PTP-IP 初始化：${resp.cameraName} conn=${resp.connectionNumber}")
            openEventChannel(resp.connectionNumber)
        } catch (e: Exception) {
            closeQuietly(cmd)
            command = null
            throw e
        }
    }

    private fun openEventChannel(connectionNumber: Long) {
        val ev = try {
            connect()
        } catch (e: Exception) {
            // 事件通道失败不致命：镜头参数与快门都走 command；只丢事件推送。
            diag("事件通道连接失败，继续单通道：${e.message}")
            return
        }
        try {
            sendFrame(ev, PtpIp.initEventRequest(connectionNumber))
            var resp = readFrame(ev)
            while (resp.type == PtpIp.EVENT) resp = readFrame(ev)
            require(resp.type == PtpIp.INIT_EVENT_RESPONSE) {
                "expected InitEventResponse, got ${resp.type}"
            }
            event = ev
            eventThread = Thread({ drainEvents(ev) }, "ptpip-events").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            diag("事件通道握手失败，继续单通道：${e.message}")
            closeQuietly(ev)
        }
    }

    private fun drainEvents(socket: Socket) {
        try {
            val input = socket.getInputStream()
            while (!closed) {
                val frame = readFrame(input)
                if (frame.type == PtpIp.EVENT) {
                    val ev = PtpIp.event(frame.body)
                    diag("相机事件 0x%04X tid=%d".format(ev.code, ev.transactionId))
                }
            }
        } catch (_: Exception) {
            // 通道关闭/相机断链：command 侧会先报错，这里静默退出。
        }
    }

    override fun write(chunk: ByteArray): Int {
        val socket = command ?: throw IOException("transport not open")
        poisoned?.let { throw IOException(it) }
        val request = PtpIp.parseRequestContainer(chunk)
        val phase = PtpIp.dataPhaseFor(request.opcode)
        val ipTid: Long = if (request.opcode == 0x1002) { // OpenSession
            nextTid = 1
            0L
        } else {
            nextTid++
        }
        val out = PtpIp.operationRequest(phase, request.opcode, ipTid, request.params)
        sendFrame(socket, out)

        val input = socket.getInputStream()
        var response: PtpIp.OpResponse? = null
        var dataBytes: ByteArray? = null
        if (phase == PtpIp.PHASE_IN) {
            val buf = ByteArrayOutputStream()
            var declared = -1L
            loop@ while (true) {
                val frame = readFrame(input)
                when (frame.type) {
                    PtpIp.START_DATA -> {
                        require(frame.body.size >= 12) { "StartData too short" }
                        declared = PtpIp.u64(frame.body, 4)
                    }
                    PtpIp.DATA -> {
                        require(frame.body.size >= 4) { "Data too short" }
                        buf.write(frame.body, 4, frame.body.size - 4)
                    }
                    PtpIp.END_DATA -> {
                        require(frame.body.size >= 4) { "EndData too short" }
                        buf.write(frame.body, 4, frame.body.size - 4)
                        break@loop
                    }
                    PtpIp.OPERATION_RESPONSE -> {
                        response = PtpIp.operationResponse(frame.body)
                        break@loop
                    }
                    PtpIp.EVENT -> Unit // 个别固件把事件发到 command 通道
                    else -> diag("忽略未知包 type=${frame.type}")
                }
            }
            if (response == null) {
                dataBytes = buf.toByteArray()
                if (declared >= 0 && declared != dataBytes.size.toLong()) {
                    diag("数据相长度不符：声明 $declared 实收 ${dataBytes.size}")
                }
            }
        }
        if (response == null) {
            var frame = readFrame(input)
            while (frame.type != PtpIp.OPERATION_RESPONSE) {
                if (frame.type != PtpIp.EVENT) diag("期待响应，忽略 type=${frame.type}")
                frame = readFrame(input)
            }
            response = PtpIp.operationResponse(frame.body)
        }
        val resp = response!!
        dataBytes?.let { outQueue.addLast(PtpIp.dataContainer(request.opcode, request.tid, it)) }
        outQueue.addLast(PtpIp.responseContainer(resp.code, request.tid, resp.params))
        diag("PTP-IP op=0x%04X rc=0x%04X data=%d".format(
            request.opcode, resp.code, dataBytes?.size ?: 0,
        ))
        return chunk.size
    }

    /** 数据出请求：只发 OperationRequest，不读响应（相机在等数据）。 */
    override fun writeRequestNoResponse(chunk: ByteArray): Int {
        val socket = command ?: throw IOException("transport not open")
        poisoned?.let { throw IOException(it) }
        val request = PtpIp.parseRequestContainer(chunk)
        val ipTid = nextTid++
        pendingDataOut = PendingDataOut(request.opcode, request.tid, ipTid)
        val phase = if (request.opcode in dataOutOps) PtpIp.PHASE_OUT else PtpIp.PHASE_NONE
        sendFrame(socket, PtpIp.operationRequest(phase, request.opcode, ipTid, request.params))
        diag("PTP-IP → op=0x%04X 数据出请求".format(request.opcode))
        return chunk.size
    }

    /** 数据出：StartData + EndData，然后收这一笔的 OperationResponse。 */
    override fun writeData(payload: ByteArray) {
        val socket = command ?: throw IOException("transport not open")
        val pending = pendingDataOut ?: throw IOException("没有待发数据的数据出请求")
        sendFrame(socket, PtpIp.startData(pending.ipTid, payload.size.toLong()))
        sendFrame(socket, PtpIp.endData(pending.ipTid, payload))
        val input = socket.getInputStream()
        var frame = readFrame(input)
        while (frame.type != PtpIp.OPERATION_RESPONSE) {
            if (frame.type != PtpIp.EVENT) diag("数据出后期待响应，忽略 type=${frame.type}")
            frame = readFrame(input)
        }
        val resp = PtpIp.operationResponse(frame.body)
        outQueue.addLast(PtpIp.responseContainer(resp.code, pending.ptpTid, resp.params))
        diag("PTP-IP op=0x%04X rc=0x%04X 数据出=%d".format(pending.opcode, resp.code, payload.size))
        pendingDataOut = null
    }

    override fun read(buffer: ByteBuffer): Int {
        var current = partial
        if (current == null) {
            current = outQueue.removeFirstOrNull() ?: return 0
            partial = current
            partialOffset = 0
        }
        val n = minOf(buffer.remaining(), current.size - partialOffset)
        buffer.put(current, partialOffset, n)
        partialOffset += n
        if (partialOffset >= current.size) {
            partial = null
            partialOffset = 0
        }
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        command?.let { closeQuietly(it) }
        event?.let { closeQuietly(it) }
        command = null
        event = null
        eventThread?.interrupt()
        eventThread = null
        outQueue.clear()
        partial = null
        pendingDataOut = null
    }

    // ---------- socket 与帧 ----------

    private fun connect(): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = ioTimeoutMs
        socket.tcpNoDelay = true
        return socket
    }

    private fun sendFrame(socket: Socket, bytes: ByteArray) {
        socket.getOutputStream().apply {
            write(bytes)
            flush()
        }
    }

    private fun readFrame(socket: Socket): PtpIp.Frame = readFrame(socket.getInputStream())

    private fun readFrame(input: InputStream): PtpIp.Frame {
        val header = ByteArray(PtpIp.HEADER_LENGTH)
        readFully(input, header)
        val length = PtpIp.u32(header, 0).toInt()
        val type = PtpIp.u32(header, 4).toInt()
        if (length < PtpIp.HEADER_LENGTH || length > PtpIp.MAX_FRAME_BYTES) {
            throw IOException("非法 PTP-IP 帧长度 $length（流错位？）")
        }
        val body = ByteArray(length - PtpIp.HEADER_LENGTH)
        readFully(input, body)
        return PtpIp.Frame(type, body)
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("相机在帧中途关闭连接")
            off += n
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
