package com.lumina.work

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.File

/**
 * Work 渲染器（M2 预览级）：
 * 构造时给 src JPEG 文件 + 智能建议文本；render() 出滤镜后字节。
 * - 内置滤镜用 Android ColorMatrix/Canvas（离线、无外部依赖）
 * - .cube LUT 全量渲染 + GPU 加速 → M3（GitHub 调研结论已留 doc 注释）
 */
class WorkRenderer(
    srcFile: File,
    suggestText: String,
) {
    private val src: Bitmap = decode(srcFile) ?: error("Work渲染：无法解码源 JPEG ${srcFile.name}")
    private val out: Bitmap = src.copy(Bitmap.Config.ARGB_8888, true)

    init {
        val matrix = matrixFor(suggestText)
        val canvas = Canvas(out)
        val paint = Paint().apply { isFilterBitmap = true }
        matrix?.let { paint.colorFilter = ColorMatrixColorFilter(it) }
        canvas.drawBitmap(src, 0f, 0f, paint)

        // 锐利系：3x3 卷积轻强化
        val t = suggestText.lowercase()
        if (t.contains("锐利") || t.contains("sharp") || t.contains("风光")) {
            out.sharpen(0.35f)
        }
    }

    fun bytes(): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        return bos.toByteArray()
    }

    companion object {
        /** 目标文件布局：Work/<dateKey>/<原名去扩展>_w.jpg */
        fun workRelPath(dateKey: String, origName: String): String {
            val base = origName.substringBeforeLast('.')
            return "Work/$dateKey/${base}_w.jpg"
        }

        /** 滤镜矩阵选择（null = 不套色矩阵只做锐化塑形） */
        internal fun matrixFor(suggestText: String): ColorMatrix? {
            val t = suggestText.lowercase()
            return when {
                t.contains("黑白") || t.contains("bw") || t.contains("black") ->
                    ColorMatrix().apply { setSaturation(0f) }
                t.contains("暖") || t.contains("sepia") || t.contains("胶片暖") ->
                    ColorMatrix(
                        floatArrayOf(
                            1.08f, 0.02f, 0f, 0f, 0.02f,
                            0.02f, 1.02f, 0f, 0f, 0f,
                            0f, 0.02f, 0.88f, 0f, 0.03f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    )
                else -> null
            }
        }

        private fun decode(srcFile: File): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 2048 && bounds.outHeight / (sample * 2) >= 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeFile(srcFile.absolutePath, opts)
        }
    }
}

/** 位图轻锐化（3x3 卷积，跨通道合一）。 */
private fun Bitmap.sharpen(strength: Float) {
    val w = width
    val h = height
    val px = IntArray(w * h)
    getPixels(px, 0, w, 0, 0, w, h)
    val out = IntArray(w * h)
    val c = 4 + 4 * strength
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val i = y * w + x
            val srcPixel = px[i]
            var rgb = 0
            for (ch in 0..2) {
                val shift = ch * 8
                val center = (srcPixel shr shift) and 0xFF
                val around =
                    ((px[i - 1] shr shift) and 0xFF) + ((px[i + 1] shr shift) and 0xFF) +
                        ((px[i - w] shr shift) and 0xFF) + ((px[i + w] shr shift) and 0xFF)
                val v = (center * c + 128 * 4 * strength / 4 - around * strength).toInt().coerceIn(0, 255)
                rgb = rgb or (v shl shift)
            }
            out[i] = (srcPixel and 0xFF000000.toInt()) or rgb
        }
    }
    setPixels(out, 0, w, 0, 0, w, h)
}
