package com.clawwatch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val Purple = Color(0xFFBB86FC)
private val PurpleDark = Color(0xFF9C64E0)
private val Teal = Color(0xFF03DAC5)
private val ErrorRed = Color(0xFFCF6679)

private val ClawWatchColors = Colors(
    primary = Purple,
    primaryVariant = PurpleDark,
    secondary = Teal,
    secondaryVariant = Teal,
    error = ErrorRed,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onError = Color.Black,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB0B0B0),
    background = Color.Black,
    onBackground = Color.White,
)

@Composable
fun ClawWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = ClawWatchColors,
        content = content,
    )
}
