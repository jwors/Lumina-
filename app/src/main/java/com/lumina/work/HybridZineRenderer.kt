package com.lumina.work

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min
import kotlin.random.Random

/** 混合渲染的纯几何（JVM 可测）：照片在 1056x1760 画布里的位置与尺寸。 */
object HybridLayout {
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    fun photoBox(srcW: Int, srcH: Int, canvasW: Int = 1056, canvasH: Int = 1760): Box {
        if (srcW <= 0 || srcH <= 0) return Box(330, 380, 0, 0)
        val maxW = (canvasW * 0.66f).toInt()
        val maxH = (canvasH * 0.52f).toInt()
        val scale = min(maxW.toFloat() / srcW, maxH.toFloat() / srcH)
        val w = (srcW * scale).toInt().coerceAtLeast(1)
        val h = (srcH * scale).toInt().coerceAtLeast(1)
        return Box(canvasW - w - 50, 380, w, h)
    }
}

/**
 * 混合渲染：AI 只出「纸面/插画场」底图，照片本地贴回并做手撕边 + 排版。
 *
 * 这样在纯文生图端点（glm-image）上也能保住“真景为锚”——照片是原图，不是模型重画。
 */
object HybridZineRenderer {

    const val CANVAS_W = 1056
    const val CANVAS_H = 1760

    fun render(
        background: Bitmap,
        photo: Bitmap,
        title: String,
        dateKey: String,
        seed: Int = 20261112,
    ): Bitmap {
        val out = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        // 1) 底图 cover 铺满
        drawCover(canvas, background, paint)

        // 2) 照片：左侧手撕边
        val box = HybridLayout.photoBox(photo.width, photo.height)
        val left = box.left.toFloat()
        val top = box.top.toFloat()
        val right = (box.left + box.width).toFloat()
        val bottom = (box.top + box.height).toFloat()

        val rand = Random(seed)
        val path = Path().apply {
            moveTo(left, top)
            lineTo(right, top)
            lineTo(right, bottom)
            lineTo(left, bottom)
            // 左边自下而上做不规则撕口
            val steps = 46
            for (i in 1..steps) {
                val y = bottom - (i * box.height / steps).toFloat()
                val jitter = if (i % 9 == 0) -rand.nextInt(8, 25) else rand.nextInt(-9, 8)
                lineTo(left + jitter, y)
            }
            lineTo(left, top)
            close()
        }

        val save = canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(
            photo, null,
            RectF(left, top, right, bottom),
            paint,
        )
        canvas.restoreToCount(save)

        // 3) 撕口纤维：沿左边描一条奶油细线
        val fiber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.rgb(0xEE, 0xE9, 0xDA)
        }
        val edge = Path().apply {
            moveTo(left, top)
            for (i in 1..46) {
                val y = bottom - (i * box.height / 46).toFloat()
                val jitter = if (i % 9 == 0) -rand.nextInt(8, 25) else rand.nextInt(-9, 8)
                lineTo(left + jitter, y)
            }
        }
        canvas.drawPath(edge, fiber)

        // 4) 底部纸标签：保证文字对比，同时盖掉底图可能带的水印
        val bandTop = 1300f
        val band = Path().apply {
            moveTo(0f, bandTop)
            val steps = 40
            for (i in 1..steps) {
                val x = (i * CANVAS_W / steps).toFloat()
                val jitter = if (i % 7 == 0) rand.nextInt(-10, 12) else rand.nextInt(-5, 6)
                lineTo(x, bandTop + jitter)
            }
            lineTo(CANVAS_W.toFloat(), CANVAS_H.toFloat())
            lineTo(0f, CANVAS_H.toFloat())
            close()
        }
        canvas.drawPath(band, Paint().apply { color = Color.rgb(0xF5, 0xF1, 0xE4) })
        canvas.drawPath(
            band,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.rgb(0xE1, 0xE7, 0xDC)
            },
        )

        // 5) 文字：标题 / 日期 / 角标
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x1B, 0x23, 0x1C)
            textSize = 76f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x56, 0x62, 0x5A)
            textSize = 24f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x66, 0x72, 0x6A)
            textSize = 16f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val safeTitle = title.trim().ifBlank { "行山记" }
        canvas.drawText(safeTitle, 62f, 1450f, ink)
        canvas.drawText("$dateKey · 行山记", 66f, 1510f, soft)
        canvas.drawText("L U M I N A", 66f, 1630f, mark)

        return out
    }

    private fun drawCover(canvas: Canvas, src: Bitmap, paint: Paint) {
        val scale = maxOf(
            CANVAS_W.toFloat() / src.width,
            CANVAS_H.toFloat() / src.height,
        )
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        val srcRect = Rect(0, 0, src.width, src.height)
        val dst = RectF(
            ((CANVAS_W - w) / 2).toFloat(),
            ((CANVAS_H - h) / 2).toFloat(),
            ((CANVAS_W + w) / 2).toFloat(),
            ((CANVAS_H + h) / 2).toFloat(),
        )
        canvas.drawBitmap(src, srcRect, dst, paint)
    }
}
