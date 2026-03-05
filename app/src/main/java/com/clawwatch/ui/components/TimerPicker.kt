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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Text

/**
 * Simple timer picker for the watch.
 * Displays preset durations and a confirm button.
 */
@Composable
fun TimerPicker(
    onConfirm: (totalSeconds: Int) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedMinutes by remember { mutableIntStateOf(5) }
    val presets = listOf(1, 2, 3, 5, 10, 15, 30, 60)
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
            item {
                Text(
                    text = "Set Timer",
                    color = Color(0xFF9C27B0),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            items(presets.size) { index ->
                val mins = presets[index]
                val isSelected = mins == selectedMinutes
                val label = if (mins < 60) "${mins}m" else "1h"
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .background(
                            if (isSelected) Color(0xFF1A237E) else Color(0xFF111111),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { selectedMinutes = mins }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color(0xFF4FC3F7) else Color(0xFFCCCCCC),
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Text(
                        text = "Cancel",
                        color = Color(0xFF888888),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                            .clickable { onCancel() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Text(
                        text = "Start",
                        color = Color(0xFF4CAF50),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                            .clickable { onConfirm(selectedMinutes * 60) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
