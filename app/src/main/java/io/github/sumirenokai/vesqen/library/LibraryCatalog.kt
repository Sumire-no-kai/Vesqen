package io.github.sumirenokai.vesqen.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException

/**
 * Small ViewModel-facing seam for the durable local catalog. Provider-specific identities,
 * persisted SAF grants, delta detection, and reconciliation live behind this boundary.
 */
interface LibraryCatalog {
    fun snapshot(includeDeviceLibrary: Boolean): LibraryCatalogSnapshot

    fun addFolder(treeUri: Uri)

    fun removeFolder(sourceId: String)

    fun pause()

    fun resume()

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
    private val treeMetadataReader = SafAudioMetadataReader(appContext)
    private val scanGate = LibraryScanGate()

    @Volatile
    private var interruptedStateReconciled = false

    @Volatile
    private var resumePausedSourcesRequested = false

    override fun snapshot(includeDeviceLibrary: Boolean): LibraryCatalogSnapshot {
        reconcileInterruptedStates()
        return snapshotInternal(includeDeviceLibrary)
    }

    override fun addFolder(treeUri: Uri) {
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

    override fun removeFolder(sourceId: String) {
        val treeUri = store.removeFolderSource(sourceId) ?: return
        runCatching {
            contentResolver.releasePersistableUriPermission(
                treeUri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    override fun pause() {
        scanGate.pause()
    }

    override fun resume() {
        resumePausedSourcesRequested = true
        scanGate.resume()
    }

    override suspend fun refresh(
        includeDeviceLibrary: Boolean,
        onProgress: suspend (LibraryScanProgress) -> Unit,
    ): LibraryRefreshResult {
        reconcileInterruptedStates()
        if (resumePausedSourcesRequested) {
            store.preparePausedSourcesForResume()
            resumePausedSourcesRequested = false
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

        return LibraryRefreshResult(
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
        if (currentGeneration != null && source.generation == currentGeneration.toString()) {
            store.finishSourceScan(source.id, generation = currentGeneration.toString())
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
                store.upsertTrack(session, candidate)
            }
        }
        finishIteration(
            source = source,
            session = session,
            iteration = iteration,
            generation = currentGeneration?.toString(),
            onProgress = onProgress,
        )
    } catch (cancelled: CancellationException) {
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
                store.upsertTrack(session, treeMetadataReader.read(document))
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
        store.pruneUnseenTracks(session)
        store.finishSourceScan(source.id, generation)
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
