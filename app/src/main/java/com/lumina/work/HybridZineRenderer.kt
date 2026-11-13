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

    fun photoBox(
        srcW: Int,
        srcH: Int,
        canvasW: Int = 1056,
        canvasH: Int = 1760,
        fieldSide: FieldSide = FieldSide.LEFT,
    ): Box {
        if (srcW <= 0 || srcH <= 0) return Box(330, 380, 0, 0)
        val maxW = (canvasW * 0.60f).toInt()
        val maxH = (canvasH * 0.52f).toInt()
        val scale = min(maxW.toFloat() / srcW, maxH.toFloat() / srcH)
        val w = (srcW * scale).toInt().coerceAtLeast(1)
        val h = (srcH * scale).toInt().coerceAtLeast(1)
        // 照片永远落在色场对侧：色场在左 → 照片靠右；色场在右 → 照片靠左。
        val left = if (fieldSide == FieldSide.LEFT) canvasW - w - 50 else 50
        return Box(left, 380, w, h)
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
        page: ZinePage? = null,
        seed: Int = 20261112,
    ): Bitmap {
        val out = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        // 1) 底图 cover 铺满
        drawCover(canvas, background, paint)

        // 2) 照片：色场对侧 + 手撕边；整卷页才垫奶油纸，单张旧输出保持不变
        val fieldSide = page?.fieldSide ?: FieldSide.LEFT
        val box = HybridLayout.photoBox(photo.width, photo.height, fieldSide = fieldSide)
        val left = box.left.toFloat()
        val top = box.top.toFloat()
        val right = (box.left + box.width).toFloat()
        val bottom = (box.top + box.height).toFloat()

        if (page != null) {
            val mattePad = 18f
            canvas.drawRect(
                left - mattePad, top - mattePad, right + mattePad, bottom + mattePad,
                Paint().apply { color = Color.rgb(0xF5, 0xF1, 0xE4); alpha = 232 },
            )
        }

        val rand = Random(seed)
        val tearLeft = fieldSide == FieldSide.LEFT
        val tearX = if (tearLeft) left else right
        val outward = if (tearLeft) -1f else 1f
        val steps = 46
        val edgePoints = ArrayList<android.graphics.PointF>(steps + 1)
        edgePoints.add(android.graphics.PointF(tearX, top))
        for (i in 1..steps) {
            val y = bottom - (i * box.height / steps).toFloat()
            val spike = if (i % 9 == 0) rand.nextInt(8, 25) else rand.nextInt(-9, 8)
            edgePoints.add(android.graphics.PointF(tearX + outward * spike, y))
        }

        val path = Path().apply {
            if (tearLeft) {
                moveTo(left, top)
                lineTo(right, top)
                lineTo(right, bottom)
                lineTo(left, bottom)
                for (p in edgePoints) lineTo(p.x, p.y)
            } else {
                moveTo(left, top)
                for (i in edgePoints.indices.reversed()) lineTo(edgePoints[i].x, edgePoints[i].y)
                lineTo(left, bottom)
            }
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

        // 3) 撕口纤维：主描边 + 半透明晕边 + 单根毛刺
        val fiber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.rgb(0xEE, 0xE9, 0xDA)
        }
        val fiberPath = Path().apply {
            moveTo(edgePoints[0].x, edgePoints[0].y)
            for (i in 1 until edgePoints.size) lineTo(edgePoints[i].x, edgePoints[i].y)
        }
        canvas.drawPath(fiberPath, Paint(fiber).apply { alpha = 90; strokeWidth = 7f })
        canvas.drawPath(fiberPath, fiber)
        val hairRand = Random(seed + 17)
        val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1.4f
            color = Color.rgb(0xEE, 0xE9, 0xDA)
        }
        for (i in 2 until edgePoints.size step 3) {
            val p = edgePoints[i]
            val len = 4f + hairRand.nextInt(0, 12)
            hair.alpha = 120 + hairRand.nextInt(0, 90)
            canvas.drawLine(p.x, p.y, p.x + outward * len, p.y + hairRand.nextInt(-2, 3), hair)
        }

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
        val folio = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x56, 0x62, 0x5A)
            textSize = 18f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        // 整卷主色：在安静侧边缘画一条 6px 竖线，让“同主色”落成像素而不是只写在 prompt 里。
        page?.accentHex?.let { hex ->
            val accent = runCatching { Color.parseColor(hex) }.getOrNull()
            if (accent != null) {
                val barX = if (fieldSide == FieldSide.LEFT) CANVAS_W - 34f else 28f
                canvas.drawRect(barX, 380f, barX + 6f, 1290f, Paint().apply { color = accent })
            }
        }
        val issueTitle = page?.issueTitle?.takeIf { it.isNotBlank() } ?: "行山记"
        val safeTitle = title.trim().ifBlank { issueTitle }
        // 文字永远在安静侧：色场在左 → 文字右对齐；色场在右 → 文字左对齐。
        val textOnRight = page?.fieldSide == FieldSide.LEFT
        val textX = if (textOnRight) CANVAS_W - 62f else 62f
        if (textOnRight) {
            ink.textAlign = Paint.Align.RIGHT
            soft.textAlign = Paint.Align.RIGHT
            mark.textAlign = Paint.Align.RIGHT
            folio.textAlign = Paint.Align.LEFT
        }
        canvas.drawText(safeTitle, textX, 1450f, ink)
        canvas.drawText("$dateKey · 《$issueTitle》", textX, 1510f, soft)
        canvas.drawText("L U M I N A", textX, 1630f, mark)
        page?.let {
            canvas.drawText(it.label, if (textOnRight) 62f else CANVAS_W - 62f, 1630f, folio)
        }

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
