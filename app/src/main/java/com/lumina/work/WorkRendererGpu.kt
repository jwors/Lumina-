package com.lumina.work

import android.graphics.Bitmap
import java.io.File
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup

/**
 * M3-03：Work 渲染 GL 路线（.cube LUT 真渲染）。
 * 输入：JPEG Master + 已解析 CubeLut；输出：与 M2 CPU 路线同语义的 _w.jpg 字节（2048px/q88 基线保留）。
 * 失败语义：任何环节异常**不再静默**——记录日志（构造注入 logger，落到 m0-probe.log/logcat）后
 * 返回 null → 调用方回退 CPU ColorMatrix 路线。2026-11-09：GL 静默失败曾被误当"滤镜没效果"。
 *
 * M3-07：`ThumbDecode.sampled` 只做 2 的幂降采样，两轴不同时 ≥2×目标时等于不降——6000×4000 会按
 * 24MP 出片。这里补一道等比缩放，把长边钉在 [MAX_SIDE]（q88 前提下文件体积与耗时都可预期）。
 */
class WorkRendererGpu(
    private val context: android.content.Context,
    private val log: (String) -> Unit = {},
) {

    fun bytes(src: File, lut: CubeLut, strength: Float = Lut3DFilter.STRENGTH_FULL): ByteArray? = runCatching {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val decoded = com.lumina.probe.ThumbDecode.sampled(src.absolutePath, target = MAX_SIDE)
            ?: return@runCatching null.also { log("work[gl]: decode fail") }
        val bitmap = fitLongestSide(decoded, MAX_SIDE)
        val gpuImage = GPUImage(context)
        gpuImage.setImage(bitmap)
        val group = GPUImageFilterGroup()
        group.addFilter(Lut3DFilter(lut, strength))
        gpuImage.setFilter(group)
        val out = gpuImage.bitmapWithFilterApplied
        val bos = java.io.ByteArrayOutputStream()
        val ok = out.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        log("work[gl]: ok=$ok in=${decoded.width}x${decoded.height} render=${bitmap.width}x${bitmap.height} out=${bos.size()}B elapsed=${ms}ms")
        bos.toByteArray()
    }.getOrElse { e ->
        log("work[gl]: FAILED ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    companion object {
        /** Work 长边上限（规格基线）。 */
        const val MAX_SIDE = 2048

        private fun fitLongestSide(src: Bitmap, maxSide: Int): Bitmap {
            val longest = maxOf(src.width, src.height)
            if (longest <= maxSide) return src
            val scale = maxSide.toFloat() / longest
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(src, w, h, true)
        }
    }
}
