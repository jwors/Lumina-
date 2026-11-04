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
 * 02 号工单验收（真机回迁语义最终版）：
 * 连接序列 = pre-close 清相机残留（错误全容忍）→ GetDeviceInfo 探活 → OpenSession（宽容 0x201E）。
 * tid 不匹配 = 警告；请求字节形状、状态机、错误路径仍钉死。
 */
class PtpSessionTest {

    @Test
    fun `open sequence = preclose, probe, OpenSession`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),                                        // pre-close（恶意环境也容忍非 OK）
                dataPack(OpCode.GET_DEVICE_INFO, 2, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 2),
                resp(RespCode.OK, 3),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(PtpSession.State.Open, s.currentState)
        assertArrayEquals(encodeRequest(OpCode.CLOSE_SESSION, 1), t.observedWrites()[0])
        assertArrayEquals(encodeRequest(OpCode.GET_DEVICE_INFO, 2), t.observedWrites()[1])
        assertArrayEquals(encodeRequest(OpCode.OPEN_SESSION, 3, longArrayOf(1)), t.observedWrites()[2])
    }

    @Test
    fun `pre-close tolerated responses keep flow alive`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.SESSION_NOT_OPEN, 1),   // 本来就没有会话：正常清障结果
                dataPack(OpCode.GET_DEVICE_INFO, 2, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 2),
                resp(RespCode.OK, 3),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(PtpSession.State.Open, s.currentState)
        assertEquals(true, s.log.snapshot().any { it.contains("pre-close tolerated") })
    }

    @Test
    fun `open with error response throws and stays Closed`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),
                dataPack(OpCode.GET_DEVICE_INFO, 2, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 2),
                resp(RespCode.SESSION_NOT_OPEN, 3),
            ),
        )
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
                resp(RespCode.OK, 1),
                dataPack(OpCode.GET_DEVICE_INFO, 2, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 2),
                resp(RespCode.SESSION_ALREADY_OPENED, 3),
                dataPack(OpCode.GET_DEVICE_INFO, 4, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 4),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(PtpSession.State.Open, s.currentState)
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(PtpSession.State.Ready, s.currentState)
        assertArrayEquals(
            encodeRequest(OpCode.GET_DEVICE_INFO, 4),
            t.observedWrites()[3], // 写序：close 0 / probe 1 / open 2 / model 3
        )
    }

    @Test
    fun `response tid mismatch is tolerated with ring-log warning (nikon-style quirks)`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 97),                                   // pre-close（相机计数器由系统服务带动）
                dataPack(OpCode.GET_DEVICE_INFO, 98, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 99),
                resp(RespCode.OK, 100),
                dataPack(OpCode.GET_DEVICE_INFO, 101, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 102),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(PtpSession.State.Open, s.currentState)
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(true, s.log.snapshot().any { it.contains("TID mismatch") })
    }

    @Test
    fun `get model parses DeviceInfo and reaches Ready`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),
                dataPack(OpCode.GET_DEVICE_INFO, 2, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 2),
                resp(RespCode.OK, 3),
                dataPack(OpCode.GET_DEVICE_INFO, 4, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 4),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(PtpSession.State.Ready, s.currentState)
        assertArrayEquals(
            encodeRequest(OpCode.GET_DEVICE_INFO, 4),
            t.observedWrites()[3],
        )
    }

    @Test
    fun `list storage returns storage ids`() {
        val storageIds = FakeCamera.u32Array(1, 0x00010001)
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),
                dataPack(OpCode.GET_DEVICE_INFO, 2, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 2),
                resp(RespCode.OK, 3),
                dataPack(OpCode.GET_STORAGE_IDS, 4, storageIds),
                resp(RespCode.OK, 4),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(listOf(1L, 0x00010001L), s.listStorage().map { it.id })
        assertArrayEquals(
            encodeRequest(OpCode.GET_STORAGE_IDS, 4),
            t.observedWrites()[3],
        )
    }

    @Test
    fun `protocol calls before session open are rejected`() {
        val s = PtpSession(FakeTransport(listOf()))
        assertThrows(IllegalStateException::class.java) { s.getModel() }
        assertThrows(IllegalStateException::class.java) { s.listStorage() }
    }

    @Test
    fun `fragmented container across reads is reassembled`() {
        val whole = container(ContainerType.DATA, OpCode.GET_DEVICE_INFO, 2, deviceInfo("NIKON Z 30"))
        val cut = 5
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),                      // pre-close
                whole.copyOfRange(0, cut),
                whole.copyOfRange(cut, whole.size),
                resp(RespCode.OK, 2),
                resp(RespCode.OK, 3),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(PtpSession.State.Open, s.currentState)
    }

    @Test
    fun `close sends CloseSession and after close everything is dead`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),
                dataPack(OpCode.GET_DEVICE_INFO, 2, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 2),
                resp(RespCode.OK, 3),
                resp(RespCode.OK, 4),                      // close 的应答
            ),
        )
        val s = PtpSession(t)
        s.open()
        s.close()
        assertArrayEquals(
            encodeRequest(OpCode.CLOSE_SESSION, 4),
            t.observedWrites()[3], // 写序：close 0 / probe 1 / open 2 / 用户 close 3
        )
        assertThrows(IllegalStateException::class.java) { s.open() }
    }

    // ---------- 真机问题回迁的回归测试 ----------

    @Test
    fun `failed transaction still consumes its transaction id`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),
                dataPack(OpCode.GET_DEVICE_INFO, 2, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 2),
                resp(RespCode.OK, 3),               // open 成功
                resp(RespCode.SESSION_NOT_OPEN, 4), // getModel 收到错误
                resp(RespCode.OK, 5),               // close 用 t5 发请求
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertThrows(PtpResponseException::class.java) { s.getModel() }
        s.close() // 失败不重用 tid
        assertArrayEquals(
            encodeRequest(OpCode.CLOSE_SESSION, 5),
            t.observedWrites()[4], // close 0 / probe 1 / open 2 / model 3 / user close 4
        )
    }

    @Test
    fun `hostile container length claim is rejected without allocation`() {
        val opData = container(ContainerType.DATA, OpCode.GET_DEVICE_INFO, 2, byteArrayOf())
        opData[0] = 0x7F.toByte(); opData[1] = 0x7F.toByte(); opData[2] = 0x7F.toByte(); opData[3] = 0x7F.toByte()
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 1),   // pre-close 应答（先消耗）
                opData,                 // 探活的 Data 容器长度被篡改
            ),
        )
        val s = PtpSession(t)
        val ex = assertThrows(IllegalStateException::class.java) { s.open() }
        assertEquals(true, ex.message!!.contains("sane range"))
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
