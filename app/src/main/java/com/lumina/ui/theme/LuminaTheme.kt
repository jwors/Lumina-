package com.lumina.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 清爽版（v2）设计 token：暖白画布 / 白卡 / 鼠尾草绿
private val Canvas = Color(0xFFF7F8F3)
private val Card = Color(0xFFFFFFFF)
private val Ink = Color(0xFF1B231C)
private val InkSoft = Color(0xFF56625A)
private val Line = Color(0xFFE1E7DC)
private val Accent = Color(0xFF3F7B58)
private val AccentSoft = Color(0xFFDDEDE2)
private val Warn = Color(0xFFB4552F)

private val LuminaColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Accent,
    secondary = Accent,
    onSecondary = Color.White,
    background = Canvas,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = AccentSoft,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F6F0),
    surfaceContainer = Color(0xFFEFF3EA),
    surfaceContainerHigh = Color(0xFFE9EEE4),
    surfaceContainerHighest = Color(0xFFE3E9DE),
    surfaceTint = Accent,
    outline = Line,
    outlineVariant = Line,
    error = Warn,
    onError = Color.White,
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
