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
 * 02 号工单验收（含真机回迁的三项宽容语义）：
 * 协议顺序 = GetDeviceInfo 探活 → OpenSession → 型号查询 → 存储清单。
 * 字节构建器统一来自 FakeCamera（真机口径，含字符串终结 NUL）。
 */
class PtpSessionTest {

    // ---------- 行为测试 ----------

    @Test
    fun `open probes GetDeviceInfo then OpenSession, reaching Open`() {
        val t = FakeTransport(
            listOf(
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(PtpSession.State.Open, s.currentState)
        assertArrayEquals(
            encodeRequest(OpCode.GET_DEVICE_INFO, 1),
            t.observedWrites()[0],
        )
        assertArrayEquals(
            encodeRequest(OpCode.OPEN_SESSION, 2, longArrayOf(1)),
            t.observedWrites()[1],
        )
    }

    @Test
    fun `open with error response throws and stays Closed`() {
        val t = FakeTransport(
            listOf(
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
                resp(RespCode.SESSION_NOT_OPEN, 2), // OpenSession 被拒
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
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
                resp(RespCode.SESSION_ALREADY_OPENED, 2), // 相机残留会话
                dataPack(OpCode.GET_DEVICE_INFO, 3, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 3),
            ),
        )
        val s = PtpSession(t)
        s.open() // 不抛：沿用相机侧会话
        assertEquals(PtpSession.State.Open, s.currentState)
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(PtpSession.State.Ready, s.currentState)
        assertArrayEquals(
            encodeRequest(OpCode.GET_DEVICE_INFO, 3),
            t.observedWrites()[2],
        )
    }

    @Test
    fun `response tid mismatch is tolerated with ring-log warning (nikon-style quirks)`() {
        val t = FakeTransport(
            listOf(
                // 相机计数器不与主机对齐（系统服务先摇过相机）：tid 跨越但到达顺序正确
                dataPack(OpCode.GET_DEVICE_INFO, 98, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 99),
                resp(RespCode.OK, 100),
                dataPack(OpCode.GET_DEVICE_INFO, 101, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 102),
            ),
        )
        val s = PtpSession(t)
        s.open() // tid=98≠1 等全部只是警告
        assertEquals(PtpSession.State.Open, s.currentState)
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(true, s.log.snapshot().any { it.contains("TID mismatch") })
    }

    @Test
    fun `get model parses DeviceInfo and reaches Ready`() {
        val t = FakeTransport(
            listOf(
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),
                dataPack(OpCode.GET_DEVICE_INFO, 3, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 3),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(PtpSession.State.Ready, s.currentState)
        assertArrayEquals(
            encodeRequest(OpCode.GET_DEVICE_INFO, 3),
            t.observedWrites()[2], // 写序：探活 0 / OpenSession 1 / 型号 2
        )
    }

    @Test
    fun `list storage returns storage ids`() {
        val storageIds = FakeCamera.u32Array(1, 0x00010001)
        val t = FakeTransport(
            listOf(
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),
                dataPack(OpCode.GET_STORAGE_IDS, 3, storageIds),
                resp(RespCode.OK, 3),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(listOf(1L, 0x00010001L), s.listStorage().map { it.id })
        assertArrayEquals(
            encodeRequest(OpCode.GET_STORAGE_IDS, 3),
            t.observedWrites()[2],
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
        val whole = container(ContainerType.DATA, OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30"))
        val cut = 5 // 切在头中间
        val t = FakeTransport(
            listOf(
                whole.copyOfRange(0, cut),
                whole.copyOfRange(cut, whole.size),
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),
            ),
        )
        val s = PtpSession(t)
        s.open() // 探活 Data 跨两个 read 重组
        assertEquals(PtpSession.State.Open, s.currentState)
    }

    @Test
    fun `close sends CloseSession and after close everything is dead`() {
        val t = FakeTransport(
            listOf(
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),
                resp(RespCode.OK, 3),
            ),
        )
        val s = PtpSession(t)
        s.open()
        s.close()
        assertArrayEquals(
            encodeRequest(OpCode.CLOSE_SESSION, 3),
            t.observedWrites()[2],
        )
        assertThrows(IllegalStateException::class.java) { s.open() }
    }

    // ---------- 真机问题回迁的回归测试 ----------

    @Test
    fun `failed transaction still consumes its transaction id`() {
        val t = FakeTransport(
            listOf(
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),        // open 成功
                resp(RespCode.SESSION_NOT_OPEN, 3), // getModel 收到错误
                resp(RespCode.OK, 4),        // close 用 tid=4 发请求
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertThrows(PtpResponseException::class.java) { s.getModel() }
        s.close() // 失败不重用 tid
        assertArrayEquals(
            encodeRequest(OpCode.CLOSE_SESSION, 4),
            t.observedWrites()[3],
        )
    }

    @Test
    fun `hostile container length claim is rejected without allocation`() {
        val opData = container(ContainerType.DATA, OpCode.GET_DEVICE_INFO, 1, byteArrayOf())
        // 篡改长度字段为天文数字
        opData[0] = 0x7F.toByte(); opData[1] = 0x7F.toByte(); opData[2] = 0x7F.toByte(); opData[3] = 0x7F.toByte()
        val t = FakeTransport(
            listOf(
                opData, // 声明 ~21 亿字节
            ),
        )
        val s = PtpSession(t)
        val ex = assertThrows(IllegalStateException::class.java) { s.open() }
        assertEquals(true, ex.message!!.contains("sane range"))
    }

    @Test
    fun `device info absurd code array count is rejected`() {
        // 只造出能走到 Operationcodes 数组的最小 DeviceInfo 片段：
        // StandardVersion(u16) + VendorExtID(u32) + VendorExtVer(u16) + Desc(string) + FunctionalMode(u16)
        // 然后数组的 u32 计数字段谎报 0x20000000
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
        /** 多字节写入帮助（ByteArrayOutputStream 的 Kotlin 快捷形） */
        private fun buildBytes(block: java.io.ByteArrayOutputStream.() -> Unit): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            block(out)
            return out.toByteArray()
        }
    }
}
