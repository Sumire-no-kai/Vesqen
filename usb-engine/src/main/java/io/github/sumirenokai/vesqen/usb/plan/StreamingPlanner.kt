package io.github.sumirenokai.vesqen.usb.plan

import io.github.sumirenokai.vesqen.usb.control.UacRateRange
import io.github.sumirenokai.vesqen.usb.descriptor.SampleRates
import io.github.sumirenokai.vesqen.usb.descriptor.UacAltSetting
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UacSyncType
import io.github.sumirenokai.vesqen.usb.descriptor.UacVersion

enum class UsbBusSpeed {
    UNKNOWN,
    LOW,
    FULL,
    HIGH,
    WIRELESS,
    SUPER,
    SUPER_PLUS,
    ;

    companion object {
        /** The kernel's USB_SPEED_ values, which USBDEVFS_GET_SPEED returns. */
        fun fromKernel(value: Int): UsbBusSpeed = entries.getOrElse(value) { UNKNOWN }
    }
}

/** How one stream runs. The data plane receives these values and checks them again. */
data class StreamingPlan(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val endpointAddress: Int,
    val endpointSetsSampleRate: Boolean,
    val sampleRateHz: Int,
    val channels: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    val serviceIntervalUs: Int,
    val maxPacketBytes: Int,
    val packetsPerUrb: Int,
    val urbCount: Int,
    val ringFrames: Int,
) {
    val frameBytes: Int get() = channels * subslotBytes
}

enum class StreamingRefusal {
    /** UAC1 and UAC2 are implemented; UAC3 and anything else is not. */
    VERSION_NOT_IMPLEMENTED,
    NO_MATCHING_FORMAT,

    /** The first transfers run at the nominal rate. Feedback arrives in M7-3. */
    ASYNCHRONOUS_NOT_IMPLEMENTED,
    SPEED_UNSUPPORTED,
    PACKET_TOO_SMALL,
}

sealed interface StreamingPlanResult {
    data class Planned(val plan: StreamingPlan) : StreamingPlanResult

    data class Refused(val reason: StreamingRefusal) : StreamingPlanResult
}

object StreamingPlanner {
    private const val MICROS_PER_SECOND = 1_000_000L
    private const val TARGET_URB_US = 32_000
    private const val TARGET_IN_FLIGHT_US = 96_000
    private const val MAX_PACKETS_PER_URB = 128

    /**
     * The planner picks the first setting that can carry the format exactly. A UAC2 device lists its
     * rates only in answer to a request on its clock, which needs the interfaces claimed: before the
     * takeover [clockRates] is null and the rate is confirmed by the session, after it the session's
     * answer decides.
     */
    fun plan(
        function: UacFunction,
        speed: UsbBusSpeed,
        sampleRateHz: Int,
        bitResolution: Int,
        clockRates: List<UacRateRange>? = null,
    ): StreamingPlanResult {
        if (function.version != UacVersion.UAC1 && function.version != UacVersion.UAC2) {
            return StreamingPlanResult.Refused(StreamingRefusal.VERSION_NOT_IMPLEMENTED)
        }
        val baseIntervalUs = when (speed) {
            UsbBusSpeed.FULL -> 1000
            UsbBusSpeed.HIGH, UsbBusSpeed.SUPER, UsbBusSpeed.SUPER_PLUS -> 125
            UsbBusSpeed.UNKNOWN, UsbBusSpeed.LOW, UsbBusSpeed.WIRELESS ->
                return StreamingPlanResult.Refused(StreamingRefusal.SPEED_UNSUPPORTED)
        }
        val usable = function.streamingInterfaces.flatMap { streaming ->
            streaming.alternateSettings
                .filter { it.isPlayback && it.streamingBlockers.isEmpty() && it.carries(sampleRateHz, bitResolution, function.version, clockRates) }
                .map { streaming.interfaceNumber to it }
        }
        if (usable.isEmpty()) return StreamingPlanResult.Refused(StreamingRefusal.NO_MATCHING_FORMAT)

        val adaptive = usable.filter { (_, alt) -> alt.dataEndpoint?.syncType != UacSyncType.ASYNCHRONOUS }
        if (adaptive.isEmpty()) return StreamingPlanResult.Refused(StreamingRefusal.ASYNCHRONOUS_NOT_IMPLEMENTED)

        for ((interfaceNumber, alt) in adaptive) {
            val endpoint = alt.dataEndpoint ?: continue
            val format = alt.format ?: continue
            val serviceIntervalUs = baseIntervalUs shl (endpoint.interval - 1)
            val frameBytes = format.channels * format.subslotBytes
            val maxFrames = (sampleRateHz * serviceIntervalUs.toLong() + MICROS_PER_SECOND - 1) / MICROS_PER_SECOND
            if (maxFrames * frameBytes > endpoint.maxPacketBytes) continue
            val packetsPerUrb = (TARGET_URB_US / serviceIntervalUs).coerceIn(1, MAX_PACKETS_PER_URB)
            val urbUs = packetsPerUrb * serviceIntervalUs
            return StreamingPlanResult.Planned(
                StreamingPlan(
                    interfaceNumber = interfaceNumber,
                    alternateSetting = alt.alternateSetting,
                    endpointAddress = endpoint.address,
                    endpointSetsSampleRate = alt.endpointSetsSampleRate,
                    sampleRateHz = sampleRateHz,
                    channels = format.channels,
                    subslotBytes = format.subslotBytes,
                    bitResolution = format.bitResolution,
                    serviceIntervalUs = serviceIntervalUs,
                    maxPacketBytes = endpoint.maxPacketBytes,
                    packetsPerUrb = packetsPerUrb,
                    urbCount = (TARGET_IN_FLIGHT_US + urbUs - 1) / urbUs,
                    ringFrames = sampleRateHz / 2,
                ),
            )
        }
        return StreamingPlanResult.Refused(StreamingRefusal.PACKET_TOO_SMALL)
    }

    private fun UacAltSetting.carries(sampleRateHz: Int, bitResolution: Int, version: UacVersion, clockRates: List<UacRateRange>?): Boolean {
        val format = format ?: return false
        if (format.bitResolution != bitResolution) return false
        return when (val rates = format.sampleRates) {
            is SampleRates.Discrete -> sampleRateHz in rates.ratesHz
            is SampleRates.Continuous -> sampleRateHz in rates.minHz..rates.maxHz
            // Only a UAC2 clock answers at run time; UAC1 lists its rates in the descriptors.
            SampleRates.RuntimeUnknown -> version == UacVersion.UAC2 && (clockRates == null || clockRates.any { sampleRateHz in it })
        }
    }
}
