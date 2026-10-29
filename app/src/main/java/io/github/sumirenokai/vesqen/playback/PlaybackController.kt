package io.github.sumirenokai.vesqen.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
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
    /** Media IDs of merged tracks to the IDs of the records that took them over (#139). */
    private val pendingQueueTrackReplacements = mutableMapOf<String, String>()
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
    private var expectedPlayOrder: ExpectedPlayOrder? = null
    private var clearedQueue: ClearedQueue? = null
    private var reconnectAttempt = 0
    private var connectionGeneration = 0L
    private var released = false
    private val usbOutputCommand = SessionCommand(UsbOutputSessionContract.SET_MODE_ACTION, Bundle.EMPTY)
    private val moveCommand = SessionCommand(PlayOrderSessionContract.MOVE_ACTION, Bundle.EMPTY)
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

        override fun onAvailableSessionCommandsChanged(
            controller: MediaController,
            availableCommands: SessionCommands,
        ) {
            if (this@PlaybackController.controller === controller) publish(controller)
        }

        override fun onDisconnected(disconnectedController: MediaController) {
            if (controller !== disconnectedController || released) return
            disconnectedController.removeListener(playerListener)
            controller = null
            queueCache = PlaybackQueueCache()
            val disconnectedSnapshot = latestSnapshot.withDisconnectedController(
                observedAtEpochMs = System.currentTimeMillis(),
                observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            )
            usbOutputStatus = disconnectedSnapshot.usbOutputStatus
            updateSnapshot(disconnectedSnapshot)
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
                                canSetUsbOutputMode = false,
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

    /**
     * The Library merged the track [fromTrackId] into [toTrackId], which now names the same file
     * (#139). Queue entries take the merged track in their place and keep playing, and the saved queue
     * follows for when no player holds it. The entries change once a library given to [syncLibrary]
     * holds the merged track: the read after the merge can be dropped for a newer one.
     */
    fun replaceQueueTrack(fromTrackId: Long, toTrackId: Long) {
        stateStore.replaceTrackId(fromTrackId, toTrackId)
        pendingQueueTrackReplacements[fromTrackId.toString()] = toTrackId.toString()
        val player = controller ?: return
        if (replacePendingQueueTracks(player)) publish(player, rebuildQueue = true)
    }

    /** Applies the merges whose tracks the library holds; returns whether a queue entry changed. */
    private fun replacePendingQueueTracks(player: MediaController): Boolean {
        val merged = pendingQueueTrackReplacements.mapNotNull { (fromMediaId, toMediaId) ->
            availableTracksById[toMediaId]?.let { fromMediaId to it }
        }.toMap()
        if (merged.isEmpty()) return false
        pendingQueueTrackReplacements.keys.removeAll(merged.keys)
        // The entries keep their occurrences and their places in the play order (PlayOrder).
        val order = shuffledEntryIds(player)
        var replaced = false
        suppressListenerPublishing = true
        try {
            for (index in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                val track = merged[item.mediaId] ?: continue
                // The same file under another ID: Media3 updates the entry without reopening it.
                player.replaceMediaItem(
                    index,
                    track.toPlaybackMediaItem(
                        occurrenceId = item.mediaMetadata.extras?.getString(PLAYBACK_OCCURRENCE_EXTRA)
                            ?: java.util.UUID.randomUUID().toString(),
                    ),
                )
                tracksById[track.id.toString()] = track
                replaced = true
            }
            if (replaced) order?.let { expectPlayOrder(player, it) }
        } finally {
            suppressListenerPublishing = false
        }
        return replaced
    }

    private fun startQueue(
        activeController: MediaController,
        tracks: List<AudioTrack>,
        startIndex: Int,
        positionMs: Long = C.TIME_UNSET,
        playWhenReady: Boolean = true,
        // A new queue keeps the listener's shuffle and repeat modes; shuffled, it plays its start
        // track first and the others in random order (PlayOrder.cloneAndSet).
        shuffleEnabled: Boolean = activeController.shuffleModeEnabled,
        repeatMode: PlaybackRepeatMode = activeController.repeatMode.toPlaybackRepeatMode(),
        startOccurrenceId: String? = null,
        playOrder: List<Int>? = null,
    ) {
        tracksById.clear()
        tracks.forEach { track -> tracksById[track.id.toString()] = track }
        setQueue(
            activeController, playbackMediaItems(tracks, startIndex, startOccurrenceId), startIndex, positionMs,
            playWhenReady, shuffleEnabled, repeatMode, playOrder,
        )
    }

    /** Sets [entries] as the queue, which plays in [playOrder] while shuffled. */
    private fun setQueue(
        activeController: MediaController,
        entries: List<MediaItem>,
        startIndex: Int,
        positionMs: Long,
        playWhenReady: Boolean,
        shuffleEnabled: Boolean,
        repeatMode: PlaybackRepeatMode,
        playOrder: List<Int>?,
    ) {
        clearedQueue = null
        // The modes before the queue, as the service's own restore sets them: shuffle turned on after
        // the queue would put the current item first in a restored queue's saved order (#170).
        activeController.shuffleModeEnabled = shuffleEnabled
        activeController.repeatMode = repeatMode.toMedia3RepeatMode()
        val items = entries.withPlayOrder(playOrder)
        activeController.setMediaItems(items, startIndex, positionMs)
        // Listed in that order until the session's queue arrives (#170 F10).
        expectPlayOrder(activeController, playOrder?.map { items[it].queueEntryId() })
        // Restoring a paused queue is metadata recovery, not a request to create an audio output.
        if (playWhenReady) {
            activeController.prepare()
            activeController.play()
        } else {
            activeController.pause()
        }
        publish(activeController, rebuildQueue = true)
    }

    fun playNext(track: AudioTrack) {
        controller?.let { player -> insert(player, track, PlayOrderPlacement.NEXT) }
    }

    fun addToQueue(track: AudioTrack) {
        controller?.let { player -> insert(player, track, PlayOrderPlacement.LAST) }
    }

    /**
     * Inserts as an ordinary queue edit, which Media3 shows here at once (#170 F8), so a library
     * refresh right after it works on the new queue. The service places the item from its own current
     * item and, while shuffled, in the play order too (#158, PlayOrderSessionPlayer).
     */
    private fun insert(player: MediaController, track: AudioTrack, placement: PlayOrderPlacement) {
        clearedQueue = null
        tracksById[track.id.toString()] = track
        availableTracksById[track.id.toString()] = track
        val item = track.toPlaybackMediaItem()
        val order = shuffledEntryIds(player)
        val (listIndex, playsAfter) = when (placement) {
            PlayOrderPlacement.NEXT ->
                (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount) to player.currentMediaItem?.queueEntryId()
            PlayOrderPlacement.LAST -> player.mediaItemCount to order?.lastOrNull()
        }
        player.addMediaItem(listIndex, item.withPlayOrderPlacement(placement))
        expectPlayOrder(player, order?.withEntryAfter(item.queueEntryId(), playsAfter))
        publish(player, rebuildQueue = true)
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
            val order = shuffledEntryIds(player)
            val removed = player.getMediaItemAt(index).queueEntryId()
            player.removeMediaItem(index)
            expectPlayOrder(player, order?.minus(removed))
            if (player.mediaItemCount == 0) {
                currentProblem = null
            }
            publish(player, rebuildQueue = true)
        }
    }

    /**
     * Moves the item at list index [fromIndex] to the place of [toIndex], its neighbor in the
     * queue's play order. While shuffled only the play order changes, so the list keeps its order
     * for when shuffle is turned off (#158).
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        controller?.let { player ->
            if (fromIndex !in 0 until player.mediaItemCount || toIndex !in 0 until player.mediaItemCount) return
            if (player.isSessionCommandAvailable(moveCommand)) {
                // The service decides from its own shuffle mode; a refusal leaves the queue as shown.
                val order = playOrderOf(player, List(player.mediaItemCount, player::getMediaItemAt))
                val move = PlayOrderSessionContract.Move(
                    item = fromIndex,
                    itemId = player.getMediaItemAt(fromIndex).queueEntryId(),
                    anchor = toIndex,
                    anchorId = player.getMediaItemAt(toIndex).queueEntryId(),
                    after = order.indexOf(fromIndex) < order.indexOf(toIndex),
                )
                player.sendCustomCommand(moveCommand, PlayOrderSessionContract.moveArgs(move))
                return
            }
            player.moveMediaItem(fromIndex, toIndex)
            publish(player, rebuildQueue = true)
        }
    }

    /** Empties the queue. Until another queue starts, [undoClearQueue] can put it back (#165). */
    fun clearQueue() {
        controller?.let { player ->
            clearedQueue = player.takeIf { it.mediaItemCount > 0 }?.let(::clearedQueueOf)
            player.stop()
            player.clearMediaItems()
            tracksById.clear()
            currentProblem = null
            queueCache = PlaybackQueueCache()
            updateSnapshot(
                PlaybackSnapshot(
                    isControllerReady = true,
                    canSetUsbOutputMode = player.isSessionCommandAvailable(usbOutputCommand),
                    usbOutputStatus = usbOutputStatus,
                    outputVerification = outputVerificationLookup(usbOutputStatus),
                ),
            )
        }
    }

    /**
     * Puts back the queue [clearQueue] emptied: the same entries, current track, position and shuffled
     * order, paused. Does nothing once another queue has started or the queue has been put back.
     */
    fun undoClearQueue() {
        val player = controller ?: return
        val cleared = clearedQueue ?: return
        if (player.mediaItemCount > 0) return
        tracksById.putAll(cleared.tracks)
        setQueue(
            player, cleared.entries, cleared.currentIndex, cleared.positionMs, playWhenReady = false,
            cleared.shuffleEnabled, cleared.repeatMode, cleared.playOrder,
        )
    }

    /** The catalog's IDs changed (#165): a cleared queue names other songs now and cannot come back. */
    fun discardClearedQueue() {
        clearedQueue = null
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
            val wasShuffled = activeController.shuffleModeEnabled
            suppressListenerPublishing = true
            try {
                activeController.applyPlaybackOrderMode(activeController.playbackOrderMode.next())
            } finally {
                suppressListenerPublishing = false
            }
            if (!wasShuffled) {
                // The service then starts the play order at the current track (#158).
                val current = activeController.currentMediaItem?.queueEntryId()
                expectPlayOrder(activeController, shuffledEntryIds(activeController)?.withEntryAfter(current, anchor = null))
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
                    libraryDurationMs = player.currentMediaItem?.mediaId?.let(tracksById::get)?.durationMs,
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
                canSetUsbOutputMode = (player as? MediaController)
                    ?.isSessionCommandAvailable(usbOutputCommand) == true,
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
                durationMs = resolvePlaybackDurationMs(player.duration, track?.durationMs),
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
                queueIndex = queueCache.projection.indexOfFirst(PlaybackQueueItem::isCurrent).coerceAtLeast(0),
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

    /** The queue in play order, which is the shuffled order while shuffle is on (#158). */
    private fun buildQueueCache(player: Player): PlaybackQueueCache {
        val currentIndex = player.currentMediaItemIndex
        // One timeline read per item: each one is a window lookup on the main thread.
        val queueItems = List(player.mediaItemCount, player::getMediaItemAt)
        val projection = playOrderOf(player, queueItems).mapNotNull { index ->
            val queueItem = queueItems[index]
            val queueTrack = tracksById[queueItem.mediaId] ?: availableTracksById[queueItem.mediaId]
            val queueTrackId = queueItem.mediaId.toLongOrNull() ?: return@mapNotNull null
            PlaybackQueueItem(
                trackId = queueTrackId,
                title = queueTrack?.title ?: queueItem.mediaMetadata.title?.toString().orEmpty(),
                artist = queueTrack?.artist ?: queueItem.mediaMetadata.artist?.toString().orEmpty(),
                isCurrent = index == currentIndex,
                listIndex = index,
            )
        }
        return PlaybackQueueCache(
            mediaIds = queueItems.map(MediaItem::mediaId),
            currentIndex = currentIndex,
            shuffleEnabled = player.shuffleModeEnabled,
            projection = projection,
        )
    }

    /**
     * List indices in the order they play. Media3 shows this controller's own queue edits at once in a
     * timeline without the shuffle order, in list order, until the session's timeline arrives (#170
     * F10). While the controller holds that timeline, the order is the one the edit leads to.
     */
    private fun playOrderOf(player: Player, queueItems: List<MediaItem>): List<Int> =
        expectedPlayOrder
            ?.takeIf { player.shuffleModeEnabled && it.timeline === player.currentTimeline }
            ?.let { expectedPlayOrderIndices(it.entryIds, queueItems.map(MediaItem::queueEntryId)) }
            ?: player.playOrder()

    /** The queue entries in play order while shuffled, read before one of this controller's own edits. */
    private fun shuffledEntryIds(player: Player, queueItems: List<MediaItem>? = null): List<String>? {
        if (!player.shuffleModeEnabled) return null
        val items = queueItems ?: List(player.mediaItemCount, player::getMediaItemAt)
        return playOrderOf(player, items).map { items[it].queueEntryId() }
    }

    /** The play order after this controller's own edit, for the timeline Media3 now shows for it. */
    private fun expectPlayOrder(player: Player, entryIds: List<String>?) {
        expectedPlayOrder = entryIds?.takeIf { player.shuffleModeEnabled }?.let { ExpectedPlayOrder(player.currentTimeline, it) }
    }

    private class ExpectedPlayOrder(val timeline: Timeline, val entryIds: List<String>)

    /** A cleared queue as the player held it: its own entries keep their occurrences (#165). */
    private class ClearedQueue(
        val entries: List<MediaItem>,
        val tracks: Map<String, AudioTrack>,
        val currentIndex: Int,
        val positionMs: Long,
        val shuffleEnabled: Boolean,
        val repeatMode: PlaybackRepeatMode,
        val playOrder: List<Int>?,
    )

    private fun clearedQueueOf(player: Player): ClearedQueue {
        // An insert's placement request is spent once the queue holds the item.
        val entries = List(player.mediaItemCount) { player.getMediaItemAt(it).withoutRequestMetadata() }
        return ClearedQueue(
            entries = entries,
            tracks = entries.mapNotNull { entry -> tracksById[entry.mediaId]?.let { entry.mediaId to it } }.toMap(),
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode.toPlaybackRepeatMode(),
            playOrder = if (player.shuffleModeEnabled) playOrderOf(player, entries) else null,
        )
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
        // Both branches below publish. An empty player restores the saved queue, which has the records.
        replacePendingQueueTracks(player)
        if (player.mediaItemCount > 0) {
            suppressListenerPublishing = true
            try {
                val queuedItems = List(player.mediaItemCount, player::getMediaItemAt)
                val currentIndex = player.currentMediaItemIndex
                val ranges = buildPlaybackReplacementRanges(
                    currentItems = queuedItems,
                    keepsSource = { queued, replacement ->
                        queued.localConfiguration?.uri == replacement.localConfiguration?.uri
                    },
                ) { index, queuedItem ->
                    availableTracksById[queuedItem.mediaId]?.let { updatedTrack ->
                        tracksById[queuedItem.mediaId] = updatedTrack
                        val updatedFingerprint = updatedTrack.productionPlaybackMediaFingerprint()
                        if (queuedItem.matches(updatedFingerprint)) return@let null
                        // The playing item keeps its open source when its file was renamed or moved
                        // (#164): replacing it would stop playback. A later sync updates it.
                        if (index == currentIndex && queuedItem.localConfiguration?.uri?.toString() != updatedTrack.contentUri) {
                            return@let null
                        }
                        updatedTrack.toPlaybackMediaItem(
                            occurrenceId = queuedItem.mediaMetadata.extras?.getString(PLAYBACK_OCCURRENCE_EXTRA)
                                ?: java.util.UUID.randomUUID().toString(),
                        )
                    }
                }
                // Replacements keep their entries and their places in the play order (PlayOrder).
                val order = if (ranges.isEmpty()) null else shuffledEntryIds(player, queuedItems)
                // Replacing a range that holds an item with a new URI re-creates every item in it,
                // and the current one, if inside, would end. Only changed items are replaced.
                ranges.forEach { (fromIndex, items) -> player.replaceMediaItems(fromIndex, fromIndex + items.size, items) }
                order?.let { expectPlayOrder(player, it) }
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
        stateStore.holdBack(restored.heldBackTrackIds)
        startQueue(
            activeController = player,
            tracks = restored.tracks,
            startIndex = restored.startIndex,
            positionMs = restored.positionMs,
            playWhenReady = false,
            shuffleEnabled = restored.shuffleEnabled,
            repeatMode = restored.repeatMode,
            startOccurrenceId = restored.startOccurrenceId,
            playOrder = restored.playOrder,
        )
    }
}

/**
 * Maps the expected play order (queue entries) to list indices; null unless it holds exactly the
 * queue's entries, each once.
 */
internal fun expectedPlayOrderIndices(entryIds: List<String>, listEntryIds: List<String>): List<Int>? {
    val listIndexOf = listEntryIds.withIndex().associate { (index, entryId) -> entryId to index }
    if (entryIds.size != listEntryIds.size || listIndexOf.size != listEntryIds.size) return null
    return entryIds.map { listIndexOf[it] ?: return null }.takeIf { it.distinct().size == it.size }
}

/** The order with [entryId] put right after [anchor], or first without one, as PlayOrder places it. */
internal fun List<String>.withEntryAfter(entryId: String?, anchor: String?): List<String> {
    if (entryId == null) return this
    val order = filter { it != entryId }.toMutableList()
    order.add(anchor?.let(order::indexOf)?.plus(1) ?: 0, entryId)
    return order
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
 * The queue ranges a library reconciliation replaces, as (first index, replacements); a null
 * replacement means the item is already current. Unchanged items keep their media sources and their
 * place in a shuffled order. Replacements that [keepsSource] update in place, so neighbors share a
 * range and one timeline event; any other is re-created and gets a range of its own.
 */
internal inline fun <T> buildPlaybackReplacementRanges(
    currentItems: List<T>,
    keepsSource: (current: T, replacement: T) -> Boolean,
    replacementAt: (index: Int, current: T) -> T?,
): List<Pair<Int, List<T>>> {
    val ranges = mutableListOf<Pair<Int, MutableList<T>>>()
    var inPlaceRangeEnd = -1
    currentItems.forEachIndexed { index, current ->
        val replacement = replacementAt(index, current) ?: return@forEachIndexed
        val inPlace = keepsSource(current, replacement)
        if (inPlace && index == inPlaceRangeEnd) ranges.last().second += replacement else ranges += index to mutableListOf(replacement)
        inPlaceRangeEnd = if (inPlace) index + 1 else -1
    }
    return ranges
}

internal const val PLAYBACK_ARTWORK_REVISION_EXTRA =
    "io.github.sumirenokai.vesqen.playback.artwork_revision"

/**
 * The one MediaItem shape for a queue entry, whether the UI starts the queue or the service restores
 * it (#52). Telemetry, strict USB output and catalog reconciliation all read these extras.
 */
internal fun AudioTrack.toPlaybackMediaItem(
    occurrenceId: String = java.util.UUID.randomUUID().toString(),
): MediaItem {
    val extras = Bundle(TelemetryMediaItemExtras.from(this)).apply {
        putLong(PLAYBACK_ARTWORK_REVISION_EXTRA, artworkRevision)
        putString(PLAYBACK_ARTWORK_SOURCE_EXTRA, contentUri)
        putString(PLAYBACK_OCCURRENCE_EXTRA, occurrenceId)
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        // A missing tag stays unset, so notifications, the lock screen and connected devices show
        // nothing rather than an empty or placeholder line.
        .setArtist(artist.takeIf(String::isNotBlank))
        .setAlbumTitle(album.takeIf(String::isNotBlank))
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

/**
 * The media items of a queue. The start entry keeps [startOccurrenceId] when a restore resumes a
 * listen already counted (#165); every other entry is a new occurrence.
 */
internal fun playbackMediaItems(
    tracks: List<AudioTrack>,
    startIndex: Int,
    startOccurrenceId: String? = null,
): List<MediaItem> = tracks.mapIndexed { index, track ->
    if (index == startIndex && startOccurrenceId != null) {
        track.toPlaybackMediaItem(occurrenceId = startOccurrenceId)
    } else {
        track.toPlaybackMediaItem()
    }
}

internal fun PlaybackRepeatMode.toMedia3RepeatMode(): Int = when (this) {
    PlaybackRepeatMode.OFF -> Player.REPEAT_MODE_OFF
    PlaybackRepeatMode.ALL -> Player.REPEAT_MODE_ALL
    PlaybackRepeatMode.ONE -> Player.REPEAT_MODE_ONE
}

private data class PlaybackQueueCache(
    val mediaIds: List<String> = emptyList(),
    val currentIndex: Int = C.INDEX_UNSET,
    val shuffleEnabled: Boolean = false,
    val projection: List<PlaybackQueueItem> = emptyList(),
) {
    fun matches(player: Player): Boolean {
        if (
            player.mediaItemCount != mediaIds.size ||
            player.currentMediaItemIndex != currentIndex ||
            player.shuffleModeEnabled != shuffleEnabled
        ) {
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

// Paused queue recovery deliberately stays unprepared; the catalog still knows its duration.
internal fun resolvePlaybackDurationMs(playerDurationMs: Long, libraryDurationMs: Long?): Long =
    playerDurationMs.takeIf { it > 0 } ?: (libraryDurationMs ?: 0).coerceAtLeast(0)

internal fun PlaybackSnapshot.withPlayerPosition(
    isPlaying: Boolean,
    durationMs: Long,
    positionMs: Long,
    libraryDurationMs: Long? = null,
): PlaybackSnapshot = copy(
    isPlaying = isPlaying,
    durationMs = resolvePlaybackDurationMs(durationMs, libraryDurationMs),
    positionMs = positionMs.coerceAtLeast(0),
)

/** A disconnected controller cannot continue to prove a service-owned strict output claim. */
internal fun PlaybackSnapshot.withDisconnectedController(
    observedAtEpochMs: Long,
    observedAtElapsedRealtimeMs: Long,
): PlaybackSnapshot {
    val disconnectedOutputStatus = usbOutputStatus.takeUnless {
        it.mode == UsbOutputMode.STRICT_BIT_PERFECT &&
            it.phase in setOf(UsbOutputPhase.AVAILABLE, UsbOutputPhase.APPLYING, UsbOutputPhase.ACTIVE)
    } ?: usbOutputStatus.copy(
        phase = UsbOutputPhase.APPLYING,
        failure = null,
        decisionCode = "strict_usb.session_disconnected",
        observedAtEpochMs = observedAtEpochMs.coerceAtLeast(0),
        observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs.coerceAtLeast(0),
    )
    return copy(
        isControllerReady = false,
        canSetUsbOutputMode = false,
        isPlaying = false,
        showsPauseAction = false,
        usbOutputStatus = disconnectedOutputStatus,
        outputVerification = null,
    )
}

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
