package io.github.sumirenokai.vesqen.library

/**
 * Metadata read from a locally authorised MediaStore or SAF source. The URI is kept as an opaque
 * content URI so playback never needs to retain a private filesystem path.
 */
data class AudioTrack(
    val id: Long,
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /**
     * Provider album identity and artwork URI when a source exposes one. Neither value is a
     * filesystem path; callers must treat the URI as an opaque provider capability.
     */
    val albumId: Long? = null,
    val albumArtworkUri: String? = null,
    /** Source modification time, used only to invalidate in-memory artwork thumbnails. */
    val dateModifiedSeconds: Long = 0,
    /** Catalog scan revision so an explicit rescan can refresh provider artwork. */
    val artworkRevision: Long = 0,
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
