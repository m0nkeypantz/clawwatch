package com.clawwatch.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.clawwatch.data.ConnectionState
import com.clawwatch.ui.components.AnimatedConnectionDot
import com.clawwatch.ui.components.ThinkingIndicator
import com.clawwatch.ui.components.SwirlingCircle
import com.clawwatch.ui.components.WaveformLine
import com.clawwatch.ui.components.WobblyOrb
import com.clawwatch.ui.components.LiquidLifeforce
import com.clawwatch.ui.components.CommandMenu
import com.clawwatch.ui.components.TimerPicker
import com.clawwatch.viewmodel.MainViewModel
import com.clawwatch.viewmodel.UiPhase
import kotlinx.coroutines.delay
import kotlin.math.sqrt
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectVerticalDragGestures

@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: MainViewModel,
    launchedAsAssistant: Boolean = false,
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val uiPhase by viewModel.uiPhase.collectAsState()
    val responseText by viewModel.responseText.collectAsState()
    val voiceMuted by viewModel.voiceMuted.collectAsState()
    val continuousMode by viewModel.continuousMode.collectAsState()
    val timerSeconds by viewModel.timerSeconds.collectAsState()
    val sessionLoad by viewModel.sessionLoad.collectAsState()
    val isAmbient by viewModel.isAmbient.collectAsState()
    val context = LocalContext.current

    val rms by viewModel.rmsFlow.collectAsState()
    val aiRms by viewModel.aiRmsFlow.collectAsState()

    // Menu state
    var showMenu by remember { mutableStateOf(false) }
    var showTimerPicker by remember { mutableStateOf(false) }

    // Volume control
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolume by remember { mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var showVolumeIndicator by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(showVolumeIndicator, currentVolume) {
        if (showVolumeIndicator) {
            delay(1500)
            showVolumeIndicator = false
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Reconnect when app resumes; sync ambient state
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reconnectIfNeeded()
                // Sync ambient from MainActivity
                val activity = context as? com.clawwatch.MainActivity
                if (activity != null) {
                    viewModel.setAmbient(activity.isAmbient)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-start listening if launched via assistant button
    LaunchedEffect(launchedAsAssistant) {
        if (launchedAsAssistant) {
            delay(500)
            viewModel.onTapToTalk()
        }
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    // Request both audio and location permissions together
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: false
    }

    Scaffold(
        timeText = { TimeText() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onRotaryScrollEvent { event ->
                    val direction = if (event.verticalScrollPixels > 0) 1 else -1
                    val newVolume = (currentVolume + direction).coerceIn(0, maxVolume)
                    if (newVolume != currentVolume) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                        currentVolume = newVolume
                    }
                    showVolumeIndicator = true
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
                .pointerInput(uiPhase, voiceMuted, responseText, showMenu) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (showMenu || showTimerPicker) {
                                showMenu = false
                                showTimerPicker = false
                                return@detectTapGestures
                            }

                            // Dead zone: outer 15% rim
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            val dx = offset.x - centerX
                            val dy = offset.y - centerY
                            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            val radius = minOf(centerX, centerY)
                            if (dist > radius * 0.85f) return@detectTapGestures

                            // Dismiss muted text
                            if (voiceMuted && (uiPhase == UiPhase.SPEAKING || responseText.isNotBlank())) {
                                viewModel.dismissText()
                                return@detectTapGestures
                            }

                            // Tap to talk
                            if (uiPhase == UiPhase.IDLE) {
                                if (!hasAudioPermission) {
                                    permissionLauncher.launch(arrayOf(
                                        Manifest.permission.RECORD_AUDIO,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                    ))
                                    return@detectTapGestures
                                }
                                viewModel.onTapToTalk()
                            }
                        }
                    )
                }
                .pointerInput(showMenu, showTimerPicker) {
                    var totalDrag = 0f
                    var startY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            totalDrag = 0f
                            startY = offset.y
                        },
                        onVerticalDrag = { _, dragAmount ->
                            totalDrag += dragAmount
                        },
                        onDragEnd = {
                            val screenHeight = size.height.toFloat()
                            if (showMenu || showTimerPicker) {
                                // Swipe down to close menu/picker
                                if (totalDrag > 60f) {
                                    showMenu = false
                                    showTimerPicker = false
                                }
                            } else {
                                // Only respond to swipe-up starting in the bottom 40% of screen
                                if (totalDrag < -60f && startY > screenHeight * 0.6f) {
                                    showMenu = true
                                }
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // Connection dot
            AnimatedConnectionDot(
                connectionState = connectionState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 26.dp),
            )

            // Phase content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                // The unified liquid lifeforce graphic
                if (!(voiceMuted && (uiPhase == UiPhase.SPEAKING || responseText.isNotBlank()))) {
                    LiquidLifeforce(
                        modifier = Modifier.fillMaxSize(0.85f),
                        phase = uiPhase,
                        amplitudeTarget = if (uiPhase == UiPhase.SPEAKING) aiRms else rms,
                        isSpeakingColor = uiPhase == UiPhase.SPEAKING,
                        sessionLoad = sessionLoad,
                        isAmbient = isAmbient,
                    )
                }

                // Text response layer (only shown if muted)
                if (voiceMuted && (uiPhase == UiPhase.SPEAKING || responseText.isNotBlank())) {
                    val paragraphs = remember(responseText) {
                        responseText.split("\n\n").filter { it.isNotBlank() }
                    }
                    val listState = rememberScalingLazyListState()

                    LaunchedEffect(responseText) {
                        if (paragraphs.isNotEmpty()) {
                            listState.animateScrollToItem(paragraphs.lastIndex)
                        }
                    }

                    ScalingLazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        items(paragraphs.size) { index ->
                            Text(
                                text = paragraphs[index],
                                style = MaterialTheme.typography.body1,
                                color = MaterialTheme.colors.onBackground,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // Timer countdown overlay
            if (timerSeconds != null) {
                val secs = timerSeconds!!
                val mm = secs / 60
                val ss = secs % 60
                Text(
                    text = "%d:%02d".format(mm, ss),
                    color = Color(0xFF4FC3F7),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 38.dp)
                        .clickable { viewModel.cancelTimer() },
                )
            }

            // Grip indicator at bottom (swipe up affordance)
            if (!showMenu && !showTimerPicker) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .width(30.dp)
                        .height(3.dp)
                        .background(Color(0xFF444444), RoundedCornerShape(2.dp)),
                )
            }

            // Volume indicator
            AnimatedVisibility(
                visible = showVolumeIndicator,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 38.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(4.dp)
                        .background(Color(0xFF222222), RoundedCornerShape(2.dp)),
                ) {
                    val fraction = if (maxVolume > 0) currentVolume.toFloat() / maxVolume else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(Color(0xFF9C27B0), RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }

    // --- Overlays ---
    if (showMenu) {
        CommandMenu(
            voiceMuted = voiceMuted,
            continuousMode = continuousMode,
            timerActive = timerSeconds != null,
            onMuteToggle = { viewModel.toggleMute() },
            onContinuousToggle = { viewModel.toggleContinuousMode() },
            onStop = { viewModel.onStop() },
            onCompact = { viewModel.onCompact() },
            onTimerTap = { showMenu = false; showTimerPicker = true },
            onSettings = { showMenu = false; onNavigateToSettings() },
            onDismiss = { showMenu = false },
        )
    }

    if (showTimerPicker) {
        TimerPicker(
            onConfirm = { totalSeconds ->
                viewModel.onTimerStart(totalSeconds)
                showTimerPicker = false
            },
            onCancel = { showTimerPicker = false },
        )
    }
}
