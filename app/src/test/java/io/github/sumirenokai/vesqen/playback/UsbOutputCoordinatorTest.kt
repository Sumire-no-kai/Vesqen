package io.github.sumirenokai.vesqen.playback

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import io.github.sumirenokai.vesqen.library.AudioTrack as LibraryTrack
import io.github.sumirenokai.vesqen.telemetry.TelemetryMediaItemExtras
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.TakeoverMarker
import io.github.sumirenokai.vesqen.usb.session.UsbDeviceIdentity
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.util.ReflectionHelpers

/**
 * Drives the shipped state machine with a real ExoPlayer and a faked platform. No renderer runs, so
 * each test calls [UsbOutputCoordinator.getAudioOutput] and the output's play() where Media3's
 * audio sink would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RoutableAudioTrackShadow::class, RoutingAudioManagerShadow::class])
@androidx.annotation.OptIn(UnstableApi::class)
class UsbOutputCoordinatorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val preferences = context.getSharedPreferences("usb_output_preferences", Context.MODE_PRIVATE)
    private val repository = UsbOutputStateRepository()
    private val published = mutableListOf<UsbOutputStatus>()
    private val mixer = FakeMixerAdapter()
    // Stable in-memory sources keep asynchronous file-not-found errors out of lifecycle tests.
    private val realPlayer = ExoPlayer.Builder(context).setMediaSourceFactory(object : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)
        override fun createMediaSource(item: MediaItem): MediaSource {
            val source = SilenceMediaSource(60_000_000).apply { updateMediaItem(item) }
            return object : WrappingMediaSource(source) {
                // Publish the concrete period immediately, before the playback thread prepares it.
                override fun getInitialTimeline() = SinglePeriodTimeline(60_000_000, true, false, false, null, item)
            }
        }
    }).build()
    private val playerCalls = mutableListOf<String>()
    private val player = recordingPlayer(realPlayer, playerCalls)
    private val dac = audioDevice(id = 11)
    private var strictAllowed = true
    private lateinit var coordinator: UsbOutputCoordinator

    private fun start(modifyAudioSettingsGranted: Boolean = true, usbHost: Boolean = true, direct: DirectUsbRoute? = null) {
        if (modifyAudioSettingsGranted) {
            shadowOf(context).grantPermissions(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        }
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_USB_HOST, usbHost)
        repository.addListener { published += it }
        coordinator = UsbOutputCoordinator(
            context = context,
            stateRepository = repository,
            mixerAdapter = mixer,
            strictOutputAllowed = { strictAllowed },
            directRoute = direct,
        )
        coordinator.attachPlayer(player)
    }

    @After
    fun tearDown() {
        if (::coordinator.isInitialized) coordinator.close()
        realPlayer.release()
        RoutableAudioTrackShadow.accepted = true
        RoutableAudioTrackShadow.rejectCoordinatorListener = false
    }

    @Test
    fun `startup recovers the device before restoring a system playback queue`() {
        direct.recoveryPending = true
        direct.deferRecovery = true
        start(direct = direct)
        loadTrack()
        player.play()
        idle()
        assertStatus(UsbOutputPhase.APPLYING, "direct_usb.recovering")
        assertFalse("prepare" in playerCalls)
        direct.recoveryPending = false
        checkNotNull(direct.pendingRecovery)(DirectUsbRecoveryResult())
        idle()
        assertStatus(UsbOutputPhase.SYSTEM, "system_audio_route")
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `a pause while startup recovery waits cancels automatic playback`() {
        direct.recoveryPending = true
        direct.deferRecovery = true
        start(direct = direct)
        loadTrack()
        player.play()
        player.pause()
        direct.recoveryPending = false
        checkNotNull(direct.pendingRecovery)(DirectUsbRecoveryResult())
        idle()
        assertStatus(UsbOutputPhase.SYSTEM, "system_audio_route")
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `a recovery result cannot rebuild a closed playback service`() {
        direct.recoveryPending = true
        direct.deferRecovery = true
        start(direct = direct)
        val done = checkNotNull(direct.pendingRecovery)
        coordinator.close()
        playerCalls.clear()
        done(DirectUsbRecoveryResult())
        idle()
        assertFalse("prepare" in playerCalls || "play" in playerCalls)
    }

    @Test
    fun `startup recovery reports missing permission without opening a dialog`() {
        direct.recoveryPending = true
        direct.recoveryResult = DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.permission_required")
        start(direct = direct)
        idle()
        assertStatus(UsbOutputPhase.FAILED, "direct_usb.permission_required")
        assertFalse("requestPermission" in direct.calls)
    }

    @Test
    fun `unplug during the recovery dialog completes the requested system handoff`() {
        direct.recoveryPending = true
        direct.recoveryResult = DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.permission_required")
        start(direct = direct)
        direct.beforePermissionResult = {
            direct.recoveryPending = false
            direct.recoveryResult = DirectUsbRecoveryResult()
        }
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()
        assertStatus(UsbOutputPhase.SYSTEM, "system_audio_route")
        assertEquals(1, direct.calls.count { it == "requestPermission" })
    }

    @Test
    fun `an unplugged DAC can dismiss its permission dialog without stranding system mode`() {
        direct.recoveryPending = true
        direct.recoveryResult = DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.permission_required")
        start(direct = direct)
        direct.beforePermissionResult = {
            direct.permissionGranted = false
            direct.recoveryPending = false
            direct.recoveryResult = DirectUsbRecoveryResult()
        }
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()
        assertStatus(UsbOutputPhase.SYSTEM, "system_audio_route")
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `denying recovery cancels a Play even when a later mode selection can recover`() {
        direct.recoveryPending = true
        direct.recoveryResult = DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.permission_required")
        start(direct = direct)
        loadTrack()
        direct.permissionGranted = false
        player.play()
        idle()
        assertStatus(UsbOutputPhase.FAILED, "direct_usb.permission_denied")
        direct.recoveryResult = DirectUsbRecoveryResult()
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()
        assertStatus(UsbOutputPhase.SYSTEM, "system_audio_route")
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `a grant followed by another permission failure cannot ask twice`() {
        direct.recoveryPending = true
        direct.recoveryResult = DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.permission_required")
        start(direct = direct)
        direct.beforePermissionResult = {
            if (direct.calls.count { it == "requestPermission" } > 1) direct.permissionGranted = false
        }
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()
        assertStatus(UsbOutputPhase.FAILED, "direct_usb.permission_required")
        assertEquals(1, direct.calls.count { it == "requestPermission" })
    }

    @Test
    fun `startup recovery is completed before the official mixer can be requested`() {
        connectDacWithMixer()
        direct.recoveryPending = true
        direct.deferRecovery = true
        start(direct = direct)
        loadTrack()
        coordinator.requestMode(UsbOutputMode.STRICT_BIT_PERFECT)
        player.play()
        idle()
        assertFalse(mixer.calls.any { it.startsWith("setPreferred") })
        direct.recoveryPending = false
        checkNotNull(direct.pendingRecovery)(DirectUsbRecoveryResult())
        idle()
        assertEquals(UsbOutputMode.STRICT_BIT_PERFECT, status().mode)
        assertTrue(status().phase != UsbOutputPhase.FAILED)
    }

    @Test
    fun `the noisy broadcast caused by claiming the DAC does not pause direct playback`() {
        reachDirectActive()
        context.sendBroadcast(Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertTrue(player.playWhenReady)
        assertStatus(UsbOutputPhase.ACTIVE, "direct_usb.active")
    }

    @Test
    fun `a current session permission failure stops playback and revokes ACTIVE`() {
        reachDirectActive()
        checkNotNull(direct.listener).onFailed(DirectUsbFailure(DirectUsbFailure.Reason.USB_PERMISSION_REVOKED, "revoked"))
        idle()
        assertStatus(UsbOutputPhase.FAILED, "direct_usb.usb_permission_revoked")
        assertFalse(player.playWhenReady)
        assertTrue("release" in direct.calls)
    }

    // System output

    @Test
    fun `system mode returns the provider output and leaves the mixer alone`() {
        start()

        val output = coordinator.getAudioOutput(outputConfig())

        assertFalse(output is StrictGatedAudioOutput)
        assertStatus(UsbOutputPhase.SYSTEM, "system_audio_route")
        assertTrue(mixer.calls.isEmpty())
    }

    @Test
    fun `system mode ignores processing changes`() {
        start()

        player.volume = 0.5f
        player.playbackParameters = PlaybackParameters(2f)
        idle()

        assertEquals(UsbOutputPhase.SYSTEM, status().phase)
    }

    // Reaching ACTIVE

    @Test
    fun `strict output becomes active only after mixer readback route and output play agree`() {
        connectDacWithMixer()
        start()
        loadTrack()

        requestStrict()
        assertStatus(UsbOutputPhase.AVAILABLE, "strict_usb.bit_perfect_available")
        // Capability inspection while paused must not prepare a live output.
        assertTrue(playerCalls.isEmpty())
        assertEquals(listOf("profiles(11)"), mixer.calls)

        player.play()
        idle()
        assertStatus(UsbOutputPhase.APPLYING, "strict_usb.rebuilding_audio_track")
        assertEquals(
            listOf("play", "pause", "pause", "setPreferredAudioDevice(11)", "stop", "prepare"),
            playerCalls,
        )

        val output = strictOutput()
        idle()
        assertTrue(mixer.calls.containsAll(listOf("setPreferred(11)", "isPreferred(11)")))
        assertStatus(UsbOutputPhase.APPLYING, "strict_usb.mixer_applied_waiting_for_route")
        assertTrue(realPlayer.playWhenReady)
        assertFalse(output.write(pcm(), 1, 0))

        ShadowAudioTrack.setRoutedDevice(dac)
        idle()
        assertStatus(UsbOutputPhase.APPLYING, "strict_usb.waiting_for_output_play")
        assertFalse(output.write(pcm(), 1, 0))

        output.play()
        idle()
        assertStatus(UsbOutputPhase.ACTIVE, "strict_usb.active")
        assertTrue(output.write(pcm(), 1, 0))
        assertEquals("PCM 16-bit", status().sinkFormat?.encoding)
        assertEquals(48_000, status().sourceFormat?.sampleRateHz)
        assertEquals(35, status().officialMixerApiSupport?.apiLevel)
        assertEquals(UsbOutputMode.STRICT_BIT_PERFECT.name, preferences.getString(MODE_KEY, null))
    }

    @Test
    fun `the first device with a matching bit perfect mixer is selected`() {
        connect(audioDevice(id = 5))
        connectDacWithMixer()
        start()
        loadTrack()

        requestStrict()
        player.play()
        idle()

        assertEquals(listOf("profiles(5)", "profiles(11)"), mixer.calls.distinct())
        assertTrue("setPreferredAudioDevice(11)" in playerCalls)
    }

    @Test
    fun `entering strict mode resets processing to neutral`() {
        connectDacWithMixer()
        start()
        loadTrack()
        player.volume = 0.5f
        player.playbackParameters = PlaybackParameters(1.5f)
        player.skipSilenceEnabled = true

        requestStrict()

        assertEquals(1f, player.volume)
        assertEquals(PlaybackParameters.DEFAULT, player.playbackParameters)
        assertFalse(player.skipSilenceEnabled)
        assertStatus(UsbOutputPhase.AVAILABLE, "strict_usb.bit_perfect_available")
    }

    // Failing closed before an output exists

    @Test
    fun `a missing usb device fails closed and stops the player`() {
        start()
        loadTrack()

        requestStrict()

        assertFailed(
            UsbOutputFailure.NO_USB_AUDIO_DEVICE,
            "strict_usb.no_usb_audio_device",
            UsbOutputFailureOrigin.USER_MODE_CHANGE,
        )
        assertEquals(listOf("pause", "stop", "setPreferredAudioDevice(null)"), playerCalls)
    }

    @Test
    fun `a stored strict mode inspects capability at service start without preparing an output`() {
        preferences.edit { putString(MODE_KEY, UsbOutputMode.STRICT_BIT_PERFECT.name) }
        connectDacWithMixer()
        realPlayer.setMediaItem(mediaItem())

        start()
        idle()

        assertStatus(UsbOutputPhase.AVAILABLE, "strict_usb.bit_perfect_available")
        assertTrue(playerCalls.isEmpty())
        assertEquals(listOf("profiles(11)"), mixer.calls)
    }

    @Test
    fun `a service start failure keeps its origin so it is not presented as a user failure`() {
        preferences.edit { putString(MODE_KEY, UsbOutputMode.STRICT_BIT_PERFECT.name) }
        realPlayer.setMediaItem(mediaItem())

        start()
        idle()

        assertFailed(
            UsbOutputFailure.NO_USB_AUDIO_DEVICE,
            "strict_usb.no_usb_audio_device",
            UsbOutputFailureOrigin.SERVICE_START,
        )
    }

    @Test
    fun `a denied audio settings permission fails closed`() {
        connectDacWithMixer()
        start(modifyAudioSettingsGranted = false)
        loadTrack()

        requestStrict()

        assertFailed(UsbOutputFailure.MODIFY_AUDIO_SETTINGS_DENIED, "strict_usb.modify_audio_settings_denied")
        assertTrue(mixer.calls.isEmpty())
    }

    @Test
    fun `a phone without usb host fails closed`() {
        connectDacWithMixer()
        start(usbHost = false)
        loadTrack()

        requestStrict()

        assertFailed(UsbOutputFailure.USB_HOST_UNAVAILABLE, "strict_usb.usb_host_unavailable")
        assertTrue(mixer.calls.isEmpty())
    }

    @Test
    fun `an unknown source format fails closed`() {
        connectDacWithMixer()
        start()
        player.setMediaItem(MediaItem.fromUri("file:///nonexistent/unknown.flac"))
        idle()

        requestStrict()

        assertFailed(UsbOutputFailure.SOURCE_FORMAT_UNKNOWN, "strict_usb.source_format_unknown")
        assertTrue(mixer.calls.isEmpty())
    }

    @Test
    fun `an unsupported source depth fails closed`() {
        connectDacWithMixer()
        start()
        loadTrack(bitDepth = 20)

        requestStrict()

        assertFailed(UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED, "strict_usb.source_format_unsupported")
        assertTrue(mixer.calls.isEmpty())
    }

    @Test
    fun `an ended trial fails closed before the mixer is queried`() {
        connectDacWithMixer()
        strictAllowed = false
        start()
        loadTrack()

        requestStrict()

        assertFailed(UsbOutputFailure.TRIAL_ENDED, "strict_usb.trial_ended")
        assertTrue(mixer.calls.isEmpty())
    }

    @Test
    fun `a failed mixer query is reported separately from a missing mixer`() {
        connect(dac)
        mixer.profiles[dac.id] = Result.failure(IllegalStateException("private"))
        start()
        loadTrack()

        requestStrict()

        assertFailed(UsbOutputFailure.MIXER_QUERY_FAILED, "strict_usb.mixer_query_failed")
    }

    @Test
    fun `a phone offering no bit perfect mixer names the phone as the cause`() {
        connect(dac)
        mixer.profiles[dac.id] = Result.success(listOf(MIXER_48K_16.copy(bitPerfect = false)))
        start()
        loadTrack()

        requestStrict()

        assertFailed(UsbOutputFailure.NO_MATCHING_MIXER_ATTRIBUTE, NO_BIT_PERFECT_MIXER_CODE)
    }

    @Test
    fun `a bit perfect mixer in another format does not match`() {
        connectDacWithMixer()
        start()
        loadTrack(sampleRateHz = 96_000)

        requestStrict()

        assertFailed(UsbOutputFailure.NO_MATCHING_MIXER_ATTRIBUTE, "strict_usb.no_matching_mixer_attribute")
    }

    // Failing closed while creating the output

    @Test
    fun `an output in a format other than the mixer fails closed before the mixer is changed`() {
        prepareStrictPlan()

        val output = strictOutput(outputConfig(sampleRateHz = 44_100))

        assertFailed(UsbOutputFailure.AUDIO_TRACK_FORMAT_MISMATCH, "strict_usb.audio_track_format_mismatch")
        assertFalse(mixer.calls.any { it.startsWith("setPreferred") })
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `a mixer request android rejected fails closed without clearing another app's preference`() {
        prepareStrictPlan()
        mixer.setResult = Result.success(false)

        val output = strictOutput()

        assertFailed(UsbOutputFailure.MIXER_REQUEST_REJECTED, "strict_usb.mixer_request_rejected")
        assertFalse(mixer.calls.any { it.startsWith("clearPreferred") })
        assertEquals(0, status().mixerCleanup.pendingCount)
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `a mixer request that threw is cleared because it may have applied`() {
        prepareStrictPlan()
        mixer.setResult = Result.failure(IllegalStateException("private"))

        val output = strictOutput()

        assertFailed(UsbOutputFailure.MIXER_REQUEST_REJECTED, "strict_usb.mixer_request_failed")
        assertTrue("clearPreferred(11)" in mixer.calls)
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `a mixer readback mismatch fails closed and clears the request`() {
        prepareStrictPlan()
        mixer.readbackMatches = false

        val output = strictOutput()

        assertFailed(UsbOutputFailure.MIXER_READBACK_MISMATCH, "strict_usb.mixer_readback_mismatch")
        assertTrue("clearPreferred(11)" in mixer.calls)
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `a rejected preferred device fails closed`() {
        prepareStrictPlan()
        RoutableAudioTrackShadow.accepted = false

        val output = strictOutput()

        assertFailed(UsbOutputFailure.PREFERRED_DEVICE_REJECTED, "strict_usb.preferred_device_rejected")
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `a routing listener that cannot be registered fails closed`() {
        prepareStrictPlan()
        RoutableAudioTrackShadow.rejectCoordinatorListener = true

        val output = strictOutput()

        assertFailed(UsbOutputFailure.PLATFORM_ERROR, "strict_usb.routing_listener_registration_failed")
        assertEquals("clearPreferred(11)", mixer.calls.last())
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `an output that cannot be created fails closed and clears the mixer`() {
        prepareStrictPlan()

        assertThrows(Exception::class.java) {
            coordinator.getAudioOutput(outputConfig(bufferSizeBytes = 0))
        }

        assertFailed(UsbOutputFailure.PLATFORM_ERROR, "strict_usb.audio_output_creation_failed")
        assertEquals("clearPreferred(11)", mixer.calls.last())
    }

    @Test
    fun `a pending output that cannot be created fails closed`() {
        connectDacWithMixer()
        start()
        loadTrack()
        coordinator.requestMode(UsbOutputMode.STRICT_BIT_PERFECT)

        assertThrows(Exception::class.java) {
            coordinator.getAudioOutput(outputConfig(bufferSizeBytes = 0))
        }

        assertFailed(UsbOutputFailure.PLATFORM_ERROR, "strict_usb.pending_audio_output_creation_failed")
    }

    @Test
    fun `a failure keeps its reason when its muted output cannot be created`() {
        prepareStrictPlan()
        mixer.setResult = Result.success(false)

        assertThrows(Exception::class.java) {
            coordinator.getAudioOutput(outputConfig(bufferSizeBytes = 0))
        }

        assertFailed(UsbOutputFailure.MIXER_REQUEST_REJECTED, "strict_usb.mixer_request_rejected")
    }

    @Test
    fun `an output without a plan stays gated and leaves the mixer alone`() {
        connectDacWithMixer()
        start()
        loadTrack()
        coordinator.requestMode(UsbOutputMode.STRICT_BIT_PERFECT)

        val output = strictOutput()

        assertFalse(output.write(pcm(), 1, 0))
        assertTrue(mixer.calls.isEmpty())
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
    }

    // Route verification

    @Test
    fun `an output routed to another device fails closed`() {
        prepareStrictPlan()
        val output = strictOutput()
        idle()

        ShadowAudioTrack.setRoutedDevice(audioDevice(id = 99))
        idle()

        assertFailed(UsbOutputFailure.ROUTE_MISMATCH, "strict_usb.route_mismatch")
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `a route that never appears fails closed after the verification timeout`() {
        prepareStrictPlan()
        strictOutput()
        idle()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_999))
        assertEquals(UsbOutputPhase.APPLYING, status().phase)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFailed(UsbOutputFailure.ROUTE_UNAVAILABLE, "strict_usb.route_unavailable")
    }

    @Test
    fun `losing the route while active fails closed as a route change`() {
        val output = reachActive()

        ShadowAudioTrack.setRoutedDevice(null)
        idle()

        assertFailedAfterActive(
            UsbOutputFailure.ROUTE_UNAVAILABLE,
            "strict_usb.route_unavailable",
            UsbOutputFailureOrigin.ROUTE_CHANGE,
        )
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `a route moving to another device while active fails closed as a route change`() {
        val output = reachActive()

        ShadowAudioTrack.setRoutedDevice(audioDevice(id = 99))
        idle()

        assertFailedAfterActive(
            UsbOutputFailure.ROUTE_MISMATCH,
            "strict_usb.route_mismatch",
            UsbOutputFailureOrigin.ROUTE_CHANGE,
        )
        assertFalse(output.write(pcm(), 1, 0))
    }

    // Pausing and reopening the gate

    @Test
    fun `pausing closes the gate and revokes active until the output plays again`() {
        val output = reachActive()

        player.pause()
        idle()
        assertStatus(UsbOutputPhase.APPLYING, "strict_usb.configured_not_playing")
        assertFalse(output.write(pcm(), 1, 0))

        player.play()
        idle()
        assertStatus(UsbOutputPhase.APPLYING, "strict_usb.waiting_for_output_play")
        assertFalse(output.write(pcm(), 1, 0))

        output.play()
        idle()
        assertStatus(UsbOutputPhase.ACTIVE, "strict_usb.active")
        assertTrue(output.write(pcm(), 1, 0))
    }

    @Test
    fun `a renderer pause reverifies the mixer before reopening the gate`() {
        val output = reachActive()
        output.pause()
        mixer.readbackMatches = false

        output.play()
        idle()

        assertFailedAfterActive(
            UsbOutputFailure.MIXER_READBACK_MISMATCH,
            "strict_usb.active_mixer_readback_mismatch",
            UsbOutputFailureOrigin.USER_PLAYBACK,
        )
        assertFalse(output.write(pcm(), 1, 0))
    }

    // Non-neutral processing

    @Test
    fun `software volume while active fails closed`() {
        val output = reachActive()

        player.volume = 0.5f
        idle()

        assertProcessingFailure("strict_usb.software_volume_changed")
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `changed playback speed while active fails closed`() {
        val output = reachActive()

        player.playbackParameters = PlaybackParameters(1.5f)
        idle()

        assertProcessingFailure("strict_usb.playback_parameters_changed")
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `skip silence while active fails closed`() {
        val output = reachActive()

        player.skipSilenceEnabled = true
        idle()

        assertProcessingFailure("strict_usb.skip_silence_enabled")
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `a non neutral volume on the output fails closed`() {
        val output = reachActive()

        output.setVolume(0.2f)
        idle()

        assertProcessingFailure("strict_usb.output_volume_not_neutral")
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `processing seen before its callback arrives blocks reactivation`() {
        val output = reachActive()
        player.removeListener(coordinator)
        player.volume = 0.5f

        output.pause()
        output.play()
        idle()

        assertProcessingFailure("strict_usb.processing_not_neutral")
        assertFalse(output.write(pcm(), 1, 0))
    }

    // Device changes

    @Test
    fun `unplugging the selected device fails closed as a route change`() {
        val output = reachActive()

        shadowOf(audioManager).removeOutputDevice(dac, true)
        idle()

        assertFailedAfterActive(
            UsbOutputFailure.DEVICE_DISCONNECTED,
            "strict_usb.device_disconnected",
            UsbOutputFailureOrigin.ROUTE_CHANGE,
        )
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `removing another device leaves active output running`() {
        val other = audioDevice(id = 20)
        connect(other)
        val output = reachActive()

        shadowOf(audioManager).removeOutputDevice(other, true)
        idle()

        assertStatus(UsbOutputPhase.ACTIVE, "strict_usb.active")
        assertTrue(output.write(pcm(), 1, 0))
    }

    @Test
    fun `plugging in a device retries a failed strict attempt`() {
        start()
        loadTrack()
        requestStrict()
        assertEquals(UsbOutputFailure.NO_USB_AUDIO_DEVICE, status().failure)

        mixer.profiles[dac.id] = Result.success(listOf(MIXER_48K_16))
        shadowOf(audioManager).addOutputDevice(dac, true)
        idle()

        assertStatus(UsbOutputPhase.AVAILABLE, "strict_usb.bit_perfect_available")
    }

    // Track changes and stale work

    @Test
    fun `a track change closes the previous output for good`() {
        val previous = reachActive()

        player.setMediaItem(mediaItem(mediaId = "next"))
        idle()
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
        assertFalse(previous.write(pcm(), 1, 0))

        previous.play()
        idle()
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
        assertFalse(previous.write(pcm(), 1, 0))

        val next = strictOutput()
        idle()
        next.play()
        idle()
        assertStatus(UsbOutputPhase.ACTIVE, "strict_usb.active")
        assertTrue(next.write(pcm(), 1, 0))
        assertFalse(previous.write(pcm(), 1, 0))
    }

    @Test
    fun `a failed track change while playing is a track transition failure`() {
        reachActive()

        player.setMediaItem(mediaItem(sampleRateHz = 96_000, mediaId = "next"))
        idle()

        assertFailedAfterActive(
            UsbOutputFailure.NO_MATCHING_MIXER_ATTRIBUTE,
            "strict_usb.no_matching_mixer_attribute",
            UsbOutputFailureOrigin.TRACK_TRANSITION,
        )
    }

    @Test
    fun `a failed queue change while paused is a queue restore failure`() {
        prepareStrictPlan(play = false)

        player.setMediaItem(mediaItem(sampleRateHz = 96_000, mediaId = "next"))
        idle()

        assertFailed(
            UsbOutputFailure.NO_MATCHING_MIXER_ATTRIBUTE,
            "strict_usb.no_matching_mixer_attribute",
            UsbOutputFailureOrigin.QUEUE_RESTORE,
        )
    }

    @Test
    fun `leaving strict mode while the mixer request is in flight clears it and stays system`() {
        prepareStrictPlan()
        mixer.beforeSetResult = { coordinator.requestMode(UsbOutputMode.SYSTEM) }

        val output = strictOutput()
        idle()

        assertEquals(UsbOutputPhase.SYSTEM, status().phase)
        assertEquals(UsbOutputMode.SYSTEM, status().mode)
        assertEquals("clearPreferred(11)", mixer.calls.last())
        assertEquals(0, status().mixerCleanup.pendingCount)
        assertFalse(output.write(pcm(), 1, 0))
    }

    @Test
    fun `a reconfiguration while the mixer request is in flight discards that request`() {
        prepareStrictPlan()
        mixer.beforeSetResult = { coordinator.requestMode(UsbOutputMode.STRICT_BIT_PERFECT) }

        val stale = strictOutput()

        assertEquals("clearPreferred(11)", mixer.calls.last())
        assertFalse(stale.write(pcm(), 1, 0))
        assertTrue(published.none { it.phase == UsbOutputPhase.FAILED })
    }

    @Test
    fun `a superseded output cannot fail the current attempt`() {
        connectDacWithMixer()
        start()
        loadTrack()
        coordinator.requestMode(UsbOutputMode.STRICT_BIT_PERFECT)
        val superseded = strictOutput()

        coordinator.requestMode(UsbOutputMode.STRICT_BIT_PERFECT)
        superseded.setVolume(0.2f)
        idle()

        assertStatus(UsbOutputPhase.AVAILABLE, "strict_usb.bit_perfect_available")
        assertTrue(published.none { it.phase == UsbOutputPhase.FAILED })
    }

    @Test
    fun `an output that is still current can fail its attempt`() {
        connectDacWithMixer()
        start()
        loadTrack()
        coordinator.requestMode(UsbOutputMode.STRICT_BIT_PERFECT)
        val pending = strictOutput()

        pending.setVolume(0.2f)
        idle()

        assertProcessingFailure("strict_usb.output_volume_not_neutral")
    }

    @Test
    fun `a failure from the playback thread takes effect on the main thread`() {
        prepareStrictPlan()
        mixer.setResult = Result.success(false)

        val output = onPlaybackThread { strictOutput() }
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
        assertTrue(playerCalls.isEmpty())

        idle()
        assertFailed(UsbOutputFailure.MIXER_REQUEST_REJECTED, "strict_usb.mixer_request_rejected")
        assertEquals(listOf("pause", "stop", "setPreferredAudioDevice(null)"), playerCalls)
        assertFalse(output.write(pcm(), 1, 0))
    }

    // Keeping the request to play

    @Test
    fun `a play command during startup configuration resumes after the rebuild`() {
        preferences.edit { putString(MODE_KEY, UsbOutputMode.STRICT_BIT_PERFECT.name) }
        connectDacWithMixer()
        realPlayer.setMediaItem(mediaItem())
        start()

        // #52: a media button restores the queue and plays before the posted configuration runs.
        player.play()
        idle()
        assertFalse(realPlayer.playWhenReady)
        assertTrue("stop" in playerCalls)

        strictOutput()
        idle()
        assertTrue(realPlayer.playWhenReady)
    }

    @Test
    fun `a user pause during reconfiguration cancels the pending resume`() {
        prepareStrictPlan(play = false)
        player.play()
        player.play()
        player.pause()
        playerCalls.clear()

        idle()

        assertStatus(UsbOutputPhase.AVAILABLE, "strict_usb.bit_perfect_available")
        assertFalse(realPlayer.playWhenReady)
        assertFalse("prepare" in playerCalls)
    }

    @Test
    fun `the internal pause during reconfiguration keeps the pending resume`() {
        prepareStrictPlan()
        assertFalse(realPlayer.playWhenReady)

        strictOutput()
        idle()

        assertTrue(realPlayer.playWhenReady)
    }

    @Test
    fun `a failed attempt does not resume playback later`() {
        prepareStrictPlan()
        mixer.setResult = Result.success(false)

        strictOutput()
        idle()

        assertEquals(UsbOutputFailure.MIXER_REQUEST_REJECTED, status().failure)
        assertFalse(realPlayer.playWhenReady)
    }

    // Returning to system output

    @Test
    fun `returning to system clears the mixer rebuilds and keeps playing`() {
        val output = reachActive()
        playerCalls.clear()

        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()

        assertEquals(UsbOutputPhase.SYSTEM, status().phase)
        assertEquals(UsbOutputMode.SYSTEM, status().mode)
        assertEquals("clearPreferred(11)", mixer.calls.last())
        assertEquals(
            listOf("setPreferredAudioDevice(null)", "pause", "stop", "prepare", "play"),
            playerCalls,
        )
        assertTrue(realPlayer.playWhenReady)
        assertEquals(UsbOutputMode.SYSTEM.name, preferences.getString(MODE_KEY, null))
        assertFalse(output.write(pcm(), 1, 0))
        assertFalse(coordinator.getAudioOutput(outputConfig()) is StrictGatedAudioOutput)
    }

    @Test
    fun `selecting system again keeps ordinary playback when only a recovery record remains`() {
        start(direct = direct)
        loadTrack()
        player.play()
        direct.recoveryPending = true
        idle()
        assertEquals(UsbOutputMode.SYSTEM, status().mode)
        playerCalls.clear()

        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()

        assertStatus(UsbOutputPhase.SYSTEM, "system_audio_route")
        assertTrue(player.playWhenReady)
        assertFalse("stop" in playerCalls || "prepare" in playerCalls)
        assertTrue(direct.recoveryPending)
    }

    @Test
    fun `switching from strict still requires recovery after a failed session is gone`() {
        start(direct = direct)
        loadTrack()
        requestStrict()
        direct.recoveryPending = true
        playerCalls.clear()

        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()

        assertStatus(UsbOutputPhase.FAILED, "direct_usb.usb_transfer_failed")
        assertEquals(UsbOutputMode.STRICT_BIT_PERFECT, status().mode)
        assertFalse("prepare" in playerCalls || "play" in playerCalls)
        assertTrue(direct.recoveryPending)
    }

    @Test
    fun `returning to system while paused stays paused`() {
        prepareStrictPlan(play = false)
        playerCalls.clear()

        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()

        assertEquals(UsbOutputPhase.SYSTEM, status().phase)
        assertFalse("play" in playerCalls)
        assertFalse(realPlayer.playWhenReady)
    }

    @Test
    fun `a mixer that cannot be cleared keeps strict mode and fails closed`() {
        reachActive()
        mixer.clearResult = Result.success(false)

        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()

        assertFailedAfterActive(
            UsbOutputFailure.MIXER_CLEAR_FAILED,
            "strict_usb.mixer_clear_failed",
            UsbOutputFailureOrigin.USER_MODE_CHANGE,
        )
        assertEquals(UsbOutputMode.STRICT_BIT_PERFECT, status().mode)
        assertEquals(1, status().mixerCleanup.pendingCount)
        assertEquals(UsbOutputMode.STRICT_BIT_PERFECT.name, preferences.getString(MODE_KEY, null))
        assertFalse(realPlayer.playWhenReady)

        mixer.clearResult = Result.success(true)
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()

        assertEquals(UsbOutputPhase.SYSTEM, status().phase)
        assertEquals(0, status().mixerCleanup.pendingCount)
        assertEquals(UsbOutputMode.SYSTEM.name, preferences.getString(MODE_KEY, null))
    }

    @Test
    fun `a failed switch to system output is the user's whatever attempt came before it`() {
        // The last attempt is a queue restore, whose failures do not interrupt with a dialog.
        prepareStrictPlan(play = false)
        player.setMediaItem(mediaItem(mediaId = "next"))
        idle()
        strictOutput()
        idle()
        mixer.clearResult = Result.success(false)

        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()

        assertFailedAfterActive(
            UsbOutputFailure.MIXER_CLEAR_FAILED,
            "strict_usb.mixer_clear_failed",
            UsbOutputFailureOrigin.USER_MODE_CHANGE,
        )
        val firstFailure = status()

        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()

        // The dialog keys on the generation, so a failed retry shows it again.
        assertEquals(UsbOutputFailure.MIXER_CLEAR_FAILED, status().failure)
        assertEquals(UsbOutputFailureOrigin.USER_MODE_CHANGE, status().failureOrigin)
        assertTrue(status().generation > firstFailure.generation)
    }

    @Test
    fun `a mixer clear that threw reports its type and no message`() {
        reachActive()
        mixer.clearResult = Result.failure(SecurityException("device 11 private"))

        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()

        assertEquals(UsbOutputFailure.MIXER_CLEAR_FAILED, status().failure)
        assertEquals(setOf("java.lang.SecurityException"), status().mixerCleanup.exceptionTypes)
        assertEquals(
            setOf(MixerCleanupExceptionReason.PERMISSION_DENIED),
            status().mixerCleanup.exceptionReasons,
        )
    }

    @Test
    fun `a mixer that cannot be cleared stops a track change`() {
        reachActive()
        mixer.clearResult = Result.success(false)

        player.setMediaItem(mediaItem(mediaId = "next"))
        idle()

        assertFailedAfterActive(
            UsbOutputFailure.MIXER_CLEAR_FAILED,
            "strict_usb.mixer_clear_failed",
            UsbOutputFailureOrigin.TRACK_TRANSITION,
        )
        assertFalse(realPlayer.playWhenReady)
    }

    // Closing

    @Test
    fun `closing strict output clears the mixer and reports a service stop`() {
        val output = reachActive()

        coordinator.close()

        assertFailedAfterActive(
            UsbOutputFailure.SERVICE_STOPPED,
            "strict_usb.service_stopped",
            UsbOutputFailureOrigin.SERVICE_STOP,
        )
        assertEquals("clearPreferred(11)", mixer.calls.last())
        assertFalse(output.write(pcm(), 1, 0))
        assertTrue("removeListener" in playerCalls)
        val callbacks = ReflectionHelpers.getField<Set<*>>(shadowOf(audioManager), "audioDeviceCallbacks")
        assertFalse(callbacks.contains(ReflectionHelpers.getField<Any>(coordinator, "deviceCallback")))
    }

    @Test
    fun `closing system output publishes nothing`() {
        start()
        val before = status()

        coordinator.close()

        assertSame(before, status())
    }

    @Test
    fun `nothing happens after close`() {
        val output = reachActive()
        coordinator.close()
        val closed = status()

        coordinator.close()
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        output.setVolume(0.2f)
        player.volume = 0.5f
        shadowOf(audioManager).removeOutputDevice(dac, true)
        idle()

        assertSame(closed, status())
    }

    // Hardware identity

    @Test
    fun `one usb audio device publishes its identity`() {
        attachUsbHardware("/dev/bus/usb/001/002", vendorId = 0x1234, productId = 0x5678)

        reachActive()

        assertEquals(UsbHardwareIdentity(0x1234, 0x5678, "1.10"), status().hardwareIdentity)
    }

    @Test
    fun `several usb audio devices publish no identity`() {
        attachUsbHardware("/dev/bus/usb/001/002", vendorId = 0x1234, productId = 0x5678)
        attachUsbHardware("/dev/bus/usb/001/003", vendorId = 0x4321, productId = 0x8765)

        reachActive()

        assertNull(status().hardwareIdentity)
    }

    // Vesqen's own USB output (M7-4)

    @Test
    fun `an expired trial blocks an explicit resume on a retained session`() {
        reachDirectActive()
        player.pause()
        idle()
        strictAllowed = false
        player.play()
        idle()
        assertStatus(UsbOutputPhase.FAILED, "strict_usb.trial_ended")
    }

    @Test
    fun `atomic queue replacement waits for its own output delivery`() {
        reachDirectActive()
        player.setMediaItem(mediaItem(mediaId = "replacement"))
        idle()
        assertFalse("Replacement was ACTIVE before its output opened: " + status(), status().phase == UsbOutputPhase.ACTIVE)
    }

    @Test
    fun `stopping a retained queue revokes ACTIVE`() {
        reachDirectActive()
        player.stop()
        idle()
        assertEquals(androidx.media3.common.Player.STATE_IDLE, player.playbackState)
        assertFalse("Stopped stream still ACTIVE: " + status(), status().phase == UsbOutputPhase.ACTIVE)
    }

    @Test
    fun `system playback cannot prepare before DAC release`() {
        reachDirectActive()
        direct.deferTakeover = true // the existing fake also defers release acknowledgements in this mode
        direct.recoveryPending = true
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()
        assertFalse("System playback restarted while device release is unconfirmed: " + playerCalls,
            playerCalls.contains("prepare") && player.playWhenReady)
    }

    @Test
    fun `a queued old source failure cannot stop a replacement track`() {
        reachDirectActive(listOf(mediaItem(mediaId = "old")))
        // An old provider reports a data-dependent error from the playback thread.
        val oldListener = checkNotNull(direct.listener)
        val oldPeriod = player.currentTimeline.getUidOfPeriod(player.currentPeriodIndex)
        val playbackThread = Thread {
            oldListener.onFailed(DirectUsbFailure(DirectUsbFailure.Reason.PCM_NOT_EXACT, "old item"), periodUid = oldPeriod)
        }
        playbackThread.start()
        playbackThread.join()
        // A main-thread play-selection already in progress replaces the old timeline before draining callbacks.
        player.setMediaItem(mediaItem(mediaId = "fresh"))
        player.prepare()
        player.play()
        idle()
        assertEquals("fresh", player.currentMediaItem?.mediaId)
        assertFalse("Old PCM failure stopped fresh selection: " + status(), status().phase == UsbOutputPhase.FAILED)
    }

    @Test
    fun `opening an output without delivery cannot activate strict playback`() {
        connect(dac)
        start(direct = direct)
        loadTrack()
        requestStrict()
        player.play()
        idle()
        checkNotNull(direct.listener).onOutputOpened(player.currentTimeline.getUidOfPeriod(player.currentPeriodIndex))
        idle()
        assertEquals(0L, direct.readings().deliveredFrames)
        assertFalse("Zero delivered media frames still activated strict output: " + status(), status().phase == UsbOutputPhase.ACTIVE)
    }

    @Test
    fun `system mode selected during takeover waits even before streaming starts`() {
        beginHostOnlyTakeover()
        playerCalls.clear()
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()
        assertFalse(playerCalls.contains("prepare"))
        checkNotNull(direct.pendingRelease)(true)
        idle()
        assertEquals(UsbOutputPhase.SYSTEM, status().phase)
    }

    @Test
    fun `system output waits for release then honors the latest pause intent`() {
        reachDirectActive()
        direct.deferTakeover = true
        direct.recoveryPending = true
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
        assertFalse(playerCalls.contains("prepare"))
        coordinator.requestMode(UsbOutputMode.SYSTEM) // repeated selection must keep waiting
        player.play()
        player.pause()
        val done = checkNotNull(direct.pendingRelease)
        direct.recoveryPending = false
        done(true)
        idle()
        assertEquals(UsbOutputPhase.SYSTEM, status().phase)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `the Android route returning before USB cleanup acknowledges cannot strand system playback`() {
        reachDirectActive()
        direct.deferTakeover = true
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        val released = checkNotNull(direct.pendingRelease)
        shadowOf(audioManager).addOutputDevice(audioDevice(id = 12), true)
        idle()
        released(true)
        idle()
        assertStatus(UsbOutputPhase.SYSTEM, "system_audio_route")
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `selecting system during an official handoff shares the pending device release`() {
        beginHostOnlyTakeover()
        mixer.profiles[dac.id] = Result.success(listOf(MIXER_48K_16))
        shadowOf(audioManager).addOutputDevice(dac, true)
        idle()
        val released = checkNotNull(direct.pendingRelease)
        direct.recoveryPending = true
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        assertStatus(UsbOutputPhase.APPLYING, "direct_usb.returning_to_system")
        assertEquals(1, direct.calls.count { it == "release" })
        direct.recoveryPending = false
        released(true)
        idle()
        assertStatus(UsbOutputPhase.SYSTEM, "system_audio_route")
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `a first buffer error is applied after the route posts output creation`() {
        reachDirectActive()
        val period = player.currentTimeline.getUidOfPeriod(player.currentPeriodIndex)
        val token = Any()
        val listener = checkNotNull(direct.listener)
        Handler(Looper.getMainLooper()).post { listener.onOutputOpened(period, token) }
        Thread {
            listener.onFailed(DirectUsbFailure(DirectUsbFailure.Reason.PCM_NOT_EXACT, "first buffer"), periodUid = period, output = token)
        }.also { it.start(); it.join() }
        idle()
        assertEquals("direct_usb.pcm_not_exact", status().decisionCode)
        assertEquals(UsbOutputPhase.FAILED, status().phase)
    }

    @Test
    fun `a system request can join cleanup already started after a failure`() {
        val releases = PendingUsbReleases()
        val results = mutableListOf<Boolean>()
        releases.begin() // The failed session was released before the user selected system.
        releases.await(results::add)
        assertTrue(releases.pending)
        assertTrue(results.isEmpty())
        releases.complete(true)
        assertFalse(releases.pending)
        assertEquals(listOf(true), results)
    }

    @Test
    fun `all outstanding sessions must finish before a waiting release is acknowledged`() {
        val releases = PendingUsbReleases()
        val results = mutableListOf<Boolean>()
        releases.begin()
        releases.begin()
        releases.await(results::add)
        releases.complete(false)
        assertTrue(results.isEmpty())
        releases.complete(true)
        assertEquals(listOf(false), results)
        releases.begin()
        releases.await(results::add)
        releases.complete(true)
        assertEquals(listOf(false, true), results)
    }

    @Test
    fun `failed release prevents system output`() {
        reachDirectActive()
        direct.deferTakeover = true
        direct.recoveryPending = true
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()
        checkNotNull(direct.pendingRelease)(false)
        idle()
        assertFailedAfterActive(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.usb_transfer_failed", UsbOutputFailureOrigin.USER_MODE_CHANGE)
        assertFalse(playerCalls.contains("prepare"))
    }

    @Test
    fun `a system release callback cannot resume after service closure`() {
        reachDirectActive()
        direct.deferTakeover = true
        coordinator.requestMode(UsbOutputMode.SYSTEM)
        val done = checkNotNull(direct.pendingRelease)
        coordinator.close()
        playerCalls.clear()
        done(true)
        idle()
        assertFalse(playerCalls.contains("prepare"))
        assertFalse(playerCalls.contains("play"))
    }

    @Test
    fun `seeking invalidates the previous output before its replacement opens`() {
        reachDirectActive()
        val uid = player.currentTimeline.getUidOfPeriod(player.currentPeriodIndex)
        player.seekTo(500)
        checkNotNull(direct.listener).onOutputActive(uid, active = true)
        checkNotNull(direct.listener).onFailed(DirectUsbFailure(DirectUsbFailure.Reason.PCM_NOT_EXACT, "old output"), periodUid = uid, output = uid)
        idle()
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
        notifyDeliveredOutput()
        idle()
        assertEquals(UsbOutputPhase.ACTIVE, status().phase)
    }

    @Test
    fun `a retired output cannot activate or fail a successor in the same period`() {
        reachDirectActive()
        val uid = player.currentTimeline.getUidOfPeriod(player.currentPeriodIndex)
        val obsolete = Any()
        val current = Any()
        checkNotNull(direct.listener).onOutputOpened(uid, obsolete)
        checkNotNull(direct.listener).onOutputOpened(uid, current)
        checkNotNull(direct.listener).onOutputActive(uid, obsolete, true)
        checkNotNull(direct.listener).onFailed(DirectUsbFailure(DirectUsbFailure.Reason.PCM_NOT_EXACT, "old output"), periodUid = uid, output = obsolete)
        idle()
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
        checkNotNull(direct.listener).onOutputActive(uid, current, true)
        idle()
        assertEquals(UsbOutputPhase.ACTIVE, status().phase)
        checkNotNull(direct.listener).onOutputActive(uid, obsolete, false)
        idle()
        assertEquals(UsbOutputPhase.ACTIVE, status().phase)
    }

    private fun notifyDeliveredOutput() {
        val uid = player.currentTimeline.getUidOfPeriod(player.currentPeriodIndex)
        checkNotNull(direct.listener).onOutputOpened(uid)
        checkNotNull(direct.listener).onOutputActive(uid, active = true)
    }

    private val direct = FakeDirectRoute()

    private fun reachDirectActive(items: List<MediaItem> = listOf(mediaItem())) {
        connect(dac)
        start(direct = direct)
        player.setMediaItems(items)
        player.prepare()
        idle()
        requestStrict()
        player.play()
        idle()
        notifyDeliveredOutput()
        idle()
        assertStatus(UsbOutputPhase.ACTIVE, "direct_usb.active")
        playerCalls.clear()
        direct.calls.clear()
    }

    @Test
    fun `without a bit-perfect mixer strict mode inspects the own path and takes over only to play`() {
        connect(dac)
        start(direct = direct)
        loadTrack()

        requestStrict()
        assertStatus(UsbOutputPhase.AVAILABLE, "direct_usb.available")
        assertEquals("JBL Flip 7", status().deviceName)
        assertEquals(listOf("inspect"), direct.calls)
        assertTrue(playerCalls.isEmpty())

        player.play()
        idle()
        assertStatus(UsbOutputPhase.APPLYING, "direct_usb.configured_not_playing")
        assertEquals(listOf("inspect", "inspect", "takeOver"), direct.calls)
        assertEquals(listOf("stop", "prepare", "play"), playerCalls.takeLast(3))
        assertTrue("The player plays on, now through the strict sink", player.playWhenReady)

        notifyDeliveredOutput()
        idle()
        assertStatus(UsbOutputPhase.ACTIVE, "direct_usb.active")
    }

    @Test
    fun `a DAC that USB Host lists without an Android audio route goes to the own path`() {
        // #293: no AudioManager route used to mean no device, so the own path never looked.
        start(direct = direct)
        loadTrack()
        direct.usbAudioDevicePresent = true

        requestStrict()

        assertStatus(UsbOutputPhase.AVAILABLE, "direct_usb.available")
        assertEquals(listOf("inspect"), direct.calls)
    }

    @Test
    fun `with no USB audio device at all the reason stays that none is connected`() {
        start(direct = direct)
        loadTrack()

        requestStrict()

        assertFailed(UsbOutputFailure.NO_USB_AUDIO_DEVICE, "strict_usb.no_usb_audio_device")
        assertTrue(direct.calls.none { it == "inspect" })
    }

    @Test
    fun `a bit-perfect mixer keeps the official path`() {
        connectDacWithMixer()
        start(direct = direct)
        loadTrack()

        requestStrict()

        assertStatus(UsbOutputPhase.AVAILABLE, "strict_usb.bit_perfect_available")
        assertTrue(direct.calls.isEmpty())
    }

    @Test
    fun `on service start a missing USB permission is reported without a dialog`() {
        connect(dac)
        preferences.edit(commit = true) { putString(MODE_KEY, UsbOutputMode.STRICT_BIT_PERFECT.name) }
        player.setMediaItem(mediaItem())
        player.prepare()
        direct.inspection = DirectUsbInspection.PermissionNeeded("JBL Flip 7")

        start(direct = direct)
        idle()

        assertFailed(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.permission_required")
        assertEquals(listOf("inspect", "release"), direct.calls)
        assertEquals("JBL Flip 7", status().deviceName)
    }

    @Test
    fun `the user's own action asks for USB permission, and a refusal fails closed`() {
        connect(dac)
        start(direct = direct)
        loadTrack()
        direct.inspection = DirectUsbInspection.PermissionNeeded("JBL Flip 7")
        direct.permissionGranted = false

        requestStrict()

        assertFailed(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.permission_denied")
        assertEquals(listOf("inspect", "requestPermission", "release"), direct.calls)
    }

    @Test
    fun `a granted USB permission continues the attempt`() {
        connect(dac)
        start(direct = direct)
        loadTrack()
        direct.inspection = DirectUsbInspection.PermissionNeeded("JBL Flip 7")

        requestStrict()

        assertStatus(UsbOutputPhase.AVAILABLE, "direct_usb.available")
        assertEquals(listOf("inspect", "requestPermission", "inspect"), direct.calls)
    }

    @Test
    fun `a lossy track on the own path is named before the DAC is taken over`() {
        // 4d: an MP3 has no bit depth, so this used to read as a format Vesqen could not determine.
        connect(dac)
        start(direct = direct)
        player.setMediaItem(mediaItem(bitDepth = 0, mimeType = "audio/mpeg", codec = "MP3"))
        player.prepare()
        idle()

        requestStrict()

        assertFailed(UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED, "direct_usb.source_not_lossless")
        assertFalse(direct.calls.contains("inspect"))
        assertEquals("MP3", status().directDetails?.sourceCodec)
    }

    @Test
    fun `a device the own output cannot drive is named, not reported as a format problem`() {
        connect(dac)
        start(direct = direct)
        loadTrack()
        direct.inspection = DirectUsbInspection.Refused("uac2_not_supported", deviceName = "Moondrop Echo-A")

        requestStrict()

        assertFailed(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.uac2_not_supported")
        assertEquals("Moondrop Echo-A", status().deviceName)
    }

    @Test
    fun `a format refusal keeps what the DAC offers for the reason`() {
        connect(dac)
        start(direct = direct)
        loadTrack(sampleRateHz = 44_100)
        val details = DirectUsbDetails(sourceBitDepth = 16, deviceSampleRatesHz = listOf(48_000..48_000))
        direct.inspection = DirectUsbInspection.Refused(
            "rate_unsupported",
            formatRefused = true,
            deviceName = "JBL Flip 7",
            details = details,
        )

        requestStrict()

        assertFailed(UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED, "direct_usb.rate_unsupported")
        assertEquals("JBL Flip 7", status().deviceName)
        assertEquals(details, status().directDetails)
    }

    @Test
    fun `a gate refusal names the item the gate checked`() {
        reachDirectActive()

        val details = DirectUsbDetails(sourceSampleRateHz = 44_100, sourceBitDepth = 16, deviceSampleRatesHz = listOf(48_000..48_000))
        checkNotNull(direct.listener).onFailed(
            DirectUsbFailure(DirectUsbFailure.Reason.NO_MATCHING_DIRECT_FORMAT, "the DAC's setting is 48000 Hz"),
            details,
        )
        idle()

        assertFailedAfterActive(UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED, "direct_usb.no_matching_direct_format", null)
        assertEquals(details, status().directDetails)
    }

    @Test
    fun `the own output's status carries its wire format and that this build has it`() {
        reachDirectActive()

        assertEquals(48_000, status().directDetails?.wireSampleRateHz)
        assertEquals(24, status().directDetails?.wireBitDepth)
        assertTrue(status().directUsbAvailable)
    }

    @Test
    fun `without the own output the status says strict needs the official path`() {
        connectDacWithMixer()
        start()
        requestStrict()

        assertFalse(status().directUsbAvailable)
    }

    @Test
    fun `a failed takeover fails closed and gives the device back`() {
        connect(dac)
        start(direct = direct)
        loadTrack()
        direct.takeoverFailure = DirectUsbFailure(DirectUsbFailure.Reason.VOLUME_CONTROL_FAILED, "no readback")
        requestStrict()

        player.play()
        idle()

        assertFailed(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.volume_control_failed")
        assertEquals("release", direct.calls.last())
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `the route coming back after a failure starts nothing until the device is plugged in again`() {
        connect(dac)
        start(direct = direct)
        loadTrack()
        direct.takeoverFailure = DirectUsbFailure(DirectUsbFailure.Reason.INTERFACE_CLAIM_FAILED, "busy")
        requestStrict()
        player.play()
        idle()
        direct.calls.clear()
        direct.takeoverFailure = null

        connect(audioDevice(id = 12))
        idle()
        assertTrue("Android shows the released device again", direct.calls.isEmpty())

        attach(usbDevice(UsbConstants.USB_CLASS_AUDIO))
        assertEquals(listOf("inspect"), direct.calls)
    }

    @Test
    fun `choosing strict mode again while the own output plays keeps playing on the same session`() {
        reachDirectActive()

        coordinator.requestMode(UsbOutputMode.STRICT_BIT_PERFECT)
        idle()

        assertStatus(UsbOutputPhase.ACTIVE, "direct_usb.active")
        assertEquals("The device is still held, so nothing is taken over again", listOf("inspect"), direct.calls)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `a USB device that is not audio changes nothing`() {
        reachDirectActive()
        val before = status()

        attach(usbDevice(UsbConstants.USB_CLASS_MASS_STORAGE))

        assertEquals(before, status())
        assertTrue(direct.calls.isEmpty())
        assertTrue(playerCalls.isEmpty())
    }

    @Test
    fun `another audio device attached while the own output plays changes nothing`() {
        reachDirectActive()
        val before = status()

        attach(usbDevice(UsbConstants.USB_CLASS_AUDIO))

        assertEquals(before, status())
        assertTrue(direct.calls.isEmpty())
        assertTrue(playerCalls.isEmpty())
    }

    @Test
    fun `a track transition on the own output publishes the next track's source`() {
        reachDirectActive(listOf(mediaItem(bitDepth = 16, mediaId = "first"), mediaItem(bitDepth = 24, mediaId = "second")))
        assertEquals("16-bit source", status().sourceFormat?.encoding)

        player.seekToNextMediaItem()
        idle()
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
        notifyDeliveredOutput()
        idle()

        assertEquals(UsbOutputPhase.ACTIVE, status().phase)
        assertEquals("24-bit source", status().sourceFormat?.encoding)
        assertTrue(direct.calls.isEmpty())
    }

    private fun attach(device: UsbDevice) {
        context.sendBroadcast(
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).setPackage(context.packageName).putExtra(UsbManager.EXTRA_DEVICE, device),
        )
        idle()
    }

    private fun usbDevice(deviceClass: Int): UsbDevice = Shadow.newInstanceOf(UsbDevice::class.java).also { device ->
        ReflectionHelpers.setField(device, "mName", "/dev/bus/usb/001/00$deviceClass")
        ReflectionHelpers.setField(device, "mClass", deviceClass)
        ReflectionHelpers.setField(device, "mConfigurations", arrayOf<android.hardware.usb.UsbConfiguration>())
    }

    @Test
    fun `a track transition keeps the own output`() {
        reachDirectActive(listOf(mediaItem(mediaId = "first"), mediaItem(mediaId = "second")))

        player.seekToNextMediaItem()
        idle()
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
        notifyDeliveredOutput()
        idle()

        assertFalse(playerCalls.contains("stop"))
        assertTrue(direct.calls.isEmpty())
        assertEquals(UsbOutputPhase.ACTIVE, status().phase)
    }

    @Test
    fun `an ended trial stops the next track on the own output, not the current one`() {
        // #291: the retained session skipped the only entitlement check (#119).
        reachDirectActive(listOf(mediaItem(mediaId = "first"), mediaItem(mediaId = "second")))
        strictAllowed = false
        idle()
        assertEquals("The current song plays on", UsbOutputPhase.ACTIVE, status().phase)

        player.seekToNextMediaItem()
        idle()

        assertFailedAfterActive(UsbOutputFailure.TRIAL_ENDED, "strict_usb.trial_ended", UsbOutputFailureOrigin.TRACK_TRANSITION)
        assertEquals(listOf("release"), direct.calls)
    }

    @Test
    fun `an entitled next track keeps the own output`() {
        reachDirectActive(listOf(mediaItem(mediaId = "first"), mediaItem(mediaId = "second")))

        player.seekToNextMediaItem()
        idle()
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
        notifyDeliveredOutput()
        idle()

        assertEquals(UsbOutputPhase.ACTIVE, status().phase)
        assertTrue(direct.calls.isEmpty())
    }

    @Test
    fun `an emptied queue no longer reports the own output as active`() {
        // #292: clearing the queue left ACTIVE while the device was still held.
        reachDirectActive()

        player.stop()
        player.clearMediaItems()
        idle()

        assertStatus(UsbOutputPhase.APPLYING, "direct_usb.configured_not_playing")

        // A new queue is ACTIVE again only once its output opens and plays.
        player.setMediaItem(mediaItem(mediaId = "next"))
        player.prepare()
        player.play()
        idle()
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
        notifyDeliveredOutput()
        idle()
        assertStatus(UsbOutputPhase.ACTIVE, "direct_usb.active")
    }

    @Test
    fun `pausing leaves ACTIVE and playing returns to it without a new takeover`() {
        reachDirectActive()

        player.pause()
        idle()
        assertStatus(UsbOutputPhase.APPLYING, "direct_usb.configured_not_playing")

        player.play()
        idle()
        assertStatus(UsbOutputPhase.ACTIVE, "direct_usb.active")
        assertTrue(direct.calls.isEmpty())
    }

    @Test
    fun `returning to system output gives the device back and rebuilds the player`() {
        reachDirectActive()

        coordinator.requestMode(UsbOutputMode.SYSTEM)
        idle()

        assertStatus(UsbOutputPhase.SYSTEM, "system_audio_route")
        assertEquals(listOf("release"), direct.calls)
        assertTrue(playerCalls.containsAll(listOf("stop", "prepare")))
        assertFalse(direct.streaming)
    }

    @Test
    fun `a session failure fails closed with its reason`() {
        reachDirectActive()

        checkNotNull(direct.listener).onFailed(DirectUsbFailure(DirectUsbFailure.Reason.DEVICE_DISCONNECTED, "unplugged"))
        idle()

        assertFailedAfterActive(UsbOutputFailure.DEVICE_DISCONNECTED, "direct_usb.device_disconnected", UsbOutputFailureOrigin.ROUTE_CHANGE)
        assertEquals(listOf("release"), direct.calls)
    }

    @Test
    fun `an old output notification cannot reactivate an empty or replaced queue`() {
        reachDirectActive()
        val old = player.currentTimeline.getUidOfPeriod(player.currentPeriodIndex)
        checkNotNull(direct.listener).onOutputOpened(old)
        player.stop()
        player.clearMediaItems()
        idle()
        assertStatus(UsbOutputPhase.APPLYING, "direct_usb.configured_not_playing")

        player.setMediaItem(mediaItem(mediaId = "replacement"))
        player.prepare()
        player.play()
        checkNotNull(direct.listener).onOutputOpened(old)
        idle()
        assertEquals(UsbOutputPhase.APPLYING, status().phase)
        notifyDeliveredOutput()
        idle()
        assertStatus(UsbOutputPhase.ACTIVE, "direct_usb.active")
    }

    @Test
    fun `official mixer arriving during takeover waits for confirmed release`() {
        beginHostOnlyTakeover()
        val obsolete = checkNotNull(direct.pendingTakeover)
        mixer.profiles[dac.id] = Result.success(listOf(MIXER_48K_16))
        shadowOf(audioManager).addOutputDevice(dac, true)
        idle()
        val release = checkNotNull(direct.pendingRelease)
        assertFalse(direct.streaming)
        assertFalse(playerCalls.contains("prepare"))
        obsolete(null)
        idle()
        assertFalse(playerCalls.contains("prepare"))
        release(true)
        idle()
        assertTrue(playerCalls.contains("prepare"))
        assertFalse(status().decisionCode.startsWith("direct_usb."))
    }

    @Test
    fun `official mixer visible before notification does not bypass a failed release`() {
        beginHostOnlyTakeover()
        connectDacWithMixer() // The mixer is visible, but Android has not delivered its callback.
        direct.streaming = true
        checkNotNull(direct.pendingTakeover).invoke(null)
        idle()
        direct.recoveryPending = true
        checkNotNull(direct.pendingRelease).invoke(false)
        idle()
        assertFailed(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.usb_transfer_failed")
        requestStrict()
        assertFailedAfterActive(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.usb_transfer_failed", UsbOutputFailureOrigin.USER_MODE_CHANGE)
        assertFalse(playerCalls.contains("prepare"))
    }

    private fun beginHostOnlyTakeover() {
        start(direct = direct)
        loadTrack()
        direct.usbAudioDevicePresent = true
        direct.deferTakeover = true
        requestStrict()
        player.play()
        idle()
        checkNotNull(direct.pendingTakeover)
        playerCalls.clear()
    }

    @Test
    @Config(shadows = [RevokedDescriptorConnection::class])
    fun `revocation during descriptor inspection closes the connection and fails before takeover`() {
        attachUsbHardware("/dev/bus/usb/001/002", vendorId = 0x1234, productId = 0x5678)
        val route = AndroidDirectUsbRoute(context, PreferencesTakeoverMarkerStore(context),
            DirectUsbVolumeControl(), PreferencesDirectUsbVolumeMemory(context))
        RevokedDescriptorConnection.closes = 0
        start(direct = route)
        loadTrack()
        requestStrict()
        assertStatus(UsbOutputPhase.FAILED, "direct_usb.usb_permission_revoked")
        assertEquals(1, RevokedDescriptorConnection.closes)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `an unavailable host list cannot crash a strict playback request`() {
        start(direct = direct)
        loadTrack()
        direct.deviceListError = IllegalStateException("USB service unavailable")
        requestStrict()
        assertStatus(UsbOutputPhase.FAILED, "direct_usb.usb_transfer_failed")
        assertFalse("takeOver" in direct.calls)
    }

    @Test
    fun `a persisted recovery marker blocks another device until its own device is unplugged`() {
        attachUsbHardware("/dev/bus/usb/001/002", vendorId = 0x1234, productId = 0x5678)
        val manager = context.getSystemService(UsbManager::class.java)
        val device = manager.deviceList.values.single()
        val original = TakeoverMarker(UsbDeviceIdentity(device.deviceId, device.vendorId, device.productId), -2560, false)
        val store = PreferencesTakeoverMarkerStore(context)
        assertNotNull(store.acquire(original.device))
        assertTrue(store.write(original))
        assertFalse(store.finish(allReleased = false))
        // The same process keeps blocking after its own failed release.
        assertNull(store.acquire(original.device))

        val reopened = PreferencesTakeoverMarkerStore(context)
        val other = UsbDeviceIdentity(device.deviceId + 1, 22, 33)
        assertNull(reopened.acquire(other))
        val prefs = context.getSharedPreferences("direct_usb_takeover", Context.MODE_PRIVATE)
        assertEquals(-2560, prefs.getInt("volume_before", 0))
        shadowOf(manager).removeUsbDevice(device)
        assertEquals(TakeoverMarker(other, null, null), reopened.acquire(other))
        assertTrue(reopened.finish(allReleased = true))
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `a marker a dead process left is taken over by the same connection with its saved volume`() {
        attachUsbHardware("/dev/bus/usb/001/002", vendorId = 0x1234, productId = 0x5678)
        val device = context.getSystemService(UsbManager::class.java).deviceList.values.single()
        val original = TakeoverMarker(UsbDeviceIdentity(device.deviceId, device.vendorId, device.productId), -2560, true)
        val store = PreferencesTakeoverMarkerStore(context)
        assertNotNull(store.acquire(original.device))
        assertTrue(store.write(original))
        // The process dies here: nothing finishes the slot, and the DAC stays attached.

        val restarted = PreferencesTakeoverMarkerStore(context)
        assertTrue(restarted.isOccupied())
        assertEquals(original, restarted.acquire(original.device))
        assertTrue(restarted.finish(allReleased = true))
        assertTrue(context.getSharedPreferences("direct_usb_takeover", Context.MODE_PRIVATE).all.isEmpty())
    }

    private class FakeDirectRoute : DirectUsbRoute {
        val calls = mutableListOf<String>()
        var inspection: DirectUsbInspection = DirectUsbInspection.Ready("JBL Flip 7", PLAN_48K_24)
        var permissionGranted = true
        var beforePermissionResult: (() -> Unit)? = null
        var takeoverFailure: DirectUsbFailure? = null
        override fun readings() = DirectUsbReadings(48000, 24, 3, 2, "uac1", "adaptive", -30.0, 0, 0, 0, 0)
        var deferTakeover = false
        var pendingTakeover: ((DirectUsbFailure?) -> Unit)? = null
        var pendingRelease: ((Boolean) -> Unit)? = null
        var usbAudioDevicePresent = false
        var deferRecovery = false
        var pendingRecovery: ((DirectUsbRecoveryResult) -> Unit)? = null
        var recoveryResult = DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.usb_transfer_failed")

        override fun recover(onDone: (DirectUsbRecoveryResult) -> Unit) {
            calls += "recover"
            if (deferRecovery) pendingRecovery = onDone else onDone(recoveryResult)
        }

        override val provider: AudioOutputProvider = object : AudioOutputProvider {
            override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig) = throw UnsupportedOperationException()

            override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig) = throw UnsupportedOperationException()

            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = throw UnsupportedOperationException()

            override fun addListener(listener: AudioOutputProvider.Listener) = Unit

            override fun removeListener(listener: AudioOutputProvider.Listener) = Unit

            override fun release() = Unit
        }

        override var streaming = false
        override var recoveryPending = false
        override var listener: DirectUsbRoute.Listener? = null

        override fun onSinkConfigured(config: AudioSink.AudioSinkConfig) = Unit

        var deviceListError: IllegalStateException? = null

        override fun inspect(source: SourcePcmFormat): DirectUsbInspection = inspection.also { calls += "inspect" }

        override fun hasUsbAudioDevice(): Boolean {
            deviceListError?.let { throw it }
            return usbAudioDevicePresent
        }

        override fun requestPermission(forRecovery: Boolean, onResult: (granted: Boolean) -> Unit) {
            calls += "requestPermission"
            if (permissionGranted) inspection = DirectUsbInspection.Ready("JBL Flip 7", PLAN_48K_24)
            beforePermissionResult?.invoke()
            onResult(permissionGranted)
        }

        override fun takeOver(onDone: (DirectUsbFailure?) -> Unit) {
            calls += "takeOver"
            if (deferTakeover) {
                pendingTakeover = onDone
                return
            }
            val failure = takeoverFailure
            streaming = failure == null
            onDone(failure)
        }

        override fun release(onReleased: ((Boolean) -> Unit)?) {
            calls += "release"
            streaming = false
            if (deferTakeover) pendingRelease = onReleased else onReleased?.invoke(true)
        }

        override fun close() = Unit
    }

    private fun status(): UsbOutputStatus = repository.snapshot()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun connect(device: AudioDeviceInfo) = shadowOf(audioManager).addOutputDevice(device, false)

    private fun connectDacWithMixer() {
        connect(dac)
        mixer.profiles[dac.id] = Result.success(listOf(MIXER_48K_16))
    }

    private fun attachUsbHardware(name: String, vendorId: Int, productId: Int) {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", name)
        ReflectionHelpers.setField(device, "mVendorId", vendorId)
        ReflectionHelpers.setField(device, "mProductId", productId)
        ReflectionHelpers.setField(device, "mClass", UsbConstants.USB_CLASS_AUDIO)
        ReflectionHelpers.setField(device, "mVersion", "1.10")
        shadowOf(context.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
    }

    private fun loadTrack(sampleRateHz: Int = 48_000, bitDepth: Int = 16) {
        player.setMediaItem(mediaItem(sampleRateHz, bitDepth))
        player.prepare()
        idle()
        playerCalls.clear()
    }

    private fun requestStrict() {
        coordinator.requestMode(UsbOutputMode.STRICT_BIT_PERFECT)
        idle()
    }

    /** Leaves the coordinator holding a plan, with the rebuild already done when [play] is true. */
    private fun prepareStrictPlan(play: Boolean = true) {
        connectDacWithMixer()
        start()
        loadTrack()
        requestStrict()
        if (play) {
            player.play()
            idle()
            playerCalls.clear()
        }
    }

    private fun reachActive(): StrictGatedAudioOutput {
        prepareStrictPlan()
        val output = strictOutput()
        idle()
        ShadowAudioTrack.setRoutedDevice(dac)
        idle()
        output.play()
        idle()
        assertStatus(UsbOutputPhase.ACTIVE, "strict_usb.active")
        return output
    }

    private fun strictOutput(config: AudioOutputProvider.OutputConfig = outputConfig()) =
        coordinator.getAudioOutput(config) as StrictGatedAudioOutput

    /** Media3 creates its output on a thread with a Looper, and the provider requires one. */
    private fun <T> onPlaybackThread(action: () -> T): T {
        val thread = HandlerThread("playback").apply { start() }
        try {
            val result = FutureTask(action)
            Handler(thread.looper).post(result)
            return result.get(5, TimeUnit.SECONDS)
        } finally {
            thread.quitSafely()
        }
    }

    private fun assertStatus(phase: UsbOutputPhase, decisionCode: String) {
        assertEquals(status().toString(), phase, status().phase)
        assertEquals(decisionCode, status().decisionCode)
    }

    private fun assertFailed(
        failure: UsbOutputFailure,
        decisionCode: String,
        origin: UsbOutputFailureOrigin? = null,
    ) {
        assertFailedAfterActive(failure, decisionCode, origin)
        assertTrue("Reported ACTIVE on the way to a failure", published.none { it.phase == UsbOutputPhase.ACTIVE })
    }

    private fun assertFailedAfterActive(
        failure: UsbOutputFailure,
        decisionCode: String,
        origin: UsbOutputFailureOrigin?,
    ) {
        assertEquals(status().toString(), UsbOutputPhase.FAILED, status().phase)
        assertEquals(failure, status().failure)
        assertEquals(decisionCode, status().decisionCode)
        origin?.let { assertEquals(it, status().failureOrigin) }
    }

    private fun assertProcessingFailure(decisionCode: String) = assertFailedAfterActive(
        UsbOutputFailure.PROCESSING_NOT_NEUTRAL,
        decisionCode,
        UsbOutputFailureOrigin.PROCESSING_CHANGE,
    )

    private fun pcm(): ByteBuffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))

    private fun outputConfig(
        sampleRateHz: Int = 48_000,
        bufferSizeBytes: Int = 19_200,
    ): AudioOutputProvider.OutputConfig = AudioOutputProvider.OutputConfig.Builder()
        .setSampleRate(sampleRateHz)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .setBufferSize(bufferSizeBytes)
        .build()

    private class FakeMixerAdapter : MixerBitPerfectAdapter {
        val profiles = mutableMapOf<Int, Result<List<MixerProfile>>>()
        val calls = mutableListOf<String>()
        var setResult = Result.success(true)
        var clearResult = Result.success(true)
        var readbackMatches = true
        var beforeSetResult: () -> Unit = {}

        override fun profiles(device: AudioDeviceInfo): Result<List<MixerProfile>> {
            calls += "profiles(${device.id})"
            return profiles[device.id] ?: Result.success(emptyList())
        }

        override fun setPreferred(
            device: AudioDeviceInfo,
            format: PlatformPcmFormat,
            audioAttributes: AudioAttributes,
        ): Result<Boolean> {
            calls += "setPreferred(${device.id})"
            beforeSetResult()
            return setResult
        }

        override fun isPreferred(
            device: AudioDeviceInfo,
            format: PlatformPcmFormat,
            audioAttributes: AudioAttributes,
        ): Result<Boolean> {
            calls += "isPreferred(${device.id})"
            return Result.success(readbackMatches)
        }

        override fun clearPreferred(device: AudioDeviceInfo, audioAttributes: AudioAttributes): Result<Boolean> {
            calls += "clearPreferred(${device.id})"
            return clearResult
        }
    }

    private companion object {
        const val MODE_KEY = "mode"

        // AudioPort.ROLE_SINK is hidden; AudioDeviceInfoBuilder leaves the role unset.
        const val AUDIO_PORT_ROLE_SINK = 2

        val PLAN_48K_24 = StreamingPlan(
            interfaceNumber = 2, alternateSetting = 2, endpointAddress = 3, endpointSetsSampleRate = true, sampleRateHz = 48_000,
            channels = 2, subslotBytes = 3, bitResolution = 24, serviceIntervalUs = 1000, maxPacketBytes = 288,
            packetsPerUrb = 32, urbCount = 3, ringFrames = 24_000,
        )

        val MIXER_48K_16 = MixerProfile(
            PlatformPcmFormat(48_000, AudioFormat.ENCODING_PCM_16BIT, AudioFormat.CHANNEL_OUT_STEREO, 2),
            bitPerfect = true,
        )

        val RECORDED_COMMANDS = setOf("pause", "play", "stop", "prepare", "removeListener")

        fun audioDevice(id: Int): AudioDeviceInfo {
            val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build()
            val port = ReflectionHelpers.getField<Any>(device, "mPort")
            ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(port, "mHandle"), "mId", id)
            ReflectionHelpers.setField(port, "mRole", AUDIO_PORT_ROLE_SINK)
            return device
        }

        fun mediaItem(
            sampleRateHz: Int = 48_000,
            bitDepth: Int = 16,
            mediaId: String = "track",
            mimeType: String = "",
            codec: String = "",
        ): MediaItem {
            val track = LibraryTrack(
                id = 1,
                contentUri = "file:///nonexistent/$mediaId.flac",
                title = mediaId,
                artist = "Artist",
                album = "Album",
                durationMs = 1_000,
                sampleRateHz = sampleRateHz,
                bitDepth = bitDepth,
                channelCount = 2,
                mimeType = mimeType,
                codec = codec,
            )
            return MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(track.contentUri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setExtras(TelemetryMediaItemExtras.from(track)).build(),
                )
                .build()
        }

        /** Keeps ExoPlayer's real state and callback order while recording the commands it receives. */
        fun recordingPlayer(delegate: ExoPlayer, calls: MutableList<String>): ExoPlayer =
            Proxy.newProxyInstance(
                ExoPlayer::class.java.classLoader,
                arrayOf(ExoPlayer::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    in RECORDED_COMMANDS -> calls += method.name
                    "setPreferredAudioDevice" ->
                        calls += "setPreferredAudioDevice(${(arguments[0] as AudioDeviceInfo?)?.id})"
                }
                try {
                    method.invoke(delegate, *(arguments ?: emptyArray()))
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            } as ExoPlayer
    }
}

@Implements(UsbDeviceConnection::class)
class RevokedDescriptorConnection {
    @Implementation fun getRawDescriptors(): ByteArray = throw SecurityException("USB permission revoked after open")
    @Implementation fun close() { closes++ }

    companion object {
        var closes = 0
    }
}

/** Robolectric 4.17 invokes device callbacks on the test thread even when Media3 supplied its playback handler. */
@Implements(AudioManager::class)
class RoutingAudioManagerShadow : ShadowAudioManager() {
    private val registrations = mutableMapOf<AudioDeviceCallback, AudioDeviceCallback>()

    @Implementation
    @Synchronized
    override fun registerAudioDeviceCallback(callback: AudioDeviceCallback, handler: Handler?) {
        if (callback in registrations) return
        val target = handler ?: Handler(Looper.getMainLooper())
        val delivery = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) {
                target.post { callback.onAudioDevicesAdded(devices) }
            }
            override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
                target.post { callback.onAudioDevicesRemoved(devices) }
            }
        }
        registrations[callback] = delivery
        super.registerAudioDeviceCallback(delivery, target)
    }

    @Implementation
    @Synchronized
    override fun unregisterAudioDeviceCallback(callback: AudioDeviceCallback) {
        registrations.remove(callback)?.let { super.unregisterAudioDeviceCallback(it) }
    }
}

/** Robolectric does not implement this native method, so every preferred device would be refused. */
@Implements(android.media.AudioTrack::class)
class RoutableAudioTrackShadow : ShadowAudioTrack() {
    @Implementation
    protected fun native_setOutputDevice(@Suppress("UNUSED_PARAMETER") deviceId: Int): Boolean = accepted

    @Implementation
    override fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener, handler: Handler?) {
        // Media3 also registers listeners, sometimes on another track while this test is running.
        check(!rejectCoordinatorListener || !listener.javaClass.name.startsWith(UsbOutputCoordinator::class.java.name))
        super.addOnRoutingChangedListener(listener, handler)
    }

    companion object {
        @JvmStatic
        var accepted = true

        @JvmStatic
        var rejectCoordinatorListener = false
    }
}
