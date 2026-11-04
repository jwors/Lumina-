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

    /** OpenSession；成功后进入 Open。（会话负责打开底层传输，契约见 Transport.open）
     *  ① 开 Session 前先发一笔 GetDeviceInfo 探活（会话前合法；gphoto2 同款，兼校准相机计数器预期）
     *  ② 宽容 0x201E SessionAlreadyOpened：相机残留会话直接沿用。 */
    fun open() {
        check(state == State.Closed) { "session already $state" }
        transport.open()
        try {
            sendWithPayload(OpCode.GET_DEVICE_INFO)
            log.log("GetDeviceInfo probe ok (pre-session)")
        } catch (e: Exception) {
            log.log("GetDeviceInfo probe failed: ${e.message}")
            throw IllegalStateException("GetDeviceInfo probe failed: ${e.message}", e)
        }
        try {
            send(OpCode.OPEN_SESSION, longArrayOf(1))
        } catch (e: PtpResponseException) {
            if (e.code != RespCode.SESSION_ALREADY_OPENED) throw e
            log.log("OpenSession: camera reported session already opened (0x201E), reusing")
        }
        state = State.Open
    }

    /** GetDeviceInfo → DeviceInfo.Model；成功后进入 Ready。 */
    fun getModel(): String {
        check(state == State.Open) { "getModel requires Open, state=$state" }
        val data = sendWithPayload(OpCode.GET_DEVICE_INFO)
        val model = parseDeviceInfo(data)
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
        check(count in 0..MAX_STORAGE_COUNT) {
            "storage count $count out of sane range (max $MAX_STORAGE_COUNT)"
        }
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

        /** 单次 GetStorageIDs 允许的存储分区数上限。 */
        const val MAX_STORAGE_COUNT = 255

        /**
         * 容器长度声明上限：M0 数据集全在 MB 级内；4 MiB 挡住谎报天文数字 OOM。
         * M1 进入文件数据阶段（单张 NEF ~50MB）时按需上调并补测试。
         */
        const val MAX_CONTAINER_BYTES = 4 shl 20
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

    /**
     * 无数据阶段事务：请求 → Response。
     * tid 在事务发起前预递增：事务一旦发起即被消费，失败后不重用（PTP 事务号唯一性）。
     * 响应行先于 OK 校验写入日志——失败出场也要留迹（03 号日志为排障服务）。
     */
    private fun send(op: Int, params: LongArray = LongArray(0)): Container.Response {
        val myTid = tid++
        begin(op, myTid, params)
        val resp = readResponse(op, myTid)
        logResponse(op, resp)
        requireOk(resp, op, myTid)
        return resp
    }

    /** 带数据阶段事务：请求 → Data → OK Response（tid 预递增同 send）。 */
    private fun sendWithPayload(op: Int, params: LongArray = LongArray(0)): ByteArray {
        val myTid = tid++
        begin(op, myTid, params)
        val data = readData(op, myTid)
        val resp = readResponse(op, myTid)
        logResponse(op, resp)
        requireOk(resp, op, myTid)
        return data
    }

    /** 事务公共前半：日志 + 写请求。 */
    private fun begin(op: Int, myTid: Int, params: LongArray) {
        logRequest(op, myTid, params)
        transport.write(encodeRequest(op, myTid, params))
    }

    /** 从 transport 读到一个完整容器字节（容忍底层任意分片，按前置 length 字段重组）。 */
    private fun readContainerBytes(): ByteArray {
        val head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        fill(head, 12, "header")
        head.flip()
        val fullLength = head.int
        check(fullLength in 12..MAX_CONTAINER_BYTES) {
            "container length $fullLength outside sane range (12..$MAX_CONTAINER_BYTES)"
        }
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

    private fun readResponse(op: Int, tid: Int): Container.Response {
        val bytes = readContainerBytes()
        return when (val c = parseContainer(bytes)) {
            is Container.Response -> c
            is Container.Data -> throw IllegalArgumentException(
                "expected response for 0x%04X, got data container".format(op),
            )
        }
    }

    private fun readData(op: Int, tid: Int): ByteArray {
        val bytes = readContainerBytes()
        return when (val c = parseContainer(bytes)) {
            is Container.Data -> {
                check(c.opCode == op) {
                    "data code 0x%04X != op 0x%04X".format(c.opCode, op)
                }
                warnTidMismatch(c.tid, tid)
                c.payload
            }
            is Container.Response -> {
                // 响应先到 = 无数据阶段：tid 宽容警告后按错误码出场
                warnTidMismatch(c.tid, tid)
                if (c.responseCode != RespCode.OK) throw PtpResponseException(c.responseCode, op)
                throw IllegalArgumentException(
                    "expected data for 0x%04X, got response".format(op),
                )
            }
        }
    }

    private fun requireOk(resp: Container.Response, op: Int, tid: Int) {
        warnTidMismatch(resp.tid, tid)
        if (resp.responseCode != RespCode.OK) throw PtpResponseException(resp.responseCode, op)
    }

    /**
     * TID 不匹配是警告不是硬错误（gphoto2 语义）：
     * 系统服务可能先探测过相机使其计数器领先；单线程顺序协议里，
     * 响应到达顺序本身即配对依据，丢失配对会导致整块重试而非假数据。
     */
    private fun warnTidMismatch(got: Int, expected: Int) {
        if (got != expected) {
            log.log("TID mismatch: camera=$got expected=$expected, continuing by arrival order")
        }
    }

    // ---------- 环形日志 ----------

    private fun logRequest(op: Int, myTid: Int, params: LongArray) {
        log.log(
            "→ ${OpName.name(op)} tid=$myTid" +
                if (params.isEmpty()) ""
                else " params=${params.joinToString(" ") { "0x%X".format(it) }}",
        )
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
        else -> "Op0x%04X".format(code)
    }
}
