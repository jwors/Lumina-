package com.lumina.ptp

import java.nio.charset.StandardCharsets

/** MTP 数据集（DeviceInfo/ObjectInfo…）的字段游标读取器，全部小端 */
class MtpReader(private val bytes: ByteArray) {
    var pos = 0
        private set

    private fun take(n: Int): ByteArray {
        check(pos + n <= bytes.size) { "dataset truncated at $pos need $n have ${bytes.size}" }
        val out = bytes.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun u8(): Int = take(1)[0].toInt() and 0xFF

    fun u16(): Int {
        val b = take(2)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    fun u32(): Long {
        val b = take(4)
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    /** MTP string：uint8 长度（0 = 空）+ 若干 UTF-16LE 字元 */
    fun mtpString(): String {
        val len = u8()
        if (len == 0) return ""
        return String(take(len * 2), StandardCharsets.UTF_16LE).trimEnd('\u0000')
    }

    /** 代码类数组：uint32 数量 + 数量个 uint16（操作/事件/设备属性列表共用） */
    fun u16Array(): List<Int> {
        val count = u32().toInt()
        // 相机谎报天文数字时第一时间拒绝，避免按错误计数分配集合
        check(count in 0..MAX_ARRAY_COUNT) { "code-array count $count out of sane range (max $MAX_ARRAY_COUNT)" }
        return List(count) { u16() }
    }

    companion object {
        /** 任意代码数组（DevicePropcodes 等）的合理上限。 */
        const val MAX_ARRAY_COUNT = 1 shl 16
    }
}
