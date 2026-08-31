package io.github.sumirenokai.vesqen.ui

import java.util.Locale

fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0) / 1_000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
