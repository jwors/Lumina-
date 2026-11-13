package com.lumina.work

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 从照片像素里取一个「高纯度主色」，整套整卷共用（纯函数，JVM 可测）。
 *
 * 做法：过滤低饱和/过暗像素，按色相分 24 桶，取权重最大的色相族并做圆周平均，
 * 再统一压到中等明度/较高饱和度，得到适合平涂色场的印刷色。
 */
object PhotoPalette {

    const val FALLBACK_HEX = "#C8A24A"

    private const val BINS = 24
    private const val MIN_SATURATION = 0.25
    private const val MIN_VALUE = 0.20

    fun accentHex(pixels: IntArray): String {
        if (pixels.isEmpty()) return FALLBACK_HEX
        val weight = DoubleArray(BINS)
        val sumSin = DoubleArray(BINS)
        val sumCos = DoubleArray(BINS)

        for (p in pixels) {
            val alpha = (p ushr 24) and 0xFF
            if (alpha < 128) continue
            val r = ((p shr 16) and 0xFF) / 255.0
            val g = ((p shr 8) and 0xFF) / 255.0
            val b = (p and 0xFF) / 255.0
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            if (max <= 0.0) continue
            val saturation = delta / max
            val value = max
            if (saturation < MIN_SATURATION || value < MIN_VALUE) continue

            val hue = when {
                delta == 0.0 -> 0.0
                max == r -> 60.0 * (((g - b) / delta) % 6.0)
                max == g -> 60.0 * (((b - r) / delta) + 2.0)
                else -> 60.0 * (((r - g) / delta) + 4.0)
            }.let { if (it < 0) it + 360.0 else it }

            val bin = ((hue / 360.0) * BINS).toInt().coerceIn(0, BINS - 1)
            val w = saturation * value
            weight[bin] += w
            val rad = Math.toRadians(hue)
            sumSin[bin] += sin(rad) * w
            sumCos[bin] += cos(rad) * w
        }

        val best = weight.indices.maxByOrNull { weight[it] } ?: return FALLBACK_HEX
        if (weight[best] <= 0.0) return FALLBACK_HEX

        var hue = Math.toDegrees(atan2(sumSin[best], sumCos[best]))
        if (hue < 0) hue += 360.0
        val (r, g, b) = hsvToRgb(hue, saturation = 0.72, value = 0.82)
        return String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    }

    /** HSV→RGB（h 0..360，s/v 0..1），返回 0..255。 */
    fun hsvToRgb(h: Double, saturation: Double, value: Double): Triple<Int, Int, Int> {
        val c = value * saturation
        val x = c * (1 - abs((h / 60.0) % 2.0 - 1))
        val m = value - c
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return Triple(
            ((r1 + m) * 255).toInt().coerceIn(0, 255),
            ((g1 + m) * 255).toInt().coerceIn(0, 255),
            ((b1 + m) * 255).toInt().coerceIn(0, 255),
        )
    }
}
