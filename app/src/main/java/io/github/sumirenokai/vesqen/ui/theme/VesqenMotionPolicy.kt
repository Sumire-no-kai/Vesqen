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

    val modeChangeMillis: Int
        get() = if (reduceMotion) VesqenMotion.ReducedMotionMillis else VesqenMotion.ModeChangeMillis

    val playerExpandMillis: Int
        get() = if (reduceMotion) VesqenMotion.ReducedMotionMillis else VesqenMotion.PlayerExpandMillis

    val playerCollapseMillis: Int
        get() = if (reduceMotion) VesqenMotion.ReducedMotionMillis else VesqenMotion.PlayerCollapseMillis

    val trackChangeMillis: Int
        get() = if (reduceMotion) VesqenMotion.ReducedMotionMillis else VesqenMotion.TrackChangeMillis
}

/**
 * Android's animator switch is the dependable baseline available to this API-26 app. When it is
 * disabled, navigation falls back to the short crossfade defined by the product system.
 */
@Composable
fun rememberVesqenMotionPolicy(): VesqenMotionPolicy = remember {
    VesqenMotionPolicy(reduceMotion = !ValueAnimator.areAnimatorsEnabled())
}
