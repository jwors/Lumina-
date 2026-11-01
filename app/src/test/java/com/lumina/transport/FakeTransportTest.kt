package com.lumina.transport

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * M0 spec 的缝隙契约测试：FakeTransport 是 02/03 协议引擎的"相机替身"，
 * 它必须先被钉死在契约上：脚本回放有序、写入留存、close 幂等且拒绝后续 IO。
 */
class FakeTransportTest {

    @Test
    fun `write keeps all chunks in order`() {
        val t = FakeTransport(scriptedResponses = emptyList())
        t.write(byteArrayOf(1, 2, 3))
        t.write(byteArrayOf(4))
        assertEquals(2, t.written.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), t.written[0])
        assertArrayEquals(byteArrayOf(4), t.written[1])
        t.close()
    }

    @Test
    fun `read replays scripted responses in order`() {
        val t = FakeTransport(
            scriptedResponses = listOf(
                byteArrayOf(0x0A, 0x0B),
                byteArrayOf(0x0C),
            ),
        )
        val buf = ByteBuffer.allocate(8)
        assertEquals(2, t.read(buf))
        assertArrayEquals(byteArrayOf(0x0A, 0x0B), buf.array().copyOfRange(0, buf.position()))
        assertEquals(1, t.read(ByteBuffer.allocate(8)))
        assertEquals(0, t.read(ByteBuffer.allocate(8))) // 脚本耗尽 = 流结束
        t.close()
    }

    @Test
    fun `read respects buffer remaining capacity`() {
        val t = FakeTransport(scriptedResponses = listOf(byteArrayOf(1, 2, 3, 4, 5)))
        val buf = ByteBuffer.allocate(3)
        assertEquals(3, t.read(buf)) // 只装得下 3 字节
        t.close()
    }

    @Test
    fun `close idempotent, io after close rejected`() {
        val t = FakeTransport(scriptedResponses = emptyList())
        t.close()
        t.close() // 幂等
        assertThrows(IllegalStateException::class.java) { t.write(byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { t.read(ByteBuffer.allocate(4)) }
    }
}
