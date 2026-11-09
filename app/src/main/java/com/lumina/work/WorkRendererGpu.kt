package com.lumina.work

import java.io.File
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup

/**
 * M3-03：Work 渲染 GL 路线（.cube LUT 真渲染）。
 * 输入：JPEG Master + 已解析 CubeLut；输出：与 M2 CPU 路线同语义的 _w.jpg 字节（2048px/q88 基线保留）。
 * 失败语义：任何环节异常**不再静默**——记录日志（构造注入 logger，落到 m0-probe.log/logcat）后
 * 返回 null → 调用方回退 CPU ColorMatrix 路线。2026-11-09：GL 静默失败曾被误当"滤镜没效果"。
 */
class WorkRendererGpu(
    private val context: android.content.Context,
    private val log: (String) -> Unit = {},
) {

    fun bytes(src: File, lut: CubeLut): ByteArray? = runCatching {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val bitmap = com.lumina.probe.ThumbDecode.sampled(src.absolutePath, target = 2048)
            ?: return@runCatching null.also { log("work[gl]: decode fail") }
        val gpuImage = GPUImage(context)
        gpuImage.setImage(bitmap)
        val group = GPUImageFilterGroup()
        group.addFilter(Lut3DFilter(lut))
        gpuImage.setFilter(group)
        val out = gpuImage.bitmapWithFilterApplied
        val bos = java.io.ByteArrayOutputStream()
        val ok = out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, bos)
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        log("work[gl]: ok=$ok in=${bitmap.width}x${bitmap.height} out=${bos.size()}B elapsed=${ms}ms")
        bos.toByteArray()
    }.getOrElse { e ->
        log("work[gl]: FAILED ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
