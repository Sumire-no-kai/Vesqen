package io.github.sumirenokai.vesqen.playback

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import io.github.sumirenokai.vesqen.BuildConfig
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.MainActivity
import io.github.sumirenokai.vesqen.VesqenApplication
import io.github.sumirenokai.vesqen.library.AndroidLibraryCatalog
import io.github.sumirenokai.vesqen.library.musicLibraryPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The longest queue the platform session publishes. Media3 1.11 copies every queue item into the
 * platform session on each timeline change, including the two as each upcoming track is prepared,
 * and Android sends each copy to every system controller. One item with Vesqen's extras is about
 * 1.7 KB, so on the 2026-11-09 emulator run a 2,010-track queue of 0.5 s tracks sent about 200 copies
 * of 3.5 MB in 20 s and the Bluetooth app ran out of memory. A longer queue stays in the app: the
 * platform session then has no queue, and the notification, lock screen and Bluetooth controls keep
 * the current track and its commands.
 */
internal const val PLATFORM_QUEUE_LIMIT = 100

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val playbackStateStore by lazy { PlaybackStateStore(this) }
    private var playbackStateKeeper: PlaybackStateKeeper? = null
    private var sessionArtworkLoader: SessionArtworkLoader? = null
    private var usbOutputCoordinator: UsbOutputCoordinator? = null
    private var directUsbRoute: DirectUsbRoute? = null
    private var screenOffKeepAlive: ScreenOffKeepAliveController? = null
    private var problemGuard: PlaybackProblemGuard? = null
    private var sleepTimer: PlaybackSleepTimer? = null
    private var notificationProvider: DefaultMediaNotificationProvider? = null
    /** Opened on the first playback resumption; the UI keeps its own catalog. */
    private var resumptionCatalog: AndroidLibraryCatalog? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mediaButtonStart = MediaButtonStartObligation()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val outputConsentCommand = SessionCommand(UsbOutputSessionContract.OUTPUT_CONSENT_ACTION, android.os.Bundle.EMPTY)
    private val usbOutputCommand = SessionCommand(UsbOutputSessionContract.SET_MODE_ACTION, android.os.Bundle.EMPTY)
    private val moveCommand = SessionCommand(PlayOrderSessionContract.MOVE_ACTION, android.os.Bundle.EMPTY)
    private val sleepTimerCommand = SessionCommand(SleepTimerSessionContract.SET_ACTION, android.os.Bundle.EMPTY)
    private val outputStateListener: (UsbOutputStatus) -> Unit = { status ->
        mainHandler.post {
            publishSessionExtras(status)
            // A strict failure or consent prompt stops this Play without sound, ending any media
            // button start's foreground obligation too.
            if (status.stopsRequestedPlayback()) {
                // An output refusal can pause without raising PlayerError. Do not retain its gate.
                if (status == (application as VesqenApplication).usbOutputStateRepository.snapshot()) {
                    sleepTimer?.apply(SleepTimerRequest.Cancel)
                }
                endMediaButtonStartWithoutPlayback()
            }
        }
    }
    private val playbackListener = object : Player.Listener {
        // Read after the batch: a listener after this one may pause inside it (#344), and the
        // batch still reports the Play it reversed.
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.isPlaying) mediaButtonStart.playbackStarted()
        }

        override fun onPlayerError(error: PlaybackException) {
            endMediaButtonStartWithoutPlayback()
        }

        // A start that reaches the end without ever playing (nothing left to play) owes no
        // foreground either; otherwise the restore's notification would stay until the next Play.
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) endMediaButtonStartWithoutPlayback()
        }
    }

    /** Whether the platform session gets the queue ([PLATFORM_QUEUE_LIMIT]); Media3's default is yes. */
    private var platformQueuePublished = true
    private val platformCommandsReady = SettableFuture.create<Unit>()

    /**
     * Withdraws a queue that grows past [PLATFORM_QUEUE_LIMIT] from the platform session, and gives a
     * queue back once it is short again. Added to the player before the session is built, so it runs
     * before the session's own listener. Client connections wait for the notification controller,
     * so the first enqueue or media-button restore also has a controller to apply the cap through.
     */
    private val platformQueueListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val publish = timeline.windowCount <= PLATFORM_QUEUE_LIMIT
            if (publish == platformQueuePublished) return
            val session = mediaSession ?: return
            val controller = session.mediaNotificationControllerInfo ?: return
            platformQueuePublished = publish
            val commands = connectionResult(session, controller)
            session.setAvailableCommands(controller, commands.availableSessionCommands, commands.availablePlayerCommands)
        }
    }

    private val resumption = PlaybackResumption(
        loadSavedState = { playbackStateStore.load() },
        readPlayableTracks = {
            val catalog = withContext(Dispatchers.Main.immediate) {
                resumptionCatalog ?: AndroidLibraryCatalog(
                    this@PlaybackService,
                    (application as VesqenApplication).libraryCatalogStore,
                    storageScan = (application as VesqenApplication).storageScan,
                ).also { resumptionCatalog = it }
            }
            // The Library lists device music only while this permission is granted.
            catalog.playableTracks(
                includeDeviceLibrary = ContextCompat.checkSelfPermission(this, musicLibraryPermission) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        },
    )

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            if (session.isMediaNotificationController(controller)) {
                platformQueuePublished = session.player.mediaItemCount <= PLATFORM_QUEUE_LIMIT
                return Futures.immediateFuture(connectionResult(session, controller))
            }
            // Media3 initially publishes with its default commands. Keep the empty player private
            // until the notification controller's result has configured the platform session.
            return Futures.transform(Futures.nonCancellationPropagating(platformCommandsReady),
                { connectionResult(session, controller) }, MoreExecutors.directExecutor())
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            // onConnectAsync returns before Media3 applies its result; onPostConnect runs after it.
            if (session.isMediaNotificationController(controller)) platformCommandsReady.set(Unit)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            val player = session.exoPlayer()
            return when (customCommand.customAction) {
                UsbOutputSessionContract.SET_MODE_ACTION -> UsbOutputSessionContract.readMode(args)
                    ?.let(::applyUsbOutputMode)
                    ?: Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                UsbOutputSessionContract.OUTPUT_CONSENT_ACTION -> onMainThread {
                    val answered = usbOutputCoordinator?.answerUsbOutputConsent(
                        args.getLong(UsbOutputSessionContract.GENERATION, -1L),
                        args.getLong(UsbOutputSessionContract.OBSERVED_AT_ELAPSED, -1L),
                        args.getBoolean(UsbOutputSessionContract.ACCEPT_OUTPUT_CONSENT),
                    ) == true
                    SessionResult(if (answered) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE)
                }
                PlayOrderSessionContract.MOVE_ACTION -> {
                    val move = PlayOrderSessionContract.readMove(args)
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    onMainThread {
                        // A refusal means the queue changed since the controller read it.
                        SessionResult(if (player.moveInPlayOrder(move)) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE)
                    }
                }
                SleepTimerSessionContract.SET_ACTION -> {
                    val request = SleepTimerSessionContract.readRequest(args)
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    onMainThread {
                        val applied = try {
                            sleepTimer?.apply(request) == true
                        } catch (_: SecurityException) {
                            return@onMainThread SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                        }
                        if (applied) {
                            SessionResult(SessionResult.RESULT_SUCCESS,
                                SleepTimerSessionContract.withState(android.os.Bundle.EMPTY, checkNotNull(sleepTimer).state))
                        } else SessionResult(SessionError.ERROR_BAD_VALUE)
                    }
                }
                else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
        }

        /**
         * #52: Media3 calls this only when Play reaches a player without a current item. Service
         * start, process recreation and session creation never restore the queue.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            resumeSavedQueue(mediaSession.player, isForPlayback)
    }

    /**
     * Media3 1.11's deprecated onConnect default is a sentinel with empty commands, so this resolves
     * the controller's trust-aware defaults before adding the app's private commands. Media3 gives the
     * media notification controller's commands to the platform session too, so that controller reads
     * the timeline only while [platformQueuePublished].
     */
    private fun connectionResult(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val base = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
        if (controller.packageName != packageName) return base
        val playerCommands = if (session.isMediaNotificationController(controller) && !platformQueuePublished) {
            base.availablePlayerCommands.buildUpon().remove(Player.COMMAND_GET_TIMELINE).build()
        } else {
            base.availablePlayerCommands
        }
        return MediaSession.ConnectionResult.accept(
            base.availableSessionCommands.buildUpon()
                .add(usbOutputCommand).add(outputConsentCommand).add(moveCommand).add(sleepTimerCommand).build(),
            playerCommands,
        )
    }

    private fun applyUsbOutputMode(mode: UsbOutputMode): ListenableFuture<SessionResult> = onMainThread {
        usbOutputCoordinator?.requestMode(mode)
        val extras = UsbOutputSessionContract.toBundle(
            usbOutputCoordinator?.currentStatus() ?: UsbOutputStatus(),
        )
        SessionResult(SessionResult.RESULT_SUCCESS, extras)
    }

    private fun publishSessionExtras(
        outputStatus: UsbOutputStatus = (application as VesqenApplication).usbOutputStateRepository.snapshot(),
    ) {
        val session = mediaSession ?: return
        val combined = UsbOutputSessionContract.withStatus(session.sessionExtras, outputStatus)
        session.setSessionExtras(SleepTimerSessionContract.withState(combined, sleepTimer?.state ?: SleepTimerState()))
    }

    /** The player and the output coordinator belong to the main thread. */
    private fun onMainThread(apply: () -> SessionResult): ListenableFuture<SessionResult> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Futures.immediateFuture(apply())
        }
        val result = SettableFuture.create<SessionResult>()
        if (!mainHandler.post { result.set(apply()) }) {
            result.set(SessionResult(SessionError.ERROR_SESSION_DISCONNECTED))
        }
        return result
    }

    /**
     * Restores the saved queue for one Play request. Media3 then sets it on the player and plays,
     * so strict USB output checks it like any other Play (UsbOutputCoordinator, PRD 9.2).
     */
    private fun resumeSavedQueue(
        player: Player,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        serviceScope.launch {
            val restored = try {
                withContext(Dispatchers.IO) { resumption.restore() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A locked or unreadable catalog leaves nothing to restore. Media3 logs the
                // failure and applies Play to the empty player, which stays silent.
                result.setException(failure)
                null
            }
            when {
                result.isDone -> Unit
                restored == null -> result.setException(NoSuchElementException("No saved queue can be played"))
                // The UI started or restored a queue while the catalog was read. Media3 plays that
                // one instead, because a failed resumption still applies the Play request.
                isForPlayback && player.currentMediaItem != null ->
                    result.setException(IllegalStateException("A queue was set while the saved one was read"))
                else -> {
                    if (isForPlayback) {
                        player.shuffleModeEnabled = restored.shuffleEnabled
                        player.repeatMode = restored.repeatMode.toMedia3RepeatMode()
                    }
                    playbackStateStore.holdBack(restored.heldBackTrackIds)
                    result.set(
                        MediaSession.MediaItemsWithStartPosition(
                            // A shuffled queue plays in its saved order (PlayOrderSessionPlayer).
                            playbackMediaItems(restored.tracks, restored.startIndex, restored.startOccurrenceId)
                                .withPlayOrder(restored.playOrder),
                            restored.startIndex,
                            restored.positionMs,
                        ),
                    )
                }
            }
            // Runs after Media3 applied the result and the Play request to the player. A shuffled
            // queue without a saved order plays its start track first (PlayOrder.cloneAndSet).
            if (isForPlayback) {
                mainHandler.post {
                    if (player.currentMediaItem == null) endMediaButtonStartWithoutPlayback()
                }
            }
        }
        return result
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .build()
            .apply { setSmallIcon(R.drawable.ic_notification_vesqen) }
            .also { notificationProvider = it }
        setMediaNotificationProvider(notificationProvider)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val telemetry = (application as VesqenApplication).telemetryRuntime
        val dataSourceFactory = DefaultDataSource.Factory(this)
            .setTransferListener(telemetry.transferListener)
        // #249: every DSD stream reads the Audio output page's settings when its file opens.
        val dsdConversion = (application as VesqenApplication).dsdConversion
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, VesqenExtractorsFactory(dsdConversion::conversion))
        val outputStateRepository = (application as VesqenApplication).usbOutputStateRepository
        // M7-4: off in release builds until 2.0.0-beta.1 (`vesqen.directUsb`).
        val directRoute = if (BuildConfig.DIRECT_USB_ENABLED) {
            AndroidDirectUsbRoute(
                this,
                (application as VesqenApplication).directUsbMarkers,
                (application as VesqenApplication).directUsbVolume,
                (application as VesqenApplication).directUsbVolumeMemory,
            )
        } else null
        directUsbRoute = directRoute
        directRoute?.let { route -> (application as VesqenApplication).directUsbReadings.attach(route::readings) }
        val outputCoordinator = UsbOutputCoordinator(
            this,
            outputStateRepository,
            strictOutputAllowed = (application as VesqenApplication).premiumAccess::strictOutputAllowed,
            directRoute = directRoute,
            onPlayerStopping = { sleepTimer?.onPlayerStopping(it) },
        ).also {
            usbOutputCoordinator = it
        }
        val renderers = VesqenAudioRenderersFactory(this, outputCoordinator, telemetry::onDecoderOutputFormat, directRoute)
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setAudioAttributes(audioAttributes, true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                pauseAtEndOfMediaItems = false
                setShuffleOrder(PlayOrder(IntArray(0)))
                applySavedPlayModes(playbackStateStore)
            }
        player.addListener((application as VesqenApplication).deviceReportRuntime.playbackListener(player))
        player.addListener(playbackListener)
        player.addListener(platformQueueListener)
        sleepTimer = PlaybackSleepTimer(player, AndroidSleepTimerScheduler(this, mainHandler)) {
            publishSessionExtras()
        }
        player.addListener(object : Player.Listener {
            // Turning shuffle on starts the play order at the current track, so none of the others is
            // skipped (#158). A queue set while shuffled starts there already (PlayOrder.cloneAndSet).
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) player.playCurrentItemFirst()
            }
        })
        // #344: before the coordinator, the keeper and the session, which read its pause or seek.
        val interruptions = (application as VesqenApplication).playbackInterruptions
        val guard = PlaybackProblemGuard(
            player = player,
            skipUnplayable = { (application as VesqenApplication).unplayableTracks.skip.value },
            interruptions = interruptions,
            outputHoldsPlay = outputCoordinator::holdsPlaybackForOutput,
            onStopped = {
                outputCoordinator.dropHeldPlayback()
                endMediaButtonStartWithoutPlayback()
            },
            onUnplayable = (application as VesqenApplication).deviceReportRuntime::recordUnplayableTrack,
        ).also { problemGuard = it }
        player.addListener(guard)
        // A change reaches the next DSD track even when Media3 has already opened it.
        serviceScope.launch {
            var generation = 0L
            dsdConversion.values.drop(1).collect { player.reopenUpcomingDsd(++generation) }
        }
        val problemNotification = PlaybackProblemNotification(this)
        serviceScope.launch {
            interruptions.current.collect { interruption ->
                when {
                    interruption == null -> problemNotification.cancel()
                    // In the app, its dialog explains the stop instead.
                    !(application as VesqenApplication).mainActivityVisible -> problemNotification.show(interruption)
                }
            }
        }
        telemetry.attachPlayer(player)
        outputCoordinator.attachPlayer(player)
        playbackStateKeeper = PlaybackStateKeeper(
            player = player,
            stateStore = playbackStateStore,
            handler = Handler(Looper.getMainLooper()),
            onPlaybackStarted = (application as VesqenApplication)
                .playbackHistoryRecorder::recordPlayback,
        ).also(PlaybackStateKeeper::start)
        // This service writes the playback state, so a transaction's catalog merges change its saved
        // queue, its counted listen and its entries in one step (#191 D7, A2, A4).
        (application as VesqenApplication).playbackTrackMerges.attach { merges ->
            playbackStateStore.replaceTrackIds(merges)
            playbackStateKeeper?.tracksMerged(merges)
            player.replaceTrackEntries(merges)
        }
        serviceScope.launch {
            // The queue's catalog IDs name other songs, or none, in a rebuilt catalog (#165): playing,
            // counting or saving it would mix them up. The saved queue was cleared before the rebuild.
            (application as VesqenApplication).libraryCatalogStore.rebuilds.drop(1).collect {
                player.stop()
                player.clearMediaItems()
            }
        }
        val artworkLoader = SessionArtworkLoader(this).also { sessionArtworkLoader = it }
        val sessionActivity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val trackMerges = (application as VesqenApplication).playbackTrackMerges
        val orderedPlayer = PlayOrderSessionPlayer(
            player, trackMerges::mergedTrackId, guard::navigating,
            onStop = { sleepTimer?.onPlayerStopping(forOutputReturn = false) },
        )
        // With the own USB output, the session also reports the DAC's hardware volume (D4).
        val sessionPlayer = if (directRoute != null) {
            DeviceVolumeSessionPlayer(orderedPlayer, (application as VesqenApplication).directUsbVolume)
        } else orderedPlayer
        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setBitmapLoader(artworkLoader)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .setSessionExtras(SleepTimerSessionContract.withState(
                UsbOutputSessionContract.toBundle(outputStateRepository.snapshot()), checkNotNull(sleepTimer).state,
            ))
            .build()
        outputStateRepository.addListener(outputStateListener)
        if (directRoute != null) {
            // D13: only Vesqen's own output needs it; the system path keeps the app alive by itself.
            val settings = (application as VesqenApplication).screenOffKeepAlive
            val keepAlive = ScreenOffKeepAliveController(this, mainHandler, ScreenOffKeepAlive { SpeakerKeepAliveTrack.start(this) })
            screenOffKeepAlive = keepAlive
            outputStateRepository.addListener(keepAlive.statusListener)
            serviceScope.launch { settings.enabled.collect(keepAlive::setEnabled) }
        }
    }

    @UnstableApi
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val session = mediaSession ?: return null
        // The service remains exported so Android system controls, media buttons, trusted
        // assistants and this app can reach it. An arbitrary local app does not need read
        // access to the user's current title or queue.
        if (controllerInfo.isTrusted) return session
        // #52: a media button that starts the service only hands its key to the session; it is
        // not a connected controller and reads nothing. Media3 stops the service cleanly when
        // this returns null.
        val hints = controllerInfo.connectionHints
        val isMediaButtonStart = isMediaButtonStartPlaceholder(
            isPackageNameVerified = controllerInfo.isPackageNameVerified,
            controllerInfoType = hints.getString(CONNECTION_HINT_KEY_CONTROLLER_INFO_TYPE),
        )
        val mayStart = isMediaButtonStart && mediaButtonMayStartPlayback(
            keyCode = hints.getBundle(CONNECTION_HINT_KEY_INTENT_EXTRAS)
                ?.let { extras -> BundleCompat.getParcelable(extras, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }
                ?.keyCode,
            hasCurrentItem = session.player.currentMediaItem != null,
            hasSavedQueue = playbackStateStore::hasSavedQueue,
        )
        return session.takeIf { mayStart }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        val session = mediaSession
        if (
            intent?.action == Intent.ACTION_MEDIA_BUTTON &&
            session != null && isSessionAdded(session) && !isPlaybackOngoing
        ) {
            // Restoring a saved queue can outlast Android's deadline for startForeground() (5 s
            // before Android 12, 10 s from it), so that start enters the foreground at once. No
            // media notification exists for an empty player, so Media3's id is free for it.
            val restoring = session.player.currentMediaItem == null
            if (!restoring) {
                mediaButtonStart.open(enteredForeground = false)
            } else if (enterForegroundHidden(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)) {
                mediaButtonStart.open(enteredForeground = true)
            }
        }
        return result
    }

    /**
     * A media button start ended without playback (nothing restorable, a strict USB failure under
     * PRD 9.2, or a playback error). Leaves the foreground the way Media3 stops a start it rejects,
     * first entering it under a separate id when it never did, so Media3's paused notification for a
     * loaded queue stays as it was. Once Media3 runs the foreground, it owns it.
     */
    private fun endMediaButtonStartWithoutPlayback() {
        when (mediaButtonStart.endWithoutPlayback(mediaInForeground = isPlaybackOngoing)) {
            MediaButtonStartObligation.Ending.NONE -> return
            MediaButtonStartObligation.Ending.LEAVE_FOREGROUND -> Unit
            MediaButtonStartObligation.Ending.ENTER_AND_LEAVE ->
                if (!enterForegroundHidden(REJECTED_START_NOTIFICATION_ID)) return
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /**
     * Enters the foreground with a silent notification hidden from the lock screen. False when
     * Android refuses, which happens only when this was not a foreground start, so nothing is owed.
     */
    // ServiceCompat passes the API 29 foreground service type only where it exists.
    @SuppressLint("InlinedApi")
    private fun enterForegroundHidden(notificationId: Int): Boolean {
        val channel = checkNotNull(notificationProvider).notificationChannelInfo
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(channel.id, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(channel.name)
                .build(),
        )
        val notification = NotificationCompat.Builder(this, channel.id)
            .setSmallIcon(R.drawable.ic_notification_vesqen)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        try {
            ServiceCompat.startForeground(this, notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (notAllowed: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                notAllowed !is ForegroundServiceStartNotAllowedException
            ) {
                throw notAllowed
            }
            return false
        }
        return true
    }

    override fun onDestroy() {
        platformCommandsReady.cancel(false)
        sleepTimer?.close()
        sleepTimer = null
        (application as VesqenApplication).playbackTrackMerges.detach()
        serviceScope.cancel()
        (application as VesqenApplication).usbOutputStateRepository.removeListener(outputStateListener)
        screenOffKeepAlive?.let { keepAlive ->
            (application as VesqenApplication).usbOutputStateRepository.removeListener(keepAlive.statusListener)
            keepAlive.close()
        }
        screenOffKeepAlive = null
        usbOutputCoordinator?.close()
        usbOutputCoordinator = null
        (application as VesqenApplication).directUsbReadings.detach()
        directUsbRoute?.close()
        directUsbRoute = null
        playbackStateKeeper?.stop()
        playbackStateKeeper = null
        mediaSession?.run {
            val exoPlayer = exoPlayer()
            (player as? DeviceVolumeSessionPlayer)?.stopReporting()
            exoPlayer.removeListener(playbackListener)
            exoPlayer.removeListener(platformQueueListener)
            problemGuard?.let(exoPlayer::removeListener)
            problemGuard = null
            (application as VesqenApplication).telemetryRuntime.detachPlayer(exoPlayer)
            exoPlayer.release()
            release()
        }
        mediaSession = null
        sessionArtworkLoader?.close()
        sessionArtworkLoader = null
        resumptionCatalog?.close()
        resumptionCatalog = null
        super.onDestroy()
    }

    private companion object {
        /** Media3 uses 1001 for the media notification and 20938 for its own stop notification. */
        const val REJECTED_START_NOTIFICATION_ID = 20939
    }
}

/** The service's ExoPlayer behind the session's player wrappers. */
@UnstableApi
private fun MediaSession.exoPlayer(): ExoPlayer {
    val ordered = (player as? DeviceVolumeSessionPlayer)?.wrappedPlayer ?: player
    return (ordered as PlayOrderSessionPlayer).exoPlayer
}
