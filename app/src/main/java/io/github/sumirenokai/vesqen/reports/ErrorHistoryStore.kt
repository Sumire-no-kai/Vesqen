package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputFailureOrigin
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** IO-only, application-owned error journal. No player/telemetry observer or diagnostic recorder. */
internal class ErrorHistoryStore(private val directory: File) {
    private val file = File(directory, "errors.bin")
    private val pending = File(directory, "errors.next")

    /** Set once an unreadable journal (truncated, or written by another version) was discarded. */
    @Volatile var discardedUnreadableJournal = false
        private set

    @Synchronized
    fun append(event: ReportErrorEvent, nowEpochMs: Long) {
        val entries = load()
        val safe = DeviceReportPrivacy.error(event)
        // Historical exits are returned again after every launch; do not turn them into new errors.
        val duplicateExit = safe.kind == ReportErrorKind.PROCESS_EXIT && entries.any {
            it.kind == safe.kind && it.occurredAtEpochMs == safe.occurredAtEpochMs && it.platformCode == safe.platformCode
        }
        save(retain(if (duplicateExit) entries else entries + safe, nowEpochMs))
    }

    @Synchronized
    fun read(nowEpochMs: Long): List<ReportErrorEvent> {
        val entries = load()
        val retained = retain(entries, nowEpochMs)
        if (retained != entries) save(retained)
        // Interrupted writes are never read and must not retain expired records indefinitely.
        Files.deleteIfExists(pending.toPath())
        return retained
    }

    private fun retain(entries: List<ReportErrorEvent>, now: Long): List<ReportErrorEvent> {
        require(now >= 0)
        return entries.filter { it.occurredAtEpochMs <= now && now - it.occurredAtEpochMs <= MAX_AGE_MS }
            .sortedBy { it.occurredAtEpochMs }.takeLast(MAX_EVENTS)
    }

    /**
     * The journal is a bounded cache of recent errors. One that cannot be decoded is dropped so the
     * history keeps working; the reset is reported instead of failing every later read and write.
     */
    private fun load(): List<ReportErrorEvent> = try {
        decode()
    } catch (unreadable: IOException) {
        if (unreadable is java.nio.file.FileSystemException) throw unreadable
        Files.deleteIfExists(file.toPath())
        discardedUnreadableJournal = true
        emptyList()
    }

    private fun decode(): List<ReportErrorEvent> {
        if (!file.exists()) return emptyList()
        if (file.length() > MAX_FILE_BYTES) throw IOException("Error journal exceeds its size limit")
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != 1) throw IOException("Unsupported error journal version")
                val count = input.readInt()
                if (count !in 0..MAX_EVENTS) throw IOException("Invalid error journal count")
                return List(count) {
                    val kind = ReportErrorKind.valueOf(input.readUTF())
                    val epoch = input.readLong()
                    val elapsed = input.readLong().takeIf { it >= 0 }
                    val code = if (input.readBoolean()) input.readInt() else null
                    val strict = input.readUTF().takeIf(String::isNotEmpty)?.let(UsbOutputFailure::valueOf)
                    val origin = input.readUTF().takeIf(String::isNotEmpty)?.let(UsbOutputFailureOrigin::valueOf)
                    val format = if (input.readBoolean()) FailedTrackFormat(
                        container = input.readUTF().takeIf(String::isNotEmpty),
                        codecMime = input.readUTF().takeIf(String::isNotEmpty),
                        sampleRateHz = input.readInt().takeIf { it > 0 },
                        bitDepth = input.readInt().takeIf { it > 0 },
                        channelCount = input.readInt().takeIf { it > 0 },
                        source = ErrorFormatSource.valueOf(input.readUTF()),
                        privacyFiltered = input.readBoolean(),
                        codecLabel = input.readUTF().takeIf(String::isNotEmpty),
                    ) else null
                    DeviceReportPrivacy.error(ReportErrorEvent(kind, epoch, elapsed, code, strict, origin, format,
                        input.readUTF().takeIf(String::isNotEmpty)))
                }.also { if (input.read() != -1) throw IOException("Unexpected error journal data") }
            }
        } catch (invalid: IllegalArgumentException) {
            throw IOException("Invalid error journal record", invalid)
        }
    }

    private fun save(entries: List<ReportErrorEvent>) {
        Files.createDirectories(directory.toPath())
        val stream = pending.outputStream()
        DataOutputStream(stream.buffered()).use { output ->
            output.writeInt(1)
            output.writeInt(entries.size)
            entries.forEach { event ->
                output.writeUTF(event.kind.name)
                output.writeLong(event.occurredAtEpochMs)
                output.writeLong(event.occurredAtElapsedRealtimeMs ?: -1)
                output.writeBoolean(event.platformCode != null)
                event.platformCode?.let(output::writeInt)
                output.writeUTF(event.strictFailure?.name.orEmpty())
                output.writeUTF(event.strictOrigin?.name.orEmpty())
                output.writeBoolean(event.format != null)
                event.format?.let { format ->
                    output.writeUTF(format.container.orEmpty()); output.writeUTF(format.codecMime.orEmpty())
                    output.writeInt(format.sampleRateHz ?: 0); output.writeInt(format.bitDepth ?: 0)
                    output.writeInt(format.channelCount ?: 0); output.writeUTF(format.source.name)
                    output.writeBoolean(format.privacyFiltered)
                    output.writeUTF(format.codecLabel.orEmpty())
                }
                output.writeUTF(event.fileName.orEmpty())
            }
            output.flush()
            // Durable before the rename, so a power loss cannot leave a truncated journal behind.
            stream.fd.sync()
        }
        if (pending.length() > MAX_FILE_BYTES) throw IOException("Error journal exceeds its size limit")
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val MAX_EVENTS = 100
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
        const val MAX_FILE_BYTES = 256L * 1_024
    }
}
