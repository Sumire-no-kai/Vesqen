package io.github.sumirenokai.vesqen.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutputProvider
import io.github.sumirenokai.vesqen.library.AudioTrack as LibraryTrack
import io.github.sumirenokai.vesqen.telemetry.TelemetryMediaItemExtras
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.util.ReflectionHelpers

/**
 * Drives the shipped state machine with a real ExoPlayer and a faked platform. No renderer runs, so
 * each test calls [UsbOutputCoordinator.getAudioOutput] and the output's play() where Media3's
 * audio sink would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RoutableAudioTrackShadow::class])
@androidx.annotation.OptIn(UnstableApi::class)
class UsbOutputCoordinatorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val preferences = context.getSharedPreferences("usb_output_preferences", Context.MODE_PRIVATE)
    private val repository = UsbOutputStateRepository()
    private val published = mutableListOf<UsbOutputStatus>()
    private val mixer = FakeMixerAdapter()
    private val realPlayer = ExoPlayer.Builder(context).build()
    private val playerCalls = mutableListOf<String>()
    private val player = recordingPlayer(realPlayer, playerCalls)
    private val dac = audioDevice(id = 11)
    private var strictAllowed = true
    private lateinit var coordinator: UsbOutputCoordinator

    private fun start(modifyAudioSettingsGranted: Boolean = true, usbHost: Boolean = true) {
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
        )
        coordinator.attachPlayer(player)
    }

    @After
    fun tearDown() {
        if (::coordinator.isInitialized) coordinator.close()
        realPlayer.release()
        RoutableAudioTrackShadow.accepted = true
        RoutableAudioTrackShadow.routingListenersAccepted = Int.MAX_VALUE
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
        // Media3 registers its own listener on the new track before the coordinator does.
        RoutableAudioTrackShadow.routingListenersAccepted = 1

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
        assertTrue(ReflectionHelpers.getField<Set<*>>(shadowOf(audioManager), "audioDeviceCallbacks").isEmpty())
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

        fun mediaItem(sampleRateHz: Int = 48_000, bitDepth: Int = 16, mediaId: String = "track"): MediaItem {
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

/** Robolectric does not implement this native method, so every preferred device would be refused. */
@Implements(android.media.AudioTrack::class)
class RoutableAudioTrackShadow : ShadowAudioTrack() {
    @Implementation
    protected fun native_setOutputDevice(@Suppress("UNUSED_PARAMETER") deviceId: Int): Boolean = accepted

    @Implementation
    override fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener, handler: Handler?) {
        check(routingListenersAccepted-- > 0)
        super.addOnRoutingChangedListener(listener, handler)
    }

    companion object {
        @JvmStatic
        var accepted = true

        @JvmStatic
        var routingListenersAccepted = Int.MAX_VALUE
    }
}
