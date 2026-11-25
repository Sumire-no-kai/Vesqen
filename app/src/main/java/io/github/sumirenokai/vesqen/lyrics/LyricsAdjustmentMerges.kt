package io.github.sumirenokai.vesqen.lyrics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Adjustment-only recovery: SQLite decides whether a prepared file transfer committed. */
internal class LyricsAdjustmentMerges(
    private val directory: File,
    private val write: (File, ByteArray) -> Unit,
    private val duplicate: (File, File) -> Unit,
) {
    private val pending = ArrayList<Journal>()

    fun prepare(from: Long, into: Long): Boolean {
        if (from == into) return false
        val entries = files(from).map { file ->
            val key = file.name.substringAfter('.').removeSuffix(SUFFIX)
            Entry(key, target(into, key).exists())
        }
        if (entries.isEmpty()) return false
        val order = (journals().maxOfOrNull { it.order } ?: 0L) + 1L
        val file = File(directory, "$PREFIX${order.toString().padStart(20, '0')}-$from-$into.merge")
        val journal = Journal(order, from, into, entries, file)
        // This must precede the first sidecar mutation, even if the process dies before prepareCommit.
        val bytes = encode(journal)
        if (bytes.size > MAX_JOURNAL_BYTES) throw IOException("Oversized lyrics adjustment merge journal")
        write(file, bytes)
        pending += journal
        entries.filterNot(Entry::destinationExisted).forEach { entry ->
            try {
                duplicate(target(from, entry.key), target(into, entry.key))
            } catch (_: IOException) {
                // A committed chain must never pass through a row missing its intended correction.
                // If even a rename fails, let the catalog roll back while the journal still exists.
                move(target(from, entry.key), target(into, entry.key))
            }
        }
        return true
    }

    /** All prepares in this transaction are already committed, including chained merges. */
    fun committed() {
        val records = pending.toList()
        pending.clear()
        complete(records, trackExists = null)
    }

    fun rolledBack() {
        val records = pending.toList()
        pending.clear()
        records.asReversed().forEach(::restore)
    }

    fun clear() = pending.clear()

    /** A concurrent post-scan sweep must not remove a prepared transfer's only remaining copy. */
    fun protectedTrackIds(): Set<Long> = journals().flatMap { listOf(it.from, it.into) }.toSet()

    fun recover(trackExists: (Long) -> Boolean) {
        val records = journals()
        records.filter { trackExists(it.from) }.asReversed().forEach(::restore)
        complete(records.filterNot { trackExists(it.from) }, trackExists)
        pending.clear()
    }

    private fun complete(records: List<Journal>, trackExists: ((Long) -> Boolean)?) {
        val destinations = records.associate { it.from to it.into }
        records.forEach { record ->
            val chain = ArrayList<Long>()
            var id = record.from
            while (true) {
                if (id in chain) throw IOException("Cyclic lyrics adjustment merge")
                chain += id
                id = destinations[id] ?: break
            }
            val survivor = chain.last()
            record.entries.forEach { entry ->
                val destination = target(survivor, entry.key)
                if (trackExists == null || trackExists(survivor)) {
                    if (!destination.exists()) {
                        val original = chain.asReversed().map { target(it, entry.key) }.firstOrNull(File::exists)
                            ?: throw IOException("Missing lyrics adjustment during merge recovery")
                        move(original, destination)
                    }
                } else {
                    delete(destination)
                }
                chain.dropLast(1).forEach { removed -> delete(target(removed, entry.key)) }
            }
            delete(record.file)
        }
    }

    private fun restore(record: Journal) {
        record.entries.asReversed().filterNot(Entry::destinationExisted).forEach { entry ->
            val original = target(record.from, entry.key)
            val copy = target(record.into, entry.key)
            if (original.exists()) {
                delete(copy)
            } else if (copy.exists()) {
                move(copy, original)
            } else {
                throw IOException("Missing lyrics adjustment during rollback recovery")
            }
        }
        delete(record.file)
    }

    private fun journals(): List<Journal> = directory.listFiles().orEmpty()
        .filter { it.name.startsWith(PREFIX) && it.name.endsWith(".merge") }
        .map(::decode)
        .sortedBy(Journal::order)

    private fun files(trackId: Long): List<File> = directory.listFiles().orEmpty().filter {
        it.name.startsWith("$trackId.") && it.name.endsWith(SUFFIX) &&
            KEY.matches(it.name.substringAfter('.').removeSuffix(SUFFIX))
    }

    private fun target(trackId: Long, key: String) = File(directory, "$trackId.$key$SUFFIX")

    private fun encode(journal: Journal): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(1)
            output.writeLong(journal.order)
            output.writeLong(journal.from)
            output.writeLong(journal.into)
            output.writeInt(journal.entries.size)
            journal.entries.forEach { entry ->
                output.writeUTF(entry.key)
                output.writeBoolean(entry.destinationExisted)
            }
        }
        bytes.toByteArray()
    }

    private fun decode(file: File): Journal {
        val bytes = file.inputStream().use { it.readAtMost(MAX_JOURNAL_BYTES + 1) }
        if (bytes.size > MAX_JOURNAL_BYTES) throw IOException("Oversized lyrics adjustment merge journal")
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != 1) throw IOException("Unknown lyrics adjustment merge journal")
            val order = input.readLong()
            val from = input.readLong()
            val into = input.readLong()
            val count = input.readInt()
            if (order <= 0 || from <= 0 || into <= 0 || from == into || count < 0 || count > bytes.size / 67) {
                throw IOException("Invalid lyrics adjustment merge journal")
            }
            val entries = List(count) {
                val key = input.readUTF()
                if (!KEY.matches(key)) throw IOException("Invalid lyrics adjustment content key")
                Entry(key, input.readBoolean())
            }
            if (input.available() != 0) throw IOException("Trailing lyrics adjustment merge data")
            Journal(order, from, into, entries, file)
        }
    }

    private fun move(from: File, into: File) {
        Files.move(from.toPath(), into.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun delete(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Cannot clean up lyrics adjustment merge")
    }

    private data class Entry(val key: String, val destinationExisted: Boolean)
    private data class Journal(val order: Long, val from: Long, val into: Long, val entries: List<Entry>, val file: File)

    private companion object {
        const val PREFIX = ".adjustment-merge-"
        const val SUFFIX = ".adjustment"
        const val MAX_JOURNAL_BYTES = 512 * 1024
        val KEY = Regex("[0-9a-f]{64}")
    }
}
