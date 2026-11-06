package io.github.sumirenokai.vesqen.usb.chain

import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import java.io.File
import java.io.IOException

/** Finds real files the device can play. The delay Media3 itself sees is what `mode file` reports. */
internal object ChainFileScan {
    private val EXTENSIONS = setOf("flac", "wav", "m4a", "alac", "mp4", "aif", "aiff")
    private const val LIMIT = 2000
    private const val EXAMPLES = 3

    private class Group(var files: Int = 0, val examples: MutableList<String> = mutableListOf())

    fun scan(directory: File, report: (String) -> Unit) {
        val files = directory.walkTopDown().filter { it.isFile && it.extension.lowercase() in EXTENSIONS }.take(LIMIT).toList()
        report("Scanning ${files.size} files under $directory")
        val groups = sortedMapOf<String, Group>()
        var unreadable = 0
        files.forEach { file ->
            val format = audioFormatOf(file)
            if (format == null) {
                unreadable++
                return@forEach
            }
            val key = "${format.getString(MediaFormat.KEY_MIME)} ${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz"
            val group = groups.getOrPut(key) { Group() }
            group.files++
            if (group.examples.size < EXAMPLES) group.examples += file.path
        }
        groups.forEach { (key, group) -> report("$key: ${group.files} files, for example ${group.examples}") }
        report("Unreadable: $unreadable")
    }

    /**
     * What the library would know about [file], read the same way as `LocalAudioMetadataReader`: the
     * codec, and the bit depth the container declares (null when it declares none).
     */
    fun sourceFactsOf(file: File): TelemetrySourceFacts? {
        val format = audioFormatOf(file) ?: return null
        fun positive(key: String) = if (format.containsKey(key)) format.getInteger(key).takeIf { it > 0 } else null
        return TelemetrySourceFacts(
            codecMime = format.getString(MediaFormat.KEY_MIME),
            sampleRateHz = positive(MediaFormat.KEY_SAMPLE_RATE),
            bitDepth = positive("bits-per-sample"),
            channelCount = positive(MediaFormat.KEY_CHANNEL_COUNT),
        )
    }

    private fun audioFormatOf(file: File): MediaFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (_: IOException) {
            null
        } finally {
            extractor.release()
        }
    }
}
