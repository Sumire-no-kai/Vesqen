package io.github.sumirenokai.vesqen.playback

import android.content.ComponentName
import android.content.Context
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
class PlaybackController(context: Context) {
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
                        snapshot = PlaybackSnapshot()
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
        snapshot = PlaybackSnapshot(
            isControllerReady = true,
            isPlaying = player.isPlaying,
            title = track?.title.orEmpty(),
            artist = track?.artist.orEmpty(),
            durationMs = player.duration.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0),
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
        )
    }

    private fun AudioTrack.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(contentUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build(),
        )
        .build()

    private data class PendingQueue(
        val tracks: List<AudioTrack>,
        val startIndex: Int,
    )
}
