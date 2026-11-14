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

    @Test
    fun `ptp opcodes match the 15740 table`() {
        assertEquals(0x1014, OpCode.GET_DEVICE_PROP_DESC)
        assertEquals(0x1015, OpCode.GET_DEVICE_PROP_VALUE)
        assertEquals(0x1016, OpCode.SET_DEVICE_PROP_VALUE)
        assertEquals(0x100E, OpCode.INITIATE_CAPTURE)
        assertEquals(0x9207, NikonOpCode.CAPTURE)
        assertEquals(0x9201, NikonOpCode.START_LIVE_VIEW)
        assertEquals(0x9203, NikonOpCode.GET_LIVE_VIEW_IMAGE)
    }

    @Test
    fun `setDevicePropValue sends code as param and value as data-out`() {
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
        s.setDevicePropValue(CameraProp.F_NUMBER, PtpDataType.UINT16, 560)
        assertArrayEquals(
            encodeRequest(OpCode.SET_DEVICE_PROP_VALUE, 2, longArrayOf(CameraProp.F_NUMBER.toLong())),
            t.observedWrites().last(),
        )
        assertArrayEquals(byteArrayOf(0x30, 0x02), t.observedData().single())
    }

    @Test
    fun `setDevicePropValue requires an open session`() {
        val t = object : com.lumina.transport.Transport {
            override fun open() = Unit
            override fun write(chunk: ByteArray): Int = chunk.size
            override fun read(buffer: java.nio.ByteBuffer): Int = 0
            override fun close() = Unit
        }
        val s = PtpSession(t)
        // 直接构造会话状态不方便；这里只验证类型检查会在 Ready 之前先报状态
        val ex = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            s.setDevicePropValue(CameraProp.F_NUMBER, PtpDataType.UINT16, 560)
        }
        assertEquals("setDevicePropValue requires Ready, state=Closed", ex.message)
    }

    @Test
    fun `getObject returns the raw payload`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 0),
                dataPack(OpCode.GET_OBJECT, 1, jpeg),
                resp(RespCode.OK, 1),
            ),
        )
        val s = PtpSession(t)
        s.open()
        assertArrayEquals(jpeg, s.getObject(42))
        assertArrayEquals(
            encodeRequest(OpCode.GET_OBJECT, 1, longArrayOf(42)),
            t.observedWrites().last(),
        )
    }

    @Test
    fun `picture control payload goes out with slot params`() {
        val payload = ByteArray(614) { 0x5A }
        val t = FakeTransport(
            listOf(
                resp(RespCode.OK, 0),
                dataPack(OpCode.GET_DEVICE_INFO, 1, deviceInfo("NIKON Z 30")),
                resp(RespCode.OK, 1),
                resp(RespCode.OK, 2),
                dataPack(NikonOpCode.GET_PIC_CTRL_DATA, 3, payload),
                resp(RespCode.OK, 3),
            ),
        )
        val s = PtpSession(t)
        s.open()
        s.getModel()
        s.setPicCtrlData(0xC9, payload)
        assertArrayEquals(
            encodeRequest(NikonOpCode.SET_PIC_CTRL_DATA, 2, longArrayOf(0xC9, 0)),
            t.observedWrites()[t.observedWrites().size - 1],
        )
        assertArrayEquals(payload, t.observedData().single())
        assertArrayEquals(payload, s.getPicCtrlData(0xC9))
        assertArrayEquals(
            encodeRequest(NikonOpCode.GET_PIC_CTRL_DATA, 3, longArrayOf(0xC9, 0)),
            t.observedWrites().last(),
        )
    }
}
