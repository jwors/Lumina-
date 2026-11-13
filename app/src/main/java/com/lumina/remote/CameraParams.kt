package com.lumina.remote

import com.lumina.ptp.CameraProp
import com.lumina.ptp.PtpSession
import com.lumina.ptp.PtpValue
import com.lumina.ptp.RespCode

/**
 * 遥控页展示的镜头参数（只读）。
 * 数值来自 PTP DevicePropDesc 的 CurrentValue；单位换算按 15740 规范：
 * F 值 ×100、焦距 1/100mm、快门 1/10000s、ISO/电量为原值。
 */
data class CameraParams(
    val focalLength: String? = null,
    val aperture: String? = null,
    val shutter: String? = null,
    val iso: String? = null,
    val battery: String? = null,
    val raw: Map<Int, Long> = emptyMap(),
) {
    val isEmpty: Boolean get() = focalLength == null && aperture == null &&
        shutter == null && iso == null && battery == null

    /** UI 参数卡的固定顺序（缺项显示 —）。 */
    fun rows(): List<Pair<String, String>> = listOf(
        "焦距" to (focalLength ?: "—"),
        "光圈" to (aperture ?: "—"),
        "快门" to (shutter ?: "—"),
        "ISO" to (iso ?: "—"),
        "电量" to (battery ?: "—"),
    )
}

object CameraParamsReader {

    /** 逐项读，单项不支持/超时不拖垮整页；返回已读到的部分。 */
    fun read(session: PtpSession): CameraParams {
        val values = LinkedHashMap<Int, Long>()
        for (code in CameraProp.shootingParams) {
            val desc = runCatching { session.getDevicePropDesc(code) }.getOrNull() ?: continue
            val num = (desc.current as? PtpValue.Num)?.value ?: continue
            values[code] = num
        }
        return CameraParams(
            focalLength = values[CameraProp.FOCAL_LENGTH]?.let(::formatFocalLength),
            aperture = values[CameraProp.F_NUMBER]?.let(::formatAperture),
            shutter = values[CameraProp.EXPOSURE_TIME]?.let(::formatShutter),
            iso = values[CameraProp.EXPOSURE_INDEX]?.let(::formatIso),
            battery = values[CameraProp.BATTERY_LEVEL]?.let(::formatBattery),
            raw = values,
        )
    }
}

/** 焦距：规范口径 1/100mm（5000 → 50mm）；小于 100 的值按已是 mm 兼容处理。 */
fun formatFocalLength(raw: Long): String {
    if (raw <= 0) return "—"
    val mm = if (raw >= 100) raw / 100.0 else raw.toDouble()
    return "${trimNumber(mm)}mm"
}

/** 光圈：规范口径 ×100（560 → f/5.6）；小于 10 的值按已是 F 值兼容处理。 */
fun formatAperture(raw: Long): String {
    if (raw <= 0) return "—"
    val f = if (raw >= 10) raw / 100.0 else raw.toDouble()
    return "f/${trimNumber(f)}"
}

/** 快门：规范口径 1/10000s（40 → 1/250；20000 → 2s）。 */
fun formatShutter(raw: Long): String {
    if (raw <= 0) return "—"
    val seconds = raw / 10000.0
    if (seconds >= 1.0) return "${trimNumber(seconds)}s"
    return "1/${Math.round(1.0 / seconds)}"
}

fun formatIso(raw: Long): String = if (raw <= 0) "—" else raw.toString()

fun formatBattery(raw: Long): String =
    if (raw in 0..100) "$raw%" else "—"

/** 5.60 → "5.6"；50.0 → "50"；1.25 → "1.3"（相机参数一位小数足够）。 */
internal fun trimNumber(v: Double): String {
    val rounded = Math.round(v * 10.0) / 10.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString()
    else String.format(java.util.Locale.US, "%.1f", rounded)
}

/** 快门失败码 → 用户能照做的人话（遥控页 toast/状态行）。 */
fun humanizeCaptureError(code: Int): String = when (code) {
    RespCode.DEVICE_BUSY -> "相机忙（正在写卡或对焦），等一下再按"
    RespCode.CAPTURE_ALREADY_TERMINATED -> "上一张还在收尾，稍等半秒"
    RespCode.ACCESS_DENIED -> "相机拒绝：确认相机不在回放/菜单界面"
    RespCode.SESSION_NOT_OPEN -> "会话已断开，点重连"
    RespCode.DEVICE_PROP_NOT_SUPPORTED -> "相机不支持该操作"
    else -> "相机返回错误 0x%04X".format(code)
}
