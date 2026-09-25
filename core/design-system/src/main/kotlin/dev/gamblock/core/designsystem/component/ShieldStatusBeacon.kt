package dev.gamblock.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A status beacon with a soft pulsing glow. When [active] is true the inner dot and an
 * outer halo gently oscillate in alpha/scale (subtle, never strobe-like); a static,
 * dimmer dot is drawn otherwise so the eye still registers a neutral resting state.
 */
@Composable
fun ShieldStatusBeacon(
    color: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
    dotSize: Dp = 12.dp,
) {
    val transition = rememberInfiniteTransition(label = "shieldBeacon")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1100), RepeatMode.Reverse),
        label = "shieldBeaconAlpha",
    )
    val glow by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1100), RepeatMode.Reverse),
        label = "shieldBeaconGlow",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(dotSize * 2.3f)
                    .graphicsLayer { this.alpha = glow }
                    .background(color = color, shape = CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(dotSize)
                .graphicsLayer { this.alpha = if (active) alpha else 0.9f }
                .background(color = color, shape = CircleShape),
        )
    }
}