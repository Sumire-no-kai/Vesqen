package io.github.sumirenokai.vesqen.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small ViewModel-facing seam for the durable local catalog. Provider-specific identities,
 * persisted SAF grants, delta detection, and reconciliation live behind this boundary. Stateful
 * operations are suspending so the production adapter can serialize scans, snapshots, and edits;
 * pause/resume remain immediate control signals observed between provider rows.
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

    suspend fun refresh(
        includeDeviceLibrary: Boolean,
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): LibraryRefreshResult
}

internal class AndroidLibraryCatalog(
    context: Context,
    private val store: LibraryCatalogStore = LibraryCatalogStore(context),
) : LibraryCatalog {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val mediaStore = MediaStoreAudioRepository(appContext, contentResolver)
    private val treeScanner = TreeAudioScanner(contentResolver)
    private val metadataReader = LocalAudioMetadataReader(appContext)
    private val scanGate = LibraryScanGate()
    private val operationGate = LibraryCatalogOperationGate()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var interruptedStateReconciled = false

    private val resumePausedSourcesRequested = AtomicBoolean()

    override suspend fun snapshot(includeDeviceLibrary: Boolean): LibraryCatalogSnapshot = serialized {
        reconcileInterruptedStates()
        snapshotInternal(includeDeviceLibrary)
    }

    override suspend fun addFolder(treeUri: Uri): Unit = serialized {
        val persistedPermission = contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }
        if (!persistedPermission) {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        store.upsertFolderSource(
            treeUri = treeUri.toString(),
            displayName = treeScanner.displayName(treeUri),
        )
    }

    override suspend fun removeFolder(sourceId: String): Unit = serialized {
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

    override suspend fun refresh(
        includeDeviceLibrary: Boolean,
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): LibraryRefreshResult = serialized {
        reconcileInterruptedStates()
        if (resumePausedSourcesRequested.getAndSet(false)) {
            store.preparePausedSourcesForResume()
        }

        var hadFailure = false
        var scanPaused = false
        if (includeDeviceLibrary) {
            when (scanDeviceLibrary(onProgress)) {
                SourceScanOutcome.FAILED -> hadFailure = true
                SourceScanOutcome.PAUSED -> scanPaused = true
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
                        SourceScanOutcome.FAILED -> hadFailure = true
                        SourceScanOutcome.PAUSED -> scanPaused = true
                        else -> Unit
                    }
                }
        }

        LibraryRefreshResult(
            snapshot = snapshotInternal(includeDeviceLibrary),
            hadFailure = hadFailure,
        )
    }

    private suspend fun scanDeviceLibrary(
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): SourceScanOutcome = try {
        val source = store.ensureDeviceSource()
        if (source.scanState == LibraryScanState.PAUSED) return SourceScanOutcome.SKIPPED
        val currentGeneration = mediaStore.currentGeneration()
        if (currentGeneration != null && source.generation == currentGeneration) {
            store.finishSourceScan(source.id, generation = currentGeneration)
            return SourceScanOutcome.COMPLETED
        }
        val session = store.beginSourceScan(source.id)
        onProgress(
            LibraryScanProgress(
                sourceId = source.id,
                sourceName = source.displayName,
                scannedTrackCount = 0,
            ),
        )
        val iteration = mediaStore.scanTracks(
            shouldPause = scanGate::isPaused,
        ) { candidate ->
            if (!store.markSeenIfFingerprintMatches(session, candidate.remoteId, candidate.fingerprint)) {
                store.upsertTrack(session, metadataReader.enrich(candidate))
            }
        }
        finishIteration(
            source = source,
            session = session,
            iteration = iteration,
            generation = currentGeneration,
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
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): SourceScanOutcome {
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
        store.completeSourceScan(session, generation)
        return SourceScanOutcome.COMPLETED
    }

    private fun snapshotInternal(includeDeviceLibrary: Boolean): LibraryCatalogSnapshot {
        val readableTreeUris = persistedReadableTreeUris()
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
        return LibraryCatalogSnapshot(
            tracks = store.readTracks(visibleSourceIds),
            sources = sources,
            playlists = store.readPlaylists(),
        )
    }

    private fun persistedReadableTreeUris(): Set<String> = contentResolver.persistedUriPermissions
        .asSequence()
        .filter { it.isReadPermission }
        .map { it.uri.toString() }
        .toSet()

    private fun reconcileInterruptedStates() {
        if (!interruptedStateReconciled) {
            store.markInterruptedScans()
            interruptedStateReconciled = true
        }
    }

    override fun close() {
        if (!operationGate.requestClose()) return
        // ViewModel cancellation is asynchronous. Closing SQLite on the caller (normally main)
        // thread can therefore race the cancelled scan while it unwinds its provider cursor and
        // marks the source interrupted. Queue the close behind the same gate used by every catalog
        // operation, without blocking the main thread.
        closeScope.launch {
            try {
                operationGate.closeWhenIdle(store::close)
            } finally {
                closeScope.cancel()
            }
        }
    }

    private suspend fun <T> serialized(operation: suspend () -> T): T =
        operationGate.withOpenCatalog { operation() }
}

/**
 * Serializes catalog work and gives [Closeable.close] a non-blocking hand-off point.
 *
 * The open check is deliberately repeated after acquiring the mutex: an operation can begin
 * waiting just before close is requested, but must never reach an already-closing store.
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

    suspend fun closeWhenIdle(closeAction: () -> Unit) {
        mutex.lock()
        try {
            closeAction()
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        const val CLOSED_MESSAGE = "Library catalog is closing"
    }
}

private enum class SourceScanOutcome {
    COMPLETED,
    PAUSED,
    SKIPPED,
    FAILED,
}

/** A pause is observed between provider rows; the scanner then closes its cursor before returning. */
private class LibraryScanGate {
    @Volatile
    private var paused = false

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun isPaused(): Boolean = paused
}
