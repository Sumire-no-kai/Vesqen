package io.github.sumirenokai.vesqen.playback

/**
 * Cycles to [mode], which [current] reads back; on the main thread. A new queue keeps the modes of
 * the one before, also in a new service (#165), so a test cannot count taps from sequential, and it
 * leaves sequential behind it.
 */
internal fun PlaybackController.cycleTo(mode: PlaybackOrderMode, current: () -> PlaybackOrderMode) {
    repeat(PlaybackOrderMode.entries.size) { if (current() != mode) cyclePlaybackOrderMode() }
}
