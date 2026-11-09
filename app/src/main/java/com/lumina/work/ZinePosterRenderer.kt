package com.lumina.work

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.random.Random

/**
 * M3-03：zine 海报化排版渲染（设备专用；几何真相在 PosterLayout，本文件只负责落笔）。
 * 结构：奶油底 → 标题带（大字，≤2 行，吃 caption）→ 照片区（中心裁 4:5）→ 底注带（dateKey · 行山记）
 * + 颗粒覆盖（确定性种子，同图两次输出逐像素一致——可复现对比）。
 * 生成式化步（多章拼贴/网点/扎染）归 M4，本渲染器只出"单页 zine 海报观感"。
 */
object ZinePosterRenderer {

    const val TITLE_SIZE = 62f
    const val NOTE_SIZE = 30f

    fun render(src: Bitmap, title: String, note: String): Bitmap {
        val z = PosterLayout.zones()
        val out = Bitmap.createBitmap(PosterLayout.CANVAS_W, PosterLayout.CANVAS_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // 1) 奶油底（FilmBackground 同色系）
        canvas.drawColor(Color.rgb(0xF4, 0xF2, 0xEA))

        // 2) 标题带：大字 + 微字距，靠上两行，最长截断
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x20, 0x25, 0x1F)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = TITLE_SIZE
            letterSpacing = 0.04f
        }
        val shown = PosterLayout.clampTitle(PosterLayout.sanitizeTitle(title), maxChars = 18, ellipsis = false)
        var ty = z.titleTop + TITLE_SIZE
        val lines = wrap(shown, titlePaint, PosterLayout.CANVAS_W - z.pad * 2, maxLines = 2)
        for (line in lines) { canvas.drawText(line, z.pad.toFloat(), ty, titlePaint); ty += TITLE_SIZE * 1.22f }

        // 3) 照片区：中心裁 → 目标矩形绘制（自动缩放进相框；
        //    曾有 bug：drawBitmap(bmp, x, y) 按原尺寸粘贴，2048px 工作图溢出画布 → 截断 + 只见一角灰雾）
        val crop = PosterLayout.centerCrop(src.width, src.height, PosterLayout.CANVAS_W - z.pad * 2, z.photoHeight)
        val photo = Bitmap.createBitmap(src, crop.left, crop.top, crop.width, crop.height, null, false)
        val dest = android.graphics.RectF(
            z.pad.toFloat(), z.photoTop.toFloat(),
            (PosterLayout.CANVAS_W - z.pad).toFloat(), (z.photoTop + z.photoHeight).toFloat(),
        )
        val regionPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(photo, null, dest, regionPaint)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(0x20, 0x25, 0x1F)
            strokeWidth = 3f
        }
        canvas.drawRect(
            z.pad.toFloat(), z.photoTop.toFloat(),
            (PosterLayout.CANVAS_W - z.pad).toFloat(), (z.photoTop + z.photoHeight).toFloat(), border,
        )

        // 4) 底注带：日期 · 行山记（小字）+ 右下 Lumina 角字
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x76, 0x7F, 0x72)
            textSize = NOTE_SIZE
        }
        canvas.drawText(PosterLayout.clampTitle(note, 30, ellipsis = false), z.pad.toFloat(), (z.noteTop + z.noteHeight * 0.6f).toFloat(), notePaint)
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xB9, 0xB3, 0xA6)
            textSize = NOTE_SIZE * 0.8f
            typeface = Typeface.MONOSPACE
        }
        canvas.drawText("LUMINA", (PosterLayout.CANVAS_W - z.pad - 210).toFloat(), (z.noteTop + z.noteHeight * 0.6f).toFloat(), mark)

        // 5) 颗粒覆盖（确定性噪声 tile 4 次平铺，省内存且视觉均匀）
        val grain = grainTile(128)
        val grainPaint = Paint().apply { alpha = 14 }
        for (gx in 0 until 9) for (gy in 0 until 12) {
            canvas.drawBitmap(grain, (gx * 128 - 64).toFloat(), (gy * 128 - 64).toFloat(), grainPaint)
        }
        return out
    }

    /** 像素级随机噪点 tile（seed 固定 → 可复现）。 */
    private fun grainTile(size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val rand = Random(PosterLayout.GRAIN_SEED)
        val px = ByteArray(size * size) { if (rand.nextInt(8) == 0) (rand.nextInt(0x60)).toByte() else 0 }
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return b
    }

    /** 按像素宽断行（中文逐字 + 空格分词的简易版，v1 足够）。 */
    internal fun wrap(text: String, paint: Paint, maxWidth: Int, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        for (ch in text) {
            if (paint.measureText(cur.toString() + ch) > maxWidth && cur.isNotEmpty()) {
                lines.add(cur.toString()); cur = StringBuilder()
                if (lines.size == maxLines) break
            }
            if (lines.size < maxLines) cur.append(ch)
        }
        if (cur.isNotEmpty() && lines.size < maxLines) lines.add(cur.toString())
        return lines
    }
}
