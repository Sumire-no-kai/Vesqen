package io.github.sumirenokai.vesqen.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
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
    private val onPlaybackStarted: (Long) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)
    private val tracksById = mutableMapOf<String, AudioTrack>()
    private val availableTracksById = mutableMapOf<String, AudioTrack>()
    private val stateStore = PlaybackStateStore(appContext)
    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private var pendingQueue: PendingQueue? = null
    private var pendingLibrary: List<AudioTrack>? = null
    private var isApplyingPlaybackOrder = false
    private var currentProblem: PlaybackProblem? = null
    private var lastCountedTrackId: Long? = null
    private var lastPersistedSignature: String? = null

    var snapshot by mutableStateOf(PlaybackSnapshot())
        private set

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // Applying one listener-visible mode can require two Media3 setters. Do not expose
            // their valid-but-transient intermediate state to Compose between those setters.
            if (!isApplyingPlaybackOrder) publish(player)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastCountedTrackId = null
            controller?.let(::recordCurrentPlaybackIfNeeded)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) controller?.let(::recordCurrentPlaybackIfNeeded)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && currentProblem != null) {
                currentProblem = null
                controller?.let(::publish)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            currentProblem = error.toPlaybackProblem()
            controller?.let(::publish)
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
                        pendingLibrary?.let { tracks ->
                            pendingLibrary = null
                            synchronizeLibrary(resolvedController, tracks)
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

    fun syncLibrary(tracks: List<AudioTrack>) {
        availableTracksById.clear()
        tracks.forEach { track -> availableTracksById[track.id.toString()] = track }
        val activeController = controller
        if (activeController == null) {
            pendingLibrary = tracks
        } else {
            synchronizeLibrary(activeController, tracks)
        }
    }

    private fun startQueue(
        activeController: MediaController,
        tracks: List<AudioTrack>,
        startIndex: Int,
        positionMs: Long = C.TIME_UNSET,
        playWhenReady: Boolean = true,
        shuffleEnabled: Boolean = false,
        repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    ) {
        tracksById.clear()
        tracks.forEach { track -> tracksById[track.id.toString()] = track }
        activeController.setMediaItems(
            tracks.map { it.toMediaItem() },
            startIndex,
            positionMs,
        )
        activeController.shuffleModeEnabled = shuffleEnabled
        activeController.repeatMode = repeatMode.toMedia3RepeatMode()
        activeController.prepare()
        if (playWhenReady) activeController.play() else activeController.pause()
        publish(activeController)
    }

    fun playNext(track: AudioTrack) {
        controller?.let { player ->
            tracksById[track.id.toString()] = track
            availableTracksById[track.id.toString()] = track
            val insertionIndex = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
            player.addMediaItem(insertionIndex, track.toMediaItem())
            publish(player)
        }
    }

    fun addToQueue(track: AudioTrack) {
        controller?.let { player ->
            tracksById[track.id.toString()] = track
            availableTracksById[track.id.toString()] = track
            player.addMediaItem(track.toMediaItem())
            publish(player)
        }
    }

    fun playQueueIndex(index: Int) {
        controller?.takeIf { index in 0 until it.mediaItemCount }?.let { player ->
            player.seekToDefaultPosition(index)
            player.play()
            publish(player)
        }
    }

    fun removeQueueItem(index: Int) {
        controller?.takeIf { index in 0 until it.mediaItemCount }?.let { player ->
            player.removeMediaItem(index)
            if (player.mediaItemCount == 0) {
                stateStore.clear()
                currentProblem = null
            }
            publish(player)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        controller?.let { player ->
            if (fromIndex !in 0 until player.mediaItemCount || toIndex !in 0 until player.mediaItemCount) return
            player.moveMediaItem(fromIndex, toIndex)
            publish(player)
        }
    }

    fun clearQueue() {
        controller?.let { player ->
            player.stop()
            player.clearMediaItems()
            tracksById.clear()
            currentProblem = null
            stateStore.clear()
            lastPersistedSignature = null
            updateSnapshot(PlaybackSnapshot(isControllerReady = true))
        }
    }

    fun retryPlayback() {
        controller?.let { player ->
            currentProblem = null
            player.prepare()
            player.play()
            publish(player)
        }
    }

    fun togglePlayback() {
        controller?.let { activeController ->
            if (activeController.isPlaying) {
                activeController.pause()
            } else {
                if (activeController.playbackState == Player.STATE_ENDED && activeController.mediaItemCount > 0) {
                    activeController.seekToDefaultPosition(activeController.currentMediaItemIndex.coerceAtLeast(0))
                    activeController.prepare()
                }
                activeController.play()
            }
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
        controller?.let { persist(it, force = true) }
        controller?.removeListener(playerListener)
        controller = null
        MediaController.releaseFuture(controllerFuture)
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val track = item?.mediaId?.let(tracksById::get)
        val metadata = item?.mediaMetadata
        val queue = (0 until player.mediaItemCount).mapNotNull { index ->
            val queueItem = player.getMediaItemAt(index)
            val queueTrack = tracksById[queueItem.mediaId] ?: availableTracksById[queueItem.mediaId]
            val queueTrackId = queueItem.mediaId.toLongOrNull() ?: return@mapNotNull null
            PlaybackQueueItem(
                trackId = queueTrackId,
                title = queueTrack?.title ?: queueItem.mediaMetadata.title?.toString().orEmpty(),
                artist = queueTrack?.artist ?: queueItem.mediaMetadata.artist?.toString().orEmpty(),
                isCurrent = index == player.currentMediaItemIndex,
            )
        }
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
            queue = queue,
            problem = currentProblem,
            ),
        )
        persist(player)
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
            .setAlbumArtist(albumArtist.takeIf(String::isNotBlank))
            .setTrackNumber(trackNumber)
            .setDiscNumber(discNumber)
            .setRecordingYear(year)
            .setGenre(genre.takeIf(String::isNotBlank))
        albumArtworkUri?.takeIf(String::isNotBlank)?.let { metadata.setArtworkUri(it.toUri()) }
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

    private fun synchronizeLibrary(player: MediaController, tracks: List<AudioTrack>) {
        tracks.forEach { track -> availableTracksById[track.id.toString()] = track }
        if (player.mediaItemCount > 0) {
            (0 until player.mediaItemCount).forEach { index ->
                val mediaId = player.getMediaItemAt(index).mediaId
                availableTracksById[mediaId]?.let { tracksById[mediaId] = it }
            }
            publish(player)
            return
        }
        val restored = stateStore.load()?.restoreAgainst(tracks) ?: run {
            publish(player)
            return
        }
        startQueue(
            activeController = player,
            tracks = restored.tracks,
            startIndex = restored.startIndex,
            positionMs = restored.positionMs,
            playWhenReady = false,
            shuffleEnabled = restored.shuffleEnabled,
            repeatMode = restored.repeatMode,
        )
    }

    private fun recordCurrentPlaybackIfNeeded(player: Player) {
        if (!player.isPlaying) return
        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (lastCountedTrackId == trackId) return
        lastCountedTrackId = trackId
        onPlaybackStarted(trackId)
    }

    private fun persist(player: Player, force: Boolean = false) {
        val queueTrackIds = (0 until player.mediaItemCount).mapNotNull { index ->
            player.getMediaItemAt(index).mediaId.toLongOrNull()
        }
        if (queueTrackIds.isEmpty()) {
            if (force || lastPersistedSignature != null) stateStore.clear()
            lastPersistedSignature = null
            return
        }
        val currentTrackId = player.currentMediaItem?.mediaId?.toLongOrNull()
        val signature = buildString {
            append(queueTrackIds.joinToString(","))
            append('|').append(currentTrackId)
            append('|').append(player.currentPosition.coerceAtLeast(0) / POSITION_SAVE_BUCKET_MS)
            append('|').append(player.isPlaying)
            append('|').append(player.shuffleModeEnabled)
            append('|').append(player.repeatMode)
        }
        if (!force && signature == lastPersistedSignature) return
        lastPersistedSignature = signature
        stateStore.save(
            PersistedPlaybackState(
                queueTrackIds = queueTrackIds,
                currentTrackId = currentTrackId,
                positionMs = player.currentPosition.coerceAtLeast(0),
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode.toPlaybackRepeatMode(),
            ),
        )
    }

    private companion object {
        const val POSITION_SAVE_BUCKET_MS = 5_000L
    }
}

private fun PlaybackException.toPlaybackProblem(): PlaybackProblem {
    val name = errorCodeName.uppercase()
    return when {
        "FILE_NOT_FOUND" in name || "NO_PERMISSION" in name -> PlaybackProblem.SOURCE_UNAVAILABLE
        "UNSUPPORTED" in name || "PARSING" in name -> PlaybackProblem.UNSUPPORTED_FORMAT
        "DECOD" in name || "AUDIO_TRACK" in name -> PlaybackProblem.DECODER_FAILURE
        else -> PlaybackProblem.UNKNOWN
    }
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
