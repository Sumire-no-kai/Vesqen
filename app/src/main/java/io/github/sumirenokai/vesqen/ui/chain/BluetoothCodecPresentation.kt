package io.github.sumirenokai.vesqen.ui.chain

import android.content.Context
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.telemetry.BluetoothCodecSettings
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import java.text.NumberFormat

/**
 * "LDAC · 96 kHz · 32-bit · 990 kbps" from the codec Android reported (#73), or null when there is
 * no report. Bit rates are the LDAC tier's nominal ceiling; nothing here is a measured transport rate.
 */
internal fun bluetoothCodecSummary(
    context: Context,
    codec: TelemetryEvidence?,
    configuration: TelemetryEvidence?,
    includeNominalBitrate: Boolean = true,
): String? {
    val name = bluetoothCodecName(codec)
        ?: context.getString(R.string.bluetooth_codec_unnamed).takeIf { bluetoothCodecIsUnnamed(codec, configuration) }
        ?: return null
    return listOfNotNull(name, bluetoothCodecSettingsSummary(context, configuration, includeNominalBitrate)).joinToString(" · ")
}

/** A report whose codec has no public name (a vendor codec before Android 15) but whose settings were read. */
internal fun bluetoothCodecIsUnnamed(codec: TelemetryEvidence?, configuration: TelemetryEvidence?): Boolean =
    (codec as? TelemetryEvidence.Unavailable)?.reason == TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM &&
        configuration?.reading is TelemetryReading.Text

internal fun bluetoothCodecName(codec: TelemetryEvidence?): String? = (codec?.reading as? TelemetryReading.Text)?.value

/**
 * The settings part of [bluetoothCodecSummary], "96 kHz · 32-bit · 990 kbps", or null when none was
 * reported. The kbps is the LDAC tier's nominal ceiling, an estimate: leave it out where no
 * confidence label is shown.
 */
internal fun bluetoothCodecSettingsSummary(
    context: Context,
    configuration: TelemetryEvidence?,
    includeNominalBitrate: Boolean = true,
): String? {
    val settings = (configuration?.reading as? TelemetryReading.Text)?.value?.let(BluetoothCodecSettings::parse)
    val kilohertz = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
        maximumFractionDigits = 1
    }
    return listOfNotNull(
        settings?.sampleRateHz?.let { context.getString(R.string.chain_unit_khz, kilohertz.format(it / 1_000.0)) },
        settings?.bitsPerSample?.let { context.getString(R.string.chain_unit_bits, it.toString()) },
        settings?.ldacQuality?.takeIf { includeNominalBitrate }?.let { quality ->
            quality.nominalBitsPerSecond?.let { context.getString(R.string.chain_unit_kbps, (it / 1_000).toString()) }
                ?: context.getString(R.string.bluetooth_ldac_adaptive_or_990)
        },
    ).joinToString(" · ").ifEmpty { null }
}

/** Why there is no codec to show, in the listener's terms, or null when the route is not Bluetooth. */
internal fun bluetoothCodecUnavailableText(context: Context, codec: TelemetryEvidence?): String? =
    when ((codec as? TelemetryEvidence.Unavailable)?.reason) {
        TelemetryUnavailableReason.PERMISSION_NOT_GRANTED -> context.getString(R.string.bluetooth_codec_permission_needed)
        TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT -> context.getString(R.string.bluetooth_codec_reconnect)
        TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION -> context.getString(R.string.chain_unavailable_android_version)
        else -> null
    }

internal fun bluetoothCodecNeedsPermission(codec: TelemetryEvidence?): Boolean =
    (codec as? TelemetryEvidence.Unavailable)?.reason == TelemetryUnavailableReason.PERMISSION_NOT_GRANTED

/** PRD F6.5.2: a nominally lossless codec such as aptX Lossless falls back to lossy on a weak link. */
internal fun bluetoothCodecIsNominallyLossless(codec: TelemetryEvidence?): Boolean =
    (codec?.reading as? TelemetryReading.Text)?.value?.contains("lossless", ignoreCase = true) == true
