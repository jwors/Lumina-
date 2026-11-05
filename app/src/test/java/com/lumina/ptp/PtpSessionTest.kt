package com.lumina.ptp

import com.lumina.ptp.FakeCamera.container
import com.lumina.ptp.FakeCamera.dataPack
import com.lumina.ptp.FakeCamera.deviceInfo
import com.lumina.ptp.FakeCamera.resp
import com.lumina.transport.FakeTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 02 号工单验收（PTP tid 规范版）：
 * OpenSession 携带 tid=0x00000000（PIMA/尼康实现共识），之后主机事务从 1 起算；
 * GetDeviceInfo 必须在会话内；pre-close 取消（DeviceReset 0x66 已清相机状态机）；
 * ZLP（零长包）= 数据终止符，读到 0 必须继续；
 * tid 不匹配 = 警告；请求字节形状、状态机、错误路径钉死。
 */
class PtpSessionTest {

    @Test
    fun `open sends OpenSession with tid zero`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 0),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(PtpSession.State.Open, s.currentState)
        assertArrayEquals(
            encodeRequest(OpCode.OPEN_SESSION, 0, longArrayOf(1)),
            t.observedWrites()[0],
        )
    }

    @Test
    fun `open with error response throws and stays Closed`() {
        val t = FakeTransport(listOf(resp(RespCode.SESSION_NOT_OPEN, tid = 0)))
        val s = PtpSession(t)
        val ex = assertThrows(PtpResponseException::class.java) { s.open() }
        assertEquals(RespCode.SESSION_NOT_OPEN.toLong(), ex.code.toLong())
        assertEquals(PtpSession.State.Closed, s.currentState)
        assertEquals(true, s.log.snapshot().any { it.contains("ERR") })
    }

    @Test
    fun `session already opened (0x201E) is tolerated and session proceeds`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.SESSION_ALREADY_OPENED, tid = 0),
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
            ),
        )
        val s = PtpSession(t)
        s.open() // 不抛：沿用相机侧会话
        assertEquals(PtpSession.State.Open, s.currentState)
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(PtpSession.State.Ready, s.currentState)
        assertArrayEquals(
            encodeRequest(OpCode.GET_DEVICE_INFO, 1),
            t.observedWrites()[1],
        )
    }

    @Test
    fun `response tid mismatch is tolerated with ring-log warning (nikon-style quirks)`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 97),                                  // OpenSession：tid 回 97 也只是警告
                dataPack(OpCode.GET_DEVICE_INFO, 98, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 99),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(PtpSession.State.Open, s.currentState)
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(true, s.log.snapshot().any { it.contains("TID mismatch") })
    }

    @Test
    fun `get model parses DeviceInfo after session and reaches Ready`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 0),
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(PtpSession.State.Ready, s.currentState)
        assertArrayEquals(
            encodeRequest(OpCode.GET_DEVICE_INFO, 1),
            t.observedWrites()[1],
        )
    }

    @Test
    fun `list storage returns storage ids`() {
        val storageIds = FakeCamera.u32Array(1, 0x00010001)
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 0),
                dataPack(OpCode.GET_STORAGE_IDS, 1, storageIds),
                resp(RespCode.OK, 1),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(listOf(1L, 0x00010001L), s.listStorage().map { it.id })
        assertArrayEquals(
            encodeRequest(OpCode.GET_STORAGE_IDS, 1),
            t.observedWrites()[1],
        )
    }

    @Test
    fun `protocol calls before session open are rejected`() {
        val s = PtpSession(FakeTransport(listOf()))
        assertThrows(IllegalStateException::class.java) { s.getModel() }
        assertThrows(IllegalStateException::class.java) { s.listStorage() }
    }

    @Test
    fun `zero-length terminator packets do not break reads`() {
        // OpenSession 应答 = 12 字节整（若协议场景要求 ZLP 终止，fake 用脚本模拟零长包）
        val whole = resp(RespCode.OK, tid = 0)
        val t = FakeTransport(
            listOf(
                whole.copyOfRange(0, 5),
                ByteArray(0), // 模拟零长包（ZLP）——必须继续读而不是报死
                whole.copyOfRange(5, whole.size),
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
            ),
        )
        val s = PtpSession(t)
        s.open() // 零长包被容忍，重组继续；state Open
        assertEquals(PtpSession.State.Open, s.currentState)
        assertEquals("NIKON Z 30", s.getModel())
    }

    @Test
    fun `close sends CloseSession with tid after open and everything is dead`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 0),
                resp(RespCode.OK, tid = 1), // 用户 close 的应答（tid=1）
            ),
        )
        val s = PtpSession(t)
        s.open()
        s.close()
        assertArrayEquals(
            encodeRequest(OpCode.CLOSE_SESSION, 1),
            t.observedWrites()[1], // 写序：open 0 / user close 1
        )
        assertThrows(IllegalStateException::class.java) { s.open() }
    }

    // ---------- 真机问题回迁的回归测试 ----------

    @Test
    fun `failed transaction still consumes its transaction id`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 0),                              // open 成功
                resp(RespCode.SESSION_NOT_OPEN, tid = 1),                // listStorage 收到错误
                resp(RespCode.OK, tid = 2),                              // close 用 t2 发请求（tid 消费不重用）
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertThrows(PtpResponseException::class.java) { s.listStorage() }
        s.close()
        assertArrayEquals(
            encodeRequest(OpCode.CLOSE_SESSION, 2),
            t.observedWrites()[2], // open 0 / storage 1 / user close 2
        )
    }

    @Test
    fun `hostile container length claim is rejected without allocation`() {
        val opData = container(ContainerType.DATA, OpCode.GET_DEVICE_INFO, 1, byteArrayOf())
        opData[0] = 0x7F.toByte(); opData[1] = 0x7F.toByte(); opData[2] = 0x7F.toByte(); opData[3] = 0x7F.toByte()
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 0),   // OpenSession 应答
                opData,                       // getModel 的 Data 容器长度被篡改
            ),
        )
        val s = PtpSession(t)
        s.open()
        val ex = assertThrows(IllegalStateException::class.java) { s.getModel() }
        assertEquals(true, ex.message!!.contains("sane range"))
    }

    @Test
    fun `hostile ZLP flood cannot wedge reads`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 0),
                ByteArray(0), ByteArray(0), ByteArray(0),
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals("NIKON Z 30", s.getModel())
    }

    @Test
    fun `device info absurd code array count is rejected`() {
        val raw = PtpSessionTest.buildBytes {
            write(FakeCamera.u16(0x0100))
            write(FakeCamera.u32(6L))
            write(FakeCamera.u16(0x0100))
            write(FakeCamera.mtpString("m"))
            write(FakeCamera.u16(0))
            write(FakeCamera.u32(0x20000000))
        }
        val ex = assertThrows(IllegalStateException::class.java) { parseDeviceInfo(raw) }
        assertTrue(ex.message!!.contains("sane range"))
    }

    companion object {
        /** 多字节写入帮助（ByteArrayOutputStream 的快捷形） */
        private fun buildBytes(block: java.io.ByteArrayOutputStream.() -> Unit): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            block(out)
            return out.toByteArray()
        }
    }
}
