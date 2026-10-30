package io.github.sumirenokai.vesqen.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small ViewModel-facing seam for the durable local catalog. Provider-specific identities,
 * persisted SAF grants, delta detection, and reconciliation live behind this boundary. Stateful
 * operations are suspending so the production adapter can serialize snapshots and edits, and keep
 * scans and folder removal apart; pause/resume remain immediate control signals observed between
 * provider rows.
 */
interface LibraryCatalog : Closeable {
    suspend fun snapshot(includeDeviceLibrary: Boolean): LibraryCatalogSnapshot

    suspend fun addFolder(treeUri: Uri)

    suspend fun removeFolder(sourceId: String)

    fun pause()

    fun resume()

    suspend fun setFavorite(trackId: Long, favorite: Boolean)

    suspend fun saveTrackOrder(playlistId: Long?, trackIds: List<Long>)

    suspend fun createPlaylist(name: String): Long?

    suspend fun renamePlaylist(playlistId: Long, name: String)

    suspend fun deletePlaylist(playlistId: Long)

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long)

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)

    suspend fun movePlaylistTrack(playlistId: Long, fromIndex: Int, toIndex: Int)

    /**
     * Gives an unmatched record the song [trackId] the user chose for it (#139). The record keeps its
     * ID and [trackId]'s row goes. False when the record no longer waits for a choice.
     */
    suspend fun assignUnmatchedRecord(recordId: Long, trackId: Long): Boolean

    /** Deletes an unmatched record with its favorite, plays and playlist places (#139). */
    suspend fun discardUnmatchedRecord(recordId: Long)

    /** Every row's ID, hidden ones included, for the lyrics clean-up (#169). */
    suspend fun readTrackIdsForSweep(): CatalogTrackIds

    /** The file a catalog row names now, or null without that row (#169 lyrics import). */
    suspend fun trackContentUri(trackId: Long): String?

    suspend fun refresh(
        includeDeviceLibrary: Boolean,
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): LibraryRefreshResult
}

/** [store] is the application's one database helper (#165); every catalog shares it and none closes it. */
internal class AndroidLibraryCatalog(
    context: Context,
    private val store: LibraryCatalogStore,
) : LibraryCatalog {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val mediaStore = MediaStoreAudioRepository(appContext, contentResolver)
    private val treeScanner = TreeAudioScanner(contentResolver)
    private val metadataReader = LocalAudioMetadataReader(appContext)
    private val scanGate = LibraryScanGate(store.rebuilds)
    private val operationGate = LibraryCatalogOperationGate()

    private val resumePausedSourcesRequested = AtomicBoolean()

    override suspend fun snapshot(includeDeviceLibrary: Boolean): LibraryCatalogSnapshot = serialized {
        store.reconcileInterruptedScans()
        snapshotInternal(includeDeviceLibrary)
    }

    /**
     * The tracks the Library would list now, for the playback service to restore a saved queue
     * (#52). Unlike [snapshot] this only reads: a scan that the UI's catalog is running at the same
     * time must not be marked interrupted.
     */
    suspend fun playableTracks(includeDeviceLibrary: Boolean): List<AudioTrack> = serialized {
        snapshotInternal(includeDeviceLibrary).tracks
    }

    override suspend fun addFolder(treeUri: Uri) {
        // The picker's grant lasts only as long as the screen that asked for it, and a scan can hold
        // the catalog for minutes, so the grant is made persistent before waiting for the catalog.
        val persistedPermission = contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }
        if (!persistedPermission) {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            serialized {
                store.upsertFolderSource(
                    treeUri = treeUri.toString(),
                    displayName = treeScanner.displayName(treeUri),
                )
            }
        } catch (failure: Throwable) {
            // A folder that was never added must not keep a grant (cancellation included).
            if (!persistedPermission) {
                try {
                    contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (releaseFailure: RuntimeException) {
                    failure.addSuppressed(releaseFailure)
                }
            }
            throw failure
        }
    }

    // A scan of the folder must not run into its removal, so removal waits for any scan.
    override suspend fun removeFolder(sourceId: String): Unit = processScanLock.withLock {
        serialized {
            val treeUri = store.removeFolderSource(sourceId) ?: return@serialized Unit
            try {
                contentResolver.releasePersistableUriPermission(
                    treeUri.toUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                // The provider may already have revoked or rejected the grant; the local source is
                // still removed and no provider failure is allowed to resurrect it.
            }
        }
    }

    override fun pause() {
        scanGate.pause()
    }

    override fun resume() {
        resumePausedSourcesRequested.set(true)
        scanGate.resume()
    }

    override suspend fun setFavorite(trackId: Long, favorite: Boolean) = serialized {
        store.setFavorite(trackId, favorite)
    }

    override suspend fun saveTrackOrder(playlistId: Long?, trackIds: List<Long>) = serialized {
        store.saveTrackOrder(playlistId, trackIds)
    }

    override suspend fun createPlaylist(name: String): Long? = serialized { store.createPlaylist(name) }

    override suspend fun renamePlaylist(playlistId: Long, name: String) = serialized {
        store.renamePlaylist(playlistId, name)
    }

    override suspend fun deletePlaylist(playlistId: Long) = serialized { store.deletePlaylist(playlistId) }

    override suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) = serialized {
        store.addTrackToPlaylist(playlistId, trackId)
    }

    override suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) = serialized {
        store.removeTrackFromPlaylist(playlistId, trackId)
    }

    override suspend fun movePlaylistTrack(playlistId: Long, fromIndex: Int, toIndex: Int) = serialized {
        store.movePlaylistTrack(playlistId, fromIndex, toIndex)
    }

    // A scan holds the rows it read at its start and marks them seen as it goes, so a record that took
    // over another row during it would be hidden again at its completion: a choice waits for any scan.
    override suspend fun assignUnmatchedRecord(recordId: Long, trackId: Long): Boolean = processScanLock.withLock {
        serialized { store.assignUnmatchedRecord(recordId, trackId) }
    }

    override suspend fun readTrackIdsForSweep(): CatalogTrackIds = serialized { store.readTrackIdsForSweep() }

    override suspend fun trackContentUri(trackId: Long): String? = serialized { store.readTrackContentUri(trackId) }

    override suspend fun discardUnmatchedRecord(recordId: Long): Unit = processScanLock.withLock {
        serialized { store.discardUnmatchedRecord(recordId) }
    }

    /**
     * A scan holds only [processScanLock]. Holding this catalog's gate as well made favorites,
     * playlist edits and reads wait for the whole scan, minutes for a large library (#136 R12).
     * Edits touch the user's columns, which a scan never writes, and each store call is its own
     * statement or transaction, so they now run between the scan's rows.
     */
    override suspend fun refresh(
        includeDeviceLibrary: Boolean,
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): LibraryRefreshResult = processScanLock.withLock {
        operationGate.checkOpen()
        refreshLocked(includeDeviceLibrary, onProgress)
    }

    private suspend fun refreshLocked(
        includeDeviceLibrary: Boolean,
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): LibraryRefreshResult {
        scanGate.startScan()
        store.reconcileInterruptedScans()
        if (resumePausedSourcesRequested.getAndSet(false)) {
            store.preparePausedSourcesForResume()
        }

        var hadFailure = false
        var scanPaused = false
        if (includeDeviceLibrary) {
            when (scanDeviceLibrary(onProgress)) {
                SourceScanOutcome.FAILED,
                SourceScanOutcome.INTERRUPTED,
                -> hadFailure = true
                // A rebuilt catalog has no sources left to scan; the Library starts a full scan of it.
                SourceScanOutcome.PAUSED,
                SourceScanOutcome.REBUILT,
                -> scanPaused = true
                else -> Unit
            }
        }

        if (!scanPaused) {
            val availableTreeUris = persistedReadableTreeUris()
            store.readSources()
                .asSequence()
                .filter { it.kind == LibrarySourceKind.FOLDER && it.treeUri in availableTreeUris }
                .forEach { source ->
                    if (scanPaused || source.scanState == LibraryScanState.PAUSED) return@forEach
                    when (scanTreeSource(source, onProgress)) {
                        SourceScanOutcome.FAILED,
                        SourceScanOutcome.INTERRUPTED,
                        -> hadFailure = true
                        SourceScanOutcome.PAUSED,
                        SourceScanOutcome.REBUILT,
                        -> scanPaused = true
                        else -> Unit
                    }
                }
        }

        return LibraryRefreshResult(
            snapshot = serialized { snapshotInternal(includeDeviceLibrary) },
            hadFailure = hadFailure,
        )
    }

    private suspend fun scanDeviceLibrary(
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): SourceScanOutcome = try {
        val source = store.ensureDeviceSource()
        if (source.scanState == LibraryScanState.PAUSED) return SourceScanOutcome.SKIPPED
        val scanScope = mediaStore.currentScanScope()
        if (scanScope.generation != null && source.generation == scanScope.generation) {
            store.finishSourceScan(source.id, generation = scanScope.generation)
            return SourceScanOutcome.COMPLETED
        }
        if (
            mediaStoreScanUsesConcreteVolumes(scanScope) &&
            store.hasLegacyMediaStoreIdentities()
        ) {
            store.migrateLegacyMediaStoreIdentities(mediaStore.legacyIdentityMappings(scanScope))
        }
        val scan = store.beginMediaStoreScan(
            listedFiles = mediaStore.listFiles(scanScope),
            pathIdentifiesFile = mediaStore.pathIdentifiesFile,
            volumesWithStableIds = mediaStoreVolumesWithStableIds(source.generation, scanScope),
        )
        onProgress(
            LibraryScanProgress(
                sourceId = source.id,
                sourceName = source.displayName,
                scannedTrackCount = 0,
            ),
        )
        val iteration = mediaStore.scanTracks(
            scope = scanScope,
            shouldPause = scanGate::isPaused,
        ) { candidate ->
            if (scan.firstOffer(candidate) && !store.markSeenIfUnchanged(scan, candidate)) {
                store.upsertMediaStoreTrack(scan, metadataReader.enrich(candidate))
            }
        }
        // After a rebuild the scan's rows belong to the old catalog; finishIteration ends it (#165 U5).
        if (iteration.completed && !scanGate.catalogRebuilt()) {
            val finalScope = mediaStore.currentScanScope()
            if (!mediaStoreScanCanCommit(iteration, scanScope, finalScope)) {
                store.markSourceInterrupted(source.id)
                return SourceScanOutcome.INTERRUPTED
            }
            // Before Android 11 there is no generation to show that nothing changed.
            if (finalScope.generation == null || finalScope.generation != scanScope.generation) {
                store.keepTracksStillListed(scan, mediaStore.listFiles(scanScope))
            }
        }
        finishIteration(
            source = source,
            session = scan.session,
            iteration = iteration,
            generation = scanScope.generation,
            scannedMediaStoreVolumes = scanScope.volumeNames,
            onProgress = onProgress,
        )
    } catch (cancelled: CancellationException) {
        store.markSourceInterrupted(LibrarySourceId.DEVICE)
        throw cancelled
    } catch (_: Exception) {
        store.markSourceFailed(LibrarySourceId.DEVICE)
        SourceScanOutcome.FAILED
    }

    private suspend fun scanTreeSource(
        source: StoredLibrarySource,
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): SourceScanOutcome = try {
        val treeUri = source.treeUri?.let(Uri::parse) ?: return SourceScanOutcome.FAILED
        val session = store.beginSourceScan(source.id)
        onProgress(
            LibraryScanProgress(
                sourceId = source.id,
                sourceName = source.displayName,
                scannedTrackCount = 0,
            ),
        )
        val iteration = treeScanner.scan(
            treeUri = treeUri,
            shouldPause = scanGate::isPaused,
        ) { document ->
            if (!store.markSeenIfFingerprintMatches(session, document.documentId, document.fingerprint)) {
                store.upsertTrack(session, metadataReader.enrich(document.toTrackCandidate()))
            }
        }
        finishIteration(
            source = source,
            session = session,
            iteration = iteration,
            generation = null,
            onProgress = onProgress,
        )
    } catch (cancelled: CancellationException) {
        store.markSourceInterrupted(source.id)
        throw cancelled
    } catch (_: Exception) {
        store.markSourceFailed(source.id)
        SourceScanOutcome.FAILED
    }

    private suspend fun finishIteration(
        source: StoredLibrarySource,
        session: SourceScanSession,
        iteration: ScanIterationResult,
        generation: String?,
        scannedMediaStoreVolumes: Collection<String>? = null,
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): SourceScanOutcome {
        // The session and the rows it saw belong to the catalog that was set aside (#165 U5).
        if (scanGate.catalogRebuilt()) return SourceScanOutcome.REBUILT
        if (!iteration.completed) {
            store.markSourcePaused(source.id)
            onProgress(
                LibraryScanProgress(
                    sourceId = source.id,
                    sourceName = source.displayName,
                    scannedTrackCount = iteration.processedTrackCount,
                    isPaused = true,
                ),
            )
            return SourceScanOutcome.PAUSED
        }
        if (scannedMediaStoreVolumes == null) {
            store.completeSourceScan(session, generation)
        } else {
            store.completeMediaStoreScan(session, generation, scannedMediaStoreVolumes)
        }
        return SourceScanOutcome.COMPLETED
    }

    private fun snapshotInternal(includeDeviceLibrary: Boolean): LibraryCatalogSnapshot {
        val readableTreeUris = persistedReadableTreeUris()
        val mountedMediaStoreVolumes = if (includeDeviceLibrary) {
            mediaStore.currentVolumeNames()
        } else {
            emptyList()
        }
        val sources = store.readSources().map { source ->
            val available = when (source.kind) {
                LibrarySourceKind.DEVICE -> includeDeviceLibrary
                LibrarySourceKind.FOLDER -> source.treeUri in readableTreeUris
            }
            LibrarySource(
                id = source.id,
                kind = source.kind,
                displayName = source.displayName,
                treeUri = source.treeUri,
                scanState = source.scanState,
                trackCount = source.trackCount,
                isAvailable = available,
            )
        }
        val visibleSourceIds = sources.asSequence()
            .filter(LibrarySource::isAvailable)
            .map(LibrarySource::id)
            .toList()
        val deviceTrackLocations = if (includeDeviceLibrary) {
            store.readTrackContentUris(LibrarySourceId.DEVICE)
        } else {
            emptyMap()
        }
        val displacedDeviceTrackIds = if (includeDeviceLibrary) {
            store.readDisplacedTrackIds()
        } else {
            emptySet()
        }
        val hiddenDeviceTrackIds = deviceTrackLocations.asSequence()
            .filterNot { (_, contentUri) ->
                mediaStoreTrackIsMounted(contentUri, mountedMediaStoreVolumes)
            }
            .map { it.key }
            .toSet() + displacedDeviceTrackIds
        // Folder rows whose file a completed scan did not find keep their user data but cannot play (#164).
        val hiddenFolderTrackIds = store.readHiddenFolderTrackIds()
        val sourceTracks = store.readTracks(visibleSourceIds)
        val visibleTracks = sourceTracks.filterNot { track ->
            track.id in hiddenDeviceTrackIds || track.id in hiddenFolderTrackIds
        }
        val visibleDeviceTrackCount = deviceTrackLocations.size - hiddenDeviceTrackIds.size
        return LibraryCatalogSnapshot(
            tracks = visibleTracks,
            sources = sources.map { source ->
                if (source.kind == LibrarySourceKind.DEVICE && source.isAvailable) {
                    source.copy(trackCount = visibleDeviceTrackCount)
                } else {
                    source
                }
            },
            playlists = store.readPlaylists(),
            unmatchedRecords = if (displacedDeviceTrackIds.isEmpty() && hiddenFolderTrackIds.isEmpty()) {
                emptyList()
            } else {
                unmatchedRecords(sourceTracks, visibleTracks)
            },
        )
    }

    /** Records of the sources shown, each with the songs it could belong to that the Library shows. */
    private fun unmatchedRecords(
        sourceTracks: List<AudioTrack>,
        visibleTracks: List<AudioTrack>,
    ): List<UnmatchedLibraryRecord> {
        val records = store.readUnmatchedRecords()
        if (records.isEmpty()) return emptyList()
        val tracksById = sourceTracks.associateBy(AudioTrack::id)
        val visibleTrackIds = visibleTracks.mapTo(HashSet(), AudioTrack::id)
        return records.mapNotNull { (recordId, candidateIds) ->
            val record = tracksById[recordId] ?: return@mapNotNull null
            val candidates = candidateIds.filter(visibleTrackIds::contains).mapNotNull(tracksById::get)
            if (candidates.isEmpty()) null else UnmatchedLibraryRecord(record, candidates)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.record.title })
    }

    private fun persistedReadableTreeUris(): Set<String> = contentResolver.persistedUriPermissions
        .asSequence()
        .filter { it.isReadPermission }
        .map { it.uri.toString() }
        .toSet()

    /**
     * Refuses new operations. The store stays open: the other catalogs and listening history keep
     * using it, and a cancelled scan that is still unwinding can still mark its source interrupted.
     */
    override fun close() {
        operationGate.requestClose()
    }

    private suspend fun <T> serialized(operation: suspend () -> T): T =
        operationGate.withOpenCatalog { operation() }

    private companion object {
        /**
         * Each screen's ViewModel has its own catalog, and two can exist at once (a second activity
         * opened from the media notification over a picker or dialog). Their scans must not interleave:
         * the first to complete prunes every row the other marked with its newer epoch, and folder rows
         * are deleted with their favorites and playlist entries. Scans and folder removal therefore
         * take this lock; other operations keep only their catalog's gate, so reads and edits never
         * wait for a whole scan (#136 R12). All catalogs share the application's one database helper
         * (#165), though: where Android gives it a single connection, a read or an edit can still wait
         * for one of the scan's statements or transactions, such as its completion's prune.
         */
        val processScanLock = Mutex()
    }
}

/**
 * Serializes catalog work and refuses it once [Closeable.close] is requested.
 *
 * The open check is deliberately repeated after acquiring the mutex: an operation can begin
 * waiting just before close is requested, but must never start on a closed catalog.
 */
internal class LibraryCatalogOperationGate {
    private val mutex = Mutex()
    private val closeRequested = AtomicBoolean()

    suspend fun <T> withOpenCatalog(operation: suspend () -> T): T {
        check(!closeRequested.get()) { CLOSED_MESSAGE }
        mutex.lock()
        return try {
            check(!closeRequested.get()) { CLOSED_MESSAGE }
            operation()
        } finally {
            mutex.unlock()
        }
    }

    fun requestClose(): Boolean = closeRequested.compareAndSet(false, true)

    /** For work that does not take the gate, a scan: refuses to start once close is requested. */
    fun checkOpen() {
        check(!closeRequested.get()) { CLOSED_MESSAGE }
    }

    private companion object {
        const val CLOSED_MESSAGE = "Library catalog is closing"
    }
}

private enum class SourceScanOutcome {
    COMPLETED,
    PAUSED,
    INTERRUPTED,
    SKIPPED,
    FAILED,

    /** The catalog was rebuilt after corruption during the scan, which stopped without writing more. */
    REBUILT,
}

/**
 * A pause is observed between provider rows; the scanner then closes its cursor before returning.
 * A catalog rebuild since the scan started stops it the same way (#165 U5): its later writes would
 * go to the new, empty catalog by IDs from the old one, so an unchanged row updates nothing and the
 * scan would walk the whole library before failing, while the full scan of the new catalog waits.
 */
private class LibraryScanGate(private val rebuilds: StateFlow<Int>) {
    @Volatile
    private var paused = false

    @Volatile
    private var rebuildsAtScanStart = rebuilds.value

    fun startScan() {
        rebuildsAtScanStart = rebuilds.value
    }

    fun catalogRebuilt(): Boolean = rebuilds.value != rebuildsAtScanStart

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun isPaused(): Boolean = paused || catalogRebuilt()
}
