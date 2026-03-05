package com.clawwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Text

/**
 * Swipe-up command menu for quick actions.
 */
@Composable
fun CommandMenu(
    voiceMuted: Boolean,
    continuousMode: Boolean,
    timerActive: Boolean,
    onMuteToggle: () -> Unit,
    onContinuousToggle: () -> Unit,
    onStop: () -> Unit,
    onCompact: () -> Unit,
    onTimerTap: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE000000)),
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Close button
            item {
                Text(
                    text = "▼ Close",
                    color = Color(0xFF666666),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(bottom = 2.dp),
                )
            }

            // Header
            item {
                Text(
                    text = "Commands",
                    color = Color(0xFF9C27B0),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // Mute toggle
            item {
                MenuRow(
                    icon = if (voiceMuted) "🔇" else "🔊",
                    label = if (voiceMuted) "Unmute Voice" else "Mute Voice",
                    onClick = { onMuteToggle(); onDismiss() },
                )
            }

            // Timer
            item {
                MenuRow(
                    icon = "⏱",
                    label = if (timerActive) "Timer (active)" else "Set Timer",
                    onClick = { onTimerTap() },
                )
            }

            // Continuous mode
            item {
                MenuRow(
                    icon = if (continuousMode) "🔄" else "➡️",
                    label = if (continuousMode) "Continuous: ON" else "Continuous: OFF",
                    onClick = { onContinuousToggle(); onDismiss() },
                    highlight = continuousMode,
                )
            }

            // Stop
            item {
                MenuRow(
                    icon = "⛔",
                    label = "Stop",
                    onClick = { onStop(); onDismiss() },
                )
            }

            // Compact
            item {
                MenuRow(
                    icon = "📦",
                    label = "Compact Session",
                    onClick = { onCompact(); onDismiss() },
                )
            }

            // Settings
            item {
                MenuRow(
                    icon = "⚙️",
                    label = "Settings",
                    onClick = { onSettings(); onDismiss() },
                )
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: String,
    label: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .background(
                if (highlight) Color(0xFF1A1A2E) else Color(0xFF111111),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = icon,
            fontSize = 16.sp,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = label,
            color = if (highlight) Color(0xFF4FC3F7) else Color(0xFFCCCCCC),
            fontSize = 13.sp,
        )
    }
}
