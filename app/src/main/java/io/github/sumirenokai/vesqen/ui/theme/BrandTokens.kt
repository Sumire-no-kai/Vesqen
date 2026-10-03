package io.github.sumirenokai.vesqen.ui.theme

import androidx.compose.ui.unit.dp

object VesqenSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

object VesqenRadii {
    // Covers keep the hard edge of a record sleeve (B spec §4.3).
    val album = 4.dp
    val control = 12.dp
    val surface = 16.dp
}

object VesqenMotion {
    const val PressedMillis = 120
    const val ModeChangeMillis = 160
    const val StateChangeMillis = 180
    const val PlayerExpandMillis = 240
    const val PlayerCollapseMillis = 180
    // The focused player hands off to and from the compact shell without showing both complete
    // surfaces at once. These delays are part of the 240ms / 180ms total transitions above.
    const val PlayerHandoffDelayMillis = 24
    const val PlayerReturnRevealDelayMillis = 72
    const val TrackChangeMillis = 220
    const val ReducedMotionMillis = 80
    // B · Paper & Sound §7.
    const val AlbumTintMillis = 800
    const val CoverChangeMillis = 700
    const val NotesExpandMillis = 550
    const val NotesContentMillis = 500
}
