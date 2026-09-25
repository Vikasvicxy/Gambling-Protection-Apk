package dev.gamblock.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * A polished metric counter: the target [value] animates continuously (AnimatedContent
 * with a snappy scale/fade) so dashboard numbers slide up smoothly instead of snapping.
 */
@Composable
fun ShieldAnimatedCounter(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    val display by animateIntAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "shieldCounter",
    )
    AnimatedContent(
        targetState = display,
        transitionSpec = {
            (fadeIn(animationSpec = tween(220)) + scaleIn(
                initialScale = 0.85f,
                animationSpec = tween(220),
            )).togetherWith(fadeOut(animationSpec = tween(150)) + scaleOut(targetScale = 0.9f, animationSpec = tween(150)))
        },
        label = "shieldCounterContent",
        modifier = modifier,
    ) { shown ->
        Text(
            text = shown.toString(),
            textAlign = TextAlign.Center,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Clip,
        )
    }
}