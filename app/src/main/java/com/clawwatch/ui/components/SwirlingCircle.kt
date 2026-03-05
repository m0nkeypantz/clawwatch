package com.clawwatch.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * A swirling circular track that transitions into the waveform.
 * Used during the 'Thinking' phase.
 */
@Composable
fun SwirlingCircle(
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "swirl")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 45f,
        targetValue = 270f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )
    
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val radius = (size.minDimension / 2f) * 0.6f * pulse
        val center = Offset(size.width / 2f, size.height / 2f)
        
        // Multi-layered swirling arcs
        for (i in 0..2) {
            val offsetRot = rotation + (i * 120f)
            val strokeWidth = size.width * 0.02f * (3 - i)
            
            val alpha = 1f - (i * 0.2f)
            
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFF9C27B0).copy(alpha = 0.1f * alpha),
                        Color(0xFF00BCD4).copy(alpha = 0.5f * alpha),
                        Color(0xFF7C4DFF).copy(alpha = alpha),
                        Color(0xFF9C27B0).copy(alpha = 0.1f * alpha),
                    ),
                    center = center
                ),
                startAngle = offsetRot,
                sweepAngle = sweepAngle * (1f - (i * 0.15f)),
                useCenter = false,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round
                ),
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
            )
        }
        
        // Inner spinning dots
        for (i in 0..5) {
            val dotAngle = rotation * 2f + (i * 60f)
            val dotRad = Math.toRadians(dotAngle.toDouble()).toFloat()
            val dotRadius = radius * 0.6f
            
            drawCircle(
                color = Color.White.copy(alpha = 0.6f * pulse),
                radius = size.width * 0.015f,
                center = Offset(
                    x = center.x + dotRadius * cos(dotRad),
                    y = center.y + dotRadius * sin(dotRad)
                )
            )
        }
    }
}
