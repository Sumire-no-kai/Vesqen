package io.github.sumirenokai.vesqen.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

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
    val connectedOutputs: Set<AudioOutputType> = emptySet(),
    val activeRoute: ActiveAudioRoute? = null,
    val outputRouteRevision: Long = 0,
    val outputRouteChangedAtMs: Long = 0,
    val loadingFailed: Boolean = false,
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
    private val connectedOutputs = ConnectedAudioOutputs(application)
    private val libraryRefreshEpoch = AtomicLong()
    private var playbackController: PlaybackController? = null
    private var activeLibraryScan: Job? = null
    private var libraryRefreshQueued = false
    private var permissionsInitialised = false
    private var lastMusicPermissionGranted = false

    var uiState by mutableStateOf(VesqenUiState())
        private set

    init {
        connectedOutputs.start { routeState ->
            updateLibrary { state ->
                state.copy(
                    connectedOutputs = routeState.connectedOutputs,
                    activeRoute = routeState.activeRoute,
                    outputRouteRevision = state.outputRouteRevision + 1,
                    outputRouteChangedAtMs = System.currentTimeMillis(),
                )
            }
        }
    }

    fun initialisePermissions(musicGranted: Boolean, notificationsGranted: Boolean) {
        val firstPermissionSync = !permissionsInitialised
        val musicPermissionChanged = permissionsInitialised && lastMusicPermissionGranted != musicGranted
        applyPermissions(musicGranted, notificationsGranted, markDeniedWhenMissing = false)
        permissionsInitialised = true
        lastMusicPermissionGranted = musicGranted
        if (firstPermissionSync) {
            restoreCatalogThenRefresh()
        } else if (
            musicPermissionChanged || musicGranted || uiState.library.sources.any {
                it.kind == LibrarySourceKind.FOLDER && it.isAvailable
            }
        ) {
            refreshLibrary()
        } else {
            refreshCachedLibrary()
        }
    }

    fun onMusicPermissionRequestResult(musicGranted: Boolean, notificationsGranted: Boolean) {
        val musicPermissionChanged = !permissionsInitialised || lastMusicPermissionGranted != musicGranted
        applyPermissions(musicGranted, notificationsGranted, markDeniedWhenMissing = true)
        permissionsInitialised = true
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
        if (!musicGranted && uiState.library.musicAccess == MusicAccess.GRANTED) {
            libraryRefreshEpoch.incrementAndGet()
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
                connectedOutputs = connectedOutputs.read(),
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
        val requestEpoch = libraryRefreshEpoch.incrementAndGet()
        AlbumArtworkLoader.clearMemoryCache()
        updateLibrary {
            it.copy(
                isLoading = true,
                loadingFailed = false,
                scanProgress = null,
                connectedOutputs = connectedOutputs.read(),
            )
        }
        activeLibraryScan = viewModelScope.launch {
            val includeDeviceLibrary = uiState.library.musicAccess == MusicAccess.GRANTED
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    catalog.refresh(includeDeviceLibrary) { progress ->
                        withContext(Dispatchers.Main.immediate) {
                            if (libraryRefreshEpoch.get() == requestEpoch) {
                                updateLibrary { it.copy(scanProgress = progress) }
                            }
                        }
                    }
                }
            }
            if (
                libraryRefreshEpoch.get() != requestEpoch
            ) {
                activeLibraryScan = null
                refreshQueuedLibrary()
                return@launch
            }
            updateLibrary {
                val snapshot = result.getOrNull()?.snapshot
                it.copy(
                    isLoading = false,
                    tracks = snapshot?.tracks ?: it.tracks,
                    playlists = snapshot?.playlists ?: it.playlists,
                    sources = snapshot?.sources ?: it.sources,
                    scanProgress = it.scanProgress?.takeIf(LibraryScanProgress::isPaused)
                        ?: snapshot?.pausedProgress(),
                    loadingFailed = result.getOrNull()?.hadFailure ?: result.isFailure,
                )
            }
            result.getOrNull()?.snapshot?.let { snapshot ->
                playbackController().syncLibrary(snapshot.tracks)
            }
            activeLibraryScan = null
            refreshQueuedLibrary()
        }
    }

    fun addLibraryFolder(treeUri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { catalog.addFolder(treeUri) }
            }
            if (result.isSuccess) {
                refreshLibrary()
            } else {
                updateLibrary { it.copy(loadingFailed = true) }
            }
        }
    }

    fun removeLibraryFolder(sourceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { catalog.removeFolder(sourceId) }
            refreshCachedLibrary()
        }
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

    fun refreshPlaybackPosition() = playbackController?.refreshPosition()

    fun refreshConnectedOutputs() {
        val routeState = connectedOutputs.readState()
        updateLibrary {
            it.copy(
                connectedOutputs = routeState.connectedOutputs,
                activeRoute = routeState.activeRoute,
                outputRouteRevision = it.outputRouteRevision + 1,
                outputRouteChangedAtMs = System.currentTimeMillis(),
            )
        }
    }

    override fun onCleared() {
        connectedOutputs.stop()
        playbackController?.release()
        super.onCleared()
    }

    private fun playbackController(): PlaybackController = playbackController ?: PlaybackController(
        context = getApplication(),
        onSnapshotChanged = { snapshot -> uiState = uiState.copy(playback = snapshot) },
        onPlaybackStarted = { trackId ->
            viewModelScope.launch {
                withContext(Dispatchers.IO) { catalog.recordPlayback(trackId) }
                loadCachedLibrary()
            }
        },
    ).also { controller ->
        playbackController = controller
    }

    private inline fun updateLibrary(transform: (LibraryUiState) -> LibraryUiState) {
        uiState = uiState.copy(library = transform(uiState.library))
    }

    private fun restoreCatalogThenRefresh() {
        viewModelScope.launch {
            loadCachedLibrary()
            refreshLibrary()
        }
    }

    private fun refreshCachedLibrary() {
        viewModelScope.launch { loadCachedLibrary() }
    }

    private fun refreshQueuedLibrary() {
        if (libraryRefreshQueued) {
            libraryRefreshQueued = false
            refreshLibrary()
        }
    }

    private suspend fun loadCachedLibrary() {
        val includeDeviceLibrary = uiState.library.musicAccess == MusicAccess.GRANTED
        val snapshot = withContext(Dispatchers.IO) { catalog.snapshot(includeDeviceLibrary) }
        updateLibrary { current ->
            current.copy(
                tracks = snapshot.tracks,
                playlists = snapshot.playlists,
                sources = snapshot.sources,
                scanProgress = snapshot.pausedProgress() ?: current.scanProgress?.takeIf(LibraryScanProgress::isPaused),
            )
        }
        playbackController().syncLibrary(snapshot.tracks)
    }

    private fun mutateCatalog(action: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { action() }
            loadCachedLibrary()
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
