package io.github.sumirenokai.vesqen.lyrics

import io.github.sumirenokai.vesqen.library.CatalogTrackIds
import io.github.sumirenokai.vesqen.library.TrackMergeFollower
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The tracks with imported lyrics; [revision] grows with every change, so loaders can key on it. */
data class ImportedLyrics(
    val trackIds: Set<Long> = emptySet(),
    val revision: Long = 0,
)

enum class LyricsImportResult {
    IMPORTED,
    UNREADABLE,
    NO_LYRICS,
    TOO_LARGE,

    /** The catalog was rebuilt or the track changed while the file was read: nothing was stored. */
    TRACK_CHANGED,
    FAILED,
}

/**
 * Lyrics the user imported (#169): one UTF-8 file per catalog track ID in the app's no-backup
 * directory, so they stay on this phone like the rest of its data. The original file is copied,
 * never kept open, so moving or deleting it later changes nothing.
 *
 * One plain lock guards every change, since [clearAll] runs from the catalog's synchronous
 * corruption callback, where a coroutine Mutex cannot be taken. [clearAll] raises the generation
 * before deleting, and an import or sweep that began under an older generation commits nothing:
 * its track IDs name other songs, or none, in the rebuilt catalog.
 */
internal class LyricsStore(
    private val directory: File,
    /** Gives `into` the content of `from`; tests replace it to fail as a full disk would. */
    duplicate: ((from: File, into: File) -> Unit)? = null,
) : TrackMergeFollower {
    private val lock = Any()
    private var generation = 0L
    private var loadedTrackIds: MutableSet<Long>? = null
    private val duplicate: (File, File) -> Unit = duplicate ?: ::linkOrCopy

    /** What [prepareMerge] did for each `(from, into)` of the open merging transaction. */
    private val preparedMerges = HashMap<Pair<Long, Long>, MergePreparation>()
    private val state = MutableStateFlow(ImportedLyrics())
    val importedLyrics: StateFlow<ImportedLyrics> = state.asStateFlow()

    fun generation(): Long = synchronized(lock) { generation }

    /** Lists the directory once, off the main thread, so [importedLyrics] holds what is stored. */
    fun load() {
        synchronized(lock) { trackIds() }
    }

    /** The stored lyrics of [trackId], or null when none were imported. */
    @Throws(IOException::class)
    fun read(trackId: Long): ByteArray? {
        val file = fileFor(trackId)
        return try {
            file.readBytes()
        } catch (_: FileNotFoundException) {
            null
        }
    }

    /**
     * Stores the lyrics file [bytes] for [trackId] as UTF-8, replacing an earlier import (owner
     * decision 4). A file that cannot be decoded or holds no lyrics is refused and nothing is stored.
     * The stored UTF-8 must fit [MAX_LYRICS_TEXT_BYTES] too, since every reader refuses a larger
     * file: Chinese or Japanese text in GBK, Big5, Shift_JIS or UTF-16 grows by up to half (#191 D12).
     */
    fun import(trackId: Long, bytes: ByteArray, expectedGeneration: Long): LyricsImportResult {
        if (bytes.size > MAX_LYRICS_TEXT_BYTES) return LyricsImportResult.TOO_LARGE
        val text = decodeLyricsText(bytes) ?: return LyricsImportResult.UNREADABLE
        if (parseLyrics(text) == null) return LyricsImportResult.NO_LYRICS
        val stored = text.toByteArray(Charsets.UTF_8)
        if (stored.size > MAX_LYRICS_TEXT_BYTES) return LyricsImportResult.TOO_LARGE
        return synchronized(lock) {
            if (generation != expectedGeneration) return LyricsImportResult.TRACK_CHANGED
            try {
                writeAtomically(fileFor(trackId)) { output -> output.write(stored) }
            } catch (_: IOException) {
                return LyricsImportResult.FAILED
            }
            trackIds().add(trackId)
            publish()
            LyricsImportResult.IMPORTED
        }
    }

    fun remove(trackId: Long) {
        synchronized(lock) {
            val removed = fileFor(trackId).delete()
            if (trackIds().remove(trackId) || removed) publish()
        }
    }

    /** A rebuilt catalog starts its IDs again, so every import goes (owner decision 7). */
    fun clearAll() {
        synchronized(lock) {
            generation++
            directory.listFiles()?.forEach(File::delete)
            loadedTrackIds = HashSet()
            publish()
        }
    }

    /**
     * Deletes the lyrics of tracks the catalog no longer holds: a pruned file, a removed folder or a
     * discarded record (owner decision 7: lyrics are not user state that keeps a row). IDs above
     * [CatalogTrackIds.largestAssignedId] belong to rows added after the read and are kept.
     */
    fun sweep(catalog: CatalogTrackIds, expectedGeneration: Long) {
        synchronized(lock) {
            if (generation != expectedGeneration) return
            val gone = trackIds().filter { it !in catalog.trackIds && it <= catalog.largestAssignedId }
            if (gone.isEmpty()) return
            gone.forEach { trackId -> fileFor(trackId).delete() }
            trackIds().removeAll(gone.toSet())
            publish()
        }
    }

    override fun prepareMerge(from: Long, into: Long) {
        synchronized(lock) {
            preparedMerges[from to into] = when {
                from == into || into in trackIds() || from !in trackIds() -> MergePreparation.NOTHING
                else -> try {
                    duplicate(fileFor(from), fileFor(into))
                    trackIds().add(into)
                    publish()
                    MergePreparation.DUPLICATED
                } catch (_: IOException) {
                    moveForMerge(from, into)
                }
            }
        }
    }

    /**
     * A full disk: the file is renamed to the survivor now, inside the transaction, since a rename
     * needs no space. After the commit it is already where it belongs, so neither a process death
     * before the clean-up nor a clean-up that reads the catalog then can delete it; a rollback
     * renames it back. Only a crash before the commit leaves it with the survivor early.
     */
    private fun moveForMerge(from: Long, into: Long): MergePreparation = try {
        Files.move(fileFor(from).toPath(), fileFor(into).toPath(), StandardCopyOption.ATOMIC_MOVE)
        trackIds().remove(from)
        trackIds().add(into)
        publish()
        MergePreparation.MOVED
    } catch (_: IOException) {
        // The only copy stays where it is; the commit must not delete it.
        MergePreparation.KEPT
    }

    override fun mergeCommitted(from: Long, into: Long) {
        synchronized(lock) {
            when (preparedMerges.remove(from to into)) {
                MergePreparation.MOVED, MergePreparation.KEPT -> Unit
                MergePreparation.NOTHING, MergePreparation.DUPLICATED, null -> remove(from)
            }
        }
    }

    override fun mergeRolledBack(from: Long, into: Long) {
        synchronized(lock) {
            when (preparedMerges.remove(from to into)) {
                MergePreparation.DUPLICATED -> remove(into)
                MergePreparation.MOVED -> try {
                    Files.move(fileFor(into).toPath(), fileFor(from).toPath(), StandardCopyOption.ATOMIC_MOVE)
                    trackIds().remove(into)
                    trackIds().add(from)
                    publish()
                } catch (_: IOException) {
                    // It stays with the survivor rather than being lost.
                }
                MergePreparation.NOTHING, MergePreparation.KEPT, null -> Unit
            }
        }
    }

    /**
     * A hard link needs no space and the file it names is complete, so it survives a crash between
     * the commit and the clean-up; a copy is the fallback where links are not allowed.
     */
    private fun linkOrCopy(from: File, into: File) {
        try {
            Files.createLink(into.toPath(), from.toPath())
            return
        } catch (_: UnsupportedOperationException) {
        } catch (_: IOException) {
        }
        writeAtomically(into) { output -> FileInputStream(from).use { input -> input.copyTo(output) } }
    }

    private fun trackIds(): MutableSet<Long> = loadedTrackIds ?: HashSet<Long>().also { ids ->
        directory.listFiles()?.forEach { file ->
            file.name.removeSuffix(LYRICS_SUFFIX).takeIf { file.name.endsWith(LYRICS_SUFFIX) }
                ?.toLongOrNull()?.let(ids::add)
        }
        loadedTrackIds = ids
        publish()
    }

    private fun publish() {
        state.value = ImportedLyrics(loadedTrackIds.orEmpty().toSet(), state.value.revision + 1)
    }

    private fun fileFor(trackId: Long) = File(directory, "$trackId$LYRICS_SUFFIX")

    private inline fun writeAtomically(target: File, write: (FileOutputStream) -> Unit) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        val temporary = File(directory, target.name + TEMPORARY_SUFFIX)
        try {
            FileOutputStream(temporary).use { output ->
                write(output)
                // Durable before the rename, so a power loss cannot leave an empty file in its place.
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    private enum class MergePreparation { NOTHING, DUPLICATED, MOVED, KEPT }
}

private const val LYRICS_SUFFIX = ".lrc"
private const val TEMPORARY_SUFFIX = ".tmp"
