package io.github.sumirenokai.vesqen.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private var player: ExoPlayer? = null
    private var mode = preferences.getString(MODE_KEY, null)?.let { stored ->
        UsbOutputMode.entries.firstOrNull { it.name == stored }
    } ?: UsbOutputMode.SYSTEM
    private var sourceFormat: SourcePcmFormat? = null
    private var selectedDevice: AudioDeviceInfo? = null
    private var selectedProfiles: List<MixerProfile>? = null
    private var selectedMixerFormat: PlatformPcmFormat? = null
    private var currentAudioTrack: AudioTrack? = null
    private var currentAudioOutput: StrictGatedAudioOutput? = null
    private var currentRoutingListener: AudioRouting.OnRoutingChangedListener? = null
    private var resumeAfterConfiguration = false
    private var reconfigurePosted = false
    private var closed = false
    private var statusGeneration = stateRepository.snapshot().generation

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any(AudioDeviceInfo::isUsbAudioOutput) && currentMode() == UsbOutputMode.STRICT_BIT_PERFECT) {
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
        val previousMode = synchronized(lock) {
            val previous = mode
            mode = requestedMode
            previous
        }
        preferences.edit { putString(MODE_KEY, requestedMode.name) }
        if (requestedMode == UsbOutputMode.SYSTEM) {
            restoreSystemOutput(rebuild = previousMode != UsbOutputMode.SYSTEM)
        } else {
            scheduleReconfigure(resume = player?.playWhenReady == true)
        }
    }

    fun currentStatus(): UsbOutputStatus = stateRepository.snapshot()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        sourceFormat = mediaItem.toSourcePcmFormat()
        if (currentMode() == UsbOutputMode.STRICT_BIT_PERFECT) {
            publish(
                phase = UsbOutputPhase.APPLYING,
                device = synchronized(lock) { selectedDevice },
                source = sourceFormat,
                decisionCode = "strict_usb.media_item_changed",
            )
            scheduleReconfigure(resume = player?.playWhenReady == true)
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (currentMode() != UsbOutputMode.STRICT_BIT_PERFECT) return
        if (!playWhenReady) {
            val status = stateRepository.snapshot()
            if (status.phase == UsbOutputPhase.ACTIVE) {
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
        when (stateRepository.snapshot().phase) {
            UsbOutputPhase.SYSTEM,
            UsbOutputPhase.AVAILABLE,
            UsbOutputPhase.FAILED -> scheduleReconfigure(resume = true)
            UsbOutputPhase.APPLYING -> synchronized(lock) { currentAudioTrack }?.let(::evaluateRoute)
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
        synchronized(lock) {
            resumeAfterConfiguration = resumeAfterConfiguration || resume
            if (closed || reconfigurePosted) return
            reconfigurePosted = true
        }
        mainHandler.post {
            synchronized(lock) { reconfigurePosted = false }
            reconfigureStrictOutput()
        }
    }

    private fun reconfigureStrictOutput() {
        if (closed || currentMode() != UsbOutputMode.STRICT_BIT_PERFECT) return
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
            failClosed(preflight.failure, preflight.code)
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
            )
            return
        }
        val (device, profiles, capabilityCandidate) = selectedCandidate
        clearPreviousPreferenceUnless(device.id)
        synchronized(lock) {
            selectedDevice = device
            selectedProfiles = profiles
            selectedMixerFormat = null
        }
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
        activePlayer.pause()
        activePlayer.setPreferredAudioDevice(device)
        if (activePlayer.mediaItemCount > 0) {
            activePlayer.stop()
            activePlayer.prepare()
        }
    }

    override fun getAudioOutput(outputConfig: AudioOutputProvider.OutputConfig): AudioOutput {
        val request = outputConfig.toPlatformFormat()
        val strictPlan = synchronized(lock) {
            if (mode == UsbOutputMode.STRICT_BIT_PERFECT) {
                Triple(selectedDevice, selectedProfiles, sourceFormat)
            } else {
                null
            }
        }
        if (strictPlan == null) {
            return super.getAudioOutput(outputConfig)
        }
        val (device, profiles, source) = strictPlan
        if (device == null || profiles == null) {
            return mutedFailureOutput(
                outputConfig,
                UsbOutputFailure.PLATFORM_ERROR,
                "strict_usb.audio_track_created_without_plan",
            )
        }
        val resolved = resolver.resolve(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            apiLevel = Build.VERSION.SDK_INT,
            usbHostSupported = true,
            modifyAudioSettingsGranted = true,
            devices = listOf(UsbOutputDevice(device.id, device.displayName())),
            source = source,
            mixerProfiles = profiles,
            audioTrackFormat = request,
        )
        if (resolved is UsbOutputDecision.Rejected) {
            return mutedFailureOutput(
                outputConfig,
                resolved.failure,
                resolved.code,
            )
        }
        val candidate = resolved as UsbOutputDecision.Candidate
        val mixerFormat = requireNotNull(candidate.mixerProfile).format
        val preferredSet = mixerAdapter.setPreferred(device, mixerFormat).getOrElse {
            return mutedFailureOutput(
                outputConfig,
                UsbOutputFailure.MIXER_REQUEST_REJECTED,
                "strict_usb.mixer_request_failed",
            )
        }
        if (!preferredSet) {
            return mutedFailureOutput(
                outputConfig,
                UsbOutputFailure.MIXER_REQUEST_REJECTED,
                "strict_usb.mixer_request_rejected",
            )
        }
        val preferredMatches = mixerAdapter.isPreferred(device, mixerFormat).getOrDefault(false)
        if (!preferredMatches) {
            return mutedFailureOutput(
                outputConfig,
                UsbOutputFailure.MIXER_READBACK_MISMATCH,
                "strict_usb.mixer_readback_mismatch",
            )
        }
        val output = try {
            super.getAudioOutput(outputConfig)
        } catch (failure: Exception) {
            mixerAdapter.clearPreferred(device)
            failClosed(
                UsbOutputFailure.PLATFORM_ERROR,
                "strict_usb.audio_output_creation_failed",
                device,
                request,
            )
            throw failure
        }
        val gatedOutput = StrictGatedAudioOutput(output)
        val track = (output as? AudioTrackAudioOutput)?.audioTrack
        if (track == null) {
            output.setVolume(0f)
            failClosed(
                UsbOutputFailure.PLATFORM_ERROR,
                "strict_usb.audio_output_not_inspectable",
                device,
                request,
            )
            return output
        }
        if (!runCatching { track.setPreferredDevice(device) }.getOrDefault(false)) {
            output.setVolume(0f)
            failClosed(
                UsbOutputFailure.PREFERRED_DEVICE_REJECTED,
                "strict_usb.preferred_device_rejected",
                device,
                request,
            )
            return output
        }
        synchronized(lock) { selectedMixerFormat = mixerFormat }
        attachRoutingListener(track, gatedOutput)
        publish(
            phase = UsbOutputPhase.APPLYING,
            device = device,
            source = source,
            sink = request,
            decisionCode = "strict_usb.mixer_applied_waiting_for_route",
        )
        mainHandler.post {
            evaluateRoute(track)
            val shouldResume = synchronized(lock) {
                val resume = resumeAfterConfiguration
                resumeAfterConfiguration = false
                resume
            }
            if (shouldResume && currentMode() == UsbOutputMode.STRICT_BIT_PERFECT &&
                stateRepository.snapshot().phase != UsbOutputPhase.FAILED
            ) {
                player?.play()
            }
        }
        return gatedOutput
    }

    private fun mutedFailureOutput(
        config: AudioOutputProvider.OutputConfig,
        failure: UsbOutputFailure,
        code: String,
    ): AudioOutput {
        val output = try {
            super.getAudioOutput(config)
        } catch (creationFailure: Exception) {
            failClosed(failure, code, sink = config.toPlatformFormat())
            throw creationFailure
        }
        output.setVolume(0f)
        failClosed(failure, code, sink = config.toPlatformFormat())
        return output
    }

    private fun attachRoutingListener(track: AudioTrack, output: StrictGatedAudioOutput) {
        detachRoutingListener()
        val listener = AudioRouting.OnRoutingChangedListener { routing ->
            (routing as? AudioTrack)?.let(::evaluateRoute)
        }
        synchronized(lock) {
            currentAudioTrack = track
            currentAudioOutput = output
            currentRoutingListener = listener
        }
        track.addOnRoutingChangedListener(listener, mainHandler)
    }

    private fun evaluateRoute(track: AudioTrack) {
        val (isCurrent, targetDevice, targetFormat) = synchronized(lock) {
            Triple(currentAudioTrack === track, selectedDevice, selectedMixerFormat)
        }
        if (!isCurrent || currentMode() != UsbOutputMode.STRICT_BIT_PERFECT) return
        val routed = runCatching { track.routedDevice }.getOrNull() ?: return
        if (targetDevice == null || routed.id != targetDevice.id) {
            failClosed(UsbOutputFailure.ROUTE_MISMATCH, "strict_usb.route_mismatch", targetDevice, targetFormat)
            return
        }
        val activePlayer = player
        if (activePlayer == null || !activePlayer.hasNeutralProcessing()) {
            failClosed(
                UsbOutputFailure.PROCESSING_NOT_NEUTRAL,
                "strict_usb.processing_not_neutral",
                targetDevice,
                targetFormat,
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
        if (targetFormat == null || !mixerAdapter.isPreferred(targetDevice, targetFormat).getOrDefault(false)) {
            failClosed(
                UsbOutputFailure.MIXER_READBACK_MISMATCH,
                "strict_usb.active_mixer_readback_mismatch",
                targetDevice,
                targetFormat,
            )
            return
        }
        val activeOutput = synchronized(lock) {
            currentAudioOutput.takeIf { currentAudioTrack === track }
        }
        if (activeOutput == null || !activeOutput.activate()) {
            failClosed(
                UsbOutputFailure.PLATFORM_ERROR,
                "strict_usb.output_unmute_failed",
                targetDevice,
                targetFormat,
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

    private fun failClosed(
        failure: UsbOutputFailure,
        code: String,
        device: AudioDeviceInfo? = synchronized(lock) { selectedDevice },
        sink: PlatformPcmFormat? = synchronized(lock) { selectedMixerFormat },
    ) {
        if (currentMode() != UsbOutputMode.STRICT_BIT_PERFECT || closed) return
        device?.let { mixerAdapter.clearPreferred(it) }
        detachRoutingListener()
        synchronized(lock) {
            selectedDevice = null
            selectedProfiles = null
            selectedMixerFormat = null
            resumeAfterConfiguration = false
        }
        publish(
            phase = UsbOutputPhase.FAILED,
            failure = failure,
            device = device,
            source = sourceFormat,
            sink = sink,
            decisionCode = code,
        )
        mainHandler.post {
            if (currentMode() != UsbOutputMode.STRICT_BIT_PERFECT) return@post
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
        val oldDevice = synchronized(lock) { selectedDevice }
        oldDevice?.let { mixerAdapter.clearPreferred(it) }
        detachRoutingListener()
        synchronized(lock) {
            selectedDevice = null
            selectedProfiles = null
            selectedMixerFormat = null
            resumeAfterConfiguration = false
        }
        activePlayer?.setPreferredAudioDevice(null)
        publishSystem()
        if (rebuild && activePlayer != null && activePlayer.mediaItemCount > 0) {
            activePlayer.pause()
            activePlayer.stop()
            activePlayer.prepare()
            if (resume) activePlayer.play()
        }
    }

    private fun clearPreviousPreferenceUnless(deviceId: Int) {
        synchronized(lock) { selectedDevice }
            ?.takeIf { it.id != deviceId }
            ?.let { mixerAdapter.clearPreferred(it) }
        detachRoutingListener()
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
                sourceFormat = source?.toSummary(),
                sinkFormat = sink?.toSummary(),
                decisionCode = decisionCode,
                observedAtEpochMs = System.currentTimeMillis(),
                observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                generation = statusGeneration,
            )
        }
        stateRepository.publish(status)
    }

    private fun currentMode(): UsbOutputMode = synchronized(lock) { mode }

    fun close() {
        if (closed) return
        val closingMode = currentMode()
        val closingDevice = synchronized(lock) { selectedDevice }
        val closingSink = synchronized(lock) { selectedMixerFormat }
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        player?.removeListener(this)
        closingDevice?.let { mixerAdapter.clearPreferred(it) }
        detachRoutingListener()
        if (closingMode == UsbOutputMode.STRICT_BIT_PERFECT) {
            publish(
                phase = UsbOutputPhase.FAILED,
                failure = UsbOutputFailure.SERVICE_STOPPED,
                device = closingDevice,
                source = sourceFormat,
                sink = closingSink,
                decisionCode = "strict_usb.service_stopped",
            )
        }
        closed = true
        player = null
    }

    private fun detachRoutingListener() {
        val trackOutputAndListener = synchronized(lock) {
            val triple = Triple(currentAudioTrack, currentAudioOutput, currentRoutingListener)
            currentAudioTrack = null
            currentAudioOutput = null
            currentRoutingListener = null
            triple
        }
        runCatching { trackOutputAndListener.second?.mute() }
        val track = trackOutputAndListener.first ?: return
        val listener = trackOutputAndListener.third ?: return
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

    private companion object {
        const val PREFERENCES_NAME = "usb_output_preferences"
        const val MODE_KEY = "mode"
    }
}

/** Holds later sink volume calls at silence until the coordinator has verified the strict route. */
@UnstableApi
private class StrictGatedAudioOutput(
    private val delegateOutput: AudioOutput,
) : ForwardingAudioOutput(delegateOutput) {
    private val volumeLock = Any()
    private var gated = true
    private var requestedVolume = 1f

    init {
        delegateOutput.setVolume(0f)
    }

    override fun setVolume(volume: Float) {
        synchronized(volumeLock) {
            requestedVolume = volume
            delegateOutput.setVolume(if (gated) 0f else volume)
        }
    }

    fun activate(): Boolean = runCatching {
        synchronized(volumeLock) {
            delegateOutput.setVolume(requestedVolume)
            gated = false
        }
    }.isSuccess

    fun mute() {
        synchronized(volumeLock) {
            gated = true
            delegateOutput.setVolume(0f)
        }
    }
}

private fun AudioDeviceInfo.isUsbAudioOutput(): Boolean = isSink && type in USB_AUDIO_TYPES

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
