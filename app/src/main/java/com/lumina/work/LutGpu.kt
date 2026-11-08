package com.lumina.work

import android.graphics.Bitmap

/**
 * M3-02：GPUImage 侧的设备专用装配（不可 JVM 单测；纯逻辑均已在前置件测试）。
 * 图集契约 = LutAtlas.rgbaBytes 的排布（x = g*N + r，y = b）。
 */
object LutGpu {

    /** 图集字节 → Bitmap（ARGB_8888 原样走 copyPixelsFromBuffer；pixel 序与 LutAtlas.rgbaBytes 严格一致）。 */
    fun atlasBitmap(lut: CubeLut): Bitmap {
        val n = lut.size
        val bmp = android.graphics.Bitmap.createBitmap(n * n, n, Bitmap.Config.ARGB_8888)
        val buffer = java.nio.ByteBuffer.wrap(LutAtlas.rgbaBytes(lut))
        bmp.copyPixelsFromBuffer(buffer)
        return bmp
    }
}
