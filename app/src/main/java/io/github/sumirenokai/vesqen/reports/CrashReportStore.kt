package io.github.sumirenokai.vesqen.reports

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun crashDirectory(context: Context) = File(context.noBackupFilesDir, "device-report-crashes")

/**
 * #113: the last few crash records, one small file each, plus the time the crash question was last
 * asked. [write] runs inside the crash handler: it takes no lock and never reads existing records,
 * so it cannot wait on other IO. A record becomes visible only by an atomic rename, so readers never
 * see half of one. Everything else runs on IO threads.
 */
internal class CrashReportStore(private val directory: File) {
    private val promptState = File(directory, "prompt-state.bin")

    /** Set once an unreadable record (truncated, or written by another version) was deleted. */
    @Volatile var discardedUnreadableRecord = false
        private set

    /** The crash path: write the new record, then drop the oldest beyond [MAX_CRASHES]. */
    fun write(record: CrashRecord) {
        Files.createDirectories(directory.toPath())
        val pending = File(directory, "${record.id}$PENDING")
        val stream = pending.outputStream()
        DataOutputStream(stream.buffered()).use { output ->
            encode(CrashCapture.filtered(record), output)
            output.flush()
            // Durable before the rename: the process ends right after this.
            stream.fd.sync()
        }
        Files.move(pending.toPath(), File(directory, "${record.id}$RECORD").toPath(), StandardCopyOption.ATOMIC_MOVE)
        recordFiles().sortedByDescending(::fileTime).drop(MAX_CRASHES).forEach { Files.deleteIfExists(it.toPath()) }
    }

    @Synchronized
    fun read(nowEpochMs: Long): List<CrashRecord> {
        require(nowEpochMs >= 0)
        // A write that never reached its rename leaves a .next file. A recent one may still be in
        // progress on a crashing thread, so only older leftovers are removed.
        directory.listFiles { file -> file.name.endsWith(PENDING) }.orEmpty()
            .filter { nowEpochMs - it.lastModified() > PENDING_GRACE_MS }
            .forEach { Files.deleteIfExists(it.toPath()) }
        val decoded = recordFiles().mapNotNull(::decodeOrDiscard)
        val retained = decoded
            .filter { it.occurredAtEpochMs <= nowEpochMs && nowEpochMs - it.occurredAtEpochMs <= MAX_AGE_MS }
            .sortedBy { it.occurredAtEpochMs }.takeLast(MAX_CRASHES)
        delete((decoded - retained.toSet()).map { it.id })
        return retained
    }

    @Synchronized
    fun delete(ids: Collection<String>) {
        ids.forEach { Files.deleteIfExists(File(directory, "$it$RECORD").toPath()) }
    }

    @Synchronized
    fun deleteAll() = delete(recordFiles().map { it.name.removeSuffix(RECORD) })

    /** The newest crash time already offered in the crash question; 0 before the first one. */
    @Synchronized
    fun promptedThrough(): Long {
        if (!promptState.exists()) return 0
        if (promptState.length() != Long.SIZE_BYTES.toLong()) {
            // Unreadable: asking again about recent crashes is the safe direction.
            Files.deleteIfExists(promptState.toPath())
            return 0
        }
        return DataInputStream(promptState.inputStream()).use { it.readLong() }
    }

    @Synchronized
    fun setPromptedThrough(epochMs: Long) {
        Files.createDirectories(directory.toPath())
        val pending = File(directory, "prompt-state.next")
        val stream = pending.outputStream()
        DataOutputStream(stream).use { output ->
            output.writeLong(epochMs)
            output.flush()
            stream.fd.sync()
        }
        Files.move(pending.toPath(), promptState.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun recordFiles(): List<File> = directory.listFiles { file -> file.name.endsWith(RECORD) }.orEmpty().toList()

    /** Record files are named "<epoch ms>-<suffix>.crash"; the crash path sorts without reading them. */
    private fun fileTime(file: File): Long = file.name.substringBefore('-').toLongOrNull() ?: 0

    /** Content that cannot be decoded is dropped and reported; a file that cannot be opened is a storage failure. */
    private fun decodeOrDiscard(file: File): CrashRecord? = try {
        decode(file)
    } catch (unreadable: IOException) {
        when {
            !file.exists() -> null // A crash being written right now pruned it.
            unreadable is FileSystemException || unreadable is FileNotFoundException -> throw unreadable
            else -> {
                Files.deleteIfExists(file.toPath())
                discardedUnreadableRecord = true
                null
            }
        }
    }

    private fun decode(file: File): CrashRecord {
        if (file.length() > MAX_RECORD_BYTES) throw IOException("Crash record exceeds its size limit")
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != 1) throw IOException("Unsupported crash record version")
                val occurredAt = input.readLong()
                val appVersion = input.readUTF()
                val versionCode = input.readInt()
                val causesOmitted = input.readBoolean()
                val exceptionCount = input.readInt()
                if (exceptionCount !in 1..CrashCapture.MAX_EXCEPTIONS) throw IOException("Invalid crash record")
                val exceptions = List(exceptionCount) {
                    val type = input.readUTF().takeIf(String::isNotEmpty)
                    val omitted = input.readInt()
                    val frameCount = input.readInt()
                    if (frameCount !in 0..CrashCapture.MAX_FRAMES) throw IOException("Invalid crash record")
                    CrashException(type, List(frameCount) {
                        CrashFrame(
                            input.readUTF().takeIf(String::isNotEmpty),
                            input.readUTF().takeIf(String::isNotEmpty),
                            input.readInt().takeIf { it >= 0 },
                        )
                    }, omitted)
                }
                if (input.read() != -1) throw IOException("Unexpected crash record data")
                return CrashCapture.filtered(CrashRecord(file.name.removeSuffix(RECORD), occurredAt, appVersion,
                    versionCode, exceptions, causesOmitted))
            }
        } catch (invalid: IllegalArgumentException) {
            throw IOException("Invalid crash record", invalid)
        }
    }

    private fun encode(record: CrashRecord, output: DataOutputStream) {
        output.writeInt(1)
        output.writeLong(record.occurredAtEpochMs)
        output.writeUTF(record.appVersion)
        output.writeInt(record.versionCode)
        output.writeBoolean(record.causesOmitted)
        output.writeInt(record.exceptions.size)
        record.exceptions.forEach { exception ->
            output.writeUTF(exception.type.orEmpty())
            output.writeInt(exception.omittedFrames)
            output.writeInt(exception.frames.size)
            exception.frames.forEach { frame ->
                output.writeUTF(frame.className.orEmpty())
                output.writeUTF(frame.methodName.orEmpty())
                output.writeInt(frame.line ?: -1)
            }
        }
    }

    companion object {
        const val MAX_CRASHES = 3
        /** The same seven days as the error history. */
        const val MAX_AGE_MS = ErrorHistoryStore.MAX_AGE_MS
        /** Above the largest record the capture limits allow (about 30 KiB). */
        const val MAX_RECORD_BYTES = 64L * 1_024
        const val PENDING_GRACE_MS = 60_000L
        private const val RECORD = ".crash"
        private const val PENDING = ".next"

        /** Sortable by time without reading the file; the suffix keeps two records apart. */
        fun recordId(occurredAtEpochMs: Long) = "$occurredAtEpochMs-${java.lang.Long.toHexString(System.nanoTime())}"
    }
}
