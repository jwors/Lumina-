package com.lumina.work

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.Executors
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * M3-07：滤镜台预览引擎。GL 上下文按线程归属，所以整个引擎独占一条线程：
 * 源图解码一次 → 每次换滤镜只 setFilter（不重建 GL）→ 取 Bitmap。
 * 两条引擎各持一份源图（主画布 ~屏宽、缩略条 ~240px），互不干扰。
 *
 * 失败语义：首次渲染失败就重建实例重试一次；仍失败返回 null 并留日志——调用方保持上一帧，
 * 不静默变成黑图（黑屏曾把"滤镜没效果"误导了一整轮，见 Lut3DFilter 注释）。
 */
class FilterPreviewEngine(
    private val context: Context,
    /** 源图解码目标边（像素） */
    private val targetPx: Int,
    private val log: (String) -> Unit = {},
) {
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lumina-filter-preview-$targetPx").apply { isDaemon = true }
    }
    private val dispatcher = exec.asCoroutineDispatcher()

    private var gpu: GPUImage? = null
    private var src: Bitmap? = null

    /** 载入源图并重建 GL 实例；失败返回 null（解码坏档）。 */
    suspend fun load(file: File): Bitmap? = withContext(dispatcher) {
        closeGl()
        val bmp = runCatching { com.lumina.probe.ThumbDecode.sampled(file.absolutePath, targetPx) }
            .getOrNull()
        if (bmp == null) {
            log("preview: decode fail ${file.name}")
            return@withContext null
        }
        src = bmp
        gpu = runCatching { GPUImage(context).apply { setImage(bmp) } }
            .onFailure { log("preview: gpu init fail ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        bmp
    }

    /** 按强度渲染一帧；null = 渲染失败（调用方保留上一帧）。 */
    suspend fun render(lut: CubeLut, strength: Float): Bitmap? = withContext(dispatcher) {
        val s = src ?: return@withContext null

        fun once(): Bitmap {
            val g = gpu ?: GPUImage(context).apply { setImage(s) }.also { gpu = it }
            val group = GPUImageFilterGroup().apply { addFilter(Lut3DFilter(lut, strength)) }
            g.setFilter(group)
            return g.bitmapWithFilterApplied
        }

        val first = runCatching { once() }
            .onFailure { log("preview: render fail ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        if (first != null) return@withContext first

        // 第二条命：GL 实例偶发一次失败后不可用，重建再试一次
        closeGl()
        runCatching { once() }
            .onFailure { log("preview: render retry fail ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
    }

    fun close() {
        runCatching { exec.execute { closeGl() } }
        runCatching { exec.shutdown() }
    }

    private fun closeGl() {
        runCatching { gpu?.deleteImage() }
        gpu = null
    }
}
