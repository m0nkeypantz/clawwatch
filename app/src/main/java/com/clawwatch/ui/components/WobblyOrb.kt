package com.clawwatch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.cos
import kotlin.math.sin

/**
 * A premium animated wobbly orb using continuous frame-based animation
 * for perfectly smooth, never-snapping motion. Gradually shifts between
 * purple and blue like a living lifeforce.
 */
@Composable
fun WobblyOrb(
    modifier: Modifier = Modifier,
) {
    // Continuous time accumulator — never resets, never snaps
    var timeSeconds by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastFrame = 0L
        while (true) {
            val frameTimeNanos = awaitFrame()
            if (lastFrame != 0L) {
                val dt = (frameTimeNanos - lastFrame) / 1_000_000_000f
                timeSeconds += dt.coerceAtMost(0.05f) // cap to avoid jumps on lag
            }
            lastFrame = frameTimeNanos
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        // Smooth continuous values derived from time — no wrapping, no snapping
        val pulse = 1f + 0.04f * sin(timeSeconds * 0.8f)
        val glowPulse = 0.35f + 0.15f * sin(timeSeconds * 1.1f)

        // Slow color shift between purple and blue
        val colorT = (sin(timeSeconds * 0.15f) + 1f) / 2f  // 0..1, ~21s full cycle
        val purple = Color(0xFF9C27B0)
        val blue = Color(0xFF2196F3)
        val deepPurple = Color(0xFF6A1B9A)
        val accentColor = lerp(purple, blue, colorT)
        val secondaryColor = lerp(deepPurple, Color(0xFF1565C0), colorT)

        val baseRadius = size.minDimension / 2f * 0.90f * pulse

        // Outer glow layers
        for (layer in 3 downTo 1) {
            val glowRadius = baseRadius * (1.2f + layer * 0.18f)
            val alpha = glowPulse * (0.10f / layer)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = alpha),
                        secondaryColor.copy(alpha = alpha * 0.3f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = Offset(cx, cy),
            )
        }

        // Build wobbly blob path using irrational frequency ratios
        // so waves never align and the motion never repeats or snaps
        val path = Path()
        val points = 200

        for (i in 0..points) {
            val angle = (i.toFloat() / points) * 2f * Math.PI.toFloat()

            // Each wave uses a unique irrational-ish speed multiplier
            // so they drift continuously and never sync up
            val w1 = sin(3f * angle + timeSeconds * 0.7f)   * 0.08f
            val w2 = sin(5f * angle - timeSeconds * 0.53f)  * 0.05f
            val w3 = cos(7f * angle + timeSeconds * 0.37f)  * 0.035f
            val w4 = sin(2f * angle + timeSeconds * 0.29f)  * 0.025f
            val w5 = cos(11f * angle - timeSeconds * 0.19f) * 0.015f

            val r = baseRadius * (1f + w1 + w2 + w3 + w4 + w5)
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // Fill with rich gradient
        drawPath(
            path = path,
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.95f),
                    secondaryColor.copy(alpha = 0.85f),
                    secondaryColor.copy(alpha = 0.65f),
                ),
                center = Offset(cx - baseRadius * 0.12f, cy - baseRadius * 0.12f),
                radius = baseRadius * 1.3f,
            ),
        )

        // Secondary fill for depth
        drawPath(
            path = path,
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.06f),
                    Color.Transparent,
                ),
                center = Offset(cx + baseRadius * 0.25f, cy + baseRadius * 0.25f),
                radius = baseRadius * 0.8f,
            ),
        )

        // Specular highlight (top-left)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.20f),
                    Color.White.copy(alpha = 0.04f),
                    Color.Transparent,
                ),
                center = Offset(cx - baseRadius * 0.25f, cy - baseRadius * 0.30f),
                radius = baseRadius * 0.40f,
            ),
            radius = baseRadius * 0.40f,
            center = Offset(cx - baseRadius * 0.25f, cy - baseRadius * 0.30f),
        )

        // Inner core glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = glowPulse * 0.35f),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = baseRadius * 0.45f,
            ),
            radius = baseRadius * 0.45f,
            center = Offset(cx, cy),
        )
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t,
    )
}
