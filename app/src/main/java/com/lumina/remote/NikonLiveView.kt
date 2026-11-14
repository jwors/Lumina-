package com.lumina.remote

/**
 * 尼康实时取景帧的解析。
 *
 * 0x9203（GetLiveViewImage legacy）返回的数据通常带一段尼康头（含 AF 区域/几何），
 * JPEG 从 SOI `FF D8 FF` 开始。头格式随固件漂移，所以不按固定偏移切，直接找 SOI。
 */
object NikonLiveView {

    fun extractJpeg(frame: ByteArray): ByteArray? {
        if (frame.size < 3) return null
        if (frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte() && frame[2] == 0xFF.toByte()) {
            return frame
        }
        var i = 0
        val limit = frame.size - 3
        while (i <= limit) {
            if (frame[i] == 0xFF.toByte() &&
                frame[i + 1] == 0xD8.toByte() &&
                frame[i + 2] == 0xFF.toByte()
            ) {
                return frame.copyOfRange(i, frame.size)
            }
            i++
        }
        return null
    }
}
