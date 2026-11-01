package com.lumina.transport

/**
 * Transport 的第一实现不是 USB，而是测试本身：
 * 把脚本化的字节序列当"相机"，把写进来的字节当"请求"留存对照。
 * 02 号工单起所有协议单测都消费它。
 */
class FakeTransport(
    /** 按 read 调用次序回放的响应块（每块对应一次 read 的返回） */
    private val scriptedResponses: List<ByteArray>,
) : Transport {

    val written: MutableList<ByteArray> = mutableListOf()
    private var readIndex = 0
    private var closed = false

    override fun write(chunk: ByteArray): Int {
        check(!closed) { "transport closed" }
        written.add(chunk.copyOf())
        return chunk.size
    }

    override fun read(buffer: java.nio.ByteBuffer): Int {
        check(!closed) { "transport closed" }
        if (readIndex >= scriptedResponses.size) return 0
        val chunk = scriptedResponses[readIndex++]
        val take = minOf(chunk.size, buffer.remaining())
        buffer.put(chunk, 0, take)
        return take
    }

    override fun close() {
        closed = true
    }
}
