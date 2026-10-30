package com.qqbot.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------- 浅色 ----------
val Brand = Color(0xFF2F6BED)
val BrandSoft = Color(0xFFEDF2FF)
val Bg = Color(0xFFF5F6F8)
val SurfaceW = Color(0xFFFFFFFF)
val Ink = Color(0xFF0F172A)
val Ink2 = Color(0xFF475569)
val Muted = Color(0xFF94A3B8)
val Line = Color(0xFFEDF0F4)
val Field = Color(0xFFF3F5F8)
val Ok = Color(0xFF10B981)
val OkSoft = Color(0xFFE8F8F2)
val Danger = Color(0xFFEF4444)
val DangerSoft = Color(0xFFFEECEC)
val LogBg = Color(0xFF0F172A)
val LogText = Color(0xFFCBD5E1)

// ---------- 深色 ----------
val DkBg = Color(0xFF0D1117)
val DkSurface = Color(0xFF161B22)
val DkField = Color(0xFF21262D)
val DkLine = Color(0xFF30363D)
val DkInk = Color(0xFFE6EDF3)
val DkInk2 = Color(0xFF9BA7B4)
val DkMuted = Color(0xFF6E7681)

private val LightScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandSoft,
    onPrimaryContainer = Brand,
    background = Bg,
    onBackground = Ink,
    surface = SurfaceW,
    onSurface = Ink,
    surfaceVariant = Field,
    onSurfaceVariant = Ink2,
    outline = Line,
    error = Danger,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF79A6FF),
    onPrimary = Color(0xFF07101F),
    primaryContainer = Color(0xFF23304D),
    onPrimaryContainer = Color(0xFFC9DBFF),
    background = DkBg,
    onBackground = DkInk,
    surface = DkSurface,
    onSurface = DkInk,
    surfaceVariant = DkField,
    onSurfaceVariant = DkInk2,
    outline = DkLine,
    error = Color(0xFFFF6B6B),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
)

// ---------- 随主题走的取色（深色模式下别再用写死的浅色常量，否则白底白字看不见） ----------
@Composable fun appField() = MaterialTheme.colorScheme.surfaceVariant
@Composable fun appSurface() = MaterialTheme.colorScheme.surface
@Composable fun appBg() = MaterialTheme.colorScheme.background
@Composable fun appInk() = MaterialTheme.colorScheme.onSurface
@Composable fun appInk2() = MaterialTheme.colorScheme.onSurfaceVariant
@Composable fun appLine() = MaterialTheme.colorScheme.outline
@Composable fun appBrandSoft() = MaterialTheme.colorScheme.primaryContainer

/** 主题配色（深浅色自动） */
@Composable
fun QqBotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}