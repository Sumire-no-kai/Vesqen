package io.github.sumirenokai.vesqen.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.sumirenokai.vesqen.library.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Main-thread facade around the Media3 controller used by the Compose UI. */
class PlaybackController(
    context: Context,
    private val onSnapshotChanged: (PlaybackSnapshot) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)
    private val tracksById = mutableMapOf<String, AudioTrack>()
    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private var pendingQueue: PendingQueue? = null

    var snapshot by mutableStateOf(PlaybackSnapshot())
        private set

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }
    }

    init {
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }
                    .onSuccess { resolvedController ->
                        controller = resolvedController
                        resolvedController.addListener(playerListener)
                        publish(resolvedController)
                        pendingQueue?.let { queue ->
                            pendingQueue = null
                            startQueue(resolvedController, queue.tracks, queue.startIndex)
                        }
                    }
                    .onFailure {
                        updateSnapshot(PlaybackSnapshot())
                    }
            },
            executor,
        )
    }

    fun playQueue(tracks: List<AudioTrack>, startIndex: Int) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        val activeController = controller
        if (activeController == null) {
            pendingQueue = PendingQueue(tracks, startIndex)
            return
        }

        startQueue(activeController, tracks, startIndex)
    }

    private fun startQueue(activeController: MediaController, tracks: List<AudioTrack>, startIndex: Int) {
        tracksById.clear()
        tracks.forEach { track -> tracksById[track.id.toString()] = track }
        activeController.setMediaItems(
            tracks.map { it.toMediaItem() },
            startIndex,
            C.TIME_UNSET,
        )
        activeController.prepare()
        activeController.play()
        publish(activeController)
    }

    fun togglePlayback() {
        controller?.let { activeController ->
            if (activeController.isPlaying) activeController.pause() else activeController.play()
            publish(activeController)
        }
    }

    fun skipToPrevious() {
        controller?.let { activeController ->
            if (activeController.hasPreviousMediaItem()) activeController.seekToPreviousMediaItem()
        }
    }

    fun skipToNext() {
        controller?.let { activeController ->
            if (activeController.hasNextMediaItem()) activeController.seekToNextMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun toggleShuffle() {
        controller?.let { activeController ->
            activeController.shuffleModeEnabled = !activeController.shuffleModeEnabled
            publish(activeController)
        }
    }

    fun cycleRepeatMode() {
        controller?.let { activeController ->
            activeController.repeatMode = when (activeController.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            publish(activeController)
        }
    }

    fun refreshPosition() {
        controller?.let(::publish)
    }

    fun release() {
        controller?.removeListener(playerListener)
        controller = null
        MediaController.releaseFuture(controllerFuture)
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val track = item?.mediaId?.let(tracksById::get)
        val metadata = item?.mediaMetadata
        updateSnapshot(
            PlaybackSnapshot(
            isControllerReady = true,
            isPlaying = player.isPlaying,
            trackId = item?.mediaId?.toLongOrNull(),
            mediaUri = track?.contentUri ?: item?.localConfiguration?.uri?.toString().orEmpty(),
            albumArtworkUri = track?.albumArtworkUri ?: metadata?.artworkUri?.toString(),
            artworkRevision = track?.artworkRevision ?: 0,
            title = track?.title ?: metadata?.title?.toString().orEmpty(),
            artist = track?.artist ?: metadata?.artist?.toString().orEmpty(),
            album = track?.album ?: metadata?.albumTitle?.toString().orEmpty(),
            durationMs = player.duration.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0),
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode.toPlaybackRepeatMode(),
            queueIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            queueSize = player.mediaItemCount,
            ),
        )
    }

    private fun updateSnapshot(updated: PlaybackSnapshot) {
        snapshot = updated
        onSnapshotChanged(updated)
    }

    private fun AudioTrack.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
        albumArtworkUri?.takeIf(String::isNotBlank)?.let { metadata.setArtworkUri(Uri.parse(it)) }
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(contentUri)
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun Int.toPlaybackRepeatMode(): PlaybackRepeatMode = when (this) {
        Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ALL
        Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.ONE
        else -> PlaybackRepeatMode.OFF
    }

    private data class PendingQueue(
        val tracks: List<AudioTrack>,
        val startIndex: Int,
    )
}
