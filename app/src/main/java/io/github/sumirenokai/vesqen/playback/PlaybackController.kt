package io.github.sumirenokai.vesqen.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.telemetry.TelemetryMediaItemExtras
import io.github.sumirenokai.vesqen.verification.OutputVerificationMatch

/** Main-thread facade around the Media3 controller used by the Compose UI. */
class PlaybackController(
    context: Context,
    private val outputVerificationLookup: (UsbOutputStatus) -> OutputVerificationMatch? = { null },
    private val onSnapshotChanged: (PlaybackSnapshot) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracksById = mutableMapOf<String, AudioTrack>()
    private val availableTracksById = mutableMapOf<String, AudioTrack>()
    private val stateStore = PlaybackStateStore(appContext)
    private val librarySnapshot = PlaybackLibrarySnapshot()
    private val sessionToken = SessionToken(
        appContext,
        ComponentName(appContext, PlaybackService::class.java),
    )
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingQueue: PendingQueue? = null
    private var suppressListenerPublishing = false
    private var currentProblem: PlaybackProblem? = null
    private var queueCache = PlaybackQueueCache()
    private var reconnectAttempt = 0
    private var connectionGeneration = 0L
    private var released = false
    private val usbOutputCommand = SessionCommand(UsbOutputSessionContract.SET_MODE_ACTION, Bundle.EMPTY)
    private var usbOutputStatus = UsbOutputStatus()

    private var latestSnapshot = PlaybackSnapshot()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // Applying one listener-visible mode can require two Media3 setters. Do not expose
            // their valid-but-transient intermediate state to Compose between those setters.
            if (!suppressListenerPublishing) {
                publish(
                    player = player,
                    rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                        events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                        events.contains(Player.EVENT_MEDIA_METADATA_CHANGED),
                )
            }
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

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            if (this@PlaybackController.controller !== controller) return
            UsbOutputSessionContract.fromBundle(extras)?.let { status ->
                if (acceptUsbOutputStatus(status)) publish(controller)
            }
        }

        override fun onDisconnected(disconnectedController: MediaController) {
            if (controller !== disconnectedController || released) return
            disconnectedController.removeListener(playerListener)
            controller = null
            queueCache = PlaybackQueueCache()
            updateSnapshot(
                latestSnapshot.copy(
                    isControllerReady = false,
                    isPlaying = false,
                    showsPauseAction = false,
                    outputVerification = null,
                ),
            )
            scheduleReconnect()
        }
    }

    private val reconnectRunnable = Runnable { connect() }

    init {
        connect()
    }

    private fun connect() {
        if (released || controller != null || controllerFuture != null) return
        val generation = ++connectionGeneration
        val future = MediaController.Builder(appContext, sessionToken)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (controllerFuture !== future || generation != connectionGeneration || released) {
                    MediaController.releaseFuture(future)
                    return@addListener
                }
                controllerFuture = null
                runCatching { future.get() }
                    .onSuccess { resolvedController ->
                        mainHandler.removeCallbacks(reconnectRunnable)
                        reconnectAttempt = 0
                        controller = resolvedController
                        UsbOutputSessionContract.fromBundle(resolvedController.sessionExtras)?.let { status ->
                            acceptUsbOutputStatus(status)
                        }
                        resolvedController.addListener(playerListener)
                        val queueToApply = pendingQueue.also { pendingQueue = null }
                        // A replacement service owns a fresh, empty player. Always reconcile the
                        // most recent catalog after connecting so its persisted queue can be
                        // restored even when no new library scan happened during the disconnect.
                        // A queue explicitly requested while disconnected is applied first and is
                        // therefore never overwritten by recovery.
                        applyPlaybackConnectionState(
                            pendingQueue = queueToApply,
                            applyPendingQueue = { queue ->
                                startQueue(resolvedController, queue.tracks, queue.startIndex)
                            },
                            reconcileLibrary = {
                                synchronizeLibrary(resolvedController, librarySnapshot.current())
                            },
                        )
                    }
                    .onFailure {
                        updateSnapshot(
                            latestSnapshot.copy(
                                isControllerReady = false,
                                isPlaying = false,
                                showsPauseAction = false,
                                outputVerification = null,
                            ),
                        )
                        scheduleReconnect()
                    }
            },
            executor,
        )
    }

    private fun scheduleReconnect() {
        if (released) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, reconnectDelayMs(reconnectAttempt++))
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
        val currentLibrary = librarySnapshot.update(tracks)
        availableTracksById.clear()
        currentLibrary.forEach { track -> availableTracksById[track.id.toString()] = track }
        val activeController = controller
        if (activeController != null) synchronizeLibrary(activeController, currentLibrary)
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
        publish(activeController, rebuildQueue = true)
    }

    fun playNext(track: AudioTrack) {
        controller?.let { player ->
            tracksById[track.id.toString()] = track
            availableTracksById[track.id.toString()] = track
            val insertionIndex = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
            player.addMediaItem(insertionIndex, track.toMediaItem())
            publish(player, rebuildQueue = true)
        }
    }

    fun addToQueue(track: AudioTrack) {
        controller?.let { player ->
            tracksById[track.id.toString()] = track
            availableTracksById[track.id.toString()] = track
            player.addMediaItem(track.toMediaItem())
            publish(player, rebuildQueue = true)
        }
    }

    fun playQueueIndex(index: Int) {
        controller?.takeIf { index in 0 until it.mediaItemCount }?.let { player ->
            player.seekToDefaultPosition(index)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            publish(player, rebuildQueue = true)
        }
    }

    fun removeQueueItem(index: Int) {
        controller?.takeIf { index in 0 until it.mediaItemCount }?.let { player ->
            player.removeMediaItem(index)
            if (player.mediaItemCount == 0) {
                currentProblem = null
            }
            publish(player, rebuildQueue = true)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        controller?.let { player ->
            if (fromIndex !in 0 until player.mediaItemCount || toIndex !in 0 until player.mediaItemCount) return
            player.moveMediaItem(fromIndex, toIndex)
            publish(player, rebuildQueue = true)
        }
    }

    fun clearQueue() {
        controller?.let { player ->
            player.stop()
            player.clearMediaItems()
            tracksById.clear()
            currentProblem = null
            queueCache = PlaybackQueueCache()
            updateSnapshot(
                PlaybackSnapshot(
                    isControllerReady = true,
                    usbOutputStatus = usbOutputStatus,
                    outputVerification = outputVerificationLookup(usbOutputStatus),
                ),
            )
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
            when (playbackToggleAction(activeController.playWhenReady, activeController.playbackState)) {
                PlaybackToggleAction.PAUSE -> activeController.pause()
                PlaybackToggleAction.PLAY -> activeController.play()
                PlaybackToggleAction.PREPARE -> {
                    activeController.prepare()
                    activeController.play()
                }
                PlaybackToggleAction.REPLAY -> {
                    if (activeController.mediaItemCount == 0) return@let
                    activeController.seekToDefaultPosition(activeController.currentMediaItemIndex.coerceAtLeast(0))
                    activeController.prepare()
                    activeController.play()
                }
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
            suppressListenerPublishing = true
            try {
                activeController.applyPlaybackOrderMode(activeController.playbackOrderMode.next())
            } finally {
                suppressListenerPublishing = false
            }
            publish(activeController)
        }
    }

    fun setUsbOutputMode(mode: UsbOutputMode) {
        val activeController = controller ?: return
        if (!activeController.isSessionCommandAvailable(usbOutputCommand)) return
        val future = activeController.sendCustomCommand(
            usbOutputCommand,
            UsbOutputSessionContract.modeArguments(mode),
        )
        future.addListener(
            {
                runCatching { future.get() }
                    .getOrNull()
                    ?.extras
                    ?.let(UsbOutputSessionContract::fromBundle)
                    ?.let { status ->
                        if (acceptUsbOutputStatus(status)) controller?.let(::publish)
                    }
            },
            executor,
        )
    }

    fun refreshPosition() {
        controller?.let { player ->
            if (!queueCache.matches(player)) {
                publish(player, rebuildQueue = true)
                return@let
            }
            updateSnapshot(
                latestSnapshot.withPlayerPosition(
                    isPlaying = player.isPlaying,
                    durationMs = player.duration,
                    positionMs = player.currentPosition,
                ),
            )
        }
    }

    fun refreshOutputVerification() {
        updateSnapshot(
            latestSnapshot.copy(
                outputVerification = outputVerificationLookup(usbOutputStatus),
            ),
        )
    }

    fun release() {
        if (released) return
        released = true
        connectionGeneration++
        mainHandler.removeCallbacks(reconnectRunnable)
        controller?.let { activeController ->
            activeController.removeListener(playerListener)
            activeController.release()
        }
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    private fun publish(player: Player, rebuildQueue: Boolean = false) {
        if (rebuildQueue || !queueCache.matches(player)) {
            queueCache = buildQueueCache(player)
        }
        val item = player.currentMediaItem
        val track = item?.mediaId?.let(tracksById::get)
        val metadata = item?.mediaMetadata
        updateSnapshot(
            PlaybackSnapshot(
                isControllerReady = true,
                isPlaying = player.isPlaying,
                showsPauseAction = playbackToggleAction(player.playWhenReady, player.playbackState) ==
                    PlaybackToggleAction.PAUSE,
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
                queue = queueCache.projection,
                problem = currentProblem,
                usbOutputStatus = usbOutputStatus,
                outputVerification = outputVerificationLookup(usbOutputStatus),
            ),
        )
    }

    private fun updateSnapshot(updated: PlaybackSnapshot) {
        if (updated == latestSnapshot) return
        latestSnapshot = updated
        onSnapshotChanged(updated)
    }

    private fun acceptUsbOutputStatus(candidate: UsbOutputStatus): Boolean {
        if (candidate.generation < usbOutputStatus.generation) return false
        usbOutputStatus = candidate
        return true
    }

    private fun buildQueueCache(player: Player): PlaybackQueueCache {
        val currentIndex = player.currentMediaItemIndex
        val mediaIds = ArrayList<String>(player.mediaItemCount)
        val projection = ArrayList<PlaybackQueueItem>(player.mediaItemCount)
        repeat(player.mediaItemCount) { index ->
            val queueItem = player.getMediaItemAt(index)
            mediaIds += queueItem.mediaId
            val queueTrack = tracksById[queueItem.mediaId] ?: availableTracksById[queueItem.mediaId]
            val queueTrackId = queueItem.mediaId.toLongOrNull() ?: return@repeat
            projection += PlaybackQueueItem(
                trackId = queueTrackId,
                title = queueTrack?.title ?: queueItem.mediaMetadata.title?.toString().orEmpty(),
                artist = queueTrack?.artist ?: queueItem.mediaMetadata.artist?.toString().orEmpty(),
                isCurrent = index == currentIndex,
            )
        }
        return PlaybackQueueCache(
            mediaIds = mediaIds,
            currentIndex = currentIndex,
            projection = projection,
        )
    }

    private fun AudioTrack.toMediaItem(occurrenceId: String = java.util.UUID.randomUUID().toString()): MediaItem {
        val extras = Bundle(TelemetryMediaItemExtras.from(this)).apply {
            putLong(ARTWORK_REVISION_EXTRA, artworkRevision)
            putString(PLAYBACK_ARTWORK_SOURCE_EXTRA, contentUri)
            putString(PLAYBACK_OCCURRENCE_EXTRA, occurrenceId)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(albumArtist.takeIf(String::isNotBlank))
            .setTrackNumber(trackNumber)
            .setDiscNumber(discNumber)
            .setRecordingYear(year)
            .setGenre(genre.takeIf(String::isNotBlank))
            .setExtras(extras)
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
        if (player.mediaItemCount > 0) {
            suppressListenerPublishing = true
            try {
                val queuedItems = List(player.mediaItemCount, player::getMediaItemAt)
                val replacement = buildSinglePlaybackReplacementBatch(queuedItems) { _, queuedItem ->
                    availableTracksById[queuedItem.mediaId]?.let { updatedTrack ->
                        tracksById[queuedItem.mediaId] = updatedTrack
                        val updatedFingerprint = updatedTrack.productionPlaybackMediaFingerprint()
                        if (queuedItem.matches(updatedFingerprint)) null else updatedTrack.toMediaItem(
                            occurrenceId = queuedItem.mediaMetadata.extras?.getString(PLAYBACK_OCCURRENCE_EXTRA)
                                ?: java.util.UUID.randomUUID().toString(),
                        )
                    }
                }
                if (replacement != null) {
                    // A single range replacement produces one structural reconciliation instead
                    // of one timeline event per changed item. Compatible current items (same
                    // source URI, metadata-only changes) remain eligible for seamless playback.
                    player.replaceMediaItems(0, queuedItems.size, replacement)
                }
            } finally {
                suppressListenerPublishing = false
            }
            publish(player, rebuildQueue = true)
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

    private companion object {
        const val ARTWORK_REVISION_EXTRA = PLAYBACK_ARTWORK_REVISION_EXTRA
    }
}

/** Retains an immutable catalog view so a later controller connection can restore persisted state. */
internal class PlaybackLibrarySnapshot {
    private var latestTracks: List<AudioTrack> = emptyList()

    fun update(tracks: List<AudioTrack>): List<AudioTrack> = tracks.toList().also {
        latestTracks = it
    }

    fun current(): List<AudioTrack> = latestTracks
}

/** Pending user intent is always applied before catalog recovery on a replacement controller. */
internal inline fun <T> applyPlaybackConnectionState(
    pendingQueue: T?,
    applyPendingQueue: (T) -> Unit,
    reconcileLibrary: () -> Unit,
) {
    pendingQueue?.let(applyPendingQueue)
    reconcileLibrary()
}

/**
 * Builds the one range payload used to reconcile queue metadata. A null replacement means the
 * existing item is already current; null for the whole result means no player mutation is needed.
 */
internal inline fun <T> buildSinglePlaybackReplacementBatch(
    currentItems: List<T>,
    replacementAt: (index: Int, current: T) -> T?,
): List<T>? {
    var changed = false
    val reconciled = currentItems.mapIndexed { index, current ->
        replacementAt(index, current)?.also { changed = true } ?: current
    }
    return reconciled.takeIf { changed }
}

internal const val PLAYBACK_ARTWORK_REVISION_EXTRA =
    "io.github.sumirenokai.vesqen.playback.artwork_revision"

private data class PlaybackQueueCache(
    val mediaIds: List<String> = emptyList(),
    val currentIndex: Int = C.INDEX_UNSET,
    val projection: List<PlaybackQueueItem> = emptyList(),
) {
    fun matches(player: Player): Boolean {
        if (player.mediaItemCount != mediaIds.size || player.currentMediaItemIndex != currentIndex) {
            return false
        }
        if (mediaIds.isEmpty()) return true
        return player.currentMediaItem?.mediaId == mediaIds.getOrNull(currentIndex)
    }
}

internal data class PlaybackMediaFingerprint(
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val recordingYear: Int?,
    val genre: String?,
    val artworkUri: String?,
    val artworkRevision: Long,
    val container: String?,
    val codecMime: String?,
    val codecLabel: String?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channelCount: Int?,
    val averageBitrate: Int?,
    val fileSizeBytes: Long?,
)

internal fun AudioTrack.playbackMediaFingerprint(): PlaybackMediaFingerprint {
    return PlaybackMediaFingerprint(
        contentUri = contentUri,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist.takeIf(String::isNotBlank),
        trackNumber = trackNumber,
        discNumber = discNumber,
        recordingYear = year,
        genre = genre.takeIf(String::isNotBlank),
        artworkUri = albumArtworkUri?.takeIf(String::isNotBlank),
        artworkRevision = artworkRevision,
        container = playbackContainerFromFileName(fileName),
        codecMime = mimeType.takeIf(String::isNotBlank),
        codecLabel = codec.takeIf(String::isNotBlank),
        sampleRateHz = sampleRateHz?.takeIf { it > 0 },
        bitDepth = bitDepth?.takeIf { it > 0 },
        channelCount = channelCount?.takeIf { it > 0 },
        averageBitrate = bitrate?.takeIf { it > 0 },
        fileSizeBytes = fileSizeBytes.takeIf { it > 0 },
    )
}

private fun AudioTrack.productionPlaybackMediaFingerprint(): PlaybackMediaFingerprint {
    val emittedSourceFacts = TelemetryMediaItemExtras.read(TelemetryMediaItemExtras.from(this))
    return playbackMediaFingerprint().copy(
        container = emittedSourceFacts.container,
        codecMime = emittedSourceFacts.codecMime,
        codecLabel = emittedSourceFacts.codecLabel,
        sampleRateHz = emittedSourceFacts.sampleRateHz,
        bitDepth = emittedSourceFacts.bitDepth,
        channelCount = emittedSourceFacts.channelCount,
        averageBitrate = emittedSourceFacts.averageBitrate,
        fileSizeBytes = emittedSourceFacts.fileSizeBytes,
    )
}

private fun playbackContainerFromFileName(fileName: String): String? = when (
    fileName.substringAfterLast('.', "").lowercase()
) {
    "flac" -> "flac"
    "wav" -> "wave"
    "aif", "aiff" -> "aiff"
    "mp3" -> "mpeg_audio"
    "m4a", "mp4" -> "mp4"
    "aac" -> "adts"
    "ogg", "opus" -> "ogg"
    else -> null
}

private fun MediaItem.matches(fingerprint: PlaybackMediaFingerprint): Boolean {
    val metadata = mediaMetadata
    val sourceFacts = TelemetryMediaItemExtras.read(metadata.extras)
    return localConfiguration?.uri?.toString() == fingerprint.contentUri &&
        metadata.title?.toString().orEmpty() == fingerprint.title &&
        metadata.artist?.toString().orEmpty() == fingerprint.artist &&
        metadata.albumTitle?.toString().orEmpty() == fingerprint.album &&
        metadata.albumArtist?.toString() == fingerprint.albumArtist &&
        metadata.trackNumber == fingerprint.trackNumber &&
        metadata.discNumber == fingerprint.discNumber &&
        metadata.recordingYear == fingerprint.recordingYear &&
        metadata.genre?.toString() == fingerprint.genre &&
        metadata.artworkUri?.toString() == fingerprint.artworkUri &&
        metadata.extras?.getLong(PLAYBACK_ARTWORK_REVISION_EXTRA, Long.MIN_VALUE) ==
        fingerprint.artworkRevision &&
        sourceFacts.container == fingerprint.container &&
        sourceFacts.codecMime == fingerprint.codecMime &&
        sourceFacts.codecLabel == fingerprint.codecLabel &&
        sourceFacts.sampleRateHz == fingerprint.sampleRateHz &&
        sourceFacts.bitDepth == fingerprint.bitDepth &&
        sourceFacts.channelCount == fingerprint.channelCount &&
        sourceFacts.averageBitrate == fingerprint.averageBitrate &&
        sourceFacts.fileSizeBytes == fingerprint.fileSizeBytes
}

internal fun PlaybackSnapshot.withPlayerPosition(
    isPlaying: Boolean,
    durationMs: Long,
    positionMs: Long,
): PlaybackSnapshot = copy(
    isPlaying = isPlaying,
    durationMs = durationMs.coerceAtLeast(0),
    positionMs = positionMs.coerceAtLeast(0),
)

internal fun reconnectDelayMs(attempt: Int): Long =
    (RECONNECT_BASE_DELAY_MS shl attempt.coerceIn(0, RECONNECT_MAX_SHIFT))
        .coerceAtMost(RECONNECT_MAX_DELAY_MS)

internal enum class PlaybackToggleAction { PAUSE, PLAY, PREPARE, REPLAY }

/** isPlaying is false while buffering or suppressed; playWhenReady retains the user's intent. */
internal fun playbackToggleAction(playWhenReady: Boolean, playbackState: Int): PlaybackToggleAction = when {
    playbackState == Player.STATE_IDLE -> PlaybackToggleAction.PREPARE
    playbackState == Player.STATE_ENDED -> PlaybackToggleAction.REPLAY
    playWhenReady -> PlaybackToggleAction.PAUSE
    else -> PlaybackToggleAction.PLAY
}

private const val RECONNECT_BASE_DELAY_MS = 500L
private const val RECONNECT_MAX_SHIFT = 5
private const val RECONNECT_MAX_DELAY_MS = 10_000L

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
