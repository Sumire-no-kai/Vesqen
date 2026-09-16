package io.github.sumirenokai.vesqen.ui

import android.app.Application
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sumirenokai.vesqen.VesqenApplication
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.AlbumArtworkLoader
import io.github.sumirenokai.vesqen.library.AndroidLibraryCatalog
import io.github.sumirenokai.vesqen.library.LibraryCatalog
import io.github.sumirenokai.vesqen.library.LibraryCatalogSnapshot
import io.github.sumirenokai.vesqen.library.LibraryScanProgress
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.LibrarySourceKind
import io.github.sumirenokai.vesqen.library.LibraryPlaylist
import io.github.sumirenokai.vesqen.playback.PlaybackController
import io.github.sumirenokai.vesqen.playback.PlaybackHistoryUpdate
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MusicAccess {
    NEEDS_PERMISSION,
    GRANTED,
    DENIED,
}

data class LibraryUiState(
    val musicAccess: MusicAccess = MusicAccess.NEEDS_PERMISSION,
    val notificationsAllowed: Boolean = true,
    val isLoading: Boolean = false,
    val tracks: List<AudioTrack> = emptyList(),
    val playlists: List<LibraryPlaylist> = emptyList(),
    val sources: List<LibrarySource> = emptyList(),
    val scanProgress: LibraryScanProgress? = null,
    val loadingFailed: Boolean = false,
    val catalogMutationFailed: Boolean = false,
) {
    val isScanPaused: Boolean
        get() = scanProgress?.isPaused == true || sources.any { it.scanState == LibraryScanState.PAUSED }
}

data class VesqenUiState(
    val library: LibraryUiState = LibraryUiState(),
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
)

class VesqenViewModel(application: Application) : AndroidViewModel(application) {
    private val catalog: LibraryCatalog = AndroidLibraryCatalog(application)
    private val cachedLibraryReader = LibrarySnapshotReader()
    private var libraryRefreshEpoch = 0L
    private var playbackController: PlaybackController? = null
    private var activeLibraryScan: Job? = null
    private var libraryRefreshQueued = false
    private var lastMusicPermissionGranted: Boolean? = null
    private val latestPlaybackHistory = mutableMapOf<Long, PlaybackHistoryUpdate>()

    var uiState by mutableStateOf(VesqenUiState())
        private set

    init {
        (application as? VesqenApplication)?.let { vesqenApplication ->
            viewModelScope.launch {
                vesqenApplication.playbackHistoryRecorder.recordedUpdates.collect { update ->
                    latestPlaybackHistory[update.trackId] = update
                    updateLibrary { library ->
                        library.copy(
                            tracks = applyPlaybackHistoryUpdates(
                                tracks = library.tracks,
                                updates = listOf(update),
                            ),
                        )
                    }
                }
            }
            viewModelScope.launch {
                vesqenApplication.outputVerificationRepository.state.collect {
                    playbackController?.refreshOutputVerification()
                }
            }
        }
    }

    fun initialisePermissions(musicGranted: Boolean, notificationsGranted: Boolean) {
        val previousMusicPermission = lastMusicPermissionGranted
        val firstPermissionSync = previousMusicPermission == null
        val musicPermissionChanged = previousMusicPermission != null && previousMusicPermission != musicGranted
        applyPermissions(musicGranted, notificationsGranted, markDeniedWhenMissing = false)
        lastMusicPermissionGranted = musicGranted
        if (firstPermissionSync) {
            restoreCatalogThenRefresh()
        } else if (musicPermissionChanged) {
            refreshLibrary()
        } else {
            // ON_RESUME is also used for ordinary navigation and system-dialog returns. Re-read
            // the private catalog so revoked SAF grants are reflected, but do not rescan the full
            // MediaStore/folder library on every resume when permissions are unchanged.
            refreshCachedLibrary()
        }
    }

    fun onMusicPermissionRequestResult(musicGranted: Boolean, notificationsGranted: Boolean) {
        val musicPermissionChanged = lastMusicPermissionGranted != musicGranted
        applyPermissions(musicGranted, notificationsGranted, markDeniedWhenMissing = true)
        lastMusicPermissionGranted = musicGranted
        if (musicGranted || musicPermissionChanged) {
            refreshLibrary()
        } else {
            refreshCachedLibrary()
        }
    }

    fun updateNotificationPermission(notificationsGranted: Boolean) {
        updateLibrary { it.copy(notificationsAllowed = notificationsGranted) }
    }

    private fun applyPermissions(
        musicGranted: Boolean,
        notificationsGranted: Boolean,
        markDeniedWhenMissing: Boolean,
    ) {
        // IO started under an earlier permission snapshot must not restore its rows afterward.
        cachedLibraryReader.invalidate()
        if (!musicGranted && uiState.library.musicAccess == MusicAccess.GRANTED) {
            libraryRefreshEpoch++
            AlbumArtworkLoader.clearMemoryCache()
        }
        updateLibrary {
            val updatedMusicAccess = when {
                musicGranted -> MusicAccess.GRANTED
                // A permission can be revoked while the process is alive. Do not retain a stale
                // GRANTED state (or its cached artwork) when the lifecycle refresh observes it.
                markDeniedWhenMissing || it.musicAccess == MusicAccess.GRANTED -> MusicAccess.DENIED
                else -> it.musicAccess
            }
            it.copy(
                musicAccess = updatedMusicAccess,
                notificationsAllowed = notificationsGranted,
                // Cached SAF rows remain usable without broad MediaStore permission. A filtered
                // catalog snapshot replaces device rows on the following IO turn.
                isLoading = if (updatedMusicAccess == MusicAccess.GRANTED) it.isLoading else false,
                loadingFailed = if (updatedMusicAccess == MusicAccess.GRANTED) it.loadingFailed else false,
            )
        }
    }

    fun refreshLibrary() {
        if (activeLibraryScan?.isActive == true) {
            // A picker result can arrive while the lifecycle-triggered scan is still running.
            // Let the current cursor close normally, then reconcile the just-mutated source set.
            libraryRefreshQueued = true
            catalog.resume()
            updateLibrary {
                it.copy(scanProgress = it.scanProgress?.copy(isPaused = false))
            }
            return
        }
        val requestEpoch = ++libraryRefreshEpoch
        cachedLibraryReader.invalidate()
        AlbumArtworkLoader.clearMemoryCache()
        updateLibrary {
            it.copy(
                isLoading = true,
                loadingFailed = false,
                catalogMutationFailed = false,
                scanProgress = null,
            )
        }
        activeLibraryScan = viewModelScope.launch {
            val includeDeviceLibrary = uiState.library.musicAccess == MusicAccess.GRANTED
            val result = try {
                Result.success(
                    withContext(Dispatchers.IO) {
                        catalog.refresh(includeDeviceLibrary) { progress ->
                            withContext(Dispatchers.Main.immediate) {
                                if (libraryRefreshEpoch == requestEpoch) {
                                    updateLibrary { it.copy(scanProgress = progress) }
                                }
                            }
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            if (
                libraryRefreshEpoch != requestEpoch
            ) {
                activeLibraryScan = null
                refreshQueuedLibrary()
                return@launch
            }
            val refreshedTracks = result.getOrNull()?.snapshot?.let { snapshot ->
                mergeLatestPlaybackHistory(snapshot.tracks)
            }
            updateLibrary {
                val snapshot = result.getOrNull()?.snapshot
                it.copy(
                    isLoading = false,
                    tracks = refreshedTracks ?: it.tracks,
                    playlists = snapshot?.playlists ?: it.playlists,
                    sources = snapshot?.sources ?: it.sources,
                    scanProgress = it.scanProgress?.takeIf(LibraryScanProgress::isPaused)
                        ?: snapshot?.pausedProgress(),
                    loadingFailed = result.getOrNull()?.hadFailure ?: result.isFailure,
                )
            }
            result.getOrNull()?.snapshot?.let { snapshot ->
                playbackController().syncLibrary(refreshedTracks ?: snapshot.tracks)
            }
            activeLibraryScan = null
            refreshQueuedLibrary()
        }
    }

    fun addLibraryFolder(treeUri: Uri) {
        viewModelScope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) { catalog.addFolder(treeUri) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            if (result.isSuccess) {
                refreshLibrary()
            } else {
                updateLibrary { it.copy(loadingFailed = true) }
            }
        }
    }

    fun removeLibraryFolder(sourceId: String) {
        mutateCatalog { catalog.removeFolder(sourceId) }
    }

    fun pauseLibraryScan() {
        catalog.pause()
        updateLibrary {
            it.copy(
                scanProgress = it.scanProgress?.copy(isPaused = true) ?: pausedProgressFrom(it.sources),
            )
        }
    }

    fun resumeLibraryScan() {
        catalog.resume()
        if (activeLibraryScan?.isActive == true) {
            updateLibrary { it.copy(scanProgress = it.scanProgress?.copy(isPaused = false)) }
        } else {
            refreshLibrary()
        }
    }

    fun play(track: AudioTrack) {
        playbackController().playQueue(uiState.library.tracks, uiState.library.tracks.indexOf(track))
    }

    fun playQueue(tracks: List<AudioTrack>, startIndex: Int = 0) {
        playbackController().playQueue(tracks, startIndex)
    }

    fun playNext(track: AudioTrack) = playbackController().playNext(track)

    fun addToQueue(track: AudioTrack) = playbackController().addToQueue(track)

    fun playQueueIndex(index: Int) = playbackController?.playQueueIndex(index)

    fun removeQueueItem(index: Int) = playbackController?.removeQueueItem(index)

    fun moveQueueItem(fromIndex: Int, toIndex: Int) = playbackController?.moveQueueItem(fromIndex, toIndex)

    fun clearQueue() = playbackController?.clearQueue()

    fun retryPlayback() = playbackController?.retryPlayback()

    fun setFavorite(trackId: Long, favorite: Boolean) {
        mutateCatalog { catalog.setFavorite(trackId, favorite) }
    }

    suspend fun saveTrackOrder(playlistId: Long?, trackIds: List<Long>): Boolean = try {
        withContext(Dispatchers.IO) { catalog.saveTrackOrder(playlistId, trackIds) }
        loadCachedLibrary()
        true
    } catch (failure: SQLiteException) {
        false
    }

    fun createPlaylist(name: String) {
        mutateCatalog { catalog.createPlaylist(name) }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        mutateCatalog { catalog.renamePlaylist(playlistId, name) }
    }

    fun deletePlaylist(playlistId: Long) {
        mutateCatalog { catalog.deletePlaylist(playlistId) }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        mutateCatalog { catalog.addTrackToPlaylist(playlistId, trackId) }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        mutateCatalog { catalog.removeTrackFromPlaylist(playlistId, trackId) }
    }

    fun movePlaylistTrack(playlistId: Long, fromIndex: Int, toIndex: Int) {
        mutateCatalog { catalog.movePlaylistTrack(playlistId, fromIndex, toIndex) }
    }

    fun togglePlayback() = playbackController?.togglePlayback()

    fun skipToPrevious() = playbackController?.skipToPrevious()

    fun skipToNext() = playbackController?.skipToNext()

    fun seekTo(positionMs: Long) = playbackController?.seekTo(positionMs)

    fun cyclePlaybackOrderMode() = playbackController?.cyclePlaybackOrderMode()

    fun setUsbOutputMode(mode: UsbOutputMode) = playbackController().setUsbOutputMode(mode)

    fun refreshPlaybackPosition() = playbackController?.refreshPosition()

    override fun onCleared() {
        activeLibraryScan?.cancel()
        playbackController?.release()
        catalog.close()
        super.onCleared()
    }

    private fun playbackController(): PlaybackController = playbackController ?: PlaybackController(
        context = getApplication(),
        onSnapshotChanged = { snapshot -> uiState = uiState.copy(playback = snapshot) },
        outputVerificationLookup = (getApplication() as VesqenApplication)
            .outputVerificationRepository::match,
    ).also { controller ->
        playbackController = controller
    }

    private inline fun updateLibrary(transform: (LibraryUiState) -> LibraryUiState) {
        uiState = uiState.copy(library = transform(uiState.library))
    }

    private fun restoreCatalogThenRefresh() {
        viewModelScope.launch {
            try {
                loadCachedLibrary()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A corrupt or temporarily unavailable cache must not prevent a provider rescan.
                updateLibrary { it.copy(loadingFailed = true) }
            }
            refreshLibrary()
        }
    }

    private fun refreshCachedLibrary() {
        viewModelScope.launch {
            try {
                loadCachedLibrary()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the last good projection; the user can still request a full refresh.
                updateLibrary { it.copy(loadingFailed = true) }
            }
        }
    }

    private fun refreshQueuedLibrary() {
        if (libraryRefreshQueued) {
            libraryRefreshQueued = false
            refreshLibrary()
        }
    }

    private suspend fun loadCachedLibrary() {
        val includeDeviceLibrary = uiState.library.musicAccess == MusicAccess.GRANTED
        cachedLibraryReader.read(
            load = { withContext(Dispatchers.IO) { catalog.snapshot(includeDeviceLibrary) } },
            publish = { snapshot ->
                val refreshedTracks = mergeLatestPlaybackHistory(snapshot.tracks)
                updateLibrary { current ->
                    current.copy(
                        tracks = refreshedTracks,
                        playlists = snapshot.playlists,
                        sources = snapshot.sources,
                        scanProgress = snapshot.pausedProgress()
                            ?: current.scanProgress?.takeIf(LibraryScanProgress::isPaused),
                    )
                }
                playbackController().syncLibrary(refreshedTracks)
            },
        )
    }

    /**
     * A catalog read can begin before a history write and publish afterward. Overlay post-write
     * values until a later snapshot proves it contains the same or newer database state.
     */
    private fun mergeLatestPlaybackHistory(tracks: List<AudioTrack>): List<AudioTrack> {
        val merged = applyPlaybackHistoryUpdates(tracks, latestPlaybackHistory.values)
        tracks.forEach { track ->
            val update = latestPlaybackHistory[track.id] ?: return@forEach
            if (
                track.playCount > update.playCount ||
                (track.playCount == update.playCount && track.lastPlayedAtMs >= update.lastPlayedAtMs)
            ) {
                latestPlaybackHistory.remove(track.id)
            }
        }
        return merged
    }

    private fun mutateCatalog(action: suspend () -> Unit) {
        viewModelScope.launch {
            updateLibrary { it.copy(catalogMutationFailed = false) }
            try {
                withContext(Dispatchers.IO) { action() }
                loadCachedLibrary()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SQLiteException) {
                updateLibrary { it.copy(catalogMutationFailed = true) }
            }
        }
    }
}

private fun LibraryCatalogSnapshot.pausedProgress(): LibraryScanProgress? = sources.firstOrNull {
    it.scanState == LibraryScanState.PAUSED
}?.let { source ->
    LibraryScanProgress(
        sourceId = source.id,
        sourceName = source.displayName,
        scannedTrackCount = 0,
        isPaused = true,
    )
}

/** Applies authoritative history fields while preserving every playback/catalog identity field. */
internal fun applyPlaybackHistoryUpdates(
    tracks: List<AudioTrack>,
    updates: Collection<PlaybackHistoryUpdate>,
): List<AudioTrack> {
    if (tracks.isEmpty() || updates.isEmpty()) return tracks
    val updatesByTrackId = updates.associateBy(PlaybackHistoryUpdate::trackId)
    var result: MutableList<AudioTrack>? = null
    tracks.forEachIndexed { index, track ->
        val update = updatesByTrackId[track.id] ?: return@forEachIndexed
        val updatedTrack = when {
            update.playCount > track.playCount -> track.copy(
                playCount = update.playCount,
                lastPlayedAtMs = update.lastPlayedAtMs,
            )
            update.playCount == track.playCount && update.lastPlayedAtMs > track.lastPlayedAtMs ->
                track.copy(lastPlayedAtMs = update.lastPlayedAtMs)
            else -> track
        }
        if (updatedTrack !== track) {
            val mutable = result ?: tracks.toMutableList().also { result = it }
            mutable[index] = updatedTrack
        }
    }
    return result ?: tracks
}

private fun pausedProgressFrom(sources: List<LibrarySource>): LibraryScanProgress? = sources.firstOrNull {
    it.scanState == LibraryScanState.SCANNING || it.scanState == LibraryScanState.PAUSED
}?.let { source ->
    LibraryScanProgress(
        sourceId = source.id,
        sourceName = source.displayName,
        scannedTrackCount = 0,
        isPaused = true,
    )
}
