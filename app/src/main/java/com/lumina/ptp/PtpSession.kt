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
     *  连接序列（ptplibrary/gphoto2 结语）：
     *  ① transport.open：claim + StillImageDeviceReset(0x66) + 清 HALT；
     *  ② pre-close：清相机残留会话（一切错误码容忍，SESSION_NOT_OPEN=本来就没有）；
     *  ③ OpenSession；宽容 0x201E SessionAlreadyOpened。
     *  GetDeviceInfo 挪到会话后（部分相机必须先有 session 才答它——真机实录 probe 探活全挂）。 */
    fun open() {
        check(state == State.Closed) { "session already $state" }
        transport.open()
        try {
            send(OpCode.CLOSE_SESSION)
            log.log("pre-close: stale session cleared cleanly")
        } catch (e: PtpResponseException) {
            log.log("pre-close tolerated camera response 0x%04X".format(e.code))
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

        /** 全宽读 stage：必须 ≥ 真实 USB 包上限（USB2 bulk 512B），4K 兼容 USB3。 */
        const val READ_STAGE_SIZE = 4096
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

    /**
     * 从 transport 读一个完整容器。真实 USB bulk 的包语义：**读请求必须 ≥ 整包容量
     * （512B+），短读会把包内剩余字节丢给内核**（真机实录：GetDeviceInfo 的 Data 阶段
     * 因 12 字节头短读而永久死锁——MtpDevice 按整包读所以能通）。
     * 所以这里全部按 4K 全宽读，超出容器长度的粘包字节留存到下一笔。
     */
    private fun readContainerBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        leftover?.let { out.write(it); leftover = null }
        while (out.size() < 12) readChunkInto(out)
        val header = out.toByteArray()
        val fullLength = java.nio.ByteBuffer
            .wrap(header, 0, header.size)
            .order(ByteOrder.LITTLE_ENDIAN).int
        check(fullLength in 12..MAX_CONTAINER_BYTES) {
            "container length $fullLength outside sane range (12..$MAX_CONTAINER_BYTES)"
        }
        while (out.size() < fullLength) readChunkInto(out)
        val all = out.toByteArray()
        return if (all.size == fullLength) {
            all
        } else {
            leftover = all.copyOfRange(fullLength, all.size) // 粘包：下一笔事务的头
            all.copyOf(fullLength)
        }
    }

    /** 一次全宽读（4K stage），返回实际收到字节数。 */
    private fun readChunkInto(out: java.io.ByteArrayOutputStream) {
        val got = transport.read(ByteBuffer.wrap(stage))
        check(got > 0) { "transport exhausted while reading container (got $got)" }
        out.write(stage, 0, got)
    }

    private val stage = ByteArray(READ_STAGE_SIZE)
    private var leftover: ByteArray? = null

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
