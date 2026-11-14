package com.lumina.ptpip

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP-IP（15740 over TCP/IP）的尼康方言编解码。
 *
 * 与规范的三处差异（Zf 真机 + 开源实现互证，见 docs/adr/0006）：
 * 1. 包头 = 长度(u32) 在前 + 类型(u32) 在后，长度含 8 字节头；
 * 2. InitCommandRequest = 固定 Android GUID + UTF-16LE 主机名（NUL 结尾，**无长度前缀**）+ u32 版本；
 * 3. InitCommandResponse 多出相机 GUID / UTF-16LE 相机名 / 版本三段。
 *
 * 全部字段小端。大端包会被相机静默丢弃（症状：TCP 连上但无响应）。
 */
object PtpIp {
    const val INIT_COMMAND_REQUEST = 1
    const val INIT_COMMAND_RESPONSE = 2
    const val INIT_EVENT_REQUEST = 3
    const val INIT_EVENT_RESPONSE = 4
    const val INIT_FAIL = 5
    const val OPERATION_REQUEST = 6
    const val OPERATION_RESPONSE = 7
    const val EVENT = 8
    const val START_DATA = 9
    const val DATA = 10
    const val CANCEL = 11
    const val END_DATA = 12
    const val PROBE_REQUEST = 13
    const val PROBE_RESPONSE = 14

    const val HEADER_LENGTH = 8
    const val PORT = 15740

    /** 单帧上限（含头）：相机侧分块 ≤8MB，32MiB 是 4× 余量；超限几乎必然是流错位。 */
    const val MAX_FRAME_BYTES = 32 * 1024 * 1024

    /** 控制帧上限：握手名/命令/事件不需要 MB 级长度。 */
    const val MAX_CONTROL_FRAME_BYTES = 1024 * 1024

    /** SnapBridge 生态的固定 Android 客户端 GUID；换 GUID 相机就当成另一台手机。 */
    val ANDROID_GUID = byteArrayOf(
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
        0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
    )

    const val PROTOCOL_VERSION = 0x00010000

    /** 数据相：1 = 无数据，2 = 数据出（主机→相机），3 = 数据入（相机→主机）。 */
    const val PHASE_NONE = 1
    const val PHASE_OUT = 2
    const val PHASE_IN = 3

    val dataInOps = setOf(
        0x1001, // GetDeviceInfo
        0x1004, // GetStorageIDs
        0x1007, // GetObjectHandles
        0x1008, // GetObjectInfo
        0x1009, // GetObject
        0x1014, // GetDevicePropDesc
        0x1015, // GetDevicePropValue
        0x90CC, // Nikon GetPictCtrlData
        0x90CF, // Nikon GetPicCtrlCapability
        0x9203, // Nikon GetLiveViewImage
    )

    fun dataPhaseFor(opcode: Int): Int = if (opcode in dataInOps) PHASE_IN else PHASE_NONE

    /** 补 8 字节包头：长度（含头）在前，类型在后。 */
    fun frame(type: Int, body: ByteArray): ByteArray {
        val out = ByteArray(HEADER_LENGTH + body.size)
        putU32(out, 0, (HEADER_LENGTH + body.size).toLong())
        putU32(out, 4, type.toLong())
        body.copyInto(out, HEADER_LENGTH)
        return out
    }

    fun initCommandRequest(hostname: String): ByteArray {
        val name = hostname.toByteArray(Charsets.UTF_16LE)
        val body = ByteArray(16 + name.size + 2 + 4)
        ANDROID_GUID.copyInto(body, 0)
        name.copyInto(body, 16)
        // UTF-16LE NUL 终止符
        body[16 + name.size] = 0
        body[16 + name.size + 1] = 0
        putU32(body, 16 + name.size + 2, PROTOCOL_VERSION.toLong())
        return frame(INIT_COMMAND_REQUEST, body)
    }

    fun initEventRequest(connectionNumber: Long): ByteArray {
        val body = ByteArray(4)
        putU32(body, 0, connectionNumber)
        return frame(INIT_EVENT_REQUEST, body)
    }

    fun operationRequest(dataPhase: Int, opcode: Int, transactionId: Long, params: LongArray): ByteArray {
        val body = ByteArray(10 + params.size * 4)
        putU32(body, 0, dataPhase.toLong())
        putU16(body, 4, opcode)
        putU32(body, 6, transactionId)
        params.forEachIndexed { i, p -> putU32(body, 10 + i * 4, p) }
        return frame(OPERATION_REQUEST, body)
    }

    fun startData(transactionId: Long, totalLength: Long): ByteArray {
        val body = ByteArray(12)
        putU32(body, 0, transactionId)
        putU32(body, 4, totalLength and 0xFFFFFFFFL)
        putU32(body, 8, totalLength ushr 32)
        return frame(START_DATA, body)
    }

    fun data(transactionId: Long, payload: ByteArray): ByteArray {
        val body = ByteArray(4 + payload.size)
        putU32(body, 0, transactionId)
        payload.copyInto(body, 4)
        return frame(DATA, body)
    }

    fun endData(transactionId: Long, payload: ByteArray): ByteArray {
        val body = ByteArray(4 + payload.size)
        putU32(body, 0, transactionId)
        payload.copyInto(body, 4)
        return frame(END_DATA, body)
    }

    // ---------- 读侧 ----------

    fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    fun u32(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    fun u64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    fun putU16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun putU32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v ushr 8) and 0xFF).toByte()
        b[at + 2] = ((v ushr 16) and 0xFF).toByte()
        b[at + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun initResponse(body: ByteArray): InitResponse {
        require(body.size >= 20) { "InitCommandResponse too short: ${body.size}" }
        val conn = u32(body, 0)
        val guid = body.copyOfRange(4, 20)
        var off = 20
        val sb = StringBuilder()
        while (off + 1 < body.size) {
            val code = u16(body, off)
            if (code == 0) break
            sb.append(code.toChar())
            off += 2
        }
        val consumedName = (sb.length + 1) * 2
        val versionAt = 20 + consumedName
        val version = if (versionAt + 4 <= body.size) u32(body, versionAt).toInt() else 0
        return InitResponse(conn, guid, sb.toString(), version)
    }

    fun operationResponse(body: ByteArray): OpResponse {
        require(body.size >= 6) { "OperationResponse too short: ${body.size}" }
        return OpResponse(u16(body, 0), u32(body, 2), params(body, 6))
    }

    fun event(body: ByteArray): Event {
        require(body.size >= 6) { "Event too short: ${body.size}" }
        return Event(u16(body, 0), u32(body, 2), params(body, 6))
    }

    private fun params(body: ByteArray, from: Int): LongArray {
        val n = (body.size - from) / 4
        return LongArray(n) { i -> u32(body, from + i * 4) }
    }

    /** 一小段写容器（PtpSession 的 `encodeRequest` 产物）的解析结果。 */
    fun parseRequestContainer(bytes: ByteArray): RequestContainer {
        require(bytes.size >= 12) { "PTP container too short: ${bytes.size}" }
        val length = u32(bytes, 0).toInt()
        require(length == bytes.size) { "container length $length != ${bytes.size}" }
        val type = u16(bytes, 4)
        require(type == 1) { "not a request container: type=$type" }
        val opcode = u16(bytes, 6)
        val tid = u32(bytes, 8)
        val n = (bytes.size - 12) / 4
        val params = LongArray(n) { i -> u32(bytes, 12 + i * 4) }
        return RequestContainer(opcode, tid, params)
    }

    /** 构造 PtpSession 期望的 Data 容器（u32 length + u16 type + u16 code + u32 tid + payload）。 */
    fun dataContainer(opcode: Int, tid: Long, payload: ByteArray): ByteArray =
        container(2, opcode, tid, payload)

    /** 构造 PtpSession 期望的 Response 容器。 */
    fun responseContainer(code: Int, tid: Long, params: LongArray): ByteArray {
        val payload = ByteArray(params.size * 4)
        params.forEachIndexed { i, p -> putU32(payload, i * 4, p) }
        return container(3, code, tid, payload)
    }

    private fun container(type: Int, code: Int, tid: Long, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(12 + payload.size)
        buf.putShort(type.toShort())
        buf.putShort(code.toShort())
        buf.putInt(tid.toInt())
        buf.put(payload)
        return buf.array()
    }

    data class InitResponse(
        val connectionNumber: Long,
        val cameraGuid: ByteArray,
        val cameraName: String,
        val protocolVersion: Int,
    )

    data class OpResponse(val code: Int, val transactionId: Long, val params: LongArray)

    data class Event(val code: Int, val transactionId: Long, val params: LongArray)

    data class RequestContainer(val opcode: Int, val tid: Long, val params: LongArray)

    /** 已切好的帧：type + 去头 body。 */
    data class Frame(val type: Int, val body: ByteArray)
}
