package io.github.sumirenokai.vesqen.playback

import android.Manifest
import android.content.Context
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
import androidx.core.content.edit
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
    private var selectedDevice: AudioDeviceInfo? = null
    private var selectedProfiles: List<MixerProfile>? = null
    private var selectedMixerFormat: PlatformPcmFormat? = null
    private var selectedAudioAttributes: AudioAttributes? = null
    private var configurationGeneration = 0L
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

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (
                addedDevices.any(AudioDeviceInfo::isUsbAudioOutput) &&
                currentMode() == UsbOutputMode.STRICT_BIT_PERFECT
            ) {
                scheduleReconfigure(resume = player?.playWhenReady == true)
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
        sourceFormat = player.currentMediaItem.toSourcePcmFormat()
        if (mode == UsbOutputMode.SYSTEM) {
            publishSystem()
        } else {
            scheduleReconfigure(resume = false)
        }
    }

    fun requestMode(requestedMode: UsbOutputMode) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestMode(requestedMode) }
            return
        }
        if (closed) return
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
            scheduleReconfigure(resume = player?.playWhenReady == true)
        }
    }

    fun currentStatus(): UsbOutputStatus = stateRepository.snapshot()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        sourceFormat = mediaItem.toSourcePcmFormat()
        if (currentMode() == UsbOutputMode.STRICT_BIT_PERFECT) {
            scheduleReconfigure(resume = player?.playWhenReady == true)
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
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
        synchronized(lock) { internalPauseInProgress = false }
        if (currentMode() != UsbOutputMode.STRICT_BIT_PERFECT) return
        when (stateRepository.snapshot().phase) {
            UsbOutputPhase.SYSTEM,
            UsbOutputPhase.AVAILABLE,
            UsbOutputPhase.FAILED -> scheduleReconfigure(resume = true)
            UsbOutputPhase.APPLYING -> synchronized(lock) { currentAudioTrack }?.let { track ->
                evaluateRoute(track)
                scheduleRouteVerificationTimeout(track)
            }
            UsbOutputPhase.ACTIVE -> Unit
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

    private fun scheduleReconfigure(resume: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { scheduleReconfigure(resume) }
            return
        }
        val activePlayer = player
        val wasPlaying = activePlayer?.playWhenReady == true
        val scheduled = synchronized(lock) {
            if (closed || mode != UsbOutputMode.STRICT_BIT_PERFECT) return@synchronized null
            configurationGeneration += 1
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
        if (closed || currentMode() != UsbOutputMode.STRICT_BIT_PERFECT) return
        val generation = synchronized(lock) { configurationGeneration }
        val activePlayer = player ?: return
        sourceFormat = activePlayer.currentMediaItem.toSourcePcmFormat()
        enforceNeutralProcessing(activePlayer)
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
        var selected: Triple<AudioDeviceInfo, List<MixerProfile>, UsbOutputDecision.Candidate>? = null
        devices.forEach { candidateDevice ->
            if (selected != null) return@forEach
            val profiles = mixerAdapter.profiles(candidateDevice).getOrNull() ?: return@forEach
            anyMixerQuerySucceeded = true
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
                code = if (anyMixerQuerySucceeded) {
                    "strict_usb.no_matching_mixer_attribute"
                } else {
                    "strict_usb.mixer_query_failed"
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
        publish(
            phase = UsbOutputPhase.APPLYING,
            device = device,
            source = capabilityCandidate.source,
            decisionCode = "strict_usb.rebuilding_audio_track",
        )
        val pauseNeeded = activePlayer.playWhenReady
        if (pauseNeeded) {
            synchronized(lock) { internalPauseInProgress = true }
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
            mixerPreferences.track(mixerPreference)
            runCatching {
                mixerAdapter.setPreferred(device, mixerFormat, audioAttributes).getOrThrow()
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
            )
            configurationGeneration += 1
            selectedPlanGeneration = NO_GENERATION
            selectedDevice = null
            selectedProfiles = null
            selectedMixerFormat = null
            selectedAudioAttributes = null
            resumeAfterConfiguration = false
            reconfigurePosted = false
            state
        } ?: return
        detachRoutingListener()
        val cleanupSucceeded = !attemptMixerCleanup || synchronized(mixerMutationLock) {
            mixerPreferences.clearAll()
        }
        val reportedFailure = if (cleanupSucceeded) failure else UsbOutputFailure.MIXER_CLEAR_FAILED
        val reportedCode = if (cleanupSucceeded) code else "strict_usb.mixer_clear_failed"
        publish(
            phase = UsbOutputPhase.FAILED,
            failure = reportedFailure,
            device = failedState.device,
            source = sourceFormat,
            sink = failedState.sink,
            decisionCode = reportedCode,
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
        val oldState = synchronized(lock) { FailedOutputState(selectedDevice, selectedMixerFormat) }
        detachRoutingListener()
        val cleanupSucceeded = synchronized(mixerMutationLock) {
            if (!mixerPreferences.clearAll()) {
                false
            } else {
                synchronized(lock) {
                    mode = UsbOutputMode.SYSTEM
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
            )
            return
        }
        preferences.edit { putString(MODE_KEY, UsbOutputMode.SYSTEM.name) }
        activePlayer?.setPreferredAudioDevice(null)
        publishSystem()
        if (rebuild && activePlayer != null && activePlayer.mediaItemCount > 0) {
            activePlayer.pause()
            activePlayer.stop()
            activePlayer.prepare()
            if (resume) activePlayer.play()
        }
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
    ) {
        val status = synchronized(lock) {
            statusGeneration += 1
            UsbOutputStatus(
                mode = explicitMode ?: mode,
                phase = phase,
                failure = failure,
                deviceName = device?.displayName(),
                hardwareIdentity = device?.let { uniqueUsbHardwareIdentity() },
                sourceFormat = source?.toSummary(),
                sinkFormat = sink?.toSummary(),
                decisionCode = decisionCode,
                observedAtEpochMs = System.currentTimeMillis(),
                observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                generation = statusGeneration,
                officialMixerApiSupport = officialMixerApiSupport,
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
        player?.removeListener(this)
        detachRoutingListener()
        val cleanupSucceeded = synchronized(mixerMutationLock) { mixerPreferences.clearAll() }
        if (closingState.mode == UsbOutputMode.STRICT_BIT_PERFECT) {
            publish(
                phase = UsbOutputPhase.FAILED,
                failure = if (cleanupSucceeded) {
                    UsbOutputFailure.SERVICE_STOPPED
                } else {
                    UsbOutputFailure.MIXER_CLEAR_FAILED
                },
                device = closingState.device,
                source = sourceFormat,
                sink = closingState.sink,
                decisionCode = if (cleanupSucceeded) {
                    "strict_usb.service_stopped"
                } else {
                    "strict_usb.mixer_clear_failed"
                },
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

    private fun MediaItem?.toSourcePcmFormat(): SourcePcmFormat? {
        val facts = TelemetryMediaItemExtras.read(this?.mediaMetadata?.extras)
        val sampleRate = facts.sampleRateHz ?: return null
        val bitDepth = facts.bitDepth ?: return null
        val channels = facts.channelCount ?: return null
        return SourcePcmFormat(sampleRate, bitDepth, channels)
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

private data class FailedOutputState(
    val device: AudioDeviceInfo?,
    val sink: PlatformPcmFormat?,
    val audioAttributes: AudioAttributes? = null,
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

    @Synchronized
    fun track(preference: T) {
        tracked += preference
    }

    @Synchronized
    fun clear(preference: T): Boolean {
        if (preference !in tracked) return true
        val cleared = runCatching { clearPreference(preference).getOrThrow() }.getOrDefault(false)
        if (cleared) tracked -= preference
        return cleared
    }

    @Synchronized
    fun clearAll(): Boolean {
        tracked.toList().forEach(::clear)
        return tracked.isEmpty()
    }

    @Synchronized
    internal fun pendingCount(): Int = tracked.size
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

private fun UsbDevice.isUsbAudioDevice(): Boolean =
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
