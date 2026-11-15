package com.lumina.ui.feedback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * 勾选/选片复选框：圆形填充 + 对勾逐段生长 + 可选粒子迸发。
 *
 * 对应参考里的「复选框动画 / Like Burst」：把正向情绪放在"选片"这个高频动作上，
 * 但粒子只在图库选片开启（相机列表批量勾选保持安静）。
 *
 * 视觉 22dp，触控 48dp（Android 无障碍最小触控区；与 Material 默认 Checkbox 一致）。
 */
@Composable
fun LuminaCheck(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    burst: Boolean = false,
    hapticOnToggle: Boolean = true,
    visualSize: Dp = 22.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    val progress = remember { Animatable(if (checked) 1f else 0f) }
    val burstProgress = remember { Animatable(0f) }
    var first by remember { mutableStateOf(true) }
    val indication = remember {
        // 白波纹在金色填充与暗色描边上都看得见；不随 checked 重建，避免打断扩散。
        ripple(color = Color.White)
    }

    LaunchedEffect(checked) {
        if (first) {
            progress.snapTo(if (checked) 1f else 0f)
            first = false
            return@LaunchedEffect
        }
        if (!LuminaUiPrefs.animationsEnabled()) {
            progress.snapTo(if (checked) 1f else 0f)
        } else {
            progress.animateTo(
                targetValue = if (checked) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = 720f),
            )
        }
        if (checked && burst && LuminaUiPrefs.animationsEnabled()) {
            launch {
                burstProgress.snapTo(0f)
                burstProgress.animateTo(1f, tween(durationMillis = 460, easing = LinearOutSlowInEasing))
            }
        }
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .luminaPressFeedback(
                interactionSource = interaction,
                enabled = enabled,
                pressScale = 0.86f,
                shape = RoundedCornerShape(50),
                pressOverlay = Color.Transparent,
            )
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = indication,
                enabled = enabled,
                role = Role.Checkbox,
            ) { next ->
                if (next != checked) {
                    onCheckedChange(next)
                    if (hapticOnToggle) {
                        (if (next) LuminaHaptic.ToggleOn else LuminaHaptic.ToggleOff).perform(view)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(48.dp)) {
            val p = burstProgress.value
            if (p > 0f && p < 0.999f) {
                val eased = 1f - (1f - p) * (1f - p)
                val radius = size.minDimension * (0.12f + 0.34f * eased)
                val alpha = (1f - p) * 0.55f
                drawCircle(
                    color = LuminaFeedbackDefaults.Gold.copy(alpha = alpha),
                    radius = radius,
                    style = Stroke(width = 1.2.dp.toPx()),
                )
                for (i in 0 until 6) {
                    val angle = (Math.PI * 2.0 * i / 6.0 + Math.PI / 6.0).toFloat()
                    val inner = radius * 0.86f
                    val outer = radius * 1.05f
                    drawLine(
                        color = LuminaFeedbackDefaults.Gold.copy(alpha = alpha * 0.9f),
                        start = Offset(
                            x = center.x + inner * cos(angle),
                            y = center.y + inner * sin(angle),
                        ),
                        end = Offset(
                            x = center.x + outer * cos(angle),
                            y = center.y + outer * sin(angle),
                        ),
                        strokeWidth = 1.6.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Canvas(Modifier.size(visualSize)) {
            val w = size.width
            val h = size.height
            val stroke = 1.6.dp.toPx()
            val value = progress.value.coerceIn(0f, 1f)
            val fillScale = 0.55f + 0.45f * value
            if (value > 0f) {
                // 亮图上的深色描边底，保证金色勾选圈在缩略图上仍可辨。
                drawCircle(
                    color = Color(0x66000000),
                    radius = w / 2f * fillScale + 1.dp.toPx(),
                )
                drawCircle(
                    color = LuminaFeedbackDefaults.Gold.copy(alpha = value),
                    radius = w / 2f * fillScale,
                )
            }
            drawCircle(
                color = if (value > 0.5f) {
                    LuminaFeedbackDefaults.Gold
                } else {
                    LuminaFeedbackDefaults.ThumbOff.copy(alpha = 0.75f)
                },
                radius = w / 2f - stroke / 2f,
                style = Stroke(width = stroke),
            )
            if (value > 0.001f) {
                val p1 = Offset(w * 0.27f, h * 0.52f)
                val p2 = Offset(w * 0.44f, h * 0.69f)
                val p3 = Offset(w * 0.75f, h * 0.33f)
                val checkColor = LuminaFeedbackDefaults.CheckInk.copy(alpha = value)
                if (value <= 0.45f) {
                    drawLine(
                        color = checkColor,
                        start = p1,
                        end = lerp(p1, p2, value / 0.45f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                } else {
                    drawLine(
                        color = checkColor,
                        start = p1,
                        end = p2,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = checkColor,
                        start = p2,
                        end = lerp(p2, p3, (value - 0.45f) / 0.55f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
