package com.lumina.work

import java.io.File
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup

/**
 * M3-02：Work 渲染 GL 路线（.cube LUT 真渲染）。
 * 输入：JPEG Master + 已解析 CubeLut；输出：与 M2 CPU 路线同语义的 _w.jpg 字节（2048px/q88 基线保留）。
 * 失败语义：任何环节异常返回 null → 调用方回退 CPU ColorMatrix 路线（ProbeController.renderWorkFor 已布）。
 */
class WorkRendererGpu(private val context: android.content.Context) {

    fun bytes(src: File, lut: CubeLut): ByteArray? = runCatching {
        val bitmap = com.lumina.probe.ThumbDecode.sampled(src.absolutePath, target = 2048)
            ?: return@runCatching null
        val gpuImage = GPUImage(context)
        gpuImage.setImage(bitmap)
        val group = GPUImageFilterGroup()
        group.addFilter(Lut3DFilter(lut))
        gpuImage.setFilter(group)
        val out = gpuImage.bitmapWithFilterApplied
        val bos = java.io.ByteArrayOutputStream()
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, bos)
        bos.toByteArray()
    }.getOrNull()
}
