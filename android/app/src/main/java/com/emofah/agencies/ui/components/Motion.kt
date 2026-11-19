package com.emofah.agencies.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            scale == 0f
        }.getOrDefault(false)
    }
}

object EmofahMotion {
    const val QUICK_MS = 160
    const val STANDARD_MS = 220
    const val EMPHASIZED_MS = 280

    fun <T> standardTween(durationMs: Int = STANDARD_MS) = tween<T>(
        durationMillis = durationMs,
        easing = FastOutSlowInEasing,
    )

    fun <T> tactileSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}

fun Modifier.emofahPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.975f,
): Modifier = composed {
    val reducedMotion = LocalReducedMotion.current
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) pressedScale else 1f,
        animationSpec = if (reducedMotion) tween(0) else EmofahMotion.tactileSpring(),
        label = "pressScale",
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun EmofahAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val enter: EnterTransition = if (reducedMotion) {
        fadeIn(tween(0))
    } else {
        fadeIn(tween(EmofahMotion.STANDARD_MS)) +
            expandVertically(tween(EmofahMotion.STANDARD_MS)) +
            slideInVertically(tween(EmofahMotion.STANDARD_MS)) { -it / 10 }
    }
    val exit: ExitTransition = if (reducedMotion) {
        fadeOut(tween(0))
    } else {
        fadeOut(tween(EmofahMotion.QUICK_MS)) + shrinkVertically(tween(EmofahMotion.QUICK_MS))
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
    ) {
        content()
    }
}
