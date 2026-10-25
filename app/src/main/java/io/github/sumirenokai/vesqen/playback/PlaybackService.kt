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
import com.google.common.util.concurrent.SettableFuture
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val playbackStateStore by lazy { PlaybackStateStore(this) }
    private var playbackStateKeeper: PlaybackStateKeeper? = null
    private var sessionArtworkLoader: SessionArtworkLoader? = null
    private var usbOutputCoordinator: UsbOutputCoordinator? = null
    private var notificationProvider: DefaultMediaNotificationProvider? = null
    /** Opened on the first playback resumption; the UI keeps its own catalog. */
    private var resumptionCatalog: AndroidLibraryCatalog? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mediaButtonStart = MediaButtonStartObligation()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbOutputCommand = SessionCommand(UsbOutputSessionContract.SET_MODE_ACTION, android.os.Bundle.EMPTY)
    private val insertCommand = SessionCommand(PlayOrderSessionContract.INSERT_ACTION, android.os.Bundle.EMPTY)
    private val moveCommand = SessionCommand(PlayOrderSessionContract.MOVE_ACTION, android.os.Bundle.EMPTY)
    private val outputStateListener: (UsbOutputStatus) -> Unit = { status ->
        mainHandler.post {
            mediaSession?.setSessionExtras(UsbOutputSessionContract.toBundle(status))
            // A strict failure stops the requested Play without sound (PRD 9.2), which also ends a
            // media button start.
            if (status.stopsRequestedPlayback()) endMediaButtonStartWithoutPlayback()
        }
    }
    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) mediaButtonStart.playbackStarted()
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

    private val resumption = PlaybackResumption(
        loadSavedState = { playbackStateStore.load() },
        readPlayableTracks = {
            val catalog = withContext(Dispatchers.Main.immediate) {
                resumptionCatalog ?: AndroidLibraryCatalog(this@PlaybackService).also { resumptionCatalog = it }
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
            // Media3 1.11's deprecated onConnect default is a sentinel with empty commands.
            // Resolve the controller's trust-aware defaults before adding our private command.
            val base = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
            if (controller.packageName != packageName) return Futures.immediateFuture(base)
            return Futures.immediateFuture(MediaSession.ConnectionResult.accept(
                base.availableSessionCommands.buildUpon().add(usbOutputCommand).add(insertCommand).add(moveCommand).build(),
                base.availablePlayerCommands,
            ))
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
            val player = session.player as ExoPlayer
            return when (customCommand.customAction) {
                UsbOutputSessionContract.SET_MODE_ACTION -> UsbOutputSessionContract.readMode(args)
                    ?.let(::applyUsbOutputMode)
                    ?: Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                PlayOrderSessionContract.INSERT_ACTION -> {
                    val insert = PlayOrderSessionContract.readInsert(args)
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    onMainThread {
                        player.insertInPlayOrder(insert)
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                }
                PlayOrderSessionContract.MOVE_ACTION -> {
                    val move = PlayOrderSessionContract.readMove(args)
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    onMainThread {
                        // A refusal means the queue changed since the controller read it.
                        SessionResult(if (player.moveInPlayOrder(move)) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE)
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

    private fun applyUsbOutputMode(mode: UsbOutputMode): ListenableFuture<SessionResult> = onMainThread {
        usbOutputCoordinator?.requestMode(mode)
        val extras = UsbOutputSessionContract.toBundle(
            usbOutputCoordinator?.currentStatus() ?: UsbOutputStatus(),
        )
        SessionResult(SessionResult.RESULT_SUCCESS, extras)
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
                    result.set(
                        MediaSession.MediaItemsWithStartPosition(
                            restored.tracks.map { it.toPlaybackMediaItem() },
                            restored.startIndex,
                            restored.positionMs,
                        ),
                    )
                }
            }
            // Runs after Media3 applied the result and the Play request to the player. Shuffle was set
            // on the empty player, so the new queue's shuffled order starts at its track only now.
            if (isForPlayback) {
                mainHandler.post {
                    if (player.currentMediaItem == null) endMediaButtonStartWithoutPlayback()
                    (player as? ExoPlayer)?.playCurrentItemFirst()
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
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val outputStateRepository = (application as VesqenApplication).usbOutputStateRepository
        val outputCoordinator = UsbOutputCoordinator(
            this,
            outputStateRepository,
            strictOutputAllowed = (application as VesqenApplication).premiumAccess::strictOutputAllowed,
        ).also {
            usbOutputCoordinator = it
        }
        val player = ExoPlayer.Builder(this, VesqenAudioRenderersFactory(this, outputCoordinator, telemetry::onDecoderOutputFormat))
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setAudioAttributes(audioAttributes, true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                pauseAtEndOfMediaItems = false
            }
        player.addListener((application as VesqenApplication).deviceReportRuntime.playbackListener(player))
        player.addListener(playbackListener)
        player.addListener(object : Player.Listener {
            // Turning shuffle on, here or when the app restores a shuffled queue, starts the play order
            // at the current track, so none of the others is skipped (#158). A media-button
            // resumption sets shuffle before its items; resumeSavedQueue handles that one.
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) player.playCurrentItemFirst()
            }
        })
        telemetry.attachPlayer(player)
        outputCoordinator.attachPlayer(player)
        playbackStateKeeper = PlaybackStateKeeper(
            player = player,
            stateStore = playbackStateStore,
            handler = Handler(Looper.getMainLooper()),
            onPlaybackStarted = (application as VesqenApplication)
                .playbackHistoryRecorder::recordPlayback,
        ).also(PlaybackStateKeeper::start)
        val artworkLoader = SessionArtworkLoader(this).also { sessionArtworkLoader = it }
        val sessionActivity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSession.Builder(this, player)
            .setBitmapLoader(artworkLoader)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .setSessionExtras(UsbOutputSessionContract.toBundle(outputStateRepository.snapshot()))
            .build()
        outputStateRepository.addListener(outputStateListener)
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
        serviceScope.cancel()
        (application as VesqenApplication).usbOutputStateRepository.removeListener(outputStateListener)
        usbOutputCoordinator?.close()
        usbOutputCoordinator = null
        playbackStateKeeper?.stop()
        playbackStateKeeper = null
        mediaSession?.run {
            player.removeListener(playbackListener)
            (application as VesqenApplication).telemetryRuntime.detachPlayer(player as ExoPlayer)
            player.release()
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
