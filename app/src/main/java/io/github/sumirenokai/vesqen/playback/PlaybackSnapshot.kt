package io.github.sumirenokai.vesqen.playback

enum class PlaybackRepeatMode {
    OFF,
    ALL,
    ONE,
}

/**
 * The listener-facing play order used by Vesqen's single footer control.
 *
 * Media3 exposes shuffle and repeat as independent switches and can therefore represent compound
 * states. Vesqen's normal click cycle keeps its common listening choices mutually exclusive, while
 * still representing externally supplied compound states exactly instead of hiding one switch.
 */
enum class PlaybackOrderMode {
    SEQUENTIAL,
    SHUFFLE,
    REPEAT_ALL,
    REPEAT_ONE,
    /** An externally supplied Media3 compound state; never selected by Vesqen's normal cycle. */
    SHUFFLE_REPEAT_ALL,
    /** An externally supplied Media3 compound state; never selected by Vesqen's normal cycle. */
    SHUFFLE_REPEAT_ONE,
}

internal data class PlaybackOrderSettings(
    val shuffleEnabled: Boolean,
    val repeatMode: PlaybackRepeatMode,
)

/** Resolve every Media3 state exactly; compound states must never be hidden behind one glyph. */
internal fun resolvePlaybackOrderMode(
    shuffleEnabled: Boolean,
    repeatMode: PlaybackRepeatMode,
): PlaybackOrderMode = when {
    shuffleEnabled && repeatMode == PlaybackRepeatMode.ALL -> PlaybackOrderMode.SHUFFLE_REPEAT_ALL
    shuffleEnabled && repeatMode == PlaybackRepeatMode.ONE -> PlaybackOrderMode.SHUFFLE_REPEAT_ONE
    shuffleEnabled -> PlaybackOrderMode.SHUFFLE
    repeatMode == PlaybackRepeatMode.ALL -> PlaybackOrderMode.REPEAT_ALL
    repeatMode == PlaybackRepeatMode.ONE -> PlaybackOrderMode.REPEAT_ONE
    else -> PlaybackOrderMode.SEQUENTIAL
}

internal fun PlaybackOrderMode.next(): PlaybackOrderMode = when (this) {
    PlaybackOrderMode.SEQUENTIAL -> PlaybackOrderMode.SHUFFLE
    PlaybackOrderMode.SHUFFLE -> PlaybackOrderMode.REPEAT_ALL
    PlaybackOrderMode.REPEAT_ALL -> PlaybackOrderMode.REPEAT_ONE
    PlaybackOrderMode.REPEAT_ONE -> PlaybackOrderMode.SEQUENTIAL
    // Vesqen does not generate compound modes. If an external controller supplies one, one tap
    // returns the player to an unambiguous baseline before the normal cycle resumes.
    PlaybackOrderMode.SHUFFLE_REPEAT_ALL,
    PlaybackOrderMode.SHUFFLE_REPEAT_ONE -> PlaybackOrderMode.SEQUENTIAL
}

internal fun PlaybackOrderMode.toSettings(): PlaybackOrderSettings = when (this) {
    PlaybackOrderMode.SEQUENTIAL -> PlaybackOrderSettings(
        shuffleEnabled = false,
        repeatMode = PlaybackRepeatMode.OFF,
    )
    PlaybackOrderMode.SHUFFLE -> PlaybackOrderSettings(
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.OFF,
    )
    PlaybackOrderMode.REPEAT_ALL -> PlaybackOrderSettings(
        shuffleEnabled = false,
        repeatMode = PlaybackRepeatMode.ALL,
    )
    PlaybackOrderMode.REPEAT_ONE -> PlaybackOrderSettings(
        shuffleEnabled = false,
        repeatMode = PlaybackRepeatMode.ONE,
    )
    PlaybackOrderMode.SHUFFLE_REPEAT_ALL -> PlaybackOrderSettings(
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ALL,
    )
    PlaybackOrderMode.SHUFFLE_REPEAT_ONE -> PlaybackOrderSettings(
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ONE,
    )
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

    /** The exact listener-facing projection of Media3's shuffle and repeat switches. */
    val playbackOrderMode: PlaybackOrderMode
        get() = resolvePlaybackOrderMode(
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
        )
}
