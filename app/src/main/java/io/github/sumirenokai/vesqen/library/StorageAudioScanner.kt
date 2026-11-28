package io.github.sumirenokai.vesqen.library

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A mounted shared-storage volume; [id] names it as [fileLocationKey] does: "primary" or a card's UUID. */
internal data class StorageVolumeRoot(val id: String, val root: File)

/**
 * The volumes the whole-storage scan walks (#249): the phone's own storage, memory cards and USB
 * drives that are mounted. Android 9 and 10 have no `StorageVolume.getDirectory`, so a removable
 * volume is the `/storage/<UUID>` folder Android mounts it at.
 */
internal fun mountedStorageVolumes(context: Context): List<StorageVolumeRoot> {
    val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
    return manager.storageVolumes.mapNotNull { volume ->
        val state = volume.state
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
        val id = if (volume.isPrimary) PRIMARY_STORAGE_VOLUME else volume.uuid?.lowercase() ?: return@mapNotNull null
        val root = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> volume.directory
            volume.isPrimary -> @Suppress("DEPRECATION") Environment.getExternalStorageDirectory()
            else -> volume.uuid?.let { File(STORAGE_MOUNT_ROOT, it) }
        }
        root?.takeIf(File::isDirectory)?.let { StorageVolumeRoot(id, it) }
    }
}

/** An audio file the whole-storage scan found, with the cover image beside it, if any. */
internal data class StorageAudioFile(
    val file: File,
    val volumeId: String,
    /** The folder's path from the volume's root, "Music/Album", as MediaStore's `RELATIVE_PATH`. */
    val folderName: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
    val albumArtwork: File? = null,
    val albumArtworkFingerprint: String = "",
) {
    val path: String get() = file.path

    val locationKey: String?
        get() = fileLocationKey(volumeId, listOf(folderName, file.name).filter(String::isNotEmpty).joinToString("/"))

    val fingerprint: String = libraryFingerprint(
        "file",
        LIBRARY_METADATA_REVISION,
        file.path,
        lastModifiedMs,
        sizeBytes,
        albumArtwork?.path,
        albumArtworkFingerprint,
        *dsdFingerprintFields(file.name, null),
    )

    fun toTrackCandidate(): LibraryTrackCandidate = LibraryTrackCandidate(
        remoteId = file.path,
        contentUri = Uri.fromFile(file).toString(),
        title = file.name.toTrackTitleFallback(),
        artist = "",
        album = "",
        durationMs = 0,
        albumArtworkUri = albumArtwork?.let { Uri.fromFile(it).toString() },
        dateModifiedSeconds = lastModifiedMs / 1_000,
        sizeBytes = sizeBytes,
        fileName = file.name,
        folderName = folderName,
        fingerprint = fingerprint,
        locationKey = locationKey,
    )
}

/**
 * #249: walks the shared storage volumes for the whole-storage scan. It leaves out the Android folder
 * at a volume's root, hidden folders and files (a name that starts with a dot, which also covers the
 * "._" copies macOS leaves beside music), and a folder holding a ".nomedia" file with all below it. It
 * reads only the names it is given and the audio files it reports; no other file is opened.
 *
 * A folder that cannot be listed makes the walk incomplete: [IncompleteLibraryProviderException] is
 * thrown at its end, after every other file was reported, so the scan prunes nothing it could not see.
 */
internal class StorageAudioScanner {
    suspend fun scan(
        volumes: List<StorageVolumeRoot>,
        shouldPause: () -> Boolean,
        onSkippedAudio: suspend (UnsupportedAudioFormat) -> Unit = {},
        onAudioFile: suspend (StorageAudioFile) -> Unit,
    ): ScanIterationResult {
        val scanContext = currentCoroutineContext()
        var processedTrackCount = 0
        var incomplete = false
        for (volume in volumes) {
            val visited = HashSet<String>()
            val pending = ArrayDeque<File>().apply { add(volume.root) }
            while (pending.isNotEmpty()) {
                scanContext.ensureActive()
                if (shouldPause()) return ScanIterationResult(completed = false, processedTrackCount = processedTrackCount)
                val directory = pending.removeFirst()
                // A link back to a folder above would otherwise be walked without end.
                if (!visited.add(canonicalPath(directory) ?: continue)) continue
                val entries = directory.listFiles()
                if (entries == null) {
                    incomplete = true
                    continue
                }
                if (directory.hasNoMediaMarker()) continue
                val folderName = relativeFolder(volume.root, directory)
                val cover = entries.filter(File::isFile).sortedBy(File::getName).firstOrNull { isSameDirectoryCover("", it.name) }
                val coverFingerprint = cover?.let { libraryFingerprint(it.path, it.lastModified(), it.length()) }.orEmpty()
                for (entry in entries.sortedBy(File::getName)) {
                    scanContext.ensureActive()
                    if (shouldPause()) return ScanIterationResult(completed = false, processedTrackCount = processedTrackCount)
                    val name = entry.name
                    if (!entry.isStorageScanEntry(volume.root)) continue
                    if (entry.isDirectory) {
                        pending.add(entry)
                    } else if (isSupportedAudioDocument(null, name)) {
                        onAudioFile(
                            StorageAudioFile(
                                file = entry,
                                volumeId = volume.id,
                                folderName = folderName,
                                lastModifiedMs = entry.lastModified(),
                                sizeBytes = entry.length(),
                                albumArtwork = cover,
                                albumArtworkFingerprint = coverFingerprint,
                            ),
                        )
                        processedTrackCount++
                    } else {
                        knownUnsupportedAudioFormat(null, name)?.let { onSkippedAudio(it) }
                    }
                }
            }
        }
        if (incomplete) throw IncompleteLibraryProviderException()
        return ScanIterationResult(completed = true, processedTrackCount = processedTrackCount)
    }

    private fun canonicalPath(directory: File): String? = try {
        directory.canonicalPath
    } catch (_: IOException) {
        null
    }

    private fun relativeFolder(root: File, directory: File): String =
        directory.path.removePrefix(root.path).trim('/')
}

/** The names skipped by the storage walk, also checked before transferring a removed folder's tracks. */
internal fun File.isStorageScanEntry(root: File): Boolean =
    !name.startsWith('.') && !(parentFile == root && name == ANDROID_FOLDER)

private fun File.hasNoMediaMarker(): Boolean = File(this, NO_MEDIA).isFile

/** Checking a single file's directory also needs the exclusions on its ancestors. */
internal fun File.isStorageScanDirectory(root: File): Boolean {
    var directory = this
    while (directory != root) {
        if (!directory.isStorageScanEntry(root) || directory.hasNoMediaMarker()) return false
        directory = directory.parentFile ?: return false
    }
    return !root.hasNoMediaMarker()
}

private const val STORAGE_MOUNT_ROOT = "/storage"
private const val NO_MEDIA = ".nomedia"
private const val ANDROID_FOLDER = "Android"
