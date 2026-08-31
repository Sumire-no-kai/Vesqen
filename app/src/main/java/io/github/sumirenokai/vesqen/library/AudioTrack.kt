package io.github.sumirenokai.vesqen.library

/**
 * Metadata read from MediaStore. The URI is kept as an opaque content URI so playback never needs
 * to retain a private filesystem path.
 */
data class AudioTrack(
    val id: Long,
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
) {
    fun displaySubtitle(): String =
        listOf(artist, album)
            .filter { it.isNotBlank() && it != UNKNOWN_VALUE }
            .joinToString(SEPARATOR)

    companion object {
        private const val UNKNOWN_VALUE = "<unknown>"
        private const val SEPARATOR = " · "
    }
}
