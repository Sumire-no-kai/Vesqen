package io.github.sumirenokai.vesqen.playback

data class PlaybackSnapshot(
    val isControllerReady: Boolean = false,
    val isPlaying: Boolean = false,
    val trackId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val declaration: OutputDeclaration = OutputDeclaration.SYSTEM_MIXED,
) {
    val hasActiveTrack: Boolean
        get() = trackId != null

    val progressFraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
