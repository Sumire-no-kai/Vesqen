package io.github.sumirenokai.vesqen.ui.chain

import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.isBluetoothRouteType

internal enum class OutputRouteKind { SPEAKER, WIRED, BLUETOOTH, OTHER }

/** The kind of route the screen describes, and the evidence it came from. */
internal data class OutputRoute(val kind: OutputRouteKind, val evidence: TelemetryEvidence)

/**
 * The selected route's type, or Android's anticipated device for media when that type is missing:
 * Android 11–13 name the selected route without a type, and older versions lack it for some routes
 * (#136 R4). The app-segment verdict and device reports keep using the selected type only.
 */
internal fun outputRoute(metrics: Map<TelemetryMetricId, TelemetryMetric>): OutputRoute? {
    fun read(id: TelemetryMetricId, kind: (String) -> OutputRouteKind?): OutputRoute? {
        val evidence = metrics[id]?.evidence ?: return null
        val type = (evidence.reading as? TelemetryReading.Text)?.value ?: return null
        return kind(type)?.let { OutputRoute(it, evidence) }
    }
    return read(Metrics.ROUTE_SELECTED_SYSTEM_TYPE, ::selectedRouteKind)
        ?: read(Metrics.ROUTE_ANTICIPATED_TYPE, ::deviceRouteKind)
}

/** Selected-route types are Vesqen's coarse output types. */
private fun selectedRouteKind(type: String): OutputRouteKind = when {
    type == "phone_speaker" -> OutputRouteKind.SPEAKER
    type == "wired_or_usb" -> OutputRouteKind.WIRED
    isBluetoothRouteType(type) -> OutputRouteKind.BLUETOOTH
    else -> OutputRouteKind.OTHER
}

/** Anticipated-route types are AudioDeviceInfo types; an unrecognized one says nothing. */
private fun deviceRouteKind(type: String): OutputRouteKind? = when {
    type == "built_in_speaker" || type == "built_in_speaker_safe" -> OutputRouteKind.SPEAKER
    type in WiredDeviceTypes -> OutputRouteKind.WIRED
    isBluetoothRouteType(type) -> OutputRouteKind.BLUETOOTH
    type == "unknown" || type == "other" || type.startsWith("type_") -> null
    else -> OutputRouteKind.OTHER
}

private val WiredDeviceTypes = setOf(
    "wired_headset", "wired_headphones", "line_analog", "line_digital", "usb_device", "usb_accessory", "usb_headset",
)
