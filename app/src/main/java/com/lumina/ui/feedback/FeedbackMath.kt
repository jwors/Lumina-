package com.lumina.ui.feedback

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * 磁吸按钮的纯函数部分（可 JVM 单测，不依赖 Compose 运行时）。
 *
 * 按下点相对控件中心的偏移，按半宽/半高归一化后乘以最大吸力；
 * 结果再按向量长度限幅，保证斜向按角落也不会吸出对角线 1.41 倍。
 */
internal fun magneticOffset(
    press: Offset,
    size: IntSize,
    maxPullPx: Float,
): Offset {
    if (size.width <= 0 || size.height <= 0 || maxPullPx <= 0f) return Offset.Zero
    val center = Offset(size.width / 2f, size.height / 2f)
    val halfW = size.width / 2f
    val halfH = size.height / 2f
    val nx = ((press.x - center.x) / halfW).coerceIn(-1f, 1f)
    val ny = ((press.y - center.y) / halfH).coerceIn(-1f, 1f)
    val raw = Offset(nx * maxPullPx, ny * maxPullPx)
    val length = raw.getDistance()
    if (length <= maxPullPx || length == 0f) return raw
    return raw * (maxPullPx / length)
}

/**
 * 弹性开关的挤压系数：0 在两端，1 在中点。
 * 用来让滑块在移动途中横向拉长、纵向压扁，停下后恢复圆形。
 */
internal fun switchSquash(fraction: Float): Float {
    val f = abs(fraction.coerceIn(0f, 1f))
    return 4f * f * (1f - f)
}
