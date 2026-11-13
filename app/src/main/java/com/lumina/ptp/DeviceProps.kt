package com.lumina.ptp

/** PTP 数据类型码（15740 表 4） */
object PtpDataType {
    const val INT8 = 0x0001
    const val UINT8 = 0x0002
    const val INT16 = 0x0003
    const val UINT16 = 0x0004
    const val INT32 = 0x0005
    const val UINT32 = 0x0006
    const val INT64 = 0x0007
    const val UINT64 = 0x0008
    const val STRING = 0xFFFF
}

/** 一个 PTP 数据值（设备属性当前值/默认值）。数值统一用 Long 承载，避免有无符号两套。 */
sealed interface PtpValue {
    data class Num(val value: Long) : PtpValue
    data class Text(val value: String) : PtpValue
    data class Blob(val bytes: ByteArray) : PtpValue {
        override fun equals(other: Any?): Boolean =
            other is Blob && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/**
 * DevicePropDesc 数据集（15740 表 12）：相机自报属性类型 + 可选值形态 + 当前值。
 * 读它而不是按属性号硬编码类型——Z30 的厂商属性类型可能与规范漂移。
 */
data class DevicePropDesc(
    val code: Int,
    val dataType: Int,
    val getSet: Int,
    val factoryDefault: PtpValue,
    val current: PtpValue,
    val formFlag: Int,
)

/** GetSet 位（表 12）：bit0 = 可读，bit1 = 可写 */
object PropGetSet {
    const val READ = 0x01
    const val WRITE = 0x02
}

/**
 * 常用设备属性号。遥控页只读展示，不写回。
 * 单位按 PTP 规范：F 值 ×100、焦距 1/100mm、快门 1/10000s、ISO 原值、电量百分比。
 */
object CameraProp {
    const val BATTERY_LEVEL = 0x5001
    const val F_NUMBER = 0x5007
    const val FOCAL_LENGTH = 0x5008
    const val EXPOSURE_TIME = 0x500D
    const val EXPOSURE_INDEX = 0x500F
    const val STILL_CAPTURE_MODE = 0x5013

    val shootingParams = listOf(FOCAL_LENGTH, F_NUMBER, EXPOSURE_TIME, EXPOSURE_INDEX, BATTERY_LEVEL)

    fun name(code: Int): String = when (code) {
        BATTERY_LEVEL -> "电量"
        F_NUMBER -> "光圈"
        FOCAL_LENGTH -> "焦距"
        EXPOSURE_TIME -> "快门"
        EXPOSURE_INDEX -> "ISO"
        STILL_CAPTURE_MODE -> "拍摄模式"
        else -> "0x%04X".format(code)
    }
}

fun parseDevicePropDesc(bytes: ByteArray): DevicePropDesc {
    val r = MtpReader(bytes)
    val code = r.u16()
    val type = r.u16()
    val getSet = r.u8()
    val factory = readTypedValue(r, type)
    val current = readTypedValue(r, type)
    val form = if (r.remaining > 0) r.u8() else 0
    return DevicePropDesc(code, type, getSet, factory, current, form)
}

private fun readTypedValue(r: MtpReader, type: Int): PtpValue = when (type) {
    PtpDataType.INT8 -> PtpValue.Num(r.u8().toByte().toLong())
    PtpDataType.UINT8 -> PtpValue.Num(r.u8().toLong())
    PtpDataType.INT16 -> PtpValue.Num(r.u16().toShort().toLong())
    PtpDataType.UINT16 -> PtpValue.Num(r.u16().toLong())
    PtpDataType.INT32 -> PtpValue.Num(r.u32().toInt().toLong())
    PtpDataType.UINT32 -> PtpValue.Num(r.u32())
    PtpDataType.INT64, PtpDataType.UINT64 -> PtpValue.Num(r.u64())
    PtpDataType.STRING -> PtpValue.Text(r.mtpString())
    else -> throw IllegalArgumentException("unsupported PTP data type 0x%04X".format(type))
}
