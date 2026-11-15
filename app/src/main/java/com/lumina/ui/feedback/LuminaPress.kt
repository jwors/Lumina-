package com.lumina.ui.feedback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 与黑金主题对齐的反馈色；独立放在 ui.feedback，避免反向依赖 MainActivity 的 token。 */
object LuminaFeedbackDefaults {
    val Gold = Color(0xFFC9A24B)
    val CheckInk = Color(0xFF16130A)
    val TrackOff = Color(0xFF2C2820)
    val ThumbOff = Color(0xFF8A8273)
    val Halo = Color(0xFFC9A24B)
}

/**
 * 按压状态层：缩放 + 跟手接触光斑 + 可选磁吸位移。
 *
 * 只负责"视觉反馈"，不注册点击；由 [luminaClickable] 或 `toggleable` 负责事件。
 * - 按下 90ms 快速下压；松开用低阻尼 spring 回弹（轻微过冲 = 手感）；
 * - 光斑中心 = 真实触点（不是控件中心），与 Material ripple 的扩散起点一致；
 * - 磁吸位移最多 [magneticPull]，松手回中。
 */
@Composable
fun Modifier.luminaPressFeedback(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressScale: Float = 0.97f,
    magnetic: Boolean = false,
    magneticPull: Dp = 5.dp,
    shape: Shape = RectangleShape,
    pressOverlay: Color = Color.White.copy(alpha = 0.10f),
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val density = LocalDensity.current
    val pullPx = with(density) { magneticPull.toPx() }
    val glowRadiusPx = with(density) { 72.dp.toPx() }
    val scale = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    val shift = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var pressPosition by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> pressPosition = interaction.pressPosition
                is PressInteraction.Release, is PressInteraction.Cancel -> pressPosition = null
            }
        }
    }

    LaunchedEffect(pressed, enabled) {
        if (!enabled) {
            scale.snapTo(1f)
            glow.snapTo(0f)
            shift.snapTo(Offset.Zero)
            return@LaunchedEffect
        }
        if (!LuminaUiPrefs.animationsEnabled()) {
            scale.snapTo(if (pressed) pressScale else 1f)
            glow.snapTo(if (pressed) 1f else 0f)
            shift.snapTo(Offset.Zero)
            return@LaunchedEffect
        }
        if (pressed) {
            launch { scale.animateTo(pressScale, tween(90, easing = FastOutSlowInEasing)) }
            launch { glow.animateTo(1f, tween(110, easing = FastOutSlowInEasing)) }
        } else {
            launch {
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.38f, stiffness = 620f),
                )
            }
            launch { glow.animateTo(0f, tween(180, easing = LinearOutSlowInEasing)) }
        }
    }

    // 触点位置 -> 磁吸偏移。PressInteraction 只给按下点，触屏上足够（拖动会取消点击）。
    LaunchedEffect(pressed, pressPosition, size, magnetic, pullPx) {
        val target = if (pressed && magnetic && pullPx > 0f) {
            pressPosition?.let { magneticOffset(it, size, pullPx) } ?: Offset.Zero
        } else {
            Offset.Zero
        }
        if (!LuminaUiPrefs.animationsEnabled()) {
            shift.snapTo(Offset.Zero)
        } else {
            shift.animateTo(
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.45f, stiffness = 560f),
            )
        }
    }

    return this
        .onSizeChanged { size = it }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            translationX = shift.value.x
            translationY = shift.value.y
        }
        .clip(shape)
        .drawBehind {
            val alpha = glow.value
            if (alpha > 0.001f) {
                val center = pressPosition ?: this.center
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            LuminaFeedbackDefaults.Halo.copy(alpha = 0.20f * alpha),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = glowRadiusPx,
                    ),
                    radius = glowRadiusPx,
                    center = center,
                )
            }
        }
        .drawWithContent {
            drawContent()
            // 金色按钮上金色波纹对比不足；统一叠 10% 白，深色/金色底都有"按下去"的亮度变化。
            val alpha = glow.value
            if (alpha > 0.001f && pressOverlay.alpha > 0f) {
                drawRect(pressOverlay.copy(alpha = pressOverlay.alpha * alpha))
            }
        }
}

/**
 * 统一的可点击修饰符：Material ripple（从触点扩散）+ 接触光斑 + 回弹 + 可选磁吸。
 *
 * 触感放在 `onClick` 里而不是按下时：列表里"按下即滚"不会误振，语义也更接近 iOS
 * 的 selection/impact —— 动作成立才反馈。
 */
@Composable
fun Modifier.luminaClickable(
    enabled: Boolean = true,
    pressScale: Float = 0.97f,
    haptic: LuminaHaptic = LuminaHaptic.None,
    magnetic: Boolean = false,
    shape: Shape = RectangleShape,
    rippleColor: Color = LuminaFeedbackDefaults.Gold,
    pressOverlay: Color = Color.White.copy(alpha = 0.10f),
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    val indication = remember(rippleColor) {
        ripple(color = rippleColor)
    }
    return this
        .luminaPressFeedback(
            interactionSource = interaction,
            enabled = enabled,
            pressScale = pressScale,
            magnetic = magnetic,
            shape = shape,
            pressOverlay = pressOverlay,
        )
        .clickable(
            interactionSource = interaction,
            indication = indication,
            enabled = enabled,
        ) {
            haptic.perform(view)
            onClick()
        }
}
