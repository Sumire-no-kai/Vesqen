package io.github.sumirenokai.vesqen.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    /** Restores only the selected existing source; a different folder is never a replacement. */
    suspend fun renewFolder(sourceId: String, selectedTreeUri: Uri): FolderGrantRenewalResult =
        FolderGrantRenewalResult.UNSUPPORTED

    suspend fun removeFolder(sourceId: String)

    /** #249: drops the rows an earlier GitHub install's whole-storage scan found, where it cannot run. */
    suspend fun removeStorageSource()

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

    /**
     * Runs a short local [update] with the row's URI (null if gone), while scans, merges and removals
     * cannot change its identity. Read provider files before entering this step (#205 lyrics import).
     */
    suspend fun <T> withTrackContentUri(trackId: Long, update: (String?) -> T): T

    suspend fun refresh(
        includeDeviceLibrary: Boolean,
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): LibraryRefreshResult
}

/**
 * [store] is the application's one database helper (#165); every catalog shares it and none closes it.
 * [folderGrants] lists the folders this app may read: a new catalog takes its folders back from them
 * (#191 D20), and only those folders are shown and scanned. Tests list their own, since only the
 * system picker can grant a folder.
 */
internal class AndroidLibraryCatalog(
    context: Context,
    private val store: LibraryCatalogStore,
    private val folderGrants: () -> List<Uri> = { persistedReadableTreeGrants(context.contentResolver) },
    /** #249: the GitHub build's whole-storage scan; null in the Play build. */
    private val storageScan: StorageScan? = null,
) : LibraryCatalog {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val mediaStore = MediaStoreAudioRepository(appContext, contentResolver)
    private val treeScanner = TreeAudioScanner(contentResolver)
    private val storageScanner = StorageAudioScanner()
    private val metadataReader = LocalAudioMetadataReader(appContext)
    private val scanGate = LibraryScanGate(store.rebuilds) { scanYieldRequests.get() > 0 }
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
        snapshotInternal(includeDeviceLibrary, includeLibraryDetails = false).tracks
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

    override suspend fun renewFolder(sourceId: String, selectedTreeUri: Uri): FolderGrantRenewalResult {
        if (LibrarySourceId.forTree(selectedTreeUri.toString()) != sourceId) return FolderGrantRenewalResult.WRONG_FOLDER
        // Removal takes the same process-wide gate. Check the source and take its grant together.
        return betweenScans {
            serialized {
                val source = store.readSources().firstOrNull { it.id == sourceId }
                    ?: return@serialized FolderGrantRenewalResult.SOURCE_NOT_FOUND
                if (source.kind != LibrarySourceKind.FOLDER || source.treeUri != selectedTreeUri.toString()) {
                    return@serialized FolderGrantRenewalResult.WRONG_FOLDER
                }
                if (selectedTreeUri !in folderGrants()) {
                    try {
                        contentResolver.takePersistableUriPermission(selectedTreeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) {
                        return@serialized FolderGrantRenewalResult.PERMISSION_DENIED
                    }
                }
                // The existing source owns this grant even if a later catalog write fails.
                store.upsertFolderSource(source.treeUri, source.displayName)
                FolderGrantRenewalResult.RENEWED
            }
        }
    }

    // A scan of the folder must not run into its removal, so removal waits for any scan.
    override suspend fun removeFolder(sourceId: String): Unit = betweenScans {
        serialized {
            val treeUri = store.removeFolderSource(
                sourceId,
                readableTreeUris(),
                deviceFilesForFolderRemoval(),
                activeStorageScan()?.volumes()?.associate { it.id to it.root }.orEmpty(),
            ) ?: return@serialized Unit
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

    override suspend fun removeStorageSource(): Unit = betweenScans {
        serialized { store.removeStorageSource(deviceFilesForFolderRemoval()) }
    }

    /** The whole-storage scan while its switch is on and Android allows it (#249). */
    private fun activeStorageScan(): StorageScan? = storageScan?.takeIf(StorageScan::isActive)

    /**
     * #359: the folder's files MediaStore lists stay in the library as the device library's rows, with
     * their data. Without the music permission or a mounted volume there are none. A MediaStore that
     * should list them and cannot be read stops the removal, which would otherwise delete that data.
     */
    private suspend fun deviceFilesForFolderRemoval(): List<ListedMediaStoreFile> {
        if (!mediaStore.hasMusicLibraryPermission() || mediaStore.currentVolumeNames().isEmpty()) return emptyList()
        return try {
            mediaStore.listFiles(mediaStore.currentScanScope())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            throw FolderRemovalUnavailableException(failure)
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
    override suspend fun assignUnmatchedRecord(recordId: Long, trackId: Long): Boolean = betweenScans {
        serialized { store.assignUnmatchedRecord(recordId, trackId) }
    }

    override suspend fun readTrackIdsForSweep(): CatalogTrackIds = serialized { store.readTrackIdsForSweep() }

    override suspend fun <T> withTrackContentUri(trackId: Long, update: (String?) -> T): T = betweenScans {
        serialized { update(store.readTrackContentUri(trackId)) }
    }

    override suspend fun discardUnmatchedRecord(recordId: Long): Unit = betweenScans {
        serialized { store.discardUnmatchedRecord(recordId) }
    }

    /**
     * Runs [edit] under [processScanLock], which a scan holds to its end. While the edit waits, a running
     * scan stops at its next row, and the Library starts it again afterwards (#191 D17): the edit waits
     * for a row, not for a whole scan, and does not outlive the screen that asked for it by minutes.
     */
    private suspend fun <T> betweenScans(edit: suspend () -> T): T {
        scanYieldRequests.incrementAndGet()
        var waiting = true
        try {
            return processScanLock.withLock {
                // The scan has stopped; one started after this request runs to its end.
                scanYieldRequests.decrementAndGet()
                waiting = false
                edit()
            }
        } finally {
            if (waiting) scanYieldRequests.decrementAndGet()
        }
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
        restoreFoldersToNewCatalog()
        if (resumePausedSourcesRequested.getAndSet(false)) {
            store.preparePausedSourcesForResume()
        }

        var hadFailure = false
        var scanPaused = false
        var yielded = false
        val sourceResults = mutableListOf<LibrarySourceScanResult>()
        if (includeDeviceLibrary) {
            val source = store.ensureDeviceSource()
            val report = SourceScanReport(source)
            val outcome = scanDeviceLibrary(source, report, onProgress, playableSources(includeDeviceLibrary))
            sourceResults += report.result(outcome)
            when (outcome) {
                LibrarySourceScanStatus.FAILED,
                LibrarySourceScanStatus.INTERRUPTED,
                -> hadFailure = true
                LibrarySourceScanStatus.PAUSED -> scanPaused = source.scanState != LibraryScanState.PAUSED
                // A rebuilt catalog has no sources left to scan; the Library starts a full scan of it.
                LibrarySourceScanStatus.REBUILT -> scanPaused = true
                LibrarySourceScanStatus.YIELDED -> {
                    scanPaused = true
                    yielded = true
                }
                else -> Unit
            }
        }

        if (!scanPaused) {
            val availableTreeUris = readableTreeUris()
            store.readSources()
                .asSequence()
                .filter { it.kind == LibrarySourceKind.FOLDER }
                .forEach { source ->
                    if (scanPaused) return@forEach
                    val report = SourceScanReport(source)
                    if (source.treeUri !in availableTreeUris) {
                        sourceResults += report.result(LibrarySourceScanStatus.UNAVAILABLE, LibraryScanFailureReason.PERMISSION_REQUIRED)
                        return@forEach
                    }
                    if (source.scanState == LibraryScanState.PAUSED) {
                        sourceResults += report.result(LibrarySourceScanStatus.PAUSED)
                        return@forEach
                    }
                    val outcome = scanTreeSource(source, report, onProgress, playableSources(includeDeviceLibrary))
                    sourceResults += report.result(outcome)
                    when (outcome) {
                        LibrarySourceScanStatus.FAILED,
                        LibrarySourceScanStatus.INTERRUPTED,
                        -> hadFailure = true
                        LibrarySourceScanStatus.PAUSED,
                        LibrarySourceScanStatus.REBUILT,
                        -> scanPaused = true
                        LibrarySourceScanStatus.YIELDED -> {
                            scanPaused = true
                            yielded = true
                        }
                        else -> Unit
                    }
                }
        }

        // #249: the whole-storage scan reads last, as it holds only the files no other source has.
        val storage = activeStorageScan()
        val storageScanEnabled = storage?.enabledForScan()
        if (!scanPaused && storage != null && storageScanEnabled != null) {
            val source = store.ensureStorageSource()
            val report = SourceScanReport(source)
            if (source.scanState == LibraryScanState.PAUSED) {
                sourceResults += report.result(LibrarySourceScanStatus.PAUSED)
            } else {
                val outcome = scanStorageSource(
                    source, report, onProgress, playableSources(includeDeviceLibrary), storage, storageScanEnabled,
                )
                sourceResults += report.result(outcome)
                when (outcome) {
                    LibrarySourceScanStatus.FAILED,
                    LibrarySourceScanStatus.INTERRUPTED,
                    -> hadFailure = true
                    LibrarySourceScanStatus.YIELDED -> yielded = true
                    else -> Unit
                }
            }
        }

        // #359: rows two sources still hold for one file, as catalogs before #359 have them, become one.
        if (!scanGate.catalogRebuilt()) store.mergeDuplicateLocations(playableSources(includeDeviceLibrary))

        return LibraryRefreshResult(
            snapshot = serialized { snapshotInternal(includeDeviceLibrary) },
            hadFailure = hadFailure,
            yieldedToEdit = yielded,
            sourceResults = sourceResults,
        )
    }

    /**
     * A catalog that replaced a corrupt one adds back each folder Android still grants, as if it were
     * picked again, and the scan that called this reads them (#191 D20). Once they are added the mark
     * clears; a folder removed after that keeps its row deleted, even if its grant could not be released.
     */
    private suspend fun restoreFoldersToNewCatalog(): Unit = serialized {
        // A corruption found while the app runs only sets the old files aside; the new catalog, which
        // marks the folders due, is created by the next read. So the catalog is read before the mark.
        val listedTreeUris = store.readSources().mapNotNullTo(HashSet()) { it.treeUri }
        val due = store.folderRestore.due()
        if (due == 0L) return@serialized
        folderGrants()
            .filterNot { it.toString() in listedTreeUris }
            .forEach { treeUri -> store.upsertFolderSource(treeUri.toString(), treeScanner.displayName(treeUri)) }
        // A catalog created during these writes marked the folders due again, and the next scan adds them.
        store.folderRestore.restored(due)
    }

    /**
     * Whether a row's source can play it now (#359): the device library while its permission holds
     * and the row's volume is mounted, a folder while Android still grants it, the whole-storage scan
     * while it is on, Android allows it and the row's volume is mounted (#249). Another source that
     * reads the same file takes over a row its own source cannot play.
     */
    private fun playableSources(includeDeviceLibrary: Boolean): (sourceId: String, contentUri: String) -> Boolean {
        val deviceAvailable = includeDeviceLibrary && mediaStore.hasMusicLibraryPermission()
        val mountedVolumes = if (deviceAvailable) mediaStore.currentVolumeNames() else emptyList()
        val readableTrees = readableTreeUris()
        val readableFolders = store.readSources()
            .filter { it.kind == LibrarySourceKind.FOLDER && it.treeUri in readableTrees }
            .mapTo(HashSet(), StoredLibrarySource::id)
        val storageRoots = activeStorageScan()?.volumes()?.map(StorageVolumeRoot::root).orEmpty()
        return { sourceId, contentUri ->
            when (sourceId) {
                LibrarySourceId.DEVICE -> deviceAvailable && mediaStoreTrackIsMounted(contentUri, mountedVolumes)
                LibrarySourceId.STORAGE -> storageFileIsMounted(contentUri, storageRoots)
                else -> sourceId in readableFolders
            }
        }
    }

    private suspend fun scanDeviceLibrary(
        source: StoredLibrarySource,
        report: SourceScanReport,
        onProgress: suspend (LibraryScanProgress) -> Unit,
        isPlayable: (sourceId: String, contentUri: String) -> Boolean,
    ): LibrarySourceScanStatus = try {
        if (source.scanState == LibraryScanState.PAUSED) return LibrarySourceScanStatus.PAUSED
        val scanScope = mediaStore.currentScanScope()
        // A stale refresh request can outlive the runtime grant. Android 10+ would otherwise
        // report only this app's files and make the missing shared-library rows look deleted.
        if (!scanScope.hasMusicLibraryPermission) {
            report.failureReason = LibraryScanFailureReason.PERMISSION_REQUIRED
            return LibrarySourceScanStatus.UNAVAILABLE
        }
        val owners = store.readLocationOwners(LibrarySourceId.DEVICE, isPlayable)
        if (
            scanScope.generation != null && source.generation == scanScope.generation &&
            !mediaStoreListsUnplayableRow(owners) { mediaStore.listFiles(scanScope) }
        ) {
            store.finishSourceScan(source.id, generation = scanScope.generation)
            return LibrarySourceScanStatus.UNCHANGED
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
        onProgress(report.progress())
        val iteration = mediaStore.scanTracks(
            scope = scanScope,
            shouldPause = scanGate::isPaused,
        ) { candidate ->
            if (scan.firstOffer(candidate) && !store.markSeenIfUnchanged(scan, candidate)) {
                store.writeMediaStoreTrack(scan, candidate, owners) { metadataReader.enrich(candidate) }
            }
            report.checkedTrack()
            if (report.shouldPublish()) onProgress(report.progress())
        }
        finishIteration(
            source = source,
            session = scan.session,
            iteration = iteration,
            generation = scanScope.generation,
            mediaStoreScan = scan,
            mediaStoreScope = scanScope,
            report = report,
            onProgress = onProgress,
        )
    } catch (cancelled: CancellationException) {
        store.markSourceInterrupted(LibrarySourceId.DEVICE)
        throw cancelled
    } catch (failure: Exception) {
        report.failureReason = scanFailureReason(failure)
        store.markSourceFailed(LibrarySourceId.DEVICE)
        LibrarySourceScanStatus.FAILED
    }

    private suspend fun scanTreeSource(
        source: StoredLibrarySource,
        report: SourceScanReport,
        onProgress: suspend (LibraryScanProgress) -> Unit,
        isPlayable: (sourceId: String, contentUri: String) -> Boolean,
    ): LibrarySourceScanStatus = try {
        val treeUri = source.treeUri?.let(Uri::parse) ?: return LibrarySourceScanStatus.FAILED
        // A folder added back while its provider could not be read has a stand-in name (#191 D20).
        val displayName = treeScanner.readDisplayName(treeUri)
            ?.also { name -> if (name != source.displayName) store.renameFolderSource(source.id, name) }
            ?: source.displayName
        val session = store.beginSourceScan(source.id)
        val owners = store.readLocationOwners(source.id, isPlayable)
        report.sourceName = displayName
        onProgress(report.progress())
        val iteration = treeScanner.scan(
            treeUri = treeUri,
            shouldPause = scanGate::isPaused,
            onSkippedAudio = { format ->
                report.skippedAudio(format)
                if (report.shouldPublish()) onProgress(report.progress())
            },
        ) { document ->
            if (!store.markSeenIfFingerprintMatches(session, document.documentId, document.fingerprint)) {
                store.writeSourceTrack(session, document.documentId, document.locationKey, owners) {
                    metadataReader.enrich(document.toTrackCandidate())
                }
            }
            report.checkedTrack()
            if (report.shouldPublish()) onProgress(report.progress())
        }
        finishIteration(
            source = source.copy(displayName = displayName),
            session = session,
            iteration = iteration,
            generation = null,
            report = report,
            onProgress = onProgress,
        )
    } catch (cancelled: CancellationException) {
        store.markSourceInterrupted(source.id)
        throw cancelled
    } catch (failure: Exception) {
        report.failureReason = scanFailureReason(failure)
        store.markSourceFailed(source.id)
        LibrarySourceScanStatus.FAILED
    }

    private suspend fun scanStorageSource(
        source: StoredLibrarySource,
        report: SourceScanReport,
        onProgress: suspend (LibraryScanProgress) -> Unit,
        isPlayable: (sourceId: String, contentUri: String) -> Boolean,
        storage: StorageScan,
        storageScanEnabled: () -> Boolean,
    ): LibrarySourceScanStatus = try {
        val volumes = storage.volumes()
        // No mounted volume is no evidence that the files are gone, so nothing is scanned or pruned.
        if (volumes.isEmpty()) throw IncompleteLibraryProviderException()
        val session = store.beginSourceScan(source.id)
        val owners = store.readLocationOwners(source.id, isPlayable)
        onProgress(report.progress())
        val iteration = storageScanner.scan(
            volumes = volumes,
            shouldPause = { !storageScanEnabled() || scanGate.isPaused() },
            onSkippedAudio = { format ->
                report.skippedAudio(format)
                if (report.shouldPublish()) onProgress(report.progress())
            },
        ) { file ->
            if (!storageScanEnabled()) return@scan
            if (!store.markSeenIfFingerprintMatches(session, file.path, file.fingerprint)) {
                store.writeSourceTrack(session, file.path, file.locationKey, owners) {
                    if (storageScanEnabled()) {
                        metadataReader.enrich(file.toTrackCandidate()).takeIf { storageScanEnabled() }
                    } else {
                        null
                    }
                }
            }
            report.checkedTrack()
            if (report.shouldPublish()) onProgress(report.progress())
        }
        finishIteration(
            source = source,
            session = session,
            iteration = iteration,
            generation = null,
            storageVolumes = volumes,
            storageScanEnabled = storageScanEnabled,
            report = report,
            onProgress = onProgress,
        )
    } catch (cancelled: CancellationException) {
        store.markSourceInterrupted(source.id)
        throw cancelled
    } catch (failure: Exception) {
        report.failureReason = scanFailureReason(failure)
        store.markSourceFailed(source.id)
        LibrarySourceScanStatus.FAILED
    }

    private suspend fun finishIteration(
        source: StoredLibrarySource,
        session: SourceScanSession,
        iteration: ScanIterationResult,
        generation: String?,
        mediaStoreScan: MediaStoreScanSession? = null,
        mediaStoreScope: MediaStoreScanScope? = null,
        storageVolumes: List<StorageVolumeRoot>? = null,
        storageScanEnabled: (() -> Boolean)? = null,
        report: SourceScanReport,
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): LibrarySourceScanStatus {
        // The session and the rows it saw belong to the catalog that was set aside (#165 U5).
        if (scanGate.catalogRebuilt()) return LibrarySourceScanStatus.REBUILT
        if (storageScanEnabled?.invoke() == false) {
            store.markSourceInterrupted(source.id)
            return LibrarySourceScanStatus.UNAVAILABLE
        }
        if (!iteration.completed || scanGate.isPaused()) {
            return stopBeforeCommit(source, report, LibraryScanStage.READING, onProgress)
        }
        onProgress(report.progress(stage = LibraryScanStage.RECONCILING))
        // Reporting can suspend while permissions, pause requests or the catalog itself change.
        if (scanGate.catalogRebuilt()) return LibrarySourceScanStatus.REBUILT
        if (storageScanEnabled?.invoke() == false) {
            store.markSourceInterrupted(source.id)
            return LibrarySourceScanStatus.UNAVAILABLE
        }
        if (
            source.kind == LibrarySourceKind.FOLDER && source.treeUri !in readableTreeUris() ||
            source.kind == LibrarySourceKind.STORAGE && activeStorageScan() == null
        ) {
            store.markSourceInterrupted(source.id)
            report.failureReason = LibraryScanFailureReason.PERMISSION_REQUIRED
            return LibrarySourceScanStatus.UNAVAILABLE
        }
        if (scanGate.isPaused()) {
            return stopBeforeCommit(source, report, LibraryScanStage.RECONCILING, onProgress)
        }
        if (mediaStoreScope != null) {
            var finalScope = mediaStore.currentScanScope()
            if (mediaStoreScanCanCommit(iteration, mediaStoreScope, finalScope) &&
                (finalScope.generation == null || finalScope.generation != mediaStoreScope.generation)
            ) {
                // A changing cursor may have skipped rows. Re-list after the observer, while the
                // scan still owns this catalog, and keep those rows only within the same scope.
                val listedFiles = mediaStore.listFiles(mediaStoreScope)
                if (scanGate.catalogRebuilt()) return LibrarySourceScanStatus.REBUILT
                finalScope = mediaStore.currentScanScope()
                if (mediaStoreScanCanCommit(iteration, mediaStoreScope, finalScope)) {
                    store.keepTracksStillListed(checkNotNull(mediaStoreScan), listedFiles)
                }
            }
            if (!mediaStoreScanCanCommit(iteration, mediaStoreScope, finalScope)) {
                store.markSourceInterrupted(source.id)
                val permissionRevoked = mediaStoreScope.hasMusicLibraryPermission && !finalScope.hasMusicLibraryPermission
                report.failureReason = if (permissionRevoked) LibraryScanFailureReason.PERMISSION_REQUIRED else LibraryScanFailureReason.SOURCE_CHANGED
                return if (permissionRevoked) LibrarySourceScanStatus.UNAVAILABLE else LibrarySourceScanStatus.INTERRUPTED
            }
        }
        val excludedTrackIds = storageVolumes?.let { store.readStorageExclusionIds(session, it) }.orEmpty()
        // The re-list can suspend too; a pause, edit or catalog rebuild must still prevent pruning.
        if (scanGate.catalogRebuilt()) return LibrarySourceScanStatus.REBUILT
        if (scanGate.isPaused()) {
            return stopBeforeCommit(source, report, LibraryScanStage.RECONCILING, onProgress)
        }
        if (storageVolumes != null) {
            if (storageScanEnabled?.invoke() != true || activeStorageScan() == null) {
                store.markSourceInterrupted(source.id)
                return LibrarySourceScanStatus.UNAVAILABLE
            }
            if (storageScan?.volumes()?.containsAll(storageVolumes) != true) {
                // A missing drive cannot authorize reattaching or pruning the tracks that were on it.
                store.markSourceInterrupted(source.id)
                report.failureReason = LibraryScanFailureReason.SOURCE_CHANGED
                return LibrarySourceScanStatus.INTERRUPTED
            }
            store.completeStorageScan(session, storageVolumes.map(StorageVolumeRoot::id), excludedTrackIds)
        } else if (mediaStoreScope == null) {
            store.completeSourceScan(session, generation)
        } else {
            store.completeMediaStoreScan(session, generation, mediaStoreScope.volumeNames)
        }
        // The terminal result is returned by refresh. No observer runs after this commit: its
        // failure/cancellation must not relabel a committed scan as failed or interrupted.
        return LibrarySourceScanStatus.COMPLETED
    }

    private suspend fun stopBeforeCommit(
        source: StoredLibrarySource,
        report: SourceScanReport,
        stage: LibraryScanStage,
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): LibrarySourceScanStatus {
        if (!scanGate.pausedByUser()) {
            store.markSourceYieldedToEdit(source.id)
            return LibrarySourceScanStatus.YIELDED
        }
        store.markSourcePaused(source.id)
        onProgress(report.progress(stage = stage, isPaused = true))
        return LibrarySourceScanStatus.PAUSED
    }

    private fun snapshotInternal(includeDeviceLibrary: Boolean, includeLibraryDetails: Boolean = true): LibraryCatalogSnapshot {
        val deviceAvailable = includeDeviceLibrary && mediaStore.hasMusicLibraryPermission()
        val readableTreeUris = readableTreeUris()
        val storageRoots = activeStorageScan()?.volumes()?.map(StorageVolumeRoot::root)
        val mountedMediaStoreVolumes = if (deviceAvailable) {
            mediaStore.currentVolumeNames()
        } else {
            emptyList()
        }
        val sources = store.readSources().map { source ->
            val available = when (source.kind) {
                LibrarySourceKind.DEVICE -> deviceAvailable
                LibrarySourceKind.FOLDER -> source.treeUri in readableTreeUris
                LibrarySourceKind.STORAGE -> storageRoots != null
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
        val deviceTrackLocations = if (deviceAvailable) {
            store.readTrackContentUris(LibrarySourceId.DEVICE)
        } else {
            emptyMap()
        }
        val displacedDeviceTrackIds = if (deviceAvailable) {
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
        // #249: the whole-storage scan's rows on a card or drive that is not mounted wait for it.
        val unmountedStorageTrackIds = storageRoots?.let { roots ->
            store.readTrackContentUris(LibrarySourceId.STORAGE)
                .filterValues { contentUri -> !storageFileIsMounted(contentUri, roots) }
                .keys
        }.orEmpty()
        val sourceTracks = store.readTracks(visibleSourceIds)
        val visibleTracks = sourceTracks.filterNot { track ->
            track.id in hiddenDeviceTrackIds || track.id in hiddenFolderTrackIds || track.id in unmountedStorageTrackIds
        }
        val visibleDeviceTrackCount = deviceTrackLocations.size - hiddenDeviceTrackIds.size
        return LibraryCatalogSnapshot(
            tracks = visibleTracks,
            sources = sources.map { source ->
                when {
                    !source.isAvailable -> source
                    source.kind == LibrarySourceKind.DEVICE -> source.copy(trackCount = visibleDeviceTrackCount)
                    // #249: every song on the phone's storage that the library shows, whichever source plays it.
                    source.kind == LibrarySourceKind.STORAGE -> source.copy(trackCount = visibleTracks.count { it.locationKey != null })
                    else -> source.copy(trackCount = folderTrackCount(source.treeUri, visibleTracks) ?: source.trackCount)
                }
            },
            playlists = if (includeLibraryDetails) store.readPlaylists() else emptyList(),
            unmatchedRecords = if (!includeLibraryDetails || displacedDeviceTrackIds.isEmpty() && hiddenFolderTrackIds.isEmpty()) {
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

    private fun readableTreeUris(): Set<String> = folderGrants().mapTo(HashSet(), Uri::toString)

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

        /** Edits waiting for [processScanLock]; a scan stops at its next row while there is one (#191 D17). */
        val scanYieldRequests = AtomicInteger()
    }
}

/** Read grants on folder trees; Vesqen takes one for each folder added (#191 D20). */
private fun persistedReadableTreeGrants(contentResolver: ContentResolver): List<Uri> = contentResolver.persistedUriPermissions
    .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
    .map { it.uri }

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

internal fun scanFailureReason(failure: Exception): LibraryScanFailureReason = when (failure) {
    is SecurityException -> LibraryScanFailureReason.PERMISSION_REQUIRED
    is IncompleteLibraryProviderException -> LibraryScanFailureReason.PROVIDER_NOT_READY
    else -> LibraryScanFailureReason.READ_FAILED
}

internal class SourceScanReport(source: StoredLibrarySource) {
    private val sourceId = LibrarySourceId.forScan(source.id)
    var sourceName = source.displayName
    private var checked = 0
    private val skipped = linkedMapOf<UnsupportedAudioFormat, Int>()
    var failureReason: LibraryScanFailureReason? = null

    fun checkedTrack() { checked++ }

    fun skippedAudio(format: UnsupportedAudioFormat) { skipped[format] = (skipped[format] ?: 0) + 1 }

    // Publish a bounded batch, then the exact final count at the phase/terminal boundary.
    fun shouldPublish(): Boolean {
        val observed = checked + skipped.values.sum()
        return observed > 0 && observed % PROGRESS_BATCH_SIZE == 0
    }

    fun progress(stage: LibraryScanStage = LibraryScanStage.READING, isPaused: Boolean = false) = LibraryScanProgress(
        sourceId, sourceName, checked, isPaused, stage, skipped.toMap(),
    )

    fun result(outcome: LibrarySourceScanStatus, reason: LibraryScanFailureReason? = failureReason) = LibrarySourceScanResult(
        sourceId = sourceId,
        sourceName = sourceName,
        status = outcome,
        scannedTrackCount = checked,
        skippedUnsupportedAudio = skipped.toMap(),
        failureReason = reason ?: LibraryScanFailureReason.READ_FAILED.takeIf { outcome == LibrarySourceScanStatus.FAILED },
    )

    private companion object { const val PROGRESS_BATCH_SIZE = 32 }
}

/**
 * A pause is observed between provider rows; the scanner then closes its cursor before returning.
 * A catalog rebuild since the scan started stops it the same way (#165 U5): its later writes would
 * go to the new, empty catalog by IDs from the old one, so an unchanged row updates nothing and the
 * scan would walk the whole library before failing, while the full scan of the new catalog waits.
 */
private class LibraryScanGate(
    private val rebuilds: StateFlow<Int>,
    /** An edit waits for the scan lock (#191 D17). */
    private val editWaiting: () -> Boolean,
) {
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

    fun pausedByUser(): Boolean = paused

    fun isPaused(): Boolean = paused || catalogRebuilt() || editWaiting()
}

/**
 * #359: whether a row its own source cannot play now lies where MediaStore lists a file. A folder
 * whose grant went leaves such rows; an unchanged MediaStore generation would end the device scan
 * before it takes them over. MediaStore's files are listed only while there are such rows.
 */
internal suspend fun mediaStoreListsUnplayableRow(
    owners: Map<String, LocationOwner>,
    listFiles: suspend () -> List<ListedMediaStoreFile>,
): Boolean {
    val waiting = owners.filterValues { !it.playable }.keys
    if (waiting.isEmpty()) return false
    return listFiles().any { it.locationKey in waiting }
}

/**
 * The songs inside an added folder, whichever source plays each (#359): a file MediaStore lists as
 * well, or another added folder holds too, is that source's row. Null for another provider's folder,
 * whose files are only its own rows.
 */
internal fun folderTrackCount(treeUri: String?, visibleTracks: List<AudioTrack>): Int? {
    val prefix = treeUri?.toUri()?.let(::documentTreeLocationPrefix) ?: return null
    return visibleTracks.count { track -> track.locationKey?.startsWith(prefix) == true }
}

/** #359: a folder was not removed because MediaStore, which lists some of its songs, could not be read. */
class FolderRemovalUnavailableException(cause: Throwable) :
    Exception("MediaStore could not be read to keep the removed folder's songs", cause)

/** Whether a whole-storage row's file lies on one of the mounted [roots] (#249). */
internal fun storageFileIsMounted(contentUri: String, roots: List<File>): Boolean {
    val path = Uri.parse(contentUri).path ?: return false
    return roots.any { root -> path.startsWith(root.path.trimEnd('/') + "/") }
}
