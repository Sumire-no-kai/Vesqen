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
        // The format layers (D10), checked before an output is opened, and the samples while it runs.
        SOURCE_FORMAT_UNKNOWN,
        SOURCE_NOT_LOSSLESS,
        SOURCE_BIT_DEPTH_UNSUPPORTED,
        /** A lossless source that still needs samples trimmed (encoder delay or padding). */
        SOURCE_FORMAT_UNSUPPORTED,
        DECODER_PRECISION_LOST,
        NO_MATCHING_DIRECT_FORMAT,
        PCM_NOT_EXACT,
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

abstract class TakeoverMarkerStore {
    private var held = false

    /** Set by the first takeover in this process; a record found before that was left by a process that died. */
    private var wroteHere = false

    @Synchronized
    fun isOccupied(): Boolean = held || hasPendingRecovery()

    /**
     * One store owns the marker for the application's lifetime, across service instances. Returns the
     * record now stored for [device], or null while the slot is occupied.
     *
     * A record that a process which died left for this same connection is taken over with the volume it
     * saved, so the release still restores the volume from before that process took the device. A record
     * kept after an incomplete interface release, or one for another connected device, keeps blocking.
     */
    @Synchronized
    fun acquire(device: UsbDeviceIdentity): TakeoverMarker? {
        if (held) return null
        val left = if (hasPendingRecovery()) read() else null
        if (left != null && (wroteHere || left.device != device)) return null
        val marker = TakeoverMarker(device, left?.volumeBefore, left?.mutedBefore)
        if (!write(marker)) return null
        held = true
        wroteHere = true
        return marker
    }

    abstract fun write(marker: TakeoverMarker): Boolean
    protected abstract fun read(): TakeoverMarker?
    protected abstract fun hasPendingRecovery(): Boolean
    protected abstract fun clear(): Boolean

    /** Only the session that acquired the slot can finish it. A failed release keeps its record. */
    @Synchronized
    fun finish(allReleased: Boolean, settingsRestored: Boolean = true): Boolean {
        check(held)
        val cleared = allReleased && settingsRestored && clear()
        // Interfaces are back: the same connection may retry its saved settings on the next takeover.
        wroteHere = !allReleased
        held = false
        return cleared
    }
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
