package io.github.sumirenokai.vesqen.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.AlbumArtworkLoader
import io.github.sumirenokai.vesqen.library.MediaStoreAudioRepository
import io.github.sumirenokai.vesqen.playback.PlaybackController
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import kotlinx.coroutines.Dispatchers
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
    val connectedOutputs: Set<AudioOutputType> = emptySet(),
    val loadingFailed: Boolean = false,
)

data class VesqenUiState(
    val library: LibraryUiState = LibraryUiState(),
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
)

class VesqenViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaStoreAudioRepository(application.contentResolver)
    private val connectedOutputs = ConnectedAudioOutputs(application)
    private val libraryRefreshEpoch = AtomicLong()
    private var playbackController: PlaybackController? = null

    var uiState by mutableStateOf(VesqenUiState())
        private set

    fun initialisePermissions(musicGranted: Boolean, notificationsGranted: Boolean) {
        applyPermissions(musicGranted, notificationsGranted, markDeniedWhenMissing = false)
    }

    fun onMusicPermissionRequestResult(musicGranted: Boolean, notificationsGranted: Boolean) {
        applyPermissions(musicGranted, notificationsGranted, markDeniedWhenMissing = true)
    }

    fun updateNotificationPermission(notificationsGranted: Boolean) {
        updateLibrary { it.copy(notificationsAllowed = notificationsGranted) }
    }

    private fun applyPermissions(
        musicGranted: Boolean,
        notificationsGranted: Boolean,
        markDeniedWhenMissing: Boolean,
    ) {
        if (!musicGranted) {
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
                // The old MediaStore rows can no longer safely provide metadata or artwork after
                // revocation. Clear them so AlbumArtwork's produceState receives a null track.
                tracks = if (updatedMusicAccess == MusicAccess.GRANTED) it.tracks else emptyList(),
                isLoading = if (updatedMusicAccess == MusicAccess.GRANTED) it.isLoading else false,
                loadingFailed = if (updatedMusicAccess == MusicAccess.GRANTED) it.loadingFailed else false,
            )
        }
        if (musicGranted) refreshLibrary()
    }

    fun refreshLibrary() {
        if (uiState.library.musicAccess != MusicAccess.GRANTED) return
        val requestEpoch = libraryRefreshEpoch.incrementAndGet()
        AlbumArtworkLoader.clearMemoryCache()
        updateLibrary {
            it.copy(isLoading = true, loadingFailed = false, connectedOutputs = connectedOutputs.read())
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.loadTracks() }
            }
            if (
                libraryRefreshEpoch.get() != requestEpoch ||
                uiState.library.musicAccess != MusicAccess.GRANTED
            ) {
                return@launch
            }
            updateLibrary {
                it.copy(
                    isLoading = false,
                    tracks = result.getOrDefault(emptyList()),
                    loadingFailed = result.isFailure,
                )
            }
        }
    }

    fun play(track: AudioTrack) {
        playbackController().playQueue(uiState.library.tracks, uiState.library.tracks.indexOf(track))
    }

    fun togglePlayback() = playbackController?.togglePlayback()

    fun skipToPrevious() = playbackController?.skipToPrevious()

    fun skipToNext() = playbackController?.skipToNext()

    fun seekTo(positionMs: Long) = playbackController?.seekTo(positionMs)

    fun toggleShuffle() = playbackController?.toggleShuffle()

    fun cycleRepeatMode() = playbackController?.cycleRepeatMode()

    fun refreshPlaybackPosition() = playbackController?.refreshPosition()

    fun refreshConnectedOutputs() {
        updateLibrary { it.copy(connectedOutputs = connectedOutputs.read()) }
    }

    override fun onCleared() {
        playbackController?.release()
        super.onCleared()
    }

    private fun playbackController(): PlaybackController = playbackController ?: PlaybackController(
        context = getApplication(),
        onSnapshotChanged = { snapshot -> uiState = uiState.copy(playback = snapshot) },
    ).also { controller ->
        playbackController = controller
    }

    private inline fun updateLibrary(transform: (LibraryUiState) -> LibraryUiState) {
        uiState = uiState.copy(library = transform(uiState.library))
    }
}
