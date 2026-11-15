package com.lumina.ui.feedback

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 弹性开关：拇指位置用低阻尼 spring 过渡，移动途中横向拉伸/纵向压扁，
 * 停下恢复圆形；轨道与拇指颜色同步渐变。
 *
 * 视觉 48×28dp，触控 48×48dp（`Box` 正方形），不牺牲无障碍热区。
 * 交互语义仍是标准 switch（role=Switch、toggleable），不引入拖拽，避免和页面滚动抢手势。
 */
@Composable
fun LuminaSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    val indication = remember {
        // 白波纹在金色轨道与暗色轨道上都看得见；不随 checked 重建，避免打断扩散。
        ripple(color = Color.White)
    }

    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (LuminaUiPrefs.animationsEnabled()) {
            spring(dampingRatio = 0.42f, stiffness = 640f)
        } else {
            tween(durationMillis = 0)
        },
        label = "luminaSwitchThumb",
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) LuminaFeedbackDefaults.Gold else LuminaFeedbackDefaults.TrackOff,
        animationSpec = tween(durationMillis = if (LuminaUiPrefs.animationsEnabled()) 220 else 0),
        label = "luminaSwitchTrack",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) LuminaFeedbackDefaults.CheckInk else LuminaFeedbackDefaults.ThumbOff,
        animationSpec = tween(durationMillis = if (LuminaUiPrefs.animationsEnabled()) 220 else 0),
        label = "luminaSwitchThumbColor",
    )

    Box(
        modifier = modifier
            .size(48.dp)
            .luminaPressFeedback(
                interactionSource = interaction,
                enabled = enabled,
                pressScale = 0.95f,
                shape = RoundedCornerShape(50),
                pressOverlay = Color.Transparent,
            )
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = indication,
                enabled = enabled,
                role = Role.Switch,
            ) { next ->
                if (next != checked) {
                    onCheckedChange(next)
                    (if (next) LuminaHaptic.ToggleOn else LuminaHaptic.ToggleOff).perform(view)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackW = size.width
            val trackH = 28.dp.toPx()
            val trackTop = (size.height - trackH) / 2f
            val trackRadius = trackH / 2f
            val f = fraction.coerceIn(-0.06f, 1.06f)
            val squash = switchSquash(f)
            val thumbBase = trackH / 2f - 3.dp.toPx()
            val thumbW = thumbBase * 2f + 3.dp.toPx() * squash
            val thumbH = thumbBase * 2f - 2.dp.toPx() * squash
            val startX = trackH / 2f
            val endX = trackW - trackH / 2f
            val cx = startX + (endX - startX) * f

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, trackTop),
                size = Size(trackW, trackH),
                cornerRadius = CornerRadius(trackRadius),
            )
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(cx - thumbW / 2f, size.height / 2f - thumbH / 2f),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(thumbH / 2f),
            )
        }
    }
}
