package com.clawwatch.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * A polished waveform visualizer with gradient stroke, glow effect,
 * and smooth multi-layered sine waves.
 */
@Composable
fun WaveformLine(
    amplitudeTarget: Float,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase1",
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase2",
    )

    val animatedAmplitude by animateFloatAsState(
        targetValue = amplitudeTarget.coerceIn(0.01f, 1f),
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "amp",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp),
    ) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val points = 100
        val maxDeflection = h / 2f * 0.75f

        // Build waveform path
        val path = Path()
        for (i in 0..points) {
            val t = i.toFloat() / points
            val x = t * w

            // Smooth edge taper (cubic for smoother falloff)
            val taper = sin(t * Math.PI).toFloat()
            val edgeFade = taper * taper

            // Three layered sine waves for richness
            val wave1 = sin(t * Math.PI * 4.0 + phase1).toFloat()
            val wave2 = sin(t * Math.PI * 6.5 - phase2 * 1.3f).toFloat() * 0.4f
            val wave3 = sin(t * Math.PI * 9.0 + phase1 * 0.7f).toFloat() * 0.15f

            val combined = (wave1 + wave2 + wave3) * animatedAmplitude * edgeFade
            val y = cy + combined * maxDeflection

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Glow layer (thicker, semi-transparent)
        drawPath(
            path = path,
            color = lineColor.copy(alpha = 0.15f),
            style = Stroke(
                width = 12.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // Mid glow
        drawPath(
            path = path,
            color = lineColor.copy(alpha = 0.3f),
            style = Stroke(
                width = 6.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // Core line
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = 2.5f.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // Bright center highlight
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.4f),
            style = Stroke(
                width = 1.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
