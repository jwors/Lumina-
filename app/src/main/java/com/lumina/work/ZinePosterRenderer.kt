package com.lumina.work

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.random.Random

/**
 * M3-03：zine 海报化排版渲染（设备专用；几何真相在 PosterLayout，本文件只负责落笔）。
 * **2026-11-09 用户拍板：顶部文案带取消**——结构 = 奶油底 → 照片区（顶满 pad，中心裁 4:5）
 * → 底注带（date · 行山记）+ LUMINA 角字 + 颗粒覆盖（确定性种子，同图两次输出逐像素一致）。
 * caption 文本不进海报（它只用于分享配文 EXTRA_TEXT）；生成式化步归 M4。
 */
object ZinePosterRenderer {

    const val NOTE_SIZE = 30f

    fun render(src: Bitmap, note: String): Bitmap {
        val z = PosterLayout.zones()
        val out = Bitmap.createBitmap(PosterLayout.CANVAS_W, PosterLayout.CANVAS_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // 1) 奶油底（FilmBackground 同色系）
        canvas.drawColor(Color.rgb(0xF4, 0xF2, 0xEA))

        // 2) 照片区：中心裁 → 目标矩形绘制（自动缩放进相框；
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
}
