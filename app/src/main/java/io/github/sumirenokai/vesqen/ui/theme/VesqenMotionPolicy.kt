package io.github.sumirenokai.vesqen.ui.theme

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember

@Immutable
data class VesqenMotionPolicy(
    val reduceMotion: Boolean,
) {
    val stateChangeMillis: Int
        get() = if (reduceMotion) VesqenMotion.ReducedMotionMillis else VesqenMotion.StateChangeMillis

    val playerExpandMillis: Int
        get() = if (reduceMotion) VesqenMotion.ReducedMotionMillis else VesqenMotion.PlayerExpandMillis
}

/**
 * Android's animator switch is the dependable baseline available to this API-26 app. When it is
 * disabled, navigation falls back to the short crossfade defined by the product system.
 */
@Composable
fun rememberVesqenMotionPolicy(): VesqenMotionPolicy = remember {
    VesqenMotionPolicy(reduceMotion = !ValueAnimator.areAnimatorsEnabled())
}
