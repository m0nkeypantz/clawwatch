package com.clawwatch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.clawwatch.data.ConnectionState

@Composable
fun AnimatedConnectionDot(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier,
) {
    val targetColor = when (connectionState) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50)
        ConnectionState.CONNECTING, ConnectionState.HANDSHAKING -> Color(0xFFFFC107)
        ConnectionState.DISCONNECTED -> Color(0xFFF44336)
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 400),
        label = "dotColor",
    )

    val isConnected = connectionState == ConnectionState.CONNECTED
    val isPulsing = connectionState == ConnectionState.CONNECTING ||
        connectionState == ConnectionState.HANDSHAKING

    val infiniteTransition = rememberInfiniteTransition(label = "dotPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    Box(
        modifier = modifier
            .graphicsLayer { this.alpha = if (isPulsing) alpha else 1f },
        contentAlignment = Alignment.Center,
    ) {
        // Glow ring — visible when NOT connected
        if (!isConnected) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.25f)),
            )
        }

        // Dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
