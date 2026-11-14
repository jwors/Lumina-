package com.lumina.ble

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 0x2006 CURRENT_TIME 的 10 字节载荷（小端）：
 * 年 u16 + 月/日/时/分/秒（UTC）+ DST(恒 0) + 时区时/分（有符号）。
 * 除了校时，它也是 BLE 会话的心跳——相机长时间无 BLE 流量会掐链/收热点。
 */
object NikonTimePayload {
    const val LENGTH = 10

    fun build(nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val zoned = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        val utc = zoned.withZoneSameInstant(ZoneOffset.UTC)
        val offsetMinutes = zoned.offset.totalSeconds / 60
        // 向零截断，保证 -3:30 为 (-3, -30)
        val hours = offsetMinutes / 60
        val minutes = offsetMinutes % 60
        val out = ByteArray(LENGTH)
        out[0] = (utc.year and 0xFF).toByte()
        out[1] = ((utc.year ushr 8) and 0xFF).toByte()
        out[2] = utc.monthValue.toByte()
        out[3] = utc.dayOfMonth.toByte()
        out[4] = utc.hour.toByte()
        out[5] = utc.minute.toByte()
        out[6] = utc.second.toByte()
        out[7] = 0 // DST：时区字节已含 DST，这里恒 0
        out[8] = hours.toByte()
        out[9] = minutes.toByte()
        return out
    }
}
