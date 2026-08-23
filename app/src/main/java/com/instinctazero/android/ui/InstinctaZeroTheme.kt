package com.instinctazero.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object LegacyColors {
    val Background = Color(0xFF161512)
    val Surface = Color(0xFF202020)
    val SurfaceRaised = Color(0xFF272727)
    val Divider = Color(0xFF3A3937)
    val Text = Color(0xFFCCCCCC)
    val Muted = Color(0xFFB3B3B3)
    val Accent = Color(0xFFD64F00)
    val AccentStrong = Color(0xFFBF811D)
    val CurrentMove = Color(0xFF3B93DA)
    val BoardLight = Color(0xFFF0D9B5)
    val BoardDark = Color(0xFFB58863)
    val LastMoveLight = Color(0xA69BC700)
    val LastMoveDark = Color(0xA69BC700)
    val WhiteWins = Color(0xFFE8E4DC)
    val Draws = Color(0xFF999792)
    val BlackWins = Color(0xFF555350)
    val Positive = Color(0xFFCAD899)
}

private val InstinctaZeroDarkScheme = darkColorScheme(
    primary = LegacyColors.Accent,
    onPrimary = LegacyColors.Background,
    background = LegacyColors.Background,
    onBackground = LegacyColors.Text,
    surface = LegacyColors.Surface,
    onSurface = LegacyColors.Text,
    surfaceVariant = LegacyColors.SurfaceRaised,
    onSurfaceVariant = LegacyColors.Muted,
    outline = LegacyColors.Divider,
)

@Composable
fun InstinctaZeroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InstinctaZeroDarkScheme,
        content = content,
    )
}
