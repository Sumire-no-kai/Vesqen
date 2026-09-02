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
    val albumArtist: String = "",
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String = "",
    /** Provider display name only; this is never resolved to a private filesystem path. */
    val fileName: String = "",
    /** Relative/provider folder label used for browsing without exposing a raw filesystem path. */
    val folderName: String = "",
    val fileSizeBytes: Long = 0,
    val mimeType: String = "",
    val codec: String = "",
    val channelCount: Int? = null,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val bitrate: Int? = null,
    val isFavorite: Boolean = false,
    val lastPlayedAtMs: Long = 0,
    val playCount: Int = 0,
) {
    fun displaySubtitle(): String =
        listOf(artist, album)
            .filter { it.isNotBlank() && it != UNKNOWN_VALUE }
            .joinToString(SEPARATOR)

    fun technicalSummary(): String = buildList {
        codec.takeIf(String::isNotBlank)?.let(::add)
        sampleRateHz?.takeIf { it > 0 }?.let { add("${it / 1_000f} kHz") }
        bitDepth?.takeIf { it > 0 }?.let { add("$it-bit") }
        channelCount?.takeIf { it > 0 }?.let { add("$it ch") }
    }.joinToString(SEPARATOR)

    companion object {
        private const val UNKNOWN_VALUE = "<unknown>"
        private const val SEPARATOR = " · "
    }
}
