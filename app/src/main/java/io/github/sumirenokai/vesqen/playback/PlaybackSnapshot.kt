package io.github.sumirenokai.vesqen.playback

enum class PlaybackRepeatMode {
    OFF,
    ALL,
    ONE,
}

data class PlaybackSnapshot(
    val isControllerReady: Boolean = false,
    val isPlaying: Boolean = false,
    val trackId: Long? = null,
    /** Opaque source URI retained from Media3 so artwork can survive a UI/controller reconnect. */
    val mediaUri: String = "",
    /** Provider-owned or Media3 metadata artwork URI; never a filesystem path. */
    val albumArtworkUri: String? = null,
    /** In-process MediaStore scan revision paired with [albumArtworkUri]. */
    val artworkRevision: Long = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** True only when the session grants a usable route to the previous queue item. */
    val canSkipPrevious: Boolean = hasPrevious,
    /** True only when the session grants a usable route to the next queue item. */
    val canSkipNext: Boolean = hasNext,
    val shuffleEnabled: Boolean = false,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    /** Zero-based media item index and item count reported by the active Media3 player. */
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val declaration: OutputDeclaration = OutputDeclaration.SYSTEM_MIXED,
) {
    val hasActiveTrack: Boolean
        get() = trackId != null

    val progressFraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val queuePosition: Int?
        get() = queueSize.takeIf { it > 0 }?.let { queueIndex.coerceIn(0, it - 1) + 1 }
}
