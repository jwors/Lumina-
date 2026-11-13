package com.lumina.remote

import com.lumina.ptp.CameraProp
import com.lumina.ptp.FakeCamera
import com.lumina.ptp.OpCode
import com.lumina.ptp.PtpDataType
import com.lumina.ptp.PtpSession
import com.lumina.ptp.RespCode
import com.lumina.transport.FakeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 工单 02：参数单位换算与"缺项不拖垮整页"的读取策略。 */
class CameraParamsTest {

    @Test
    fun `focal length follows 1-100mm spec with mm fallback`() {
        assertEquals("50mm", formatFocalLength(5000))
        assertEquals("16mm", formatFocalLength(1600))
        assertEquals("50mm", formatFocalLength(50))
        assertEquals("—", formatFocalLength(0))
    }

    @Test
    fun `aperture follows x100 spec`() {
        assertEquals("f/5.6", formatAperture(560))
        assertEquals("f/3.5", formatAperture(350))
        assertEquals("f/8", formatAperture(800))
        assertEquals("—", formatAperture(0))
    }

    @Test
    fun `shutter formats fractions and whole seconds`() {
        assertEquals("1/250", formatShutter(40))
        assertEquals("1/80", formatShutter(125))
        assertEquals("1s", formatShutter(10000))
        assertEquals("2s", formatShutter(20000))
        assertEquals("1.3s", formatShutter(13000))
        assertEquals("—", formatShutter(0))
    }

    @Test
    fun `iso and battery are plain`() {
        assertEquals("400", formatIso(400))
        assertEquals("—", formatIso(0))
        assertEquals("82%", formatBattery(82))
        assertEquals("—", formatBattery(101))
    }

    @Test
    fun `rows keep fixed order with em dash for missing`() {
        val rows = CameraParams(focalLength = "50mm").rows()
        assertEquals(
            listOf("焦距", "光圈", "快门", "ISO", "电量"),
            rows.map { it.first },
        )
        assertEquals("50mm", rows[0].second)
        assertEquals("—", rows[1].second)
    }

    @Test
    fun `reader maps five properties in one pass`() {
        val t = FakeTransport(
            listOf(
                FakeCamera.resp(RespCode.OK, 0),
                FakeCamera.dataPack(
                    OpCode.GET_DEVICE_PROP_DESC, 1,
                    FakeCamera.devicePropDesc(CameraProp.FOCAL_LENGTH, PtpDataType.UINT32, 5000),
                ),
                FakeCamera.resp(RespCode.OK, 1),
                FakeCamera.dataPack(
                    OpCode.GET_DEVICE_PROP_DESC, 2,
                    FakeCamera.devicePropDesc(CameraProp.F_NUMBER, PtpDataType.UINT16, 560),
                ),
                FakeCamera.resp(RespCode.OK, 2),
                FakeCamera.dataPack(
                    OpCode.GET_DEVICE_PROP_DESC, 3,
                    FakeCamera.devicePropDesc(CameraProp.EXPOSURE_TIME, PtpDataType.UINT32, 40),
                ),
                FakeCamera.resp(RespCode.OK, 3),
                FakeCamera.dataPack(
                    OpCode.GET_DEVICE_PROP_DESC, 4,
                    FakeCamera.devicePropDesc(CameraProp.EXPOSURE_INDEX, PtpDataType.UINT16, 400),
                ),
                FakeCamera.resp(RespCode.OK, 4),
                FakeCamera.dataPack(
                    OpCode.GET_DEVICE_PROP_DESC, 5,
                    FakeCamera.devicePropDesc(CameraProp.BATTERY_LEVEL, PtpDataType.UINT8, 82),
                ),
                FakeCamera.resp(RespCode.OK, 5),
            ),
        )
        val s = PtpSession(t)
        s.open()
        val p = CameraParamsReader.read(s)
        assertEquals("50mm", p.focalLength)
        assertEquals("f/5.6", p.aperture)
        assertEquals("1/250", p.shutter)
        assertEquals("400", p.iso)
        assertEquals("82%", p.battery)
        assertTrue(p.raw.containsKey(CameraProp.F_NUMBER))
    }

    @Test
    fun `unsupported property is skipped not fatal`() {
        val t = FakeTransport(
            listOf(
                FakeCamera.resp(RespCode.OK, 0),
                FakeCamera.resp(RespCode.DEVICE_PROP_NOT_SUPPORTED, 1),
                FakeCamera.dataPack(
                    OpCode.GET_DEVICE_PROP_DESC, 2,
                    FakeCamera.devicePropDesc(CameraProp.F_NUMBER, PtpDataType.UINT16, 560),
                ),
                FakeCamera.resp(RespCode.OK, 2),
                FakeCamera.resp(RespCode.DEVICE_PROP_NOT_SUPPORTED, 3),
                FakeCamera.resp(RespCode.DEVICE_PROP_NOT_SUPPORTED, 4),
                FakeCamera.resp(RespCode.DEVICE_PROP_NOT_SUPPORTED, 5),
            ),
        )
        val s = PtpSession(t)
        s.open()
        val p = CameraParamsReader.read(s)
        assertEquals(null, p.focalLength)
        assertEquals("f/5.6", p.aperture)
        assertEquals("—", p.rows()[0].second)
    }

    @Test
    fun `capture errors humanized`() {
        assertEquals("相机忙（正在写卡或对焦），等一下再按", humanizeCaptureError(RespCode.DEVICE_BUSY))
        assertTrue(humanizeCaptureError(0x2999).contains("0x2999"))
    }
}
