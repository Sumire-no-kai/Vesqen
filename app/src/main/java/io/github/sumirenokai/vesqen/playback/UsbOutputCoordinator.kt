package io.github.sumirenokai.vesqen.playback

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import io.github.sumirenokai.vesqen.telemetry.TelemetryMediaItemExtras
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import java.nio.ByteBuffer

/**
 * Service-owned strict USB state machine and AudioTrack creation seam.
 *
 * A mixer request is only ACTIVE after the exact AudioTrack format, mixer readback, processing
 * settings, and routed USB device all agree. Every failure path pauses and tears down the current
 * output so strict mode never continues through Android's normal mixed path.
 */
@UnstableApi
internal class UsbOutputCoordinator(
    context: Context,
    private val stateRepository: UsbOutputStateRepository,
    private val resolver: UsbOutputStrategyResolver = UsbOutputStrategyResolver(),
    private val mixerAdapter: MixerBitPerfectAdapter = MixerBitPerfectAdapterFactory.create(context),
    /** #119: false once the strict output trial ended without a purchase. */
    private val strictOutputAllowed: () -> Boolean = { true },
    /**
     * M7-4: Vesqen's own USB output, for phones whose Android offers no bit-perfect mixer for the
     * device. Null when the build leaves it off (`vesqen.directUsb`).
     */
    private val directRoute: DirectUsbRoute? = null,
) : ForwardingAudioOutputProvider(
    AudioTrackAudioOutputProvider.Builder(context.applicationContext).build(),
), Player.Listener {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val mixerMutationLock = Any()
    private val mixerPreferences = MixerPreferenceCleanupTracker<MixerPreference> { preference ->
        mixerAdapter.clearPreferred(preference.device, preference.audioAttributes)
    }
    private val officialMixerApiSupport = OfficialMixerApiSupport(
        androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() },
        apiLevel = Build.VERSION.SDK_INT,
        mixerApiAvailable = MixerBitPerfectAdapterFactory.isPlatformApiAvailable(),
    )

    private var player: ExoPlayer? = null
    private var mode = preferences.getString(MODE_KEY, null)?.let { stored ->
        UsbOutputMode.entries.firstOrNull { it.name == stored }
    } ?: UsbOutputMode.SYSTEM
    @Volatile private var sourceFormat: SourcePcmFormat? = null
    @Volatile private var sourceFacts: TelemetrySourceFacts? = null
    private var selectedDevice: AudioDeviceInfo? = null
    private var selectedProfiles: List<MixerProfile>? = null
    private var selectedMixerFormat: PlatformPcmFormat? = null
    private var selectedAudioAttributes: AudioAttributes? = null
    private var configurationGeneration = 0L
    private var configurationOrigin = UsbOutputFailureOrigin.SERVICE_START
    private var internalResumeInProgress = false
    private var selectedPlanGeneration = NO_GENERATION
    private var currentAudioTrack: AudioTrack? = null
    private var currentAudioTrackGeneration = NO_GENERATION
    private var currentAudioOutput: StrictGatedAudioOutput? = null
    private var currentRoutingListener: AudioRouting.OnRoutingChangedListener? = null
    private var resumeAfterConfiguration = false
    private var internalPauseInProgress = false
    private var reconfigurePosted = false
    private var closed = false
    private var statusGeneration = stateRepository.snapshot().generation

    /** The current strict attempt uses Vesqen's own USB output; set from a ready inspection until it ends. */
    private var directPath = false
    private var directDeviceName: String? = null
    private var directPlan: StreamingPlan? = null

    /** Output identity and delivery are separate: opening or retaining a session alone is not ACTIVE. */
    private var directOutputOpened = false
    @Volatile private var directSourceGeneration = 0L

    /** Main thread: the sources the route reported since the current output instance opened. */
    private val directSources = mutableListOf<DirectSource>()
    private var returnTarget: UsbOutputMode? = null

    /**
     * Set when the own path gave the device back after a failure. Android then shows the USB route
     * again, which must not start another takeover; a real plug-in (a USB attach) clears it.
     */
    private var awaitingReattach = false

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val attached = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            // Storage, hubs and keyboards are not this path's business, and while the own output
            // holds a DAC another one plugged in does not interrupt it.
            if (attached == null || !attached.isUsbAudioDevice() || directRoute?.streaming == true) return
            synchronized(lock) { awaitingReattach = false }
            if (currentMode() == UsbOutputMode.STRICT_BIT_PERFECT) {
                scheduleReconfigure(resume = player?.playWhenReady == true, origin = UsbOutputFailureOrigin.ROUTE_CHANGE)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (currentMode() != UsbOutputMode.STRICT_BIT_PERFECT || addedDevices.none(AudioDeviceInfo::isUsbAudioOutput)) return
            if (synchronized(lock) { awaitingReattach } || returnTarget != null) return
            if (synchronized(lock) { directPath }) {
                // Once playback owns the DAC, later route events do not interrupt it. Before
                // that point a newly visible official mixer still has priority (#298).
                val route = directRoute ?: return
                if (!route.streaming && officialMixerOffered(addedDevices.filter(AudioDeviceInfo::isUsbAudioOutput))) {
                    returnDirectForOfficial(route)
                }
            } else {
                scheduleReconfigure(resume = player?.playWhenReady == true, origin = UsbOutputFailureOrigin.ROUTE_CHANGE)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removedIds = removedDevices.mapTo(mutableSetOf(), AudioDeviceInfo::getId)
            val targetRemoved = synchronized(lock) { selectedDevice?.id in removedIds }
            if (targetRemoved) {
                failClosed(
                    failure = UsbOutputFailure.DEVICE_DISCONNECTED,
                    code = "strict_usb.device_disconnected",
                )
            }
        }
    }

    fun attachPlayer(player: ExoPlayer) {
        check(this.player == null) { "USB output coordinator already has a player" }
        this.player = player
        player.addListener(this)
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        directRoute?.let { route ->
            route.listener = object : DirectUsbRoute.Listener {
                override fun onOutputOpened(periodUid: Any, output: Any, follows: Any?) {
                    if (closed || !directPath || player.currentTimeline.getIndexOfPeriod(periodUid) == C.INDEX_UNSET) return
                    val predecessor = directSources.lastOrNull { it.token === follows }.takeIf { directOutputOpened }
                    // A new output comes only after Media3 released the previous one. A reused output
                    // keeps every source it has not retired: with short items the ring can hold several.
                    if (predecessor == null) directSources.clear()
                    directSources += DirectSource(output, periodUid, predecessor?.token, predecessor?.period)
                    directOutputOpened = true
                    publishDirectActiveIfPlaying()
                }

                override fun onOutputActive(periodUid: Any, output: Any, active: Boolean) {
                    val source = directSources.lastOrNull { it.token === output }
                    if (closed || source == null || source.period != periodUid) return
                    // One source plays at a time. Delivery retires the sources before it, as in the output,
                    // which reports the successor first; the predecessor's own retirement then finds nothing.
                    if (active) {
                        directSources.subList(0, directSources.indexOf(source)).clear()
                        source.delivered = true
                    }
                    source.delivering = active
                    publishDirectActiveIfPlaying()
                }

                override fun onFailed(failure: DirectUsbFailure, details: DirectUsbDetails?, periodUid: Any?, output: Any?) {
                    val generation = synchronized(lock) { configurationGeneration }
                    val sourceGeneration = directSourceGeneration
                    mainHandler.post {
                        if (!isGenerationCurrent(generation)) return@post
                        if (periodUid != null && sourceGeneration != directSourceGeneration &&
                            player.currentTimeline.getIndexOfPeriod(periodUid) != player.currentPeriodIndex) return@post
                        if (periodUid != null && player.currentTimeline.getIndexOfPeriod(periodUid) == C.INDEX_UNSET) return@post
                        if (output != null && directSources.none { it.token === output }) return@post
                        val (reason, code) = failure.toStrictFailure()
                        failClosed(reason, code, expectedGeneration = generation, directDetails = details)
                    }
                }
            }
            ContextCompat.registerReceiver(
                appContext,
                usbAttachReceiver,
                IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        updateSource(player.currentMediaItem)
        if (mode == UsbOutputMode.SYSTEM) {
            publishSystem()
        } else {
            scheduleReconfigure(resume = false, origin = UsbOutputFailureOrigin.SERVICE_START)
        }
    }

    fun requestMode(requestedMode: UsbOutputMode) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestMode(requestedMode) }
            return
        }
        if (closed) return
        if (returnTarget != null) {
            // The connection is already being returned. Use the latest selection when it is back.
            if (returnTarget != requestedMode) {
                returnTarget = requestedMode
                if (requestedMode == UsbOutputMode.STRICT_BIT_PERFECT) {
                    preferences.edit { putString(MODE_KEY, requestedMode.name) }
                }
                publishReturning(requestedMode)
            }
            synchronized(lock) { configurationOrigin = UsbOutputFailureOrigin.USER_MODE_CHANGE }
            return
        }
        if (requestedMode == UsbOutputMode.SYSTEM) {
            val previousMode = synchronized(lock) {
                val previous = mode
                configurationGeneration += 1
                previous
            }
            restoreSystemOutput(rebuild = previousMode != UsbOutputMode.SYSTEM)
        } else {
            synchronized(lock) {
                configurationGeneration += 1
                mode = requestedMode
            }
            preferences.edit { putString(MODE_KEY, requestedMode.name) }
            scheduleReconfigure(resume = player?.playWhenReady == true, origin = UsbOutputFailureOrigin.USER_MODE_CHANGE)
        }
    }

    fun currentStatus(): UsbOutputStatus = stateRepository.snapshot()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        directSourceGeneration++
        val timeline = player?.currentTimeline
        val currentPeriod = if (timeline == null || timeline.isEmpty) null else timeline.getUidOfPeriod(checkNotNull(player).currentPeriodIndex)
        val natural = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        // A natural change keeps the previous item's source while its tail still plays, and the next
        // ones whose frames have not arrived yet. Other changes keep only the current item's sources.
        directSources.removeAll { it.period != currentPeriod && (!natural || it.delivered && !it.delivering) }
        updateSource(mediaItem)
        if (returnTarget != null) return
        // The own output checks every item where its PCM begins (#282) and keeps the DAC between
        // items, so a transition rebuilds nothing; the status only takes the new item's source.
        if (synchronized(lock) { directPath } && directRoute?.streaming == true) {
            val playbackRequested = player?.playWhenReady == true || synchronized(lock) { resumeAfterConfiguration }
            if (mediaItem == null) {
                // #292: an empty queue plays nothing, whatever the device is still held for. The next
                // queue is ACTIVE again only once its own output opens and plays.
                synchronized(lock) { directOutputOpened = false }
                publishDirect(UsbOutputPhase.APPLYING, "direct_usb.configured_not_playing")
                return
            }
            // #291: reconfiguration holds the only entitlement check (#119), and a retained session skips
            // it. The song that was playing has finished; the next one may not start.
            if (!strictOutputAllowed()) {
                failClosed(
                    UsbOutputFailure.TRIAL_ENDED,
                    "strict_usb.trial_ended",
                    origin = if (playbackRequested) UsbOutputFailureOrigin.TRACK_TRANSITION else UsbOutputFailureOrigin.QUEUE_RESTORE,
                )
                return
            }
            publishDirectActiveIfPlaying()
            return
        }
        if (currentMode() == UsbOutputMode.STRICT_BIT_PERFECT) {
            val playbackRequested = player?.playWhenReady == true || synchronized(lock) { resumeAfterConfiguration }
            scheduleReconfigure(
                resume = playbackRequested,
                origin = if (playbackRequested) UsbOutputFailureOrigin.TRACK_TRANSITION
                    else UsbOutputFailureOrigin.QUEUE_RESTORE,
            )
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (returnTarget != null) {
            resumeAfterConfiguration = playWhenReady
            return
        }
        if (!playWhenReady) {
            synchronized(lock) {
                if (internalPauseInProgress) {
                    internalPauseInProgress = false
                } else {
                    resumeAfterConfiguration = false
                }
            }
            if (currentMode() != UsbOutputMode.STRICT_BIT_PERFECT) return
            val status = stateRepository.snapshot()
            if (status.phase == UsbOutputPhase.ACTIVE && synchronized(lock) { directPath }) {
                // The output stops its stream on pause, so nothing needs gating here.
                publishDirect(UsbOutputPhase.APPLYING, "direct_usb.configured_not_playing")
                return
            }
            if (status.phase == UsbOutputPhase.ACTIVE) {
                // Pause/focus-loss callbacks can race the playback thread. Close the gate before
                // revoking ACTIVE so buffered or newly resumed PCM cannot pass until reverified.
                val activeOutput = synchronized(lock) { currentAudioOutput }
                if (activeOutput == null || runCatching(activeOutput::mute).isFailure) {
                    failClosed(
                        UsbOutputFailure.PLATFORM_ERROR,
                        "strict_usb.output_mute_failed",
                    )
                    return
                }
                publish(
                    phase = UsbOutputPhase.APPLYING,
                    device = synchronized(lock) { selectedDevice },
                    source = sourceFormat,
                    sink = synchronized(lock) { selectedMixerFormat },
                    decisionCode = "strict_usb.configured_not_playing",
                )
            }
            return
        }
        synchronized(lock) {
            internalPauseInProgress = false
            if (!internalResumeInProgress && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                configurationOrigin = UsbOutputFailureOrigin.USER_PLAYBACK
            }
            internalResumeInProgress = false
        }
        if (currentMode() != UsbOutputMode.STRICT_BIT_PERFECT) return
        if (!strictOutputAllowed()) {
            failClosed(UsbOutputFailure.TRIAL_ENDED, "strict_usb.trial_ended", origin = UsbOutputFailureOrigin.USER_PLAYBACK)
            return
        }
        if (synchronized(lock) { directPath } && directRoute?.streaming == true &&
            stateRepository.snapshot().phase == UsbOutputPhase.APPLYING
        ) {
            publishDirectActiveIfPlaying()
            return
        }
        when (stateRepository.snapshot().phase) {
            UsbOutputPhase.SYSTEM,
            UsbOutputPhase.AVAILABLE,
            UsbOutputPhase.FAILED -> scheduleReconfigure(resume = true, origin = synchronized(lock) { configurationOrigin })
            UsbOutputPhase.APPLYING -> synchronized(lock) { currentAudioTrack }?.let { track ->
                evaluateRoute(track)
                scheduleRouteVerificationTimeout(track)
            }
            UsbOutputPhase.ACTIVE -> Unit
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
            reason == Player.DISCONTINUITY_REASON_REMOVE) {
            directRoute?.invalidateOutput()
            directSources.clear()
            publishDirectActiveIfPlaying()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (directOutputOpened && playbackState == player?.playbackState &&
            (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)) {
            directRoute?.invalidateOutput()
            directSources.clear()
            publishDirectActiveIfPlaying()
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        if (currentMode() == UsbOutputMode.STRICT_BIT_PERFECT && playbackParameters != PlaybackParameters.DEFAULT) {
            failClosed(UsbOutputFailure.PROCESSING_NOT_NEUTRAL, "strict_usb.playback_parameters_changed")
        }
    }

    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
        if (currentMode() == UsbOutputMode.STRICT_BIT_PERFECT && skipSilenceEnabled) {
            failClosed(UsbOutputFailure.PROCESSING_NOT_NEUTRAL, "strict_usb.skip_silence_enabled")
        }
    }

    override fun onVolumeChanged(volume: Float) {
        if (currentMode() == UsbOutputMode.STRICT_BIT_PERFECT && volume != 1f) {
            failClosed(UsbOutputFailure.PROCESSING_NOT_NEUTRAL, "strict_usb.software_volume_changed")
        }
    }

    private fun scheduleReconfigure(resume: Boolean, origin: UsbOutputFailureOrigin) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { scheduleReconfigure(resume, origin) }
            return
        }
        if (returnTarget != null) return
        val activePlayer = player
        val wasPlaying = activePlayer?.playWhenReady == true
        val scheduled = synchronized(lock) {
            if (closed || mode != UsbOutputMode.STRICT_BIT_PERFECT) return@synchronized null
            configurationGeneration += 1
            // Coalesced queue callbacks must not erase a Play command already received in this attempt.
            if (!(reconfigurePosted && configurationOrigin == UsbOutputFailureOrigin.USER_PLAYBACK &&
                    origin == UsbOutputFailureOrigin.QUEUE_RESTORE)) {
                configurationOrigin = origin
            }
            val previousDevice = selectedDevice
            val previousSink = selectedMixerFormat
            selectedDevice = null
            selectedProfiles = null
            selectedMixerFormat = null
            selectedAudioAttributes = null
            selectedPlanGeneration = NO_GENERATION
            resumeAfterConfiguration = resumeAfterConfiguration || resume || wasPlaying
            val shouldPost = !reconfigurePosted
            if (shouldPost) reconfigurePosted = true
            ReconfigurationSchedule(
                device = previousDevice,
                sink = previousSink,
                generation = configurationGeneration,
                shouldPost = shouldPost,
                pausePlayer = wasPlaying,
            )
        } ?: return
        detachRoutingListener()
        if (!synchronized(mixerMutationLock) { mixerPreferences.clearAll() }) {
            failClosed(
                failure = UsbOutputFailure.MIXER_CLEAR_FAILED,
                code = "strict_usb.mixer_clear_failed",
                device = scheduled.device,
                sink = scheduled.sink,
                expectedGeneration = scheduled.generation,
                attemptMixerCleanup = false,
            )
            return
        }
        publish(
            phase = UsbOutputPhase.APPLYING,
            source = sourceFormat,
            decisionCode = "strict_usb.reconfiguration_requested",
        )
        if (scheduled.pausePlayer) {
            synchronized(lock) { internalPauseInProgress = true }
            activePlayer?.pause()
        }
        if (scheduled.shouldPost) {
            mainHandler.post {
                synchronized(lock) { reconfigurePosted = false }
                if (stateRepository.snapshot().phase != UsbOutputPhase.FAILED) {
                    reconfigureStrictOutput()
                }
            }
        }
    }

    private fun reconfigureStrictOutput() {
        if (closed || returnTarget != null || currentMode() != UsbOutputMode.STRICT_BIT_PERFECT) return
        val generation = synchronized(lock) { configurationGeneration }
        val activePlayer = player ?: return
        updateSource(activePlayer.currentMediaItem)
        // Checked whenever strict output is prepared, never mid-track: a song that is already
        // playing finishes, and the next attempt stops like any other strict failure (#119).
        if (!strictOutputAllowed()) {
            failClosed(UsbOutputFailure.TRIAL_ENDED, "strict_usb.trial_ended", expectedGeneration = generation)
            return
        }
        enforceNeutralProcessing(activePlayer)
        val route = directRoute
        if (route != null && usesDirectPath(route)) {
            reconfigureDirectOutput(route, generation, activePlayer)
            return
        }
        if (route != null && synchronized(lock) { directPath }) {
            returnDirectForOfficial(route)
            return
        }
        // Also applies after a retry or service recreation: Android may list a mixer while an
        // earlier session still owns the interfaces or has an unresolved recovery record.
        if (route?.recoveryPending == true) {
            failClosed(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.usb_transfer_failed", expectedGeneration = generation)
            return
        }
        val source = sourceFormat
        val devices = usbAudioOutputs()
        val deviceModels = devices.map { UsbOutputDevice(it.id, it.displayName()) }
        val preflight = resolver.resolve(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            apiLevel = Build.VERSION.SDK_INT,
            usbHostSupported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            modifyAudioSettingsGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
            ) == PackageManager.PERMISSION_GRANTED,
            devices = deviceModels,
            source = source,
            mixerProfiles = null,
        )
        if (preflight is UsbOutputDecision.Rejected) {
            failClosed(preflight.failure, preflight.code, expectedGeneration = generation)
            return
        }
        val preflightCandidate = preflight as UsbOutputDecision.Candidate
        var anyMixerQuerySucceeded = false
        var anyBitPerfectMixer = false
        var selected: Triple<AudioDeviceInfo, List<MixerProfile>, UsbOutputDecision.Candidate>? = null
        devices.forEach { candidateDevice ->
            if (selected != null) return@forEach
            val profiles = mixerAdapter.profiles(candidateDevice).getOrNull() ?: return@forEach
            anyMixerQuerySucceeded = true
            anyBitPerfectMixer = anyBitPerfectMixer || profiles.any(MixerProfile::bitPerfect)
            val capability = resolver.resolve(
                mode = UsbOutputMode.STRICT_BIT_PERFECT,
                apiLevel = Build.VERSION.SDK_INT,
                usbHostSupported = true,
                modifyAudioSettingsGranted = true,
                devices = listOf(UsbOutputDevice(candidateDevice.id, candidateDevice.displayName())),
                source = preflightCandidate.source,
                mixerProfiles = profiles,
            )
            if (capability is UsbOutputDecision.Candidate) {
                selected = Triple(candidateDevice, profiles, capability)
            }
        }
        val selectedCandidate = selected
        if (!isGenerationCurrent(generation)) return
        if (selectedCandidate == null) {
            failClosed(
                failure = if (anyMixerQuerySucceeded) {
                    UsbOutputFailure.NO_MATCHING_MIXER_ATTRIBUTE
                } else {
                    UsbOutputFailure.MIXER_QUERY_FAILED
                },
                code = when {
                    !anyMixerQuerySucceeded -> "strict_usb.mixer_query_failed"
                    !anyBitPerfectMixer -> NO_BIT_PERFECT_MIXER_CODE
                    else -> "strict_usb.no_matching_mixer_attribute"
                },
                expectedGeneration = generation,
            )
            return
        }
        val (device, profiles, capabilityCandidate) = selectedCandidate
        val planInstalled = synchronized(lock) {
            if (!isGenerationCurrentLocked(generation)) {
                false
            } else {
                selectedDevice = device
                selectedProfiles = profiles
                selectedMixerFormat = null
                selectedAudioAttributes = null
                selectedPlanGeneration = generation
                true
            }
        }
        if (!planInstalled) return
        publish(
            phase = UsbOutputPhase.AVAILABLE,
            device = device,
            source = capabilityCandidate.source,
            decisionCode = capabilityCandidate.code,
        )
        // Capability inspection on startup/queue restoration must not prepare a live AudioTrack.
        if (!activePlayer.playWhenReady && !synchronized(lock) { resumeAfterConfiguration }) return
        publish(
            phase = UsbOutputPhase.APPLYING,
            device = device,
            source = capabilityCandidate.source,
            decisionCode = "strict_usb.rebuilding_audio_track",
        )
        val pauseNeeded = activePlayer.playWhenReady
        if (pauseNeeded) {
            // Play can arrive while this reconfiguration is pending, for example when a media
            // button restores the queue and plays it in one step (#52). The internal pause must
            // keep that request, as scheduleReconfigure() does for one made before it.
            synchronized(lock) {
                internalPauseInProgress = true
                resumeAfterConfiguration = true
            }
        }
        activePlayer.pause()
        if (!isGenerationCurrent(generation)) return
        activePlayer.setPreferredAudioDevice(device)
        if (activePlayer.mediaItemCount > 0) {
            activePlayer.stop()
            activePlayer.prepare()
        }
    }

    override fun getAudioOutput(outputConfig: AudioOutputProvider.OutputConfig): AudioOutput {
        val request = outputConfig.toPlatformFormat()
        val audioAttributes = outputConfig.audioAttributes.getPlatformAudioAttributes()
        val outputMode = currentMode()
        if (outputMode == UsbOutputMode.SYSTEM) {
            return super.getAudioOutput(outputConfig)
        }
        val strictPlan = synchronized(lock) {
            val device = selectedDevice
            val profiles = selectedProfiles
            if (
                mode == UsbOutputMode.STRICT_BIT_PERFECT &&
                device != null && profiles != null &&
                selectedPlanGeneration == configurationGeneration
            ) {
                StrictOutputPlan(device, profiles, sourceFormat, configurationGeneration)
            } else {
                null
            }
        } ?: return pendingStrictOutput(outputConfig)
        val device = strictPlan.device
        val source = strictPlan.source
        if (!isPlanCurrent(strictPlan)) {
            return pendingStrictOutput(outputConfig)
        }
        val resolved = resolver.resolve(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            apiLevel = Build.VERSION.SDK_INT,
            usbHostSupported = true,
            modifyAudioSettingsGranted = true,
            devices = listOf(UsbOutputDevice(device.id, device.displayName())),
            source = source,
            mixerProfiles = strictPlan.profiles,
            audioTrackFormat = request,
        )
        if (resolved is UsbOutputDecision.Rejected) {
            return mutedFailureOutput(
                outputConfig,
                resolved.failure,
                resolved.code,
                expectedGeneration = strictPlan.generation,
            )
        }
        val candidate = resolved as UsbOutputDecision.Candidate
        val mixerFormat = requireNotNull(candidate.mixerProfile).format
        if (!isPlanCurrent(strictPlan)) return pendingStrictOutput(outputConfig)
        val mixerPreference = MixerPreference(device, audioAttributes)
        val preferredSetResult = synchronized(mixerMutationLock) {
            if (!isPlanCurrent(strictPlan)) return@synchronized null
            mixerPreferences.request(mixerPreference) {
                mixerAdapter.setPreferred(device, mixerFormat, audioAttributes)
            }
        } ?: return pendingStrictOutput(outputConfig)
        val preferredSet = preferredSetResult.getOrElse {
            return mutedFailureOutput(
                outputConfig,
                UsbOutputFailure.MIXER_REQUEST_REJECTED,
                "strict_usb.mixer_request_failed",
                expectedGeneration = strictPlan.generation,
            )
        }
        if (!preferredSet) {
            return mutedFailureOutput(
                outputConfig,
                UsbOutputFailure.MIXER_REQUEST_REJECTED,
                "strict_usb.mixer_request_rejected",
                expectedGeneration = strictPlan.generation,
            )
        }
        if (!isPlanCurrent(strictPlan)) {
            clearMixerPreferenceOrFail(mixerPreference, device, request)
            return pendingStrictOutput(outputConfig)
        }
        val preferredMatches = mixerAdapter.isPreferred(device, mixerFormat, audioAttributes).getOrDefault(false)
        if (!isPlanCurrent(strictPlan)) {
            clearMixerPreferenceOrFail(mixerPreference, device, request)
            return pendingStrictOutput(outputConfig)
        }
        if (!preferredMatches) {
            return mutedFailureOutput(
                outputConfig,
                UsbOutputFailure.MIXER_READBACK_MISMATCH,
                "strict_usb.mixer_readback_mismatch",
                expectedGeneration = strictPlan.generation,
            )
        }
        val (_, gatedOutput, track) = try {
            val createdOutput = super.getAudioOutput(outputConfig)
            val createdTrack = (createdOutput as? AudioTrackAudioOutput)?.audioTrack
            Triple(
                createdOutput,
                StrictGatedAudioOutput(
                    delegateOutput = createdOutput,
                    onGatedPlay = {
                        createdTrack?.let(::requestRouteReverification)
                    },
                    onNonNeutralVolume = {
                        failClosed(
                            UsbOutputFailure.PROCESSING_NOT_NEUTRAL,
                            "strict_usb.output_volume_not_neutral",
                            device,
                            request,
                            strictPlan.generation,
                            audioAttributes,
                        )
                    },
                ),
                createdTrack,
            )
        } catch (failure: Exception) {
            failClosed(
                UsbOutputFailure.PLATFORM_ERROR,
                "strict_usb.audio_output_creation_failed",
                device,
                request,
                strictPlan.generation,
                audioAttributes,
            )
            throw failure
        }
        if (!isPlanCurrent(strictPlan)) {
            clearMixerPreferenceOrFail(mixerPreference, device, request)
            return gatedOutput
        }
        if (track == null) {
            failClosed(
                UsbOutputFailure.PLATFORM_ERROR,
                "strict_usb.audio_output_not_inspectable",
                device,
                request,
                strictPlan.generation,
                audioAttributes,
            )
            return gatedOutput
        }
        if (!runCatching { track.setPreferredDevice(device) }.getOrDefault(false)) {
            failClosed(
                UsbOutputFailure.PREFERRED_DEVICE_REJECTED,
                "strict_usb.preferred_device_rejected",
                device,
                request,
                strictPlan.generation,
                audioAttributes,
            )
            return gatedOutput
        }
        if (!attachRoutingListener(track, gatedOutput, mixerFormat, audioAttributes, strictPlan)) {
            clearMixerPreferenceOrFail(mixerPreference, device, request)
            return gatedOutput
        }
        mainHandler.post {
            if (!isPlanCurrent(strictPlan)) return@post
            publish(
                phase = UsbOutputPhase.APPLYING,
                device = device,
                source = source,
                sink = request,
                decisionCode = "strict_usb.mixer_applied_waiting_for_route",
            )
            evaluateRoute(track)
            val shouldResume = synchronized(lock) {
                if (!isPlanCurrentLocked(strictPlan)) return@synchronized false
                val resume = resumeAfterConfiguration
                resumeAfterConfiguration = false
                resume
            }
            if (shouldResume && currentMode() == UsbOutputMode.STRICT_BIT_PERFECT &&
                stateRepository.snapshot().phase != UsbOutputPhase.FAILED
            ) {
                synchronized(lock) { internalResumeInProgress = true }
                player?.play()
            }
            scheduleRouteVerificationTimeout(track)
        }
        return gatedOutput
    }

    /** A renderer-level pause closes the PCM gate even when playWhenReady stays true. */
    private fun requestRouteReverification(track: AudioTrack) {
        mainHandler.post {
            val routePlan = synchronized(lock) {
                if (
                    mode == UsbOutputMode.STRICT_BIT_PERFECT && currentAudioTrack === track &&
                    currentAudioTrackGeneration == configurationGeneration &&
                    selectedPlanGeneration == configurationGeneration
                ) {
                    StrictRoutePlan(
                        device = selectedDevice,
                        mixerFormat = selectedMixerFormat,
                        audioAttributes = selectedAudioAttributes,
                        generation = configurationGeneration,
                    )
                } else {
                    null
                }
            } ?: return@post
            if (stateRepository.snapshot().phase == UsbOutputPhase.ACTIVE) {
                publish(
                    phase = UsbOutputPhase.APPLYING,
                    device = routePlan.device,
                    source = sourceFormat,
                    sink = routePlan.mixerFormat,
                    decisionCode = "strict_usb.reverifying_after_output_resume",
                )
            }
            evaluateRoute(track)
            scheduleRouteVerificationTimeout(track)
        }
    }

    private fun pendingStrictOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val expectedGeneration = synchronized(lock) {
            configurationGeneration.takeIf { mode == UsbOutputMode.STRICT_BIT_PERFECT && !closed }
        }
        return try {
            StrictGatedAudioOutput(super.getAudioOutput(config)) {
                if (expectedGeneration != null) {
                    failClosed(
                        UsbOutputFailure.PROCESSING_NOT_NEUTRAL,
                        "strict_usb.output_volume_not_neutral",
                        sink = config.toPlatformFormat(),
                        expectedGeneration = expectedGeneration,
                        audioAttributes = config.audioAttributes.getPlatformAudioAttributes(),
                    )
                }
            }
        } catch (creationFailure: Exception) {
            if (expectedGeneration != null) {
                failClosed(
                    UsbOutputFailure.PLATFORM_ERROR,
                    "strict_usb.pending_audio_output_creation_failed",
                    sink = config.toPlatformFormat(),
                    expectedGeneration = expectedGeneration,
                    audioAttributes = config.audioAttributes.getPlatformAudioAttributes(),
                )
            }
            throw creationFailure
        }
    }

    private fun mutedFailureOutput(
        config: AudioOutputProvider.OutputConfig,
        failure: UsbOutputFailure,
        code: String,
        expectedGeneration: Long,
    ): AudioOutput {
        val gatedOutput = try {
            StrictGatedAudioOutput(super.getAudioOutput(config))
        } catch (creationFailure: Exception) {
            failClosed(
                failure,
                code,
                sink = config.toPlatformFormat(),
                expectedGeneration = expectedGeneration,
                audioAttributes = config.audioAttributes.getPlatformAudioAttributes(),
            )
            throw creationFailure
        }
        failClosed(
            failure,
            code,
            sink = config.toPlatformFormat(),
            expectedGeneration = expectedGeneration,
            audioAttributes = config.audioAttributes.getPlatformAudioAttributes(),
        )
        return gatedOutput
    }

    private fun attachRoutingListener(
        track: AudioTrack,
        output: StrictGatedAudioOutput,
        mixerFormat: PlatformPcmFormat,
        audioAttributes: AudioAttributes,
        plan: StrictOutputPlan,
    ): Boolean {
        val listener = AudioRouting.OnRoutingChangedListener { routing ->
            (routing as? AudioTrack)?.let(::evaluateRoute)
        }
        var accepted = false
        val previous = synchronized(lock) {
            if (!isPlanCurrentLocked(plan)) {
                null
            } else {
                accepted = true
                val old = RoutingAttachment(currentAudioTrack, currentAudioOutput, currentRoutingListener)
                selectedMixerFormat = mixerFormat
                selectedAudioAttributes = audioAttributes
                currentAudioTrack = track
                currentAudioTrackGeneration = plan.generation
                currentAudioOutput = output
                currentRoutingListener = listener
                old
            }
        }
        if (!accepted) return false
        releaseRoutingAttachment(previous)
        if (runCatching { track.addOnRoutingChangedListener(listener, mainHandler) }.isFailure) {
            synchronized(lock) {
                if (currentAudioTrack === track && currentRoutingListener === listener) {
                    currentAudioTrack = null
                    currentAudioTrackGeneration = NO_GENERATION
                    currentAudioOutput = null
                    currentRoutingListener = null
                }
            }
            runCatching(output::mute)
            failClosed(
                UsbOutputFailure.PLATFORM_ERROR,
                "strict_usb.routing_listener_registration_failed",
                plan.device,
                mixerFormat,
                plan.generation,
                audioAttributes,
            )
            return false
        }
        val stillCurrent = synchronized(lock) {
            isPlanCurrentLocked(plan) && currentAudioTrack === track &&
                currentAudioTrackGeneration == plan.generation
        }
        if (!stillCurrent) {
            runCatching { track.removeOnRoutingChangedListener(listener) }
            runCatching(output::mute)
        }
        return stillCurrent
    }

    private fun evaluateRoute(track: AudioTrack) {
        val routePlan = synchronized(lock) {
            if (
                mode == UsbOutputMode.STRICT_BIT_PERFECT && currentAudioTrack === track &&
                currentAudioTrackGeneration == configurationGeneration &&
                selectedPlanGeneration == configurationGeneration
            ) {
                StrictRoutePlan(
                    device = selectedDevice,
                    mixerFormat = selectedMixerFormat,
                    audioAttributes = selectedAudioAttributes,
                    generation = configurationGeneration,
                )
            } else {
                null
            }
        } ?: return
        val targetDevice = routePlan.device
        val targetFormat = routePlan.mixerFormat
        val routed = runCatching { track.routedDevice }.getOrNull()
        if (stateRepository.snapshot().phase == UsbOutputPhase.ACTIVE &&
            (routed == null || routed.id != targetDevice?.id)) {
            synchronized(lock) { configurationOrigin = UsbOutputFailureOrigin.ROUTE_CHANGE }
        }
        if (routed == null) {
            if (strictRouteUnavailableFailure(stateRepository.snapshot().phase) != null) {
                failClosed(
                    UsbOutputFailure.ROUTE_UNAVAILABLE,
                    "strict_usb.route_unavailable",
                    targetDevice,
                    targetFormat,
                    routePlan.generation,
                    routePlan.audioAttributes,
                )
            }
            return
        }
        if (targetDevice == null || routed.id != targetDevice.id) {
            failClosed(
                UsbOutputFailure.ROUTE_MISMATCH,
                "strict_usb.route_mismatch",
                targetDevice,
                targetFormat,
                routePlan.generation,
            )
            return
        }
        val activePlayer = player
        if (activePlayer == null || !activePlayer.hasNeutralProcessing()) {
            failClosed(
                UsbOutputFailure.PROCESSING_NOT_NEUTRAL,
                "strict_usb.processing_not_neutral",
                targetDevice,
                targetFormat,
                routePlan.generation,
            )
            return
        }
        if (!activePlayer.playWhenReady) {
            publish(
                phase = UsbOutputPhase.APPLYING,
                device = targetDevice,
                source = sourceFormat,
                sink = targetFormat,
                decisionCode = "strict_usb.configured_not_playing",
            )
            return
        }
        val targetAudioAttributes = routePlan.audioAttributes
        if (
            targetFormat == null || targetAudioAttributes == null ||
            !mixerAdapter.isPreferred(targetDevice, targetFormat, targetAudioAttributes).getOrDefault(false)
        ) {
            failClosed(
                UsbOutputFailure.MIXER_READBACK_MISMATCH,
                "strict_usb.active_mixer_readback_mismatch",
                targetDevice,
                targetFormat,
                routePlan.generation,
                targetAudioAttributes,
            )
            return
        }
        val activeOutput = synchronized(lock) {
            currentAudioOutput.takeIf { currentAudioTrack === track }
        }
        if (activeOutput == null) {
            failClosed(
                UsbOutputFailure.PLATFORM_ERROR,
                "strict_usb.output_unmute_failed",
                targetDevice,
                targetFormat,
                routePlan.generation,
            )
            return
        }
        if (!activeOutput.isReadyForActivation()) {
            publish(
                phase = UsbOutputPhase.APPLYING,
                device = targetDevice,
                source = sourceFormat,
                sink = targetFormat,
                decisionCode = "strict_usb.waiting_for_output_play",
            )
            return
        }
        if (!activeOutput.activate()) {
            failClosed(
                UsbOutputFailure.PLATFORM_ERROR,
                "strict_usb.output_unmute_failed",
                targetDevice,
                targetFormat,
                routePlan.generation,
            )
            return
        }
        publish(
            phase = UsbOutputPhase.ACTIVE,
            device = targetDevice,
            source = sourceFormat,
            sink = targetFormat,
            decisionCode = "strict_usb.active",
        )
    }

    private fun scheduleRouteVerificationTimeout(track: AudioTrack) {
        val expectedGeneration = synchronized(lock) {
            currentAudioTrackGeneration.takeIf { currentAudioTrack === track }
        } ?: return
        mainHandler.postDelayed(
            {
                val stillWaiting = synchronized(lock) {
                    currentAudioTrack === track &&
                        currentAudioTrackGeneration == expectedGeneration &&
                        isGenerationCurrentLocked(expectedGeneration)
                } && player?.playWhenReady == true &&
                    stateRepository.snapshot().phase == UsbOutputPhase.APPLYING
                if (!stillWaiting) return@postDelayed
                if (runCatching { track.routedDevice }.getOrNull() == null) {
                    failClosed(
                        UsbOutputFailure.ROUTE_UNAVAILABLE,
                        "strict_usb.route_unavailable",
                        expectedGeneration = expectedGeneration,
                    )
                } else {
                    evaluateRoute(track)
                }
            },
            ROUTE_VERIFICATION_TIMEOUT_MS,
        )
    }

    private fun failClosed(
        failure: UsbOutputFailure,
        code: String,
        device: AudioDeviceInfo? = synchronized(lock) { selectedDevice },
        sink: PlatformPcmFormat? = synchronized(lock) { selectedMixerFormat },
        expectedGeneration: Long? = null,
        audioAttributes: AudioAttributes? = synchronized(lock) { selectedAudioAttributes },
        attemptMixerCleanup: Boolean = true,
        origin: UsbOutputFailureOrigin = when (failure) {
            UsbOutputFailure.DEVICE_DISCONNECTED -> UsbOutputFailureOrigin.ROUTE_CHANGE
            UsbOutputFailure.PROCESSING_NOT_NEUTRAL -> UsbOutputFailureOrigin.PROCESSING_CHANGE
            else -> synchronized(lock) { configurationOrigin }
        },
        directDevice: String? = null,
        directDetails: DirectUsbDetails? = null,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                failClosed(
                    failure,
                    code,
                    device,
                    sink,
                    expectedGeneration,
                    audioAttributes,
                    attemptMixerCleanup,
                    origin,
                    directDevice,
                    directDetails,
                )
            }
            return
        }
        val failedState = synchronized(lock) {
            if (
                mode != UsbOutputMode.STRICT_BIT_PERFECT || closed ||
                (expectedGeneration != null && configurationGeneration != expectedGeneration)
            ) {
                return@synchronized null
            }
            val state = FailedOutputState(
                device = device ?: selectedDevice,
                sink = sink ?: selectedMixerFormat,
                audioAttributes = audioAttributes ?: selectedAudioAttributes,
                directDeviceName = directDevice ?: directDeviceName.takeIf { directPath },
            )
            configurationGeneration += 1
            selectedPlanGeneration = NO_GENERATION
            selectedDevice = null
            selectedProfiles = null
            selectedMixerFormat = null
            selectedAudioAttributes = null
            resumeAfterConfiguration = false
            reconfigurePosted = false
            if (directPath) awaitingReattach = true
            directPath = false
            directOutputOpened = false
            state
        } ?: return
        returnTarget = null
        directRoute?.release()
        detachRoutingListener()
        if (attemptMixerCleanup) synchronized(mixerMutationLock) {
            mixerPreferences.clearAll()
        }
        publish(
            phase = UsbOutputPhase.FAILED,
            failure = failure,
            device = failedState.device,
            source = sourceFormat,
            sink = failedState.sink,
            decisionCode = code,
            failureOrigin = origin,
            deviceName = failedState.device?.displayName() ?: failedState.directDeviceName,
            directDetails = directDetails,
        )
        if (currentMode() == UsbOutputMode.STRICT_BIT_PERFECT) {
            player?.run {
                pause()
                stop()
                setPreferredAudioDevice(null)
            }
        }
    }

    private fun restoreSystemOutput(rebuild: Boolean) {
        val activePlayer = player
        val resume = activePlayer?.playWhenReady == true || synchronized(lock) { resumeAfterConfiguration }
        val wasDirect = synchronized(lock) { directPath }
        val oldState = synchronized(lock) { FailedOutputState(selectedDevice, selectedMixerFormat) }
        detachRoutingListener()
        val cleanupSucceeded = synchronized(mixerMutationLock) {
            if (!mixerPreferences.clearAll()) {
                false
            } else {
                synchronized(lock) {
                    directPath = false
                    directOutputOpened = false
                    selectedDevice = null
                    selectedProfiles = null
                    selectedMixerFormat = null
                    selectedAudioAttributes = null
                    selectedPlanGeneration = NO_GENERATION
                    resumeAfterConfiguration = false
                }
                true
            }
        }
        if (!cleanupSucceeded) {
            failClosed(
                failure = UsbOutputFailure.MIXER_CLEAR_FAILED,
                code = "strict_usb.mixer_clear_failed",
                device = oldState.device,
                sink = oldState.sink,
                attemptMixerCleanup = false,
                // Only requestMode() gets here, and it does not update configurationOrigin (#245).
                origin = UsbOutputFailureOrigin.USER_MODE_CHANGE,
            )
            return
        }
        val route = directRoute
        // A recovery marker gates a transition from strict. Selecting an already active system
        // route starts no new playback and must leave both the player and the marker intact.
        if (route != null && (wasDirect || route.streaming || (rebuild && route.recoveryPending))) {
            returnDirect(route, UsbOutputMode.SYSTEM, resume, UsbOutputFailureOrigin.USER_MODE_CHANGE)
        } else {
            route?.release()
            completeSystemOutput(rebuild, resume)
        }
    }

    private fun completeSystemOutput(rebuild: Boolean, resume: Boolean) {
        synchronized(lock) {
            mode = UsbOutputMode.SYSTEM
            resumeAfterConfiguration = false
        }
        preferences.edit { putString(MODE_KEY, UsbOutputMode.SYSTEM.name) }
        val activePlayer = player
        activePlayer?.setPreferredAudioDevice(null)
        publishSystem()
        if (rebuild && activePlayer != null && activePlayer.mediaItemCount > 0) {
            activePlayer.pause()
            activePlayer.stop()
            activePlayer.prepare()
            if (resume) activePlayer.play()
        }
    }

    /**
     * The official path wherever Android offers a bit-perfect mixer for the device, the own path
     * otherwise. Decided per USB connection: once the device is held, its route is gone and the
     * attempt stays on the own path.
     */
    private fun usesDirectPath(route: DirectUsbRoute): Boolean {
        if (route.streaming) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val routes = usbAudioOutputs()
        // Without an Android route the official path has nothing to ask for a mixer, but USB Host may
        // still list the DAC (#293); the own path then inspects it. With neither, the official
        // preflight reports the missing device, and an arriving route starts the next attempt.
        if (routes.isEmpty()) return route.hasUsbAudioDevice()
        return !officialMixerOffered(routes)
    }

    private fun officialMixerOffered(devices: List<AudioDeviceInfo>): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            devices.any { mixerAdapter.profiles(it).getOrNull()?.any(MixerProfile::bitPerfect) == true }

    private fun returnDirectForOfficial(route: DirectUsbRoute) {
        val resume = synchronized(lock) { resumeAfterConfiguration } || player?.playWhenReady == true
        returnDirect(route, UsbOutputMode.STRICT_BIT_PERFECT, resume, UsbOutputFailureOrigin.ROUTE_CHANGE)
    }

    private fun returnDirect(route: DirectUsbRoute, target: UsbOutputMode, resume: Boolean, origin: UsbOutputFailureOrigin) {
        if (returnTarget != null) return
        returnTarget = target
        val generation = synchronized(lock) {
            mode = UsbOutputMode.STRICT_BIT_PERFECT
            directPath = false
            directOutputOpened = false // The next output starts a new list of sources.
            resumeAfterConfiguration = resume
            configurationOrigin = origin
            ++configurationGeneration
        }
        player?.stop()
        publishReturning(target)
        route.release { released ->
            if (!isGenerationCurrent(generation)) return@release
            val selected = returnTarget ?: return@release
            returnTarget = null
            if (!released || route.recoveryPending) {
                synchronized(lock) { awaitingReattach = true }
                failClosed(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.usb_transfer_failed", origin = configurationOrigin)
            } else if (selected == UsbOutputMode.SYSTEM) {
                completeSystemOutput(rebuild = true, resume = resumeAfterConfiguration)
            } else {
                scheduleReconfigure(resume = resumeAfterConfiguration, origin = configurationOrigin)
            }
        }
    }

    private fun publishReturning(target: UsbOutputMode) {
        val code = if (target == UsbOutputMode.SYSTEM) "direct_usb.returning_to_system" else "strict_usb.reconfiguration_requested"
        publishDirect(UsbOutputPhase.APPLYING, code)
    }

    private fun reconfigureDirectOutput(route: DirectUsbRoute, generation: Long, activePlayer: ExoPlayer) {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            failClosed(UsbOutputFailure.USB_HOST_UNAVAILABLE, "direct_usb.usb_host_unavailable", expectedGeneration = generation)
            return
        }
        // The file's own layer of D10 needs no DAC, so a lossy or 32-bit file is named before takeover.
        // A missing fact still goes the old way: the format gate checks it once Media3 configures.
        val facts = sourceFacts
        DirectUsbFormatGate.sourceRefusal(facts)?.takeUnless { it.reason == DirectUsbFailure.Reason.SOURCE_FORMAT_UNKNOWN }?.let { refusal ->
            val (reason, code) = refusal.toStrictFailure()
            failClosed(reason, code, expectedGeneration = generation, directDetails = facts?.toDirectDetails())
            return
        }
        val source = sourceFormat
        if (source == null) {
            failClosed(UsbOutputFailure.SOURCE_FORMAT_UNKNOWN, "direct_usb.source_format_unknown", expectedGeneration = generation)
            return
        }
        when (val inspection = route.inspect(source)) {
            DirectUsbInspection.NoDevice ->
                failClosed(UsbOutputFailure.NO_USB_AUDIO_DEVICE, "direct_usb.no_usb_audio_device", expectedGeneration = generation)
            DirectUsbInspection.SeveralDevices ->
                failClosed(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.device_ambiguous", expectedGeneration = generation)
            is DirectUsbInspection.PermissionNeeded -> requestDirectPermission(route, generation, source, inspection.deviceName)
            is DirectUsbInspection.Refused -> failClosed(
                if (inspection.formatRefused) UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED else UsbOutputFailure.PLATFORM_ERROR,
                "direct_usb.${inspection.code}",
                expectedGeneration = generation,
                directDevice = inspection.deviceName,
                directDetails = inspection.details,
            )
            is DirectUsbInspection.Ready -> startDirectOutput(route, generation, activePlayer, inspection, source)
        }
    }

    /** USB permission is asked for only on the user's own action; otherwise the state says it is missing (#34). */
    private fun requestDirectPermission(route: DirectUsbRoute, generation: Long, source: SourcePcmFormat, deviceName: String) {
        val origin = synchronized(lock) { configurationOrigin }
        if (origin != UsbOutputFailureOrigin.USER_MODE_CHANGE && origin != UsbOutputFailureOrigin.USER_PLAYBACK) {
            failClosed(
                UsbOutputFailure.PLATFORM_ERROR,
                "direct_usb.permission_required",
                expectedGeneration = generation,
                directDevice = deviceName,
            )
            return
        }
        publish(
            phase = UsbOutputPhase.APPLYING,
            source = source,
            decisionCode = "direct_usb.permission_requested",
            deviceName = deviceName,
        )
        route.requestPermission { granted ->
            mainHandler.post {
                if (!isGenerationCurrent(generation)) return@post
                if (granted) {
                    scheduleReconfigure(resume = false, origin = origin)
                } else {
                    failClosed(
                        UsbOutputFailure.PLATFORM_ERROR,
                        "direct_usb.permission_denied",
                        expectedGeneration = generation,
                        directDevice = deviceName,
                    )
                }
            }
        }
    }

    private fun startDirectOutput(
        route: DirectUsbRoute,
        generation: Long,
        activePlayer: ExoPlayer,
        inspection: DirectUsbInspection.Ready,
        source: SourcePcmFormat,
    ) {
        val installed = synchronized(lock) {
            if (!isGenerationCurrentLocked(generation)) return@synchronized false
            directPath = true
            directDeviceName = inspection.deviceName
            directPlan = inspection.plan
            true
        }
        if (!installed) return
        if (route.streaming) {
            // Still holding the device with a plan for this source, so the strict sink is in place.
            // Not AVAILABLE: playing from AVAILABLE would start another attempt, and so on.
            publishDirect(UsbOutputPhase.APPLYING, "direct_usb.configured_not_playing", source)
            resumeAfterDirectRebuild(activePlayer, generation)
            return
        }
        publishDirect(UsbOutputPhase.AVAILABLE, "direct_usb.available", source)
        // As on the official path, inspection on startup or queue restoration takes nothing over.
        if (!activePlayer.playWhenReady && !synchronized(lock) { resumeAfterConfiguration }) return
        publishDirect(UsbOutputPhase.APPLYING, "direct_usb.taking_over", source)
        synchronized(lock) { directOutputOpened = false }
        if (activePlayer.playWhenReady) {
            synchronized(lock) {
                internalPauseInProgress = true
                resumeAfterConfiguration = true
            }
        }
        activePlayer.pause()
        route.takeOver { failure ->
            if (!isGenerationCurrent(generation)) return@takeOver
            if (failure != null) {
                val (reason, code) = failure.toStrictFailure()
                failClosed(reason, code, expectedGeneration = generation)
                return@takeOver
            }
            // AudioManager may already list the mixer before its added callback is delivered.
            if (officialMixerOffered(usbAudioOutputs())) {
                returnDirectForOfficial(route)
                return@takeOver
            }
            activePlayer.setPreferredAudioDevice(null)
            // Media3 resets the sink on stop(), which is where the selecting sink turns to the
            // strict sink and the session's outputs.
            if (activePlayer.mediaItemCount > 0) {
                activePlayer.stop()
                activePlayer.prepare()
            }
            resumeAfterDirectRebuild(activePlayer, generation)
        }
    }

    private fun resumeAfterDirectRebuild(activePlayer: ExoPlayer, generation: Long) {
        val resume = synchronized(lock) {
            if (!isGenerationCurrentLocked(generation)) return@synchronized false
            val requested = resumeAfterConfiguration
            resumeAfterConfiguration = false
            if (requested) internalResumeInProgress = true
            requested
        }
        if (resume) activePlayer.play()
    }

    /** Playback-thread delivery evidence belongs to the current period and output, not the retained session. */
    private fun publishDirectActiveIfPlaying() {
        val route = directRoute ?: return
        if (!synchronized(lock) { directPath } || !route.streaming || currentMode() != UsbOutputMode.STRICT_BIT_PERFECT) return
        if (stateRepository.snapshot().phase == UsbOutputPhase.FAILED) return
        val activePlayer = player ?: return
        val timeline = activePlayer.currentTimeline
        val period = if (timeline.isEmpty) null else timeline.getUidOfPeriod(activePlayer.currentPeriodIndex)
        val playing = directSources.lastOrNull { it.delivering }
        // At a natural change Media3 and the delivered position pass the boundary a few milliseconds
        // apart, in either order. Either way one item hands over to the next without a gap (A1, #314).
        val delivering = playing != null && (playing.period == period || playing.followsPeriod == period ||
            directSources.any { it.followsToken === playing.token && it.period == period })
        val active = directOutputOpened && delivering &&
            activePlayer.playWhenReady && activePlayer.playbackState != Player.STATE_IDLE && activePlayer.playbackState != Player.STATE_ENDED
        publishDirect(
            if (active) UsbOutputPhase.ACTIVE else UsbOutputPhase.APPLYING,
            if (active) "direct_usb.active" else "direct_usb.configured_not_playing",
        )
    }

    private fun publishDirect(phase: UsbOutputPhase, code: String, source: SourcePcmFormat? = sourceFormat) {
        val (name, plan) = synchronized(lock) { directDeviceName to directPlan }
        publish(
            phase = phase,
            source = source,
            decisionCode = code,
            deviceName = name,
            directDetails = plan?.let { DirectUsbDetails(wireSampleRateHz = it.sampleRateHz, wireBitDepth = it.bitResolution) },
        )
    }

    private fun enforceNeutralProcessing(player: ExoPlayer) {
        if (player.volume != 1f) player.volume = 1f
        if (player.skipSilenceEnabled) player.skipSilenceEnabled = false
        if (player.playbackParameters != PlaybackParameters.DEFAULT) {
            player.playbackParameters = PlaybackParameters.DEFAULT
        }
    }

    private fun ExoPlayer.hasNeutralProcessing(): Boolean =
        volume == 1f && !skipSilenceEnabled && playbackParameters == PlaybackParameters.DEFAULT

    private fun usbAudioOutputs(): List<AudioDeviceInfo> = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(AudioDeviceInfo::isUsbAudioOutput)
            .sortedWith(compareBy(AudioDeviceInfo::getType, AudioDeviceInfo::getId))
    }.getOrDefault(emptyList())

    private fun publishSystem() {
        publish(
            phase = UsbOutputPhase.SYSTEM,
            decisionCode = "system_audio_route",
            explicitMode = UsbOutputMode.SYSTEM,
        )
    }

    private fun publish(
        phase: UsbOutputPhase,
        failure: UsbOutputFailure? = null,
        device: AudioDeviceInfo? = null,
        source: SourcePcmFormat? = null,
        sink: PlatformPcmFormat? = null,
        decisionCode: String,
        explicitMode: UsbOutputMode? = null,
        failureOrigin: UsbOutputFailureOrigin? = null,
        deviceName: String? = device?.displayName(),
        directDetails: DirectUsbDetails? = null,
    ) {
        val cleanup = mixerPreferences.snapshot()
        val status = synchronized(lock) {
            statusGeneration += 1
            UsbOutputStatus(
                mode = explicitMode ?: mode,
                phase = phase,
                failure = failure,
                deviceName = deviceName,
                hardwareIdentity = deviceName?.let { uniqueUsbHardwareIdentity() },
                sourceFormat = source?.toSummary(),
                sinkFormat = sink?.toSummary(),
                decisionCode = decisionCode,
                observedAtEpochMs = System.currentTimeMillis(),
                observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                generation = statusGeneration,
                officialMixerApiSupport = officialMixerApiSupport,
                mixerCleanup = cleanup,
                failureOrigin = failureOrigin,
                directDetails = directDetails,
                directUsbAvailable = directRoute != null,
            )
        }
        stateRepository.publish(status)
    }

    private fun currentMode(): UsbOutputMode = synchronized(lock) { mode }

    private fun isGenerationCurrent(generation: Long): Boolean = synchronized(lock) {
        isGenerationCurrentLocked(generation)
    }

    private fun isGenerationCurrentLocked(generation: Long): Boolean =
        !closed && mode == UsbOutputMode.STRICT_BIT_PERFECT && configurationGeneration == generation

    private fun isPlanCurrent(plan: StrictOutputPlan): Boolean = synchronized(lock) {
        isPlanCurrentLocked(plan)
    }

    private fun isPlanCurrentLocked(plan: StrictOutputPlan): Boolean =
        isGenerationCurrentLocked(plan.generation) && selectedPlanGeneration == plan.generation &&
            selectedDevice?.id == plan.device.id

    private fun clearMixerPreferenceOrFail(
        preference: MixerPreference,
        device: AudioDeviceInfo,
        sink: PlatformPcmFormat,
    ) {
        if (synchronized(mixerMutationLock) { mixerPreferences.clear(preference) }) return
        failClosed(
            failure = UsbOutputFailure.MIXER_CLEAR_FAILED,
            code = "strict_usb.mixer_clear_failed",
            device = device,
            sink = sink,
            attemptMixerCleanup = false,
        )
    }

    fun close() {
        val closingState = synchronized(lock) {
            if (closed) return
            val state = ClosingOutputState(mode, selectedDevice, selectedMixerFormat, selectedAudioAttributes)
            closed = true
            configurationGeneration += 1
            selectedPlanGeneration = NO_GENERATION
            selectedDevice = null
            selectedProfiles = null
            selectedMixerFormat = null
            selectedAudioAttributes = null
            resumeAfterConfiguration = false
            state
        }
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        if (directRoute != null) runCatching { appContext.unregisterReceiver(usbAttachReceiver) }
        directRoute?.release()
        player?.removeListener(this)
        detachRoutingListener()
        synchronized(mixerMutationLock) { mixerPreferences.clearAll() }
        if (closingState.mode == UsbOutputMode.STRICT_BIT_PERFECT) {
            publish(
                phase = UsbOutputPhase.FAILED,
                failure = UsbOutputFailure.SERVICE_STOPPED,
                device = closingState.device,
                source = sourceFormat,
                sink = closingState.sink,
                decisionCode = "strict_usb.service_stopped",
                failureOrigin = UsbOutputFailureOrigin.SERVICE_STOP,
            )
        }
        player = null
    }

    private fun detachRoutingListener() {
        val attachment = synchronized(lock) {
            val current = RoutingAttachment(currentAudioTrack, currentAudioOutput, currentRoutingListener)
            currentAudioTrack = null
            currentAudioTrackGeneration = NO_GENERATION
            currentAudioOutput = null
            currentRoutingListener = null
            current
        }
        releaseRoutingAttachment(attachment)
    }

    private fun releaseRoutingAttachment(attachment: RoutingAttachment?) {
        attachment ?: return
        runCatching { attachment.output?.mute() }
        val track = attachment.track ?: return
        val listener = attachment.listener ?: return
        runCatching { track.removeOnRoutingChangedListener(listener) }
    }

    private fun updateSource(item: MediaItem?) {
        val facts = TelemetryMediaItemExtras.read(item?.mediaMetadata?.extras)
        sourceFacts = facts
        sourceFormat = facts.toSourcePcmFormat()
    }

    private fun TelemetrySourceFacts.toSourcePcmFormat(): SourcePcmFormat? {
        val rate = sampleRateHz ?: return null
        val bits = bitDepth ?: return null
        val channels = channelCount ?: return null
        return SourcePcmFormat(rate, bits, channels)
    }

    private fun AudioOutputProvider.OutputConfig.toPlatformFormat() = PlatformPcmFormat(
        sampleRateHz = sampleRate,
        encoding = encoding,
        channelMask = channelMask,
        channelCount = Integer.bitCount(channelMask),
    )

    private fun SourcePcmFormat.toSummary() = AudioFormatSummary(
        sampleRateHz = sampleRateHz,
        channelCount = channelCount,
        encoding = "$bitDepth-bit source",
    )

    private fun PlatformPcmFormat.toSummary() = AudioFormatSummary(
        sampleRateHz = sampleRateHz,
        channelCount = channelCount,
        encoding = platformEncodingName(encoding),
    )

    private fun AudioDeviceInfo.displayName(): String = productName?.toString()?.takeIf(String::isNotBlank)
        ?: "USB audio ${id}"

    /**
     * Android does not expose a reliable AudioDeviceInfo-to-UsbDevice mapping. An exact identity
     * is therefore published only when one physical USB Audio Class device is present; multiple
     * candidates remain intentionally unverifiable instead of being matched by display name.
     */
    private fun uniqueUsbHardwareIdentity(): UsbHardwareIdentity? = runCatching {
        usbManager.deviceList.values
            .filter(UsbDevice::isUsbAudioDevice)
            .singleOrNull()
            ?.let { device ->
                val descriptorVersion = device.version.takeIf(String::isNotBlank) ?: return@runCatching null
                UsbHardwareIdentity(
                    vendorId = device.vendorId,
                    productId = device.productId,
                    descriptorVersion = descriptorVersion,
                )
            }
    }.getOrNull()

    private companion object {
        const val PREFERENCES_NAME = "usb_output_preferences"
        const val MODE_KEY = "mode"
        const val NO_GENERATION = -1L
        const val ROUTE_VERIFICATION_TIMEOUT_MS = 3_000L
    }
}

private data class ReconfigurationSchedule(
    val device: AudioDeviceInfo?,
    val sink: PlatformPcmFormat?,
    val generation: Long,
    val shouldPost: Boolean,
    val pausePlayer: Boolean,
)

private data class MixerPreference(
    val device: AudioDeviceInfo,
    val audioAttributes: AudioAttributes,
)

private data class StrictOutputPlan(
    val device: AudioDeviceInfo,
    val profiles: List<MixerProfile>,
    val source: SourcePcmFormat?,
    val generation: Long,
)

private data class StrictRoutePlan(
    val device: AudioDeviceInfo?,
    val mixerFormat: PlatformPcmFormat?,
    val audioAttributes: AudioAttributes?,
    val generation: Long,
)

private data class RoutingAttachment(
    val track: AudioTrack?,
    val output: StrictGatedAudioOutput?,
    val listener: AudioRouting.OnRoutingChangedListener?,
)

/**
 * One playlist item's PCM on the own output, as the route reports it. A reused output names the
 * source it continues ([followsToken], kept by token so finished sources do not chain up).
 */
private class DirectSource(val token: Any, val period: Any, val followsToken: Any?, val followsPeriod: Any?) {
    var delivering = false
    var delivered = false
}

private data class FailedOutputState(
    val device: AudioDeviceInfo?,
    val sink: PlatformPcmFormat?,
    val audioAttributes: AudioAttributes? = null,
    val directDeviceName: String? = null,
)

private data class ClosingOutputState(
    val mode: UsbOutputMode,
    val device: AudioDeviceInfo?,
    val sink: PlatformPcmFormat?,
    val audioAttributes: AudioAttributes?,
)

/** Retains every applied preference until Android confirms that it was cleared. */
internal class MixerPreferenceCleanupTracker<T>(
    private val clearPreference: (T) -> Result<Boolean>,
) {
    private val tracked = linkedSetOf<T>()
    private val cleanupExceptionTypes = mutableMapOf<T, Set<String>>()

    @Synchronized
    fun track(preference: T) {
        tracked += preference
    }

    /**
     * Applies [preference] and tracks it unless Android explicitly rejected it. A rejected request
     * leaves nothing behind, and clearing it could hit a preference another app holds on the same
     * device, which Android refuses. A request that threw may have applied, so it stays tracked.
     */
    @Synchronized
    fun request(preference: T, apply: () -> Result<Boolean>): Result<Boolean> {
        val result = runCatching { apply().getOrThrow() }
        if (result.getOrNull() != false) tracked += preference
        return result
    }

    @Synchronized
    fun clear(preference: T): Boolean {
        if (preference !in tracked) return true
        val result = try {
            clearPreference(preference)
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        // Keep only the type; exception messages and stack traces can contain device identifiers.
        val failure = result.exceptionOrNull()
        if (failure == null) cleanupExceptionTypes.remove(preference)
        else cleanupExceptionTypes[preference] = cleanupExceptionTypes(failure)
        if (result.getOrNull() == true) tracked -= preference
        return result.getOrNull() == true
    }

    @Synchronized
    fun clearAll(): Boolean {
        tracked.toList().forEach(::clear)
        return tracked.isEmpty()
    }

    @Synchronized
    internal fun pendingCount(): Int = tracked.size

    @Synchronized
    fun snapshot(): MixerCleanupStatus = MixerCleanupStatus(
        pendingCount = tracked.size,
        exceptionTypes = cleanupExceptionTypes.values.flatten().toSet(),
    )
}

/** AudioManager wraps RemoteException; retain causes without traversing unbounded/cyclic chains. */
internal fun cleanupExceptionTypes(failure: Throwable): Set<String> {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val types = linkedSetOf<String>()
    var current: Throwable? = failure
    repeat(8) {
        val cause = current ?: return types
        if (!seen.add(cause)) return types
        types += cause.javaClass.name
        current = cause.cause
    }
    return types
}

/** Blocks PCM until strict-route verification and closes again on pause or non-neutral volume. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class StrictGatedAudioOutput(
    private val delegateOutput: AudioOutput,
    private val onGatedPlay: () -> Unit = {},
    private val onNonNeutralVolume: (Float) -> Unit = {},
) : ForwardingAudioOutput(delegateOutput) {
    private val gateLock = Any()
    private var gated = true
    private var requestedVolume = 1f
    private var playRequested = false
    private var routeProbeStarted = false
    private var invalidated = false

    init {
        delegateOutput.setVolume(0f)
    }

    override fun setVolume(volume: Float) {
        var nonNeutralVolume = false
        synchronized(gateLock) {
            requestedVolume = volume
            if (volume != 1f) {
                invalidated = true
                gated = true
                routeProbeStarted = false
                // Closing the write gate is synchronous. The coordinator then pauses/stops the
                // player so Media3 can touch its position tracker on the renderer thread.
                runCatching { delegateOutput.setVolume(0f) }
                nonNeutralVolume = true
            } else {
                delegateOutput.setVolume(if (gated) 0f else volume)
            }
        }
        if (nonNeutralVolume) onNonNeutralVolume(volume)
    }

    override fun play() {
        var requestRouteReverification = false
        synchronized(gateLock) {
            playRequested = true
            // A first empty play lets AudioTrack establish its routed device. PCM writes remain
            // blocked until activate(), so this does not depend on volume scaling for silence.
            if (!gated || !routeProbeStarted) {
                delegateOutput.play()
                requestRouteReverification = gated && !invalidated
                routeProbeStarted = true
            }
        }
        if (requestRouteReverification) onGatedPlay()
    }

    override fun pause() {
        synchronized(gateLock) {
            gated = true
            playRequested = false
            routeProbeStarted = false
            delegateOutput.pause()
        }
    }

    override fun write(buffer: ByteBuffer, accessUnitCount: Int, presentationTimeUs: Long): Boolean =
        synchronized(gateLock) {
            if (gated) false else delegateOutput.write(buffer, accessUnitCount, presentationTimeUs)
        }

    fun isReadyForActivation(): Boolean = synchronized(gateLock) {
        !gated || (!invalidated && playRequested && routeProbeStarted)
    }

    fun activate(): Boolean = synchronized(gateLock) {
        if (!isReadyForActivation() || invalidated || requestedVolume != 1f) {
            return@synchronized false
        }
        try {
            delegateOutput.setVolume(requestedVolume)
            if (playRequested) delegateOutput.play()
            gated = false
            true
        } catch (_: Exception) {
            gated = true
            runCatching { delegateOutput.setVolume(0f) }
            false
        }
    }

    fun mute() {
        synchronized(gateLock) {
            gated = true
            routeProbeStarted = false
            // Do not call AudioOutput.pause() from the application listener thread. Media3 owns
            // the delegate's playback-thread state; the coordinator pauses the player separately.
            delegateOutput.setVolume(0f)
        }
    }
}

private fun AudioDeviceInfo.isUsbAudioOutput(): Boolean = isSink && type in USB_AUDIO_TYPES

internal fun UsbDevice.isUsbAudioDevice(): Boolean =
    deviceClass == UsbConstants.USB_CLASS_AUDIO ||
        (0 until interfaceCount).any { index ->
            getInterface(index).interfaceClass == UsbConstants.USB_CLASS_AUDIO
        }

private val USB_AUDIO_TYPES = setOf(
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)

internal fun platformEncodingName(encoding: Int): String = when (encoding) {
    android.media.AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
    android.media.AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
    android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
    android.media.AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
    android.media.AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
    else -> "encoding $encoding"
}

/** Initial route discovery may be pending, but loss of an already-active route must fail closed. */
internal fun strictRouteUnavailableFailure(phase: UsbOutputPhase): UsbOutputFailure? =
    UsbOutputFailure.ROUTE_UNAVAILABLE.takeIf { phase == UsbOutputPhase.ACTIVE }
