package com.lumina.ptp

import com.lumina.ptp.FakeCamera.dataPack
import com.lumina.ptp.FakeCamera.deviceInfo
import com.lumina.ptp.FakeCamera.devicePropDesc
import com.lumina.ptp.FakeCamera.resp
import com.lumina.transport.FakeTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 工单 02/03 的协议层验收：
 * GetDevicePropDesc 自带类型解析（不猜属性类型）、InitiateCapture 参数形状、
 * 相机忙/不支持的错误路径。
 */
class DevicePropsTest {

    private fun readySession(vararg responses: ByteArray): Pair<PtpSession, FakeTransport> {
        val t = FakeTransport(listOf(*responses))
        val s = PtpSession(t)
        s.open()
        return s to t
    }

    @Test
    fun `getDevicePropDesc parses typed current value and sends property code`() {
        val (s, t) = readySession(
            resp(RespCode.OK, 0),
            dataPack(OpCode.GET_DEVICE_PROP_DESC, 1, devicePropDesc(CameraProp.FOCAL_LENGTH, PtpDataType.UINT32, 5000)),
            resp(RespCode.OK, 1),
        )
        val desc = s.getDevicePropDesc(CameraProp.FOCAL_LENGTH)
        assertEquals(CameraProp.FOCAL_LENGTH, desc.code)
        assertEquals(PtpDataType.UINT32, desc.dataType)
        assertEquals(5000L, (desc.current as PtpValue.Num).value)
        assertArrayEquals(
            encodeRequest(OpCode.GET_DEVICE_PROP_DESC, 1, longArrayOf(CameraProp.FOCAL_LENGTH.toLong())),
            t.observedWrites().last(),
        )
    }

    @Test
    fun `string property decodes utf16le`() {
        val (s, _) = readySession(
            resp(RespCode.OK, 0),
            dataPack(
                OpCode.GET_DEVICE_PROP_DESC, 1,
                devicePropDesc(0xD100, PtpDataType.STRING, 0, text = "NIKKOR Z DX 16-50"),
            ),
            resp(RespCode.OK, 1),
        )
        val desc = s.getDevicePropDesc(0xD100)
        assertEquals("NIKKOR Z DX 16-50", (desc.current as PtpValue.Text).value)
    }

    @Test
    fun `unsupported property surfaces camera error`() {
        val (s, _) = readySession(resp(RespCode.OK, 0), resp(RespCode.DEVICE_PROP_NOT_SUPPORTED, 1))
        val ex = org.junit.Assert.assertThrows(PtpResponseException::class.java) {
            s.getDevicePropDesc(CameraProp.BATTERY_LEVEL)
        }
        assertEquals(RespCode.DEVICE_PROP_NOT_SUPPORTED, ex.code)
    }

    @Test
    fun `initiateCapture uses nikon capture opcode with 0xFFFFFFFF storage`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 0),
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),
            ),
        )
        val s = PtpSession(t)
        s.open()
        s.getModel()
        s.initiateCapture()
        assertArrayEquals(
            encodeRequest(
                NikonOpCode.CAPTURE, 2,
                longArrayOf(0xFFFFFFFFL, 0L),
            ),
            t.observedWrites().last(),
        )
    }

    @Test
    fun `device busy on capture is a typed error the UI can humanize`() {
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 0),
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
                resp(RespCode.DEVICE_BUSY, 2),
            ),
        )
        val s = PtpSession(t)
        s.open()
        s.getModel()
        val ex = org.junit.Assert.assertThrows(PtpResponseException::class.java) { s.initiateCapture() }
        assertEquals(RespCode.DEVICE_BUSY, ex.code)
    }
}
