package io.github.sumirenokai.vesqen.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.MediaStoreAudioRepository
import io.github.sumirenokai.vesqen.playback.PlaybackController
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import kotlinx.coroutines.Dispatchers
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
    val connectedOutputs: Set<AudioOutputType> = emptySet(),
    val loadingFailed: Boolean = false,
)

class VesqenViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaStoreAudioRepository(application.contentResolver)
    private val connectedOutputs = ConnectedAudioOutputs(application)
    private var playbackController: PlaybackController? = null

    var uiState by mutableStateOf(LibraryUiState())
        private set

    val playbackSnapshot: PlaybackSnapshot
        get() = playbackController?.snapshot ?: PlaybackSnapshot()

    fun initialisePermissions(musicGranted: Boolean, notificationsGranted: Boolean) {
        applyPermissions(musicGranted, notificationsGranted, markDeniedWhenMissing = false)
    }

    fun onMusicPermissionRequestResult(musicGranted: Boolean, notificationsGranted: Boolean) {
        applyPermissions(musicGranted, notificationsGranted, markDeniedWhenMissing = true)
    }

    fun updateNotificationPermission(notificationsGranted: Boolean) {
        uiState = uiState.copy(notificationsAllowed = notificationsGranted)
    }

    private fun applyPermissions(
        musicGranted: Boolean,
        notificationsGranted: Boolean,
        markDeniedWhenMissing: Boolean,
    ) {
        uiState = uiState.copy(
            musicAccess = when {
                musicGranted -> MusicAccess.GRANTED
                markDeniedWhenMissing -> MusicAccess.DENIED
                else -> uiState.musicAccess
            },
            notificationsAllowed = notificationsGranted,
            connectedOutputs = connectedOutputs.read(),
        )
        if (musicGranted) refreshLibrary()
    }

    fun refreshLibrary() {
        if (uiState.musicAccess != MusicAccess.GRANTED) return
        uiState = uiState.copy(isLoading = true, loadingFailed = false, connectedOutputs = connectedOutputs.read())
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.loadTracks() }
            }
            uiState = uiState.copy(
                isLoading = false,
                tracks = result.getOrDefault(emptyList()),
                loadingFailed = result.isFailure,
            )
        }
    }

    fun play(track: AudioTrack) {
        playbackController().playQueue(uiState.tracks, uiState.tracks.indexOf(track))
    }

    fun togglePlayback() = playbackController?.togglePlayback()

    fun skipToPrevious() = playbackController?.skipToPrevious()

    fun skipToNext() = playbackController?.skipToNext()

    fun seekTo(positionMs: Long) = playbackController?.seekTo(positionMs)

    fun refreshPlaybackPosition() = playbackController?.refreshPosition()

    override fun onCleared() {
        playbackController?.release()
        super.onCleared()
    }

    private fun playbackController(): PlaybackController = playbackController ?: PlaybackController(getApplication()).also {
        playbackController = it
    }
}
