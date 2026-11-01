package com.lumina.ptp

import com.lumina.transport.Transport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 存储分区（机内内存 / SD 卡），id 是相机侧持久标识 */
data class Storage(val id: Long)

/**
 * PTP 会话状态机与事务引擎（02 号工单核心）。
 * 状态推进只允许 Closed -> Open -> Ready -> Closed，非法操作序显式抛错。
 * 每个事务的请求字节经 Transport 留存，供单测对照（02 验收第一条）。
 */
class PtpSession(
    private val transport: Transport,
    /** 环形会话日志（03 号）：请求/响应各记一行，供探针 UI 显示。 */
    val log: RingLog = RingLog(),
) {

    sealed interface State {
        data object Closed : State
        data object Open : State
        data object Ready : State
    }

    private var state: State = State.Closed
    private var tid = 1

    val currentState: State get() = state

    /** OpenSession；成功后进入 Open。（会话负责打开底层传输，契约见 Transport.open） */
    fun open() {
        check(state == State.Closed) { "session already $state" }
        transport.open()
        send(OpCode.OPEN_SESSION, longArrayOf(1))
        state = State.Open
    }

    /** GetDeviceInfo → DeviceInfo.Model；成功后进入 Ready。 */
    fun getModel(): String {
        check(state == State.Open) { "getModel requires Open, state=$state" }
        val data = sendWithPayload(OpCode.GET_DEVICE_INFO)
        val r = MtpReader(data)
        r.u16()                 // StandardVersion
        r.u32()                 // VendorExtensionID
        r.u16()                 // VendorExtensionVersion
        r.mtpString()           // VendorExtensionDesc
        r.u16()                 // FunctionalMode
        r.u16Array()            // Operationcodes
        r.u16Array()            // Eventcodes
        r.u16Array()            // DevicePropcodes
        r.u16Array()            // CaptureFormats
        r.u16Array()            // ImageFormats
        r.mtpString()           // Manufacturer
        val model = r.mtpString() // Model
        state = State.Ready
        return model
    }

    /** GetStorageIDs → 存储分区列表（Open 或 Ready 均可） */
    fun listStorage(): List<Storage> {
        check(
            state == State.Open || state == State.Ready,
        ) { "listStorage requires session, state=$state" }
        val data = sendWithPayload(OpCode.GET_STORAGE_IDS)
        val r = MtpReader(data)
        val count = r.u32().toInt()
        return List(count) { Storage(r.u32()) }
    }

    /** GetObjectHandles (0x1007)：parent = PARENT_ALL 时全量枚举。 */
    fun getObjectHandles(storageId: Long, format: Int = 0, parent: Long = PARENT_ALL): List<Long> {
        check(state == State.Open || state == State.Ready) {
            "getObjectHandles requires session, state=$state"
        }
        val data = sendWithPayload(OpCode.GET_OBJECT_HANDLES, longArrayOf(storageId, format.toLong(), parent))
        val r = MtpReader(data)
        val count = r.u32().toInt()
        // 相机谎报天文数字时第一时间拒绝，避免按错误计数分配集合（03 号验收边界）
        check(count in 0..MAX_OBJECT_COUNT) { "object count $count out of sane range (max $MAX_OBJECT_COUNT)" }
        return List(count) { r.u32() }
    }

    /** GetObjectInfo (0x1008)：单个对象的数据集解析。 */
    fun getObjectInfo(handle: Long): ObjectInfo {
        check(state == State.Open || state == State.Ready) { "getObjectInfo requires session, state=$state" }
        val data = sendWithPayload(OpCode.GET_OBJECT_INFO, longArrayOf(handle))
        return parseObjectInfo(data)
    }

    /**
     * MTP/PTP 约定：GetObjectHandles 的 parent 参数 0xFFFFFFFF = 枚举全部对象。
     * （PTP 规格语义；真机若只回根列表，改用逐父遍历，挂 05/06 号校验。）
     */
    companion object {
        const val PARENT_ALL = 0xFFFFFFFFL

        /** 单次 GetObjectHandles 允许的最大对象数（远超一张 SD 卡的现实容量）。 */
        const val MAX_OBJECT_COUNT = 1 shl 20
    }

    /** CloseSession（尽力而为）+ 关闭底层传输 */
    fun close() {
        if (state != State.Closed) {
            try {
                send(OpCode.CLOSE_SESSION)
                // 非 OK 不往上抛：弱相机可能已自己断开，M0 允许
            } catch (_: Exception) {
            }
            state = State.Closed
        }
        transport.close()
    }

    private fun send(op: Int, params: LongArray = LongArray(0)): Container.Response {
        logRequest(op, params)
        transport.write(encodeRequest(op, tid, params))
        val resp = readResponse(op)
        requireOk(resp, op)
        logResponse(op, resp)
        tid++
        return resp
    }

    /** 带数据阶段事务：请求 → Data → OK Response */
    private fun sendWithPayload(op: Int, params: LongArray = LongArray(0)): ByteArray {
        logRequest(op, params)
        transport.write(encodeRequest(op, tid, params))
        val data = readData(op)
        val resp = readResponse(op)
        requireOk(resp, op)
        logResponse(op, resp)
        tid++
        return data
    }

    /** 从 transport 读到一个完整容器字节（容忍底层任意分片，按前置 length 字段重组）。 */
    private fun readContainerBytes(): ByteArray {
        val head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        fill(head, 12, "header")
        head.flip()
        val fullLength = head.int
        check(fullLength >= 12) { "container length $fullLength < 12" }
        val body = ByteBuffer.allocate(fullLength - 12).order(ByteOrder.LITTLE_ENDIAN)
        if (fullLength > 12) fill(body, fullLength - 12, "body")
        val full = ByteBuffer.allocate(fullLength).order(ByteOrder.LITTLE_ENDIAN)
        head.rewind() // limit 已是 12，仅把读 position 归零（flip 会把 limit 错设为 4）
        full.put(head)
        body.flip() // 填满后 position 在末尾，翻转后才能整体拷出
        full.put(body)
        return full.array()
    }

    /** 反复 read 直到填满 n 字节；USB bulk read 常常把容器任意切段。 */
    private fun fill(buffer: ByteBuffer, n: Int, what: String) {
        while (buffer.position() < n) {
            val got = transport.read(buffer)
            check(got > 0) {
                "transport EOF while reading $what (${buffer.position()}/$n)"
            }
        }
    }

    private fun readResponse(op: Int): Container.Response {
        val bytes = readContainerBytes()
        return when (val c = parseContainer(bytes)) {
            is Container.Response -> c
            is Container.Data -> throw IllegalArgumentException(
                "expected response for 0x%04X, got data container".format(op),
            )
        }
    }

    private fun readData(op: Int): ByteArray {
        val bytes = readContainerBytes()
        return when (val c = parseContainer(bytes)) {
            is Container.Data -> {
                check(c.opCode == op) {
                    "data code 0x%04X != op 0x%04X".format(c.opCode, op)
                }
                check(c.tid == tid) {
                    "transaction id mismatch: got ${c.tid}, expected $tid"
                }
                c.payload
            }
            is Container.Response -> {
                // 响应先到 = 无数据阶段：只可能是错误码场合
                if (c.responseCode != RespCode.OK) throw PtpResponseException(c.responseCode, op)
                throw IllegalArgumentException(
                    "expected data for 0x%04X, got response".format(op),
                )
            }
        }
    }

    private fun requireOk(resp: Container.Response, op: Int) {
        check(resp.tid == tid) { "transaction id mismatch: got ${resp.tid}, expected $tid" }
        if (resp.responseCode != RespCode.OK) throw PtpResponseException(resp.responseCode, op)
    }

    // ---------- 环形日志 ----------

    private fun logRequest(op: Int, params: LongArray) {
        log.log("→ ${OpName.name(op)} tid=$tid${if (params.isEmpty()) "" else " params=${params.joinToString(" ") { "0x%X".format(it) }}"}")
    }

    private fun logResponse(op: Int, resp: Container.Response) {
        val code = "%s 0x%04X".format(
            if (resp.responseCode == RespCode.OK) "OK" else "ERR",
            resp.responseCode,
        )
        log.log("← ${OpName.name(op)} tid=${resp.tid} $code")
    }
}

/** 操作码 → 可读名（仅日志显示用）。 */
object OpName {
    fun name(code: Int): String = when (code) {
        OpCode.GET_DEVICE_INFO -> "GetDeviceInfo"
        OpCode.OPEN_SESSION -> "OpenSession"
        OpCode.CLOSE_SESSION -> "CloseSession"
        OpCode.GET_STORAGE_IDS -> "GetStorageIDs"
        OpCode.GET_OBJECT_HANDLES -> "GetObjectHandles"
        OpCode.GET_OBJECT_INFO -> "GetObjectInfo"
        OpCode.GET_PARTIAL_OBJECT -> "GetPartialObject"
        else -> "Op0x%04X".format(code)
    }
}
