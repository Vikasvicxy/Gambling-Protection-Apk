package dev.gamblock.feature.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gamblock.core.designsystem.component.ShieldButton
import dev.gamblock.core.designsystem.component.ShieldCard
import dev.gamblock.core.designsystem.component.ShieldScaffold
import dev.gamblock.core.designsystem.component.ShieldText
import dev.gamblock.core.designsystem.theme.ShieldPalette
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay

enum class BreathingPhase(val label: String, val seconds: Int, val scale: Float) {
    INHALE("Breathe in", 4, 1f),
    HOLD("Hold", 7, 1f),
    EXHALE("Breathe out", 8, 0.35f),
    ;

    val totalMs: Long
        get() = seconds * 1_000L
}

object BreathingCycle {

    val phases: List<BreathingPhase> = listOf(BreathingPhase.INHALE, BreathingPhase.HOLD, BreathingPhase.EXHALE)

    val cycleDurationMs: Long = phases.sumOf { it.totalMs }

    fun phaseAt(elapsedInCycleMs: Long): BreathingPhase {
        val offset = ((elapsedInCycleMs % cycleDurationMs) + cycleDurationMs) % cycleDurationMs
        var cursor = 0L
        phases.forEach { phase ->
            if (offset < cursor + phase.totalMs) return phase
            cursor += phase.totalMs
        }
        return phases.last()
    }

    fun phaseProgress(elapsedInCycleMs: Long): Float {
        val offset = ((elapsedInCycleMs % cycleDurationMs) + cycleDurationMs) % cycleDurationMs
        val phase = phaseAt(offset)
        var cursor = 0L
        phases.forEach { candidate ->
            if (candidate == phase) {
                return ((offset - cursor).toFloat() / phase.totalMs).coerceIn(0f, 1f)
            }
            cursor += candidate.totalMs
        }
        return 0f
    }
}

@Composable
fun UrgeSurferRoute(
    onBack: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel(),
) {
    var elapsed by remember { mutableStateOf(0L) }
    var running by remember { mutableStateOf(true) }
    val haptics = LocalHapticFeedback.current
    var lastPhase by remember { mutableStateOf<BreathingPhase?>(null) }

    val phase = BreathingCycle.phaseAt(elapsed)
    val transition = rememberInfiniteTransition(label = "urge-surfer")
    val pulse by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    LaunchedEffect(running) {
        var last = System.currentTimeMillis()
        var accumulated = elapsed
        while (running) {
            val now = System.currentTimeMillis()
            accumulated += (now - last)
            last = now
            elapsed = accumulated
            delay(50)
        }
    }

    LaunchedEffect(phase) {
        if (running && lastPhase != phase) {
            lastPhase = phase
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    ShieldScaffold(title = "Urge Surfer", content = { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShieldText(
                text = "4-7-8 breathing. Follow the circle: it grows as you breathe in, holds, then shrinks as you breathe out.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer8()
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val displayScale = when (phase) {
                    BreathingPhase.INHALE -> {
                        val p = BreathingCycle.phaseProgress(elapsed)
                        0.35f + (1f - 0.35f) * p
                    }
                    BreathingPhase.EXHALE -> {
                        val p = BreathingCycle.phaseProgress(elapsed)
                        1f + (0.35f - 1f) * p
                    }
                    BreathingPhase.HOLD -> 1f
                }
                BreathingCircle(
                    progress = displayScale.coerceIn(0.2f, 1.2f),
                    pulse = pulse,
                    label = phase.label,
                    countdown = phase.totalMs / 1000L - (elapsed % BreathingCycle.cycleDurationMs) / 1000L,
                )
            }
            Spacer8()
            ShieldText(
                text = "Repeat until the urge passes. Urges are temporary waves.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer8()
            ShieldButton(
                text = if (running) "Pause" else "Resume",
                onClick = { running = !running },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer8()
            ShieldButton(text = "Back to dashboard", onClick = onBack, modifier = Modifier.fillMaxWidth())
        }
    })
}

@Composable
private fun BreathingCircle(progress: Float, pulse: Float, label: String, countdown: Long) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$label breathing guide" },
    ) {
        val minDimension = minOf(size.width, size.height)
        val maxRadius = minDimension / 2f - 24f
        val radius = maxRadius * progress.coerceIn(0.15f, 1f) * pulse.coerceIn(0.8f, 1.1f)
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = ShieldPalette.Blue.copy(alpha = 0.18f),
            radius = radius,
            center = center,
        )
        drawCircle(
            color = ShieldPalette.Blue,
            radius = radius,
            center = center,
            style = Stroke(width = 6f),
        )
        val innerRadius = (radius * 0.55f).coerceAtLeast(6f)
        drawCircle(
            color = ShieldPalette.Blue.copy(alpha = 0.35f),
            radius = innerRadius,
            center = center,
        )
    }
}

@Composable
private fun Spacer8() {
    Box(modifier = Modifier.padding(8.dp))
}
