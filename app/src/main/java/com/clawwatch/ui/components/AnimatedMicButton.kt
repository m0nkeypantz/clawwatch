package com.clawwatch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.clawwatch.voice.ListeningState

@Composable
fun AnimatedMicButton(
    listeningState: ListeningState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isListening = listeningState == ListeningState.LISTENING

    val backgroundColor by animateColorAsState(
        targetValue = if (isListening) {
            MaterialTheme.colors.error
        } else {
            MaterialTheme.colors.primary
        },
        animationSpec = tween(durationMillis = 300),
        label = "micColor",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "micScale",
    )

    val iconColor = MaterialTheme.colors.onPrimary

    Button(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                val scale = if (isListening) pulseScale else 1f
                scaleX = scale
                scaleY = scale
            },
        colors = ButtonDefaults.primaryButtonColors(backgroundColor = backgroundColor),
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val cx = size.width / 2

            if (isListening) {
                // Pause bars
                val barWidth = size.width * 0.14f
                val barHeight = size.height * 0.54f
                val barTop = (size.height - barHeight) / 2
                val gap = size.width * 0.10f

                drawRoundRect(
                    color = iconColor,
                    topLeft = Offset(cx - gap - barWidth, barTop),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2),
                )
                drawRoundRect(
                    color = iconColor,
                    topLeft = Offset(cx + gap, barTop),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2),
                )
            } else {
                // Microphone icon
                val strokeWidth = size.width * 0.08f

                // Mic body — rounded capsule
                val bodyWidth = size.width * 0.30f
                val bodyHeight = size.height * 0.40f
                val bodyTop = size.height * 0.08f
                drawRoundRect(
                    color = iconColor,
                    topLeft = Offset(cx - bodyWidth / 2, bodyTop),
                    size = Size(bodyWidth, bodyHeight),
                    cornerRadius = CornerRadius(bodyWidth / 2),
                )

                // U-shaped holder arc
                val arcDiameter = size.width * 0.50f
                drawArc(
                    color = iconColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(cx - arcDiameter / 2, bodyTop + bodyHeight * 0.25f),
                    size = Size(arcDiameter, arcDiameter),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )

                // Stem
                val stemTop = bodyTop + bodyHeight * 0.25f + arcDiameter / 2
                val stemBottom = size.height * 0.84f
                drawLine(
                    color = iconColor,
                    start = Offset(cx, stemTop),
                    end = Offset(cx, stemBottom),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )

                // Base line
                val baseHalf = size.width * 0.16f
                drawLine(
                    color = iconColor,
                    start = Offset(cx - baseHalf, stemBottom),
                    end = Offset(cx + baseHalf, stemBottom),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
