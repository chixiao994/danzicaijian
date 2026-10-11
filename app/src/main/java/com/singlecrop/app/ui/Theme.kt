package com.singlecrop.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFD54F),
    onPrimary = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFFFFD54F),
    onPrimaryContainer = Color(0xFF1A1A1A),
    secondary = Color(0xFF9E9E9E),
    onSecondary = Color(0xFF1A1A1A),
    background = Color(0xFF121212),
    onBackground = Color(0xFFEEEEEE),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFEEEEEE),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF616161)
)

@Composable
fun SingleCropTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
