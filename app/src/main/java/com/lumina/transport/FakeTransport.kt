package com.lumina.transport

import java.nio.ByteBuffer

/**
 * Transport 的第一实现不是 USB，而是测试本身：
 * 把脚本化的字节序列当"相机"，把写进来的字节留存供单测对照。
 * 02 号工单起所有协议单测都消费它。
 */
class FakeTransport(
    /** 每块对应相机的一次完整响应，按序回放；短 buffer 不丢数据，余量留在下一次 read。 */
    private val scriptedResponses: List<ByteArray>,
) : Transport {

    private sealed interface Phase {
        data object Dormant : Phase
        data object Open : Phase
        data object Closed : Phase
    }

    private var phase: Phase = Phase.Dormant
    private val written: MutableList<ByteArray> = mutableListOf()
    private var readIndex = 0
    private var current: ByteArray? = null
    private var currentPos = 0

    override fun open() {
        check(phase is Phase.Dormant) { "transport already opened (phase=$phase)" }
        phase = Phase.Open
    }

    private fun requireOpen() {
        check(phase is Phase.Open) { "io requires open transport (phase=$phase)" }
    }

    override fun write(chunk: ByteArray): Int {
        requireOpen()
        written.add(chunk.copyOf())
        return chunk.size
    }

    override fun read(buffer: ByteBuffer): Int {
        requireOpen()
        if (buffer.remaining() == 0) return 0
        if (current == null) {
            if (readIndex >= scriptedResponses.size) return 0
            current = scriptedResponses[readIndex++]
            currentPos = 0
        }
        val chunk = current!!
        val take = minOf(chunk.size - currentPos, buffer.remaining())
        buffer.put(chunk, currentPos, take)
        currentPos += take
        if (currentPos >= chunk.size) {
            current = null
            currentPos = 0
        }
        return take
    }

    /** 写入留存对照（02 号单测断言"发出的请求字节"用）。 */
    fun observedWrites(): List<ByteArray> = written.toList()

    override fun close() {
        phase = Phase.Closed
    }
}
