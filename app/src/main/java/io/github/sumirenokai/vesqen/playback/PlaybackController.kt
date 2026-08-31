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
    private var isApplyingPlaybackOrder = false

    var snapshot by mutableStateOf(PlaybackSnapshot())
        private set

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // Applying one listener-visible mode can require two Media3 setters. Do not expose
            // their valid-but-transient intermediate state to Compose between those setters.
            if (!isApplyingPlaybackOrder) publish(player)
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
            activeController.seekToNeighbor(
                hasNeighbor = activeController.hasPreviousMediaItem(),
                neighborIndex = activeController.previousMediaItemIndex,
                dedicatedCommand = Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                seekDedicated = Player::seekToPreviousMediaItem,
            )
        }
    }

    fun skipToNext() {
        controller?.let { activeController ->
            activeController.seekToNeighbor(
                hasNeighbor = activeController.hasNextMediaItem(),
                neighborIndex = activeController.nextMediaItemIndex,
                dedicatedCommand = Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                seekDedicated = Player::seekToNextMediaItem,
            )
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun cyclePlaybackOrderMode() {
        controller?.let { activeController ->
            isApplyingPlaybackOrder = true
            try {
                activeController.applyPlaybackOrderMode(activeController.playbackOrderMode.next())
            } finally {
                isApplyingPlaybackOrder = false
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
            canSkipPrevious = player.canSeekToNeighbor(
                hasNeighbor = player.hasPreviousMediaItem(),
                neighborIndex = player.previousMediaItemIndex,
                dedicatedCommand = Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            ),
            canSkipNext = player.canSeekToNeighbor(
                hasNeighbor = player.hasNextMediaItem(),
                neighborIndex = player.nextMediaItemIndex,
                dedicatedCommand = Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            ),
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

    private val Player.playbackOrderMode: PlaybackOrderMode
        get() = resolvePlaybackOrderMode(
            shuffleEnabled = shuffleModeEnabled,
            repeatMode = repeatMode.toPlaybackRepeatMode(),
        )

    /**
     * Apply the next listener-visible order as a complete configuration. This deliberately clears
     * the other Media3 switch so a single UI control cannot leave a hidden shuffle-plus-repeat
     * combination behind.
     */
    private fun Player.applyPlaybackOrderMode(mode: PlaybackOrderMode) {
        val settings = mode.toSettings()
        // Clear the mutually exclusive switch before applying the target. This prevents a
        // shuffle-to-repeat transition from ever producing a temporary shuffle-plus-repeat state.
        if (shuffleModeEnabled && !settings.shuffleEnabled) shuffleModeEnabled = false
        if (repeatMode != settings.repeatMode.toMedia3RepeatMode()) {
            repeatMode = settings.repeatMode.toMedia3RepeatMode()
        }
        if (!shuffleModeEnabled && settings.shuffleEnabled) shuffleModeEnabled = true
    }

    private fun PlaybackRepeatMode.toMedia3RepeatMode(): Int = when (this) {
        PlaybackRepeatMode.OFF -> Player.REPEAT_MODE_OFF
        PlaybackRepeatMode.ALL -> Player.REPEAT_MODE_ALL
        PlaybackRepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    private fun Player.seekToNeighbor(
        hasNeighbor: Boolean,
        neighborIndex: Int,
        dedicatedCommand: Int,
        seekDedicated: Player.() -> Unit,
    ) {
        when (
            selectNeighborSeekRoute(
                hasNeighbor = hasNeighbor,
                neighborIndex = neighborIndex,
                canSeekToMediaItem = isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM),
                canUseDedicatedCommand = isCommandAvailable(dedicatedCommand),
            )
        ) {
            NeighborSeekRoute.BY_INDEX -> seekToDefaultPosition(neighborIndex)
            NeighborSeekRoute.DEDICATED -> seekDedicated()
            NeighborSeekRoute.UNAVAILABLE -> Unit
        }
    }

    private fun Player.canSeekToNeighbor(
        hasNeighbor: Boolean,
        neighborIndex: Int,
        dedicatedCommand: Int,
    ): Boolean = selectNeighborSeekRoute(
        hasNeighbor = hasNeighbor,
        neighborIndex = neighborIndex,
        canSeekToMediaItem = isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM),
        canUseDedicatedCommand = isCommandAvailable(dedicatedCommand),
    ) != NeighborSeekRoute.UNAVAILABLE

    private data class PendingQueue(
        val tracks: List<AudioTrack>,
        val startIndex: Int,
    )
}

/**
 * Chooses a transport route that can be both represented by a MediaSession and executed by its
 * controller. Indexed seeking is preferred: it preserves Media3's shuffle-aware neighbour index
 * while avoiding a session that exposes a timeline but declines the dedicated previous/next
 * command.
 */
internal fun selectNeighborSeekRoute(
    hasNeighbor: Boolean,
    neighborIndex: Int,
    canSeekToMediaItem: Boolean,
    canUseDedicatedCommand: Boolean,
): NeighborSeekRoute = when {
    hasNeighbor && neighborIndex != C.INDEX_UNSET && canSeekToMediaItem -> NeighborSeekRoute.BY_INDEX
    hasNeighbor && canUseDedicatedCommand -> NeighborSeekRoute.DEDICATED
    else -> NeighborSeekRoute.UNAVAILABLE
}

internal enum class NeighborSeekRoute {
    BY_INDEX,
    DEDICATED,
    UNAVAILABLE,
}
