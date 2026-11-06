package io.github.sumirenokai.vesqen.usb.session

import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats

/** Why the own output stopped. The reasons follow the architecture's failure table (usb_failure_*). */
data class DirectUsbFailure(val reason: Reason, val detail: String) {
    enum class Reason {
        INTERFACE_CLAIM_FAILED,
        VOLUME_CONTROL_FAILED,
        SAMPLE_RATE_SET_FAILED,
        SAMPLE_RATE_READBACK_MISMATCH,
        USB_ENGINE_UNAVAILABLE,
        USB_TRANSFER_FAILED,
        DEVICE_DISCONNECTED,
    }
}

/** One release step. A failed step is recorded and the rest still run, except past the safety barrier. */
data class CleanupStep(val name: String, val succeeded: Boolean)

data class CleanupRecord(val steps: List<CleanupStep>) {
    val firstFailure: CleanupStep? get() = steps.firstOrNull { !it.succeeded }
}

/** The connected device, as the takeover marker keeps it. */
data class UsbDeviceIdentity(val deviceId: Int, val vendorId: Int, val productId: Int)

/**
 * Written before the first interface is claimed and cleared only once every interface is released, so a
 * process that dies in between leaves enough to give the device back and restore its volume (M7-4).
 */
data class TakeoverMarker(val device: UsbDeviceIdentity, val volumeBefore: Int?, val mutedBefore: Boolean?)

interface TakeoverMarkerStore {
    fun write(marker: TakeoverMarker)

    fun clear()
}

/** Totals over every output of a session, for diagnostics. */
data class UsbStreamTotals(val lostFrames: Long, val underruns: Long, val failedPackets: Long, val deliveredFrames: Long) {
    operator fun plus(other: UsbStreamTotals) = UsbStreamTotals(
        lostFrames + other.lostFrames,
        underruns + other.underruns,
        failedPackets + other.failedPackets,
        deliveredFrames + other.deliveredFrames,
    )

    companion object {
        val NONE = UsbStreamTotals(0, 0, 0, 0)
    }
}

/** What a stream's own failure means for the own output, or null while it has none. */
fun UsbStreamStats.asFailure(): DirectUsbFailure? = when (failure) {
    UsbStreamStats.Failure.NONE -> null
    UsbStreamStats.Failure.DEVICE_GONE ->
        DirectUsbFailure(DirectUsbFailure.Reason.DEVICE_DISCONNECTED, "the stream lost the device")
    else -> DirectUsbFailure(DirectUsbFailure.Reason.USB_TRANSFER_FAILED, "$failure, errno $failureErrno")
}
