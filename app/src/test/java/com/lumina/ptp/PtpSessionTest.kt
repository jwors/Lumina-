package com.lumina.ptp

import com.lumina.ptp.FakeCamera.container
import com.lumina.ptp.FakeCamera.dataPack
import com.lumina.ptp.FakeCamera.deviceInfo
import com.lumina.ptp.FakeCamera.resp
import com.lumina.transport.FakeTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 02 号工单验收：协议引擎对"伪相机字节流"的行为钉死——
 * 请求字节形状、交易号递增、状态机推进、错误响应、容器分片重组。
 * 字节构建器统一来自 FakeCamera（真机口径，含字符串终结 NUL）。
 */
class PtpSessionTest {

    // ---------- 行为测试 ----------

    @Test
    fun `open encodes OpenSession request and reaches Open`() {
        val t = FakeTransport(listOf(resp(RespCode.OK, tid = 1)))
        val s = PtpSession(t)
        s.open()
        assertEquals(PtpSession.State.Open, s.currentState)
        assertArrayEquals(
            encodeRequest(OpCode.OPEN_SESSION, 1, longArrayOf(1)),
            t.observedWrites()[0],
        )
    }

    @Test
    fun `open with error response throws and stays Closed`() {
        val t = FakeTransport(listOf(resp(RespCode.SESSION_NOT_OPEN, tid = 1)))
        val s = PtpSession(t)
        val ex = assertThrows(PtpResponseException::class.java) { s.open() }
        assertEquals(RespCode.SESSION_NOT_OPEN.toLong(), ex.code.toLong())
        assertEquals(PtpSession.State.Closed, s.currentState)
        // 失败出场也要留迹：日志里应有 ERR 行（code-review Spec 轴 finding）
        assertEquals(2, s.log.snapshot().size)
        assertEquals(true, s.log.snapshot()[1].contains("ERR"))
    }

    @Test
    fun `get model parses DeviceInfo and reaches Ready`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 1),
                dataPack(OpCode.GET_DEVICE_INFO, tid = 2, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, tid = 2),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(PtpSession.State.Ready, s.currentState)
        // 交易号递增：第二个事务 tid=2
        assertArrayEquals(
            encodeRequest(OpCode.GET_DEVICE_INFO, 2),
            t.observedWrites()[1],
        )
    }

    @Test
    fun `list storage returns storage ids`() {
        // u32Array 自带 uint32 计数头：count=2 + [1, 0x00010001]
        val storageIds = FakeCamera.u32Array(1, 0x00010001)
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 1),
                dataPack(OpCode.GET_STORAGE_IDS, tid = 2, storageIds),
                resp(RespCode.OK, tid = 2),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertEquals(listOf(1L, 0x00010001L), s.listStorage().map { it.id })
    }

    @Test
    fun `protocol calls before session open are rejected`() {
        val s = PtpSession(FakeTransport(listOf()))
        assertThrows(IllegalStateException::class.java) { s.getModel() }
        assertThrows(IllegalStateException::class.java) { s.listStorage() }
    }

    @Test
    fun `response tid mismatch is rejected`() {
        val t = FakeTransport(listOf(resp(RespCode.OK, tid = 99)))
        val s = PtpSession(t)
        assertThrows(IllegalStateException::class.java) { s.open() }
    }

    @Test
    fun `fragmented container across reads is reassembled`() {
        val whole = resp(RespCode.OK, tid = 1)
        val cut = 5 // 切在头中间
        val t = FakeTransport(listOf(whole.copyOfRange(0, cut), whole.copyOfRange(cut, whole.size)))
        PtpSession(t).open() // 必须无异常通过
    }

    @Test
    fun `close sends CloseSession and after close everything is dead`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 1),
                resp(RespCode.OK, tid = 2),
            ),
        )
        val s = PtpSession(t)
        s.open()
        s.close()
        assertArrayEquals(
            encodeRequest(OpCode.CLOSE_SESSION, 2),
            t.observedWrites()[1],
        )
        assertThrows(IllegalStateException::class.java) { s.open() }
    }

    @Test
    fun `session already opened (0x201E) is tolerated and session proceeds`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.SESSION_ALREADY_OPENED, tid = 1), // 相机残留会话
                dataPack(OpCode.GET_DEVICE_INFO, tid = 2, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, tid = 2),
            ),
        )
        val s = PtpSession(t)
        s.open() // 不抛：沿用相机侧会话
        assertEquals(PtpSession.State.Open, s.currentState)
        assertEquals("NIKON Z 30", s.getModel())
        assertEquals(PtpSession.State.Ready, s.currentState)
        // tid 已被 OpenSession 事务消费（递增到 2）
        assertArrayEquals(
            encodeRequest(OpCode.GET_DEVICE_INFO, 2),
            t.observedWrites()[1],
        )
    }

    // ---------- code-review 修复的回归测试 ----------

    @Test
    fun `failed transaction still consumes its transaction id`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 1),                    // open 成功
                resp(RespCode.SESSION_NOT_OPEN, tid = 2),      // getModel 的响应收到错误
                resp(RespCode.OK, tid = 3),                    // close 用 tid=3 发请求
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertThrows(PtpResponseException::class.java) { s.getModel() }
        s.close() // CloseSession tid=3（失败不重用 tid）
        assertArrayEquals(
            encodeRequest(OpCode.CLOSE_SESSION, 3),
            t.observedWrites()[2],
        )
    }

    @Test
    fun `hostile container length claim is rejected without allocation`() {
        val opData = container(ContainerType.DATA, OpCode.GET_DEVICE_INFO, 2, byteArrayOf())
        // 篡改长度字段为天文数字
        opData[0] = 0x7F.toByte(); opData[1] = 0x7F.toByte(); opData[2] = 0x7F.toByte(); opData[3] = 0x7F.toByte()
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, tid = 1),
                opData, // 声明 ~21 亿字节
                resp(RespCode.OK, tid = 2),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertOverridesMax(s)
    }

    private fun assertOverridesMax(s: PtpSession) {
        val ex = assertThrows(IllegalStateException::class.java) { s.getModel() }
        assertEquals(true, ex.message!!.contains("sane range"))
    }
}
