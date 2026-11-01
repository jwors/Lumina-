package com.lumina.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 请求容器：u32 length + u16 type + u16 code + u32 tid + 0~n 个参数（头部 12 字节，PIMA 15740） */
fun encodeRequest(opCode: Int, tid: Int, params: LongArray = LongArray(0)): ByteArray {
    val total = 12 + params.size * 4
    val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(total)
    buf.putShort(ContainerType.REQUEST.toShort())
    buf.putShort(opCode.toShort())
    buf.putInt(tid)
    for (p in params) buf.putInt(p.toInt())
    return buf.array()
}

sealed interface Container {
    /** 相机回的数据载荷（DeviceInfo / StorageIDs / ObjectInfo …） */
    data class Data(val opCode: Int, val tid: Int, val payload: ByteArray) : Container

    /** 相机回的结果（OK 或错误码 + 0~n 参数） */
    data class Response(val responseCode: Int, val tid: Int, val params: LongArray) : Container
}

/**
 * 解析一个完整容器。输入必须恰好是一个容器的完整字节——
 * 跨包分片的重组由 PtpSession.readContainer 负责（USB bulk 会把任意边界切开）。
 */
fun parseContainer(bytes: ByteArray): Container {
    if (bytes.size < 12) throw IllegalArgumentException("container too short: ${bytes.size}")
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val length = buf.int
    if (length != bytes.size) throw IllegalArgumentException(
        "container length field $length != actual ${bytes.size}",
    )
    val type = buf.short.toInt() and 0xFFFF
    val code = buf.short.toInt() and 0xFFFF
    val tid = buf.int
    val rest = bytes.copyOfRange(12, bytes.size)
    return when (type) {
        ContainerType.DATA -> Container.Data(code, tid, rest)
        ContainerType.RESPONSE -> {
            val params = LongArray(rest.size / 4)
            val pbuf = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN)
            for (i in params.indices) params[i] = pbuf.int.toLong() and 0xFFFFFFFFL
            Container.Response(code, tid, params)
        }
        else -> throw IllegalArgumentException("unexpected container type $type")
    }
}
