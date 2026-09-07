package io.github.sumirenokai.vesqen.telemetry

internal fun isBluetoothRouteType(type: String): Boolean =
    type == "bluetooth" || type == "bluetooth_a2dp" || type == "bluetooth_sco" ||
        type == "ble_headset" || type == "ble_speaker" || type == "ble_broadcast" ||
        type == "hearing_aid"

/** A connected endpoint is not evidence of selection; prefer the selected route over prediction. */
internal fun bluetoothTransportUnavailableReason(
    selectedType: String?,
    anticipatedType: String?,
): TelemetryUnavailableReason {
    fun String.isSpecific() = this != "other" && this != "unknown" && !startsWith("type_")
    val type = selectedType?.takeIf { it.isSpecific() }
        ?: anticipatedType?.takeIf { it.isSpecific() }
        ?: return TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
    return if (isBluetoothRouteType(type)) TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM
    else TelemetryUnavailableReason.NOT_APPLICABLE
}
