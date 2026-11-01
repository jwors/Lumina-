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
class PtpSession(private val transport: Transport) {

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

    /** 无数据阶段事务：请求 → Response；tid 与响应码都在 tid 递增前校验。 */
    private fun send(op: Int, params: LongArray = LongArray(0)): Container.Response {
        transport.write(encodeRequest(op, tid, params))
        val resp = readResponse(op)
        requireOk(resp, op)
        tid++
        return resp
    }

    /** 带数据阶段事务：请求 → Data → OK Response */
    private fun sendWithPayload(op: Int): ByteArray {
        transport.write(encodeRequest(op, tid))
        val data = readData(op)
        val resp = readResponse(op)
        requireOk(resp, op)
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
}
