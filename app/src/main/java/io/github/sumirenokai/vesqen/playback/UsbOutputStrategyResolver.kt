package io.github.sumirenokai.vesqen.playback

internal data class SourcePcmFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int,
)

internal data class PlatformPcmFormat(
    val sampleRateHz: Int,
    val encoding: Int,
    val channelMask: Int,
    val channelCount: Int,
)

internal data class MixerProfile(
    val format: PlatformPcmFormat,
    val bitPerfect: Boolean,
)

internal data class UsbOutputDevice(
    val id: Int,
    val name: String,
)

internal sealed interface UsbOutputDecision {
    data object System : UsbOutputDecision

    data class Rejected(
        val failure: UsbOutputFailure,
        val code: String,
    ) : UsbOutputDecision

    data class Candidate(
        val device: UsbOutputDevice,
        val source: SourcePcmFormat,
        val mixerProfile: MixerProfile?,
        val code: String,
    ) : UsbOutputDecision
}

/** Pure capability and format policy. Platform calls and player mutation stay in the service adapter. */
internal class UsbOutputStrategyResolver {
    fun resolve(
        mode: UsbOutputMode,
        apiLevel: Int,
        usbHostSupported: Boolean,
        modifyAudioSettingsGranted: Boolean,
        devices: List<UsbOutputDevice>,
        source: SourcePcmFormat?,
        mixerProfiles: List<MixerProfile>?,
        audioTrackFormat: PlatformPcmFormat? = null,
    ): UsbOutputDecision {
        if (mode == UsbOutputMode.SYSTEM) return UsbOutputDecision.System
        if (apiLevel < 34) return rejected(
            UsbOutputFailure.UNSUPPORTED_ANDROID_VERSION,
            "strict_usb.unsupported_android_version",
        )
        if (!usbHostSupported) return rejected(
            UsbOutputFailure.USB_HOST_UNAVAILABLE,
            "strict_usb.usb_host_unavailable",
        )
        if (!modifyAudioSettingsGranted) return rejected(
            UsbOutputFailure.MODIFY_AUDIO_SETTINGS_DENIED,
            "strict_usb.modify_audio_settings_denied",
        )
        val device = devices.firstOrNull() ?: return rejected(
            UsbOutputFailure.NO_USB_AUDIO_DEVICE,
            "strict_usb.no_usb_audio_device",
        )
        val resolvedSource = source ?: return rejected(
            UsbOutputFailure.SOURCE_FORMAT_UNKNOWN,
            "strict_usb.source_format_unknown",
        )
        if (!resolvedSource.isSupportedSource()) return rejected(
            UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED,
            "strict_usb.source_format_unsupported",
        )
        if (mixerProfiles == null) return UsbOutputDecision.Candidate(
            device = device,
            source = resolvedSource,
            mixerProfile = null,
            code = "strict_usb.device_and_source_available",
        )
        val bitPerfectProfiles = mixerProfiles.filter(MixerProfile::bitPerfect)
        val selected = if (audioTrackFormat == null) {
            bitPerfectProfiles.firstOrNull { it.format.isCompatibleWith(resolvedSource) }
        } else {
            bitPerfectProfiles.firstOrNull { it.format == audioTrackFormat }
        } ?: return rejected(
            if (audioTrackFormat == null) {
                UsbOutputFailure.NO_MATCHING_MIXER_ATTRIBUTE
            } else {
                UsbOutputFailure.AUDIO_TRACK_FORMAT_MISMATCH
            },
            if (audioTrackFormat == null) {
                "strict_usb.no_matching_mixer_attribute"
            } else {
                "strict_usb.audio_track_format_mismatch"
            },
        )
        return UsbOutputDecision.Candidate(
            device = device,
            source = resolvedSource,
            mixerProfile = selected,
            code = if (audioTrackFormat == null) {
                "strict_usb.bit_perfect_available"
            } else {
                "strict_usb.audio_track_matches_mixer"
            },
        )
    }

    private fun SourcePcmFormat.isSupportedSource(): Boolean =
        sampleRateHz > 0 && channelCount > 0 && bitDepth in SUPPORTED_SOURCE_DEPTHS

    private fun PlatformPcmFormat.isCompatibleWith(source: SourcePcmFormat): Boolean {
        if (sampleRateHz != source.sampleRateHz || channelCount != source.channelCount) return false
        return when (source.bitDepth) {
            16 -> encoding == PLATFORM_PCM_16_BIT
            // Media3's high-resolution public path requests float PCM. Float preserves all 24-bit
            // integer samples exactly, while a 32-bit integer source would lose low-order bits.
            24 -> encoding in setOf(PLATFORM_PCM_24_BIT_PACKED, PLATFORM_PCM_32_BIT, PLATFORM_PCM_FLOAT)
            32 -> encoding == PLATFORM_PCM_32_BIT
            else -> false
        }
    }

    private fun rejected(failure: UsbOutputFailure, code: String) =
        UsbOutputDecision.Rejected(failure, code)

    private companion object {
        val SUPPORTED_SOURCE_DEPTHS = setOf(16, 24, 32)
        const val PLATFORM_PCM_16_BIT = 2
        const val PLATFORM_PCM_FLOAT = 4
        const val PLATFORM_PCM_24_BIT_PACKED = 21
        const val PLATFORM_PCM_32_BIT = 22
    }
}
