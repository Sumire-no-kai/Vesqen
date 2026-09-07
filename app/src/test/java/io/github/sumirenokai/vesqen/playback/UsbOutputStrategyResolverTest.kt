package io.github.sumirenokai.vesqen.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbOutputStrategyResolverTest {
    private val resolver = UsbOutputStrategyResolver()
    private val device = UsbOutputDevice(id = 7, name = "USB DAC")
    private val source24 = SourcePcmFormat(sampleRateHz = 96_000, bitDepth = 24, channelCount = 2)
    private val float96 = PlatformPcmFormat(
        sampleRateHz = 96_000,
        encoding = 4,
        channelMask = 12,
        channelCount = 2,
    )
    private val floatProfile = MixerProfile(float96, bitPerfect = true)

    @Test
    fun `system mode never evaluates unavailable strict requirements`() {
        assertEquals(
            UsbOutputDecision.System,
            resolver.resolve(
                mode = UsbOutputMode.SYSTEM,
                apiLevel = 26,
                usbHostSupported = false,
                modifyAudioSettingsGranted = false,
                devices = emptyList(),
                source = null,
                mixerProfiles = null,
            ),
        )
    }

    @Test
    fun `strict mode rejects old Android before touching mixer capability`() {
        assertRejected(
            UsbOutputFailure.UNSUPPORTED_ANDROID_VERSION,
            resolver.resolve(
                mode = UsbOutputMode.STRICT_BIT_PERFECT,
                apiLevel = 33,
                usbHostSupported = true,
                modifyAudioSettingsGranted = true,
                devices = listOf(device),
                source = source24,
                mixerProfiles = null,
            ),
        )
    }

    @Test
    fun `strict prerequisites fail with a specific reason`() {
        assertRejected(
            UsbOutputFailure.USB_HOST_UNAVAILABLE,
            resolve(usbHostSupported = false),
        )
        assertRejected(
            UsbOutputFailure.MODIFY_AUDIO_SETTINGS_DENIED,
            resolve(modifyAudioSettingsGranted = false),
        )
        assertRejected(
            UsbOutputFailure.NO_USB_AUDIO_DEVICE,
            resolve(devices = emptyList()),
        )
        assertRejected(
            UsbOutputFailure.SOURCE_FORMAT_UNKNOWN,
            resolve(source = null),
        )
    }

    @Test
    fun `a compatible profile is availability and not active output`() {
        val decision = resolve(mixerProfiles = listOf(floatProfile))
        assertTrue(decision is UsbOutputDecision.Candidate)
        decision as UsbOutputDecision.Candidate
        assertEquals(device, decision.device)
        assertEquals(floatProfile, decision.mixerProfile)
        assertEquals("strict_usb.bit_perfect_available", decision.code)
    }

    @Test
    fun `actual AudioTrack request must exactly match advertised mixer format`() {
        val exact = resolve(
            mixerProfiles = listOf(floatProfile),
            audioTrackFormat = float96,
        ) as UsbOutputDecision.Candidate
        assertEquals(floatProfile, exact.mixerProfile)
        assertEquals("strict_usb.audio_track_matches_mixer", exact.code)

        assertRejected(
            UsbOutputFailure.AUDIO_TRACK_FORMAT_MISMATCH,
            resolve(
                mixerProfiles = listOf(floatProfile),
                audioTrackFormat = float96.copy(sampleRateHz = 48_000),
            ),
        )
    }

    @Test
    fun `default mixer behavior cannot satisfy strict mode`() {
        assertRejected(
            UsbOutputFailure.NO_MATCHING_MIXER_ATTRIBUTE,
            resolve(mixerProfiles = listOf(floatProfile.copy(bitPerfect = false))),
        )
    }

    @Test
    fun `32 bit integer source does not accept float conversion`() {
        assertRejected(
            UsbOutputFailure.NO_MATCHING_MIXER_ATTRIBUTE,
            resolve(
                source = source24.copy(bitDepth = 32),
                mixerProfiles = listOf(floatProfile),
            ),
        )
    }

    @Test
    fun `status declaration follows phase without a second mutable truth`() {
        val active = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.ACTIVE,
            decisionCode = "strict_usb.active",
        )
        assertEquals(OutputDeclaration.BIT_PERFECT_ACTIVE, active.declaration)
        assertEquals(OutputDeclaration.BIT_PERFECT_ACTIVE, PlaybackSnapshot(usbOutputStatus = active).declaration)
        assertNull(active.failure)
    }

    private fun resolve(
        usbHostSupported: Boolean = true,
        modifyAudioSettingsGranted: Boolean = true,
        devices: List<UsbOutputDevice> = listOf(device),
        source: SourcePcmFormat? = source24,
        mixerProfiles: List<MixerProfile>? = null,
        audioTrackFormat: PlatformPcmFormat? = null,
    ): UsbOutputDecision = resolver.resolve(
        mode = UsbOutputMode.STRICT_BIT_PERFECT,
        apiLevel = 34,
        usbHostSupported = usbHostSupported,
        modifyAudioSettingsGranted = modifyAudioSettingsGranted,
        devices = devices,
        source = source,
        mixerProfiles = mixerProfiles,
        audioTrackFormat = audioTrackFormat,
    )

    private fun assertRejected(expected: UsbOutputFailure, decision: UsbOutputDecision) {
        assertTrue(decision is UsbOutputDecision.Rejected)
        assertEquals(expected, (decision as UsbOutputDecision.Rejected).failure)
    }
}
