package com.clawwatch.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.clawwatch.viewmodel.UiPhase
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A single living entity that smoothly reshapes itself between:
 *   - ORB: large wobbly filled circle (IDLE)
 *   - LINE: flat horizontal waveform (LISTENING / SPEAKING)
 *   - RING: spinning hollow circle (THINKING)
 *
 * Every frame, every point is calculated as a weighted blend of all
 * three shapes. The weights animate via springs for organic feel.
 */
@Composable
fun LiquidLifeforce(
    modifier: Modifier = Modifier,
    phase: UiPhase,
    amplitudeTarget: Float = 0f,
    isSpeakingColor: Boolean = false,
    sessionLoad: Float = 0f,
    isAmbient: Boolean = false,
) {
    // --- Ambient Mode: static burn-in-safe outline ---
    if (isAmbient) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val R = minOf(cx, cy) * 0.42f
            drawCircle(
                color = Color.White.copy(alpha = 0.4f),
                radius = R,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f)
            )
        }
        return
    }
    // --- Continuous Time ---
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val now = awaitFrame()
            if (last != 0L) t += ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
        }
    }

    // --- Phase Weights (spring-driven) ---
    // During SPEAKING, keep the ring spinning until audio actually starts playing.
    // Once amplitude crosses the threshold, smoothly hand off to the line.
    val audioActive = amplitudeTarget > 0.02f
    val speakingReady = phase == UiPhase.SPEAKING && audioActive

    val orbW by animateFloatAsState(
        if (phase == UiPhase.IDLE) 1f else 0f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "orb"
    )
    val lineW by animateFloatAsState(
        if (phase == UiPhase.LISTENING || speakingReady) 1f else 0f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "line"
    )
    val ringW by animateFloatAsState(
        // Stay as ring during THINKING *and* during SPEAKING while waiting for audio
        if (phase == UiPhase.THINKING || (phase == UiPhase.SPEAKING && !audioActive)) 1f else 0f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "ring"
    )
    // COMPACTING: tight collapsing vortex
    val compactW by animateFloatAsState(
        if (phase == UiPhase.COMPACTING) 1f else 0f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "compact"
    )
    // --- Waveform amplitude spring ---
    var amp by remember { mutableFloatStateOf(0f) }
    var ampV by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(amplitudeTarget) {
        while (true) {
            val force = (amplitudeTarget - amp) * 300f
            ampV = (ampV + force * 0.016f) * 0.85f
            amp += ampV * 0.016f
            awaitFrame()
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val R = size.minDimension / 2f * 0.88f
        val N = 120  // path resolution (reduced from 200 for battery)

        // Living pulse (always active, all states breathe)
        val breath = 1f + 0.03f * sin(t * 0.9f)

        // Color shift: purple <-> blue
        val ct = (sin(t * 0.16f) + 1f) / 2f
        val purple = Color(0xFF9C27B0)
        val blue = Color(0xFF2196F3)
        val deepPurple = Color(0xFF6A1B9A)
        val accent = lerp(purple, blue, ct)
        val secondary = lerp(deepPurple, Color(0xFF1565C0), ct)

        // Override color towards speaking/listening tint
        val tintColor = if (isSpeakingColor) purple else Color(0xFF4FC3F7)
        val finalAccent = lerp(accent, tintColor, lineW * 0.6f)

        // Glow pulse
        val gp = 0.3f + 0.2f * sin(t * 1.1f)

        // --- Outer Glow (only draw when orb is visible — saves 3 gradient draws) ---
        if (orbW > 0.05f) {
            val glowStrength = 0.3f + orbW * 0.7f
            for (layer in 3 downTo 1) {
                val gr = R * breath * (1.1f + layer * 0.15f) * glowStrength
                val a = gp * (0.08f / layer) * glowStrength
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(finalAccent.copy(alpha = a), secondary.copy(alpha = a * 0.3f), Color.Transparent),
                        center = Offset(cx, cy), radius = gr
                    ),
                    radius = gr, center = Offset(cx, cy)
                )
            }
        }

        // ================================================================
        // UNIFIED PATH — every point is a blend of Orb + Line + Ring
        // ================================================================
        val path = Path()
        val lineLen = size.width * 0.8f
        val lineStartX = cx - lineLen / 2f
        val ringRadius = R * 0.55f

        // Spin speed for the ring (thinking) shape
        val spin = t * 2.5f

        for (i in 0..N) {
            val frac = i.toFloat() / N
            val angle = frac * 2f * PI.toFloat()

            // === Shape 1: ORB (wobbly filled circle) ===
            val w1 = sin(3f * angle + t * 0.7f)  * 0.07f
            val w2 = sin(5f * angle - t * 0.53f) * 0.045f
            val w3 = cos(7f * angle + t * 0.37f) * 0.03f
            val w4 = sin(2f * angle + t * 0.29f) * 0.02f
            val w5 = cos(11f * angle - t * 0.19f)* 0.012f
            val orbR = R * breath * (1f + w1 + w2 + w3 + w4 + w5)
            val orbX = cx + orbR * cos(angle)
            val orbY = cy + orbR * sin(angle)

            // === Shape 2: LINE (horizontal waveform) ===
            // Map the circle's angle to a horizontal position
            val lineFrac = frac
            val lineX = lineStartX + lineFrac * lineLen
            // Edge taper for waveform
            val nx = lineFrac * 2f - 1f // -1..1
            val taper = (1f - nx.pow(4)).coerceIn(0f, 1f)
            // Waveform displacement
            val wv1 = sin(nx * 8f + t * 6f) * 0.5f
            val wv2 = cos(nx * 14f - t * 8f) * 0.3f
            val wv3 = sin(nx * 22f + t * 11f) * 0.2f
            val waveDisp = (wv1 + wv2 + wv3) * amp * taper * size.height * 0.25f
            // Keep slight organic wobble even at zero amplitude
            val idleWobble = sin(nx * 6f + t * 3f) * 2f * taper
            val lineY = cy + waveDisp + idleWobble

            // === Shape 3: RING (spinning hollow circle) ===
            val ringAngle = angle + spin
            val rw1 = sin(3f * ringAngle + t * 1.5f) * 0.06f
            val rw2 = cos(5f * ringAngle - t * 1.1f) * 0.04f
            val rR = ringRadius * breath * (1f + rw1 + rw2)
            val ringX = cx + rR * cos(ringAngle)
            val ringY = cy + rR * sin(ringAngle)

            // === Shape 4: COMPACT (tight fast vortex) ===
            val compactSpin = t * 8f // much faster spin
            val compactAngle = angle + compactSpin
            val compactRadius = R * 0.15f * breath  // collapsed tight
            val cw1 = sin(5f * compactAngle + t * 4f) * 0.15f
            val cw2 = cos(7f * compactAngle - t * 3f) * 0.10f
            val cR = compactRadius * (1f + cw1 + cw2)
            val compactX = cx + cR * cos(compactAngle)
            val compactY = cy + cR * sin(compactAngle)

            // === Blend all four based on weights ===
            val totalW = (orbW + lineW + ringW + compactW).coerceAtLeast(0.001f)
            val nOrb = orbW / totalW
            val nLine = lineW / totalW
            val nRing = ringW / totalW
            val nCompact = compactW / totalW

            val px = orbX * nOrb + lineX * nLine + ringX * nRing + compactX * nCompact
            val py = orbY * nOrb + lineY * nLine + ringY * nRing + compactY * nCompact

            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()

        // ================================================================
        // RENDERING — crossfade between fill (orb) and stroke (line/ring)
        // ================================================================
        val fillAlpha = orbW.coerceIn(0f, 1f)
        val strokeAlpha = (lineW + ringW + compactW).coerceIn(0f, 1f)

        // Filled orb rendering
        if (fillAlpha > 0.01f) {
            // Main fill
            drawPath(
                path, brush = Brush.radialGradient(
                    listOf(
                        finalAccent.copy(alpha = 0.95f * fillAlpha),
                        secondary.copy(alpha = 0.85f * fillAlpha),
                        secondary.copy(alpha = 0.55f * fillAlpha),
                    ),
                    center = Offset(cx - R * 0.1f, cy - R * 0.1f),
                    radius = R * 1.4f
                )
            )
            // Specular highlight
            if (fillAlpha > 0.3f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = 0.18f * fillAlpha),
                            Color.White.copy(alpha = 0.03f * fillAlpha),
                            Color.Transparent,
                        ),
                        center = Offset(cx - R * 0.22f, cy - R * 0.28f),
                        radius = R * 0.35f
                    ),
                    radius = R * 0.38f,
                    center = Offset(cx - R * 0.22f, cy - R * 0.28f)
                )
            }
            // Inner core glow
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(finalAccent.copy(alpha = gp * 0.3f * fillAlpha), Color.Transparent),
                    center = Offset(cx, cy), radius = R * 0.4f
                ),
                radius = R * 0.4f, center = Offset(cx, cy)
            )
        }

        // Stroke rendering (line / ring mode)
        if (strokeAlpha > 0.01f) {
            val sw = 4.dp.toPx()
            // Outer glow stroke
            drawPath(
                path, color = finalAccent.copy(alpha = 0.25f * strokeAlpha),
                style = Stroke(width = sw * 3f, cap = StrokeCap.Round)
            )
            // Mid glow
            drawPath(
                path, color = finalAccent.copy(alpha = 0.5f * strokeAlpha),
                style = Stroke(width = sw * 1.5f, cap = StrokeCap.Round)
            )
            // Core stroke
            drawPath(
                path, color = finalAccent.copy(alpha = strokeAlpha),
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
            // Bright center highlight
            drawPath(
                path, color = Color.White.copy(alpha = 0.7f * strokeAlpha),
                style = Stroke(width = sw * 0.3f, cap = StrokeCap.Round)
            )
        }

        // === Session Memory Ring ===
        if (sessionLoad > 0.01f) {
            val ringColor = when {
                sessionLoad < 0.4f -> lerp(Color(0xFF4CAF50), Color(0xFFFFEB3B), sessionLoad / 0.4f)
                sessionLoad < 0.75f -> lerp(Color(0xFFFFEB3B), Color(0xFFFF9800), (sessionLoad - 0.4f) / 0.35f)
                else -> lerp(Color(0xFFFF9800), Color(0xFFF44336), (sessionLoad - 0.75f) / 0.25f)
            }
            val ringRadius = minOf(cx, cy) * 0.48f
            val pulseIntensity = 0.15f + sessionLoad * 0.25f
            val pulseAlpha = pulseIntensity * (0.5f + 0.5f * sin(t * 2f))
            drawCircle(
                color = ringColor.copy(alpha = pulseAlpha.coerceIn(0.05f, 0.5f)),
                radius = ringRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    val c = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * c,
        green = a.green + (b.green - a.green) * c,
        blue = a.blue + (b.blue - a.blue) * c,
        alpha = a.alpha + (b.alpha - a.alpha) * c,
    )
}
