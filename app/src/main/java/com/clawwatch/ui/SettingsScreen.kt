package com.clawwatch.ui

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Text
import com.clawwatch.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Settings screen for connection, TTS, and volume controls.
 * Accessible from the swipe-up command menu.
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioManager = remember {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
    }

    val gatewayUrl by viewModel.gatewayUrl.collectAsState()
    val authToken by viewModel.authToken.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val useLocalTts by viewModel.useLocalTts.collectAsState()
    val elevenLabsApiKey by viewModel.elevenLabsApiKey.collectAsState()
    val elevenLabsVoiceId by viewModel.elevenLabsVoiceId.collectAsState()
    val assistantVolume by viewModel.assistantVolume.collectAsState()
    val alarmVolume by viewModel.alarmVolume.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val modelsLoading by viewModel.modelsLoading.collectAsState()
    val modelsError by viewModel.modelsError.collectAsState()

    var gatewayDraft by remember(gatewayUrl) { mutableStateOf(gatewayUrl) }
    var authTokenDraft by remember(authToken) { mutableStateOf(authToken) }
    var selectedModelDraft by remember(selectedModel) { mutableStateOf(selectedModel) }
    var elevenLabsApiKeyDraft by remember(elevenLabsApiKey) { mutableStateOf(elevenLabsApiKey) }
    var elevenLabsVoiceIdDraft by remember(elevenLabsVoiceId) { mutableStateOf(elevenLabsVoiceId) }
    val remoteTtsMissingApiKey = !useLocalTts && elevenLabsApiKey.isBlank()
    val remoteTtsMissingVoiceId = !useLocalTts && elevenLabsVoiceId.isBlank()

    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var systemVolume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    val listState = rememberScalingLazyListState()

    LaunchedEffect(gatewayUrl, authToken) {
        if (gatewayUrl.isNotBlank() && authToken.isNotBlank() && availableModels.isEmpty() && !modelsLoading) {
            viewModel.refreshAvailableModels()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    text = "Settings",
                    color = Color(0xFF9C27B0),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            item { SectionHeader("Connection") }
            item {
                EditableSettingCard(
                    label = "Gateway URL",
                    value = gatewayDraft,
                    placeholder = "ws://host:18789",
                    secret = false,
                    onValueChange = { gatewayDraft = it },
                    onSave = { value -> scope.launch { viewModel.updateGatewayUrl(value.trim()) } },
                )
            }
            item {
                EditableSettingCard(
                    label = "Auth Token",
                    value = authTokenDraft,
                    placeholder = "gateway token",
                    secret = true,
                    onValueChange = { authTokenDraft = it },
                    onSave = { value -> scope.launch { viewModel.updateAuthToken(value.trim()) } },
                )
            }
            item { SectionHeader("Model") }
            item {
                EditableSettingCard(
                    label = "Selected Model",
                    value = selectedModelDraft,
                    placeholder = "blank = gateway default",
                    secret = false,
                    onValueChange = { selectedModelDraft = it },
                    onSave = { value -> scope.launch { viewModel.updateSelectedModel(value.trim()) } },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ActionChip(
                        label = if (modelsLoading) "Refreshing…" else "Refresh Models",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.refreshAvailableModels() },
                    )
                    if (selectedModel.isNotBlank()) {
                        ActionChip(
                            label = "Use Default",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                selectedModelDraft = ""
                                viewModel.updateSelectedModel("")
                            },
                        )
                    }
                }
            }
            item {
                val modelStatus = when {
                    modelsLoading -> "Loading models from the connected gateway account…"
                    modelsError != null -> "Model refresh failed: $modelsError"
                    availableModels.isEmpty() -> "No model list loaded yet."
                    else -> "Tap a model below to save it for future connections."
                }
                val modelStatusColor = when {
                    modelsLoading -> Color(0xFF888888)
                    modelsError != null -> Color(0xFFFFB74D)
                    else -> Color(0xFF888888)
                }
                Text(
                    text = modelStatus,
                    color = modelStatusColor,
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth(0.85f),
                )
            }
            items(availableModels.size) { index ->
                val model = availableModels[index]
                SelectableSettingCard(
                    label = model,
                    selected = model == selectedModel,
                    onClick = {
                        selectedModelDraft = model
                        viewModel.updateSelectedModel(model)
                    },
                )
            }

            item { SectionHeader("Speech") }
            item {
                ToggleSettingCard(
                    label = "Use local TTS",
                    value = useLocalTts,
                    onToggle = { scope.launch { viewModel.updateUseLocalTts(!useLocalTts) } },
                )
            }
            item {
                EditableSettingCard(
                    label = "ElevenLabs API Key",
                    value = elevenLabsApiKeyDraft,
                    placeholder = if (useLocalTts) "optional unless ElevenLabs is enabled" else "required for ElevenLabs",
                    secret = true,
                    onValueChange = { elevenLabsApiKeyDraft = it },
                    onSave = { value ->
                        scope.launch { viewModel.updateElevenLabsApiKey(value.trim()) }
                    },
                )
            }
            item {
                EditableSettingCard(
                    label = "ElevenLabs Voice ID",
                    value = elevenLabsVoiceIdDraft,
                    placeholder = if (useLocalTts) "optional unless ElevenLabs is enabled" else "required for ElevenLabs",
                    secret = false,
                    onValueChange = { elevenLabsVoiceIdDraft = it },
                    onSave = { value ->
                        scope.launch { viewModel.updateElevenLabsVoiceId(value.trim()) }
                    },
                )
            }
            item {
                val speechStatus = when {
                    useLocalTts -> "Speech plays through Android TTS on-device."
                    remoteTtsMissingApiKey && remoteTtsMissingVoiceId ->
                        "ElevenLabs mode needs both an API key and a voice ID."
                    remoteTtsMissingApiKey -> "ElevenLabs mode needs an API key."
                    remoteTtsMissingVoiceId -> "ElevenLabs mode needs a voice ID."
                    else -> "Speech streams from ElevenLabs using the saved API key and voice."
                }
                val speechStatusColor = when {
                    useLocalTts -> Color(0xFF888888)
                    remoteTtsMissingApiKey || remoteTtsMissingVoiceId -> Color(0xFFFFB74D)
                    else -> Color(0xFF888888)
                }
                Text(
                    text = speechStatus,
                    color = speechStatusColor,
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth(0.85f),
                )
            }

            item { SectionHeader("Volume") }
            item {
                VolumeRow(
                    label = "System",
                    value = systemVolume.toFloat() / maxVolume.coerceAtLeast(1),
                    onMinus = {
                        val v = (systemVolume - 1).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        systemVolume = v
                    },
                    onPlus = {
                        val v = (systemVolume + 1).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        systemVolume = v
                    },
                )
            }
            item {
                VolumeRow(
                    label = "Assistant",
                    value = assistantVolume,
                    onMinus = {
                        scope.launch {
                            viewModel.updateAssistantVolume((assistantVolume - 0.1f).coerceIn(0f, 1f))
                        }
                    },
                    onPlus = {
                        scope.launch {
                            viewModel.updateAssistantVolume((assistantVolume + 0.1f).coerceIn(0f, 1f))
                        }
                    },
                )
            }
            item {
                VolumeRow(
                    label = "Alarm",
                    value = alarmVolume,
                    onMinus = {
                        scope.launch {
                            viewModel.updateAlarmVolume((alarmVolume - 0.1f).coerceIn(0f, 1f))
                        }
                    },
                    onPlus = {
                        scope.launch {
                            viewModel.updateAlarmVolume((alarmVolume + 0.1f).coerceIn(0f, 1f))
                        }
                    },
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = Color(0xFF4FC3F7),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun EditableSettingCard(
    label: String,
    value: String,
    placeholder: String,
    secret: Boolean,
    onValueChange: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .background(Color(0xFF111111), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFFCCCCCC),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = false,
            textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            decorationBox = { innerTextField ->
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        color = Color(0xFF666666),
                        fontSize = 10.sp,
                    )
                }
                innerTextField()
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActionChip(
                label = "Save",
                modifier = Modifier.weight(1f),
                onClick = { onSave(value) },
            )
            ActionChip(
                label = "Clear",
                modifier = Modifier.weight(1f),
                onClick = {
                    onValueChange("")
                    onSave("")
                },
            )
        }
        if (secret && value.isNotBlank()) {
            Text(
                text = maskSecret(value),
                color = Color(0xFF777777),
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun ToggleSettingCard(
    label: String,
    value: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .background(Color(0xFF111111), RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Color(0xFFCCCCCC), fontSize = 12.sp)
        Text(
            text = if (value) "On" else "Off",
            color = if (value) Color(0xFF4FC3F7) else Color(0xFF888888),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SelectableSettingCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .background(
                if (selected) Color(0xFF123244) else Color(0xFF111111),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color(0xFFCCCCCC),
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "Selected",
                color = Color(0xFF4FC3F7),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(Color(0xFF222222), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color(0xFF9C27B0),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun VolumeRow(
    label: String,
    value: Float,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = "${(value * 100).toInt()}%",
                color = Color(0xFF9C27B0),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Color(0xFF222222), RoundedCornerShape(6.dp))
                    .clickable { onMinus() },
                contentAlignment = Alignment.Center,
            ) {
                Text("−", color = Color(0xFF9C27B0), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(Color(0xFF222222), RoundedCornerShape(4.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(value.coerceIn(0.02f, 1f))
                        .background(Color(0xFF9C27B0), RoundedCornerShape(4.dp)),
                )
            }

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Color(0xFF222222), RoundedCornerShape(6.dp))
                    .clickable { onPlus() },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = Color(0xFF9C27B0), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun maskSecret(value: String): String {
    return if (value.length > 8) {
        value.take(4) + "••••" + value.takeLast(4)
    } else {
        "••••"
    }
}
