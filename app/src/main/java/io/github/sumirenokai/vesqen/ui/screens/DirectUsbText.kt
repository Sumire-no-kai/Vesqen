package io.github.sumirenokai.vesqen.ui.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import java.math.BigDecimal

/** A string resource and its arguments, chosen without a Context so the choice can be tested. */
internal data class StringSpec(@StringRes val id: Int, val args: List<Any> = emptyList())

private const val DIRECT_USB_CODE_PREFIX = "direct_usb."

/** The current attempt runs, or ran, on Vesqen's own USB output rather than Android's official path. */
internal val UsbOutputStatus.usesDirectUsb: Boolean get() = decisionCode.startsWith(DIRECT_USB_CODE_PREFIX)

/**
 * 4d: the reason for a stop on Vesqen's own USB output, naming the track and the DAC where the
 * status knows them. Null where the failure's general reason already says it, or where a fact the
 * sentence needs is missing.
 */
internal fun directUsbFailureSpec(status: UsbOutputStatus, device: String, rateSeparator: String): StringSpec? {
    if (status.failure == null || !status.usesDirectUsb) return null
    val details = status.directDetails
    // The details name the item that was checked, which in a gapless change is not yet the current one.
    val sourceRate = details?.sourceSampleRateHz ?: status.sourceFormat?.sampleRateHz
    val offered = details?.deviceSampleRatesHz.orEmpty()
    val bits = details?.sourceBitDepth
    val maxBits = details?.deviceMaxBitsAtSourceRate
    val rateSpec = if (sourceRate == null || offered.isEmpty() || offered.any { sourceRate in it }) {
        null
    } else {
        StringSpec(
            R.string.usb_failure_direct_rate,
            listOf(formatKilohertz(sourceRate), device, offered.joinToString(rateSeparator, transform = ::formatRateRange)),
        )
    }
    val depthSpec = if (bits == null || maxBits == null || bits <= maxBits) {
        null
    } else {
        StringSpec(R.string.usb_failure_direct_depth, listOf(bits, device, maxBits))
    }
    val channels = details?.sourceChannelCount
    val deviceChannels = details?.deviceChannelCount
    val channelSpec = if (channels == null || deviceChannels == null || channels == deviceChannels) {
        null
    } else {
        StringSpec(R.string.usb_failure_direct_channels, listOf(channels, device, deviceChannels))
    }
    return when (status.decisionCode.removePrefix(DIRECT_USB_CODE_PREFIX)) {
        "rate_unsupported" -> rateSpec
        "depth_unsupported" -> depthSpec
        "channels_unsupported" -> channelSpec
        // The DAC is already set for the previous item; until it can be set again (M7-4 4c), say so
        // only when the DAC lacks the new rate or depth altogether.
        "no_matching_direct_format" -> rateSpec ?: depthSpec ?: channelSpec
        "source_not_lossless" -> details?.sourceCodec
            ?.let { StringSpec(R.string.usb_failure_direct_lossless_codec, listOf(it)) }
            ?: StringSpec(R.string.usb_failure_direct_lossless)
        "source_bit_depth_unsupported" -> details?.sourceBitDepth
            ?.let { StringSpec(R.string.usb_failure_direct_lossless_bits, listOf(it)) }
            ?: StringSpec(R.string.usb_failure_direct_lossless)
        "uac2_not_supported" -> StringSpec(R.string.usb_failure_direct_uac2, listOf(device))
        "uac_version_unsupported" -> StringSpec(R.string.usb_failure_direct_uac_version, listOf(device))
        "async_not_supported" -> StringSpec(R.string.usb_failure_direct_async, listOf(device))
        "speed_unsupported", "format_exceeds_bus" -> StringSpec(R.string.usb_failure_direct_bus, listOf(device))
        "permission_required" -> StringSpec(R.string.usb_failure_direct_permission_required, listOf(device))
        "permission_denied" -> StringSpec(R.string.usb_failure_direct_permission_denied, listOf(device))
        "device_ambiguous" -> StringSpec(R.string.usb_failure_direct_several_devices)
        "open_failed", "interface_claim_failed" -> StringSpec(R.string.usb_failure_direct_claim, listOf(device))
        "volume_control_failed" -> StringSpec(R.string.usb_failure_direct_volume, listOf(device))
        "sample_rate_set_failed", "sample_rate_readback_mismatch" ->
            sourceRate?.let { StringSpec(R.string.usb_failure_direct_rate_set, listOf(device, formatKilohertz(it))) }
        "descriptors_refused" -> StringSpec(R.string.usb_failure_direct_descriptors, listOf(device))
        "no_playback_function" -> StringSpec(R.string.usb_failure_direct_no_playback, listOf(device))
        "decoder_precision_lost" -> StringSpec(R.string.usb_failure_direct_decoder_precision)
        "pcm_not_exact" -> StringSpec(R.string.usb_failure_direct_pcm_not_exact)
        "usb_transfer_failed" -> StringSpec(R.string.usb_failure_direct_transfer, listOf(device))
        "usb_engine_unavailable", "engine_unavailable" -> StringSpec(R.string.usb_failure_direct_engine)
        else -> null
    }
}

/** "44.1 kHz", "48 kHz": the rate as written on a DAC, without trailing zeros. */
internal fun formatKilohertz(rateHz: Int): String = "${kilohertz(rateHz)} kHz"

private fun formatRateRange(range: IntRange): String =
    if (range.first == range.last) formatKilohertz(range.first) else "${kilohertz(range.first)}–${kilohertz(range.last)} kHz"

private fun kilohertz(rateHz: Int): String = BigDecimal(rateHz).movePointLeft(3).stripTrailingZeros().toPlainString()

/** The format Vesqen's own output sends, such as "48 kHz / 24-bit", or null before a plan exists. */
@Composable
internal fun directUsbWireFormat(status: UsbOutputStatus): String? {
    val details = status.directDetails ?: return null
    val rate = details.wireSampleRateHz ?: return null
    val bits = details.wireBitDepth ?: return null
    return stringResource(R.string.usb_wire_format, formatKilohertz(rate), bits)
}

/** Why strict output stopped, in the most specific words the status allows. */
@Composable
internal fun strictUsbFailureReason(status: UsbOutputStatus): String {
    val failure = requireNotNull(status.failure)
    val spec = directUsbFailureSpec(
        status,
        device = status.deviceName ?: stringResource(R.string.settings_usb_device_unknown),
        rateSeparator = stringResource(R.string.usb_failure_rate_separator),
    )
    return if (spec == null) {
        strictUsbFailureLabel(failure, status.decisionCode)
    } else {
        stringResource(spec.id, *spec.args.toTypedArray())
    }
}
