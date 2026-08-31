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
    /**
     * MediaStore album identity and its provider-owned artwork URI. Neither value is a filesystem
     * path; callers must treat the URI as temporary MediaStore access rather than a persistable
     * grant.
     */
    val albumId: Long? = null,
    val albumArtworkUri: String? = null,
    /** MediaStore modification time, used only to invalidate in-memory artwork thumbnails. */
    val dateModifiedSeconds: Long = 0,
    /** In-process scan revision so an explicit rescan can refresh provider artwork. */
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
