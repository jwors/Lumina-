package com.lumina.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 黑金（v3，2026-11-14）：近黑画布 / 炭黑卡 / 尼康金
private val Canvas = Color(0xFF0D0E0C)
private val Card = Color(0xFF171814)
private val Ink = Color(0xFFF3E7C8)
private val InkSoft = Color(0xFFBDB39C)
private val Line = Color(0xFF2C2820)
private val Gold = Color(0xFFC9A24B)
private val GoldSoft = Color(0xFF2A2315)
private val GoldInk = Color(0xFFE4C77E)
private val Warn = Color(0xFFE08A5B)

private val LuminaColors = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF16130A),
    primaryContainer = GoldSoft,
    onPrimaryContainer = GoldInk,
    secondary = Gold,
    onSecondary = Color(0xFF16130A),
    background = Canvas,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = GoldSoft,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Color(0xFF0A0B09),
    surfaceContainerLow = Color(0xFF121310),
    surfaceContainer = Color(0xFF171814),
    surfaceContainerHigh = Color(0xFF1E1F19),
    surfaceContainerHighest = Color(0xFF262720),
    surfaceTint = Gold,
    outline = Line,
    outlineVariant = Line,
    error = Warn,
    onError = Color(0xFF16130A),
)

private val LuminaType = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
)

private val LuminaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun LuminaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LuminaColors,
        typography = LuminaType,
        shapes = LuminaShapes,
        content = content,
    )
}
