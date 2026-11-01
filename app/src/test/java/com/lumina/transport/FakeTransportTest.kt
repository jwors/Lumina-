package com.lumina.transport

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * M0 spec 的缝隙契约测试：FakeTransport 是 02/03 协议引擎的"相机替身"，
 * 契约钉死：open 先行一次、读写有序、短 buffer 不丢余量、close 幂等且终结。
 */
class FakeTransportTest {

    private fun newTransport(vararg chunks: ByteArray) = FakeTransport(chunks.toList())

    @Test
    fun `open precedes io, single open only`() {
        val t = newTransport()
        assertThrows(IllegalStateException::class.java) { t.write(byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { t.read(ByteBuffer.allocate(4)) }
        t.open() // 第一次 open 合法
        assertThrows(IllegalStateException::class.java) { t.open() } // 第二次必炸
        t.close()
    }

    @Test
    fun `write keeps all chunks in order`() {
        val t = newTransport()
        t.open()
        t.write(byteArrayOf(1, 2, 3))
        t.write(byteArrayOf(4))
        assertEquals(2, t.observedWrites().size)
        assertArrayEquals(byteArrayOf(1, 2, 3), t.observedWrites()[0])
        assertArrayEquals(byteArrayOf(4), t.observedWrites()[1])
        t.close()
    }

    @Test
    fun `read replays scripted responses in order`() {
        val t = newTransport(
            byteArrayOf(0x0A, 0x0B),
            byteArrayOf(0x0C),
        )
        t.open()
        val buf = ByteBuffer.allocate(8)
        assertEquals(2, t.read(buf))
        assertArrayEquals(byteArrayOf(0x0A, 0x0B), buf.array().copyOfRange(0, buf.position()))
        buf.clear()
        assertEquals(1, t.read(buf))
        assertEquals(0, t.read(buf)) // 脚本耗尽 = 流结束
        t.close()
    }

    @Test
    fun `short buffer does not swallow the tail`() {
        val t = newTransport(byteArrayOf(1, 2, 3, 4, 5))
        t.open()
        val small = ByteBuffer.allocate(3)
        assertEquals(3, t.read(small))
        // 余量必须留给下一次 read（底层流的语义），不允许静默丢弃
        small.clear()
        assertEquals(2, t.read(small))
        assertEquals(0, t.read(small))
        t.close()
    }

    @Test
    fun `close terminates, reopen forbidden`() {
        val t = newTransport()
        t.open()
        t.close()
        t.close() // close 幂等
        assertThrows(IllegalStateException::class.java) { t.write(byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { t.read(ByteBuffer.allocate(4)) }
        assertThrows(IllegalStateException::class.java) { t.open() }
    }
}
