package io.github.sumirenokai.vesqen.library

/** Product-level format contract. Decoder success remains a per-device acceptance result. */
data class M1AudioFormat(
    val id: String,
    val displayName: String,
    val extensions: Set<String>,
    val mimeHints: Set<String>,
    val lossless: Boolean,
    val requiredPcmBitDepths: Set<Int> = emptySet(),
)

val M1_AUDIO_FORMAT_MATRIX: List<M1AudioFormat> = listOf(
    M1AudioFormat("flac", "FLAC", setOf("flac"), setOf("audio/flac"), true, setOf(16, 24, 32)),
    M1AudioFormat("alac", "ALAC", setOf("alac", "m4a"), setOf("audio/alac"), true, setOf(16, 24, 32)),
    M1AudioFormat("wav", "WAV", setOf("wav"), setOf("audio/wav", "audio/x-wav"), true, setOf(16, 24, 32)),
    M1AudioFormat("aiff", "AIFF", setOf("aif", "aiff"), setOf("audio/aiff", "audio/x-aiff"), true, setOf(16, 24, 32)),
    M1AudioFormat("mp3", "MP3", setOf("mp3"), setOf("audio/mpeg"), false),
    M1AudioFormat("aac", "AAC", setOf("aac", "m4a"), setOf("audio/aac", "audio/mp4a-latm"), false),
    M1AudioFormat("vorbis", "Ogg Vorbis", setOf("ogg"), setOf("audio/ogg", "audio/vorbis"), false),
    M1AudioFormat("opus", "Opus", setOf("opus"), setOf("audio/opus"), false),
)

internal val M1_AUDIO_EXTENSIONS: Set<String> = M1_AUDIO_FORMAT_MATRIX
    .flatMap(M1AudioFormat::extensions)
    .toSet()

fun m1FormatFor(mimeType: String, fileName: String): M1AudioFormat? {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    val extension = fileName.substringAfterLast('.', "").lowercase()
    M1_AUDIO_FORMAT_MATRIX.firstOrNull { normalizedMime in it.mimeHints }?.let { return it }
    return M1_AUDIO_FORMAT_MATRIX.filter { extension in it.extensions }.singleOrNull()
}
