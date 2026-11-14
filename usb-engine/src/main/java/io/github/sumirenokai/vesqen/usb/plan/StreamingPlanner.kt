package io.github.sumirenokai.vesqen.usb.plan

import io.github.sumirenokai.vesqen.usb.control.UacRateRange
import io.github.sumirenokai.vesqen.usb.descriptor.SampleRates
import io.github.sumirenokai.vesqen.usb.descriptor.UacAltSetting
import io.github.sumirenokai.vesqen.usb.descriptor.UacEndpoint
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
    /** Present for an asynchronous endpoint, whose packets follow this feedback instead of the nominal rate. */
    val feedback: FeedbackPlan? = null,
) {
    val frameBytes: Int get() = channels * subslotBytes
}

/**
 * An explicit feedback endpoint (#284). The data plane polls it every [serviceIntervalUs] and fails the
 * stream once no usable value has arrived for [timeoutUs] of bus time.
 */
data class FeedbackPlan(
    val endpointAddress: Int,
    val maxPacketBytes: Int,
    val serviceIntervalUs: Int,
    val packetsPerUrb: Int,
    val timeoutUs: Int,
)

enum class StreamingRefusal {
    /** UAC1 and UAC2 are implemented; UAC3 and anything else is not. */
    VERSION_NOT_IMPLEMENTED,
    NO_MATCHING_FORMAT,
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

    // Linux keeps the last feedback value for as long as none arrives. A stream that stops hearing from
    // the device's clock fails instead, but only after several update periods (strict decisions, M7 plan).
    private const val FEEDBACK_TIMEOUT_PERIODS = 4
    private const val MIN_FEEDBACK_TIMEOUT_US = 1_000_000

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

        for ((interfaceNumber, alt) in usable) {
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
                    // streamingBlockers has already refused an asynchronous endpoint without a usable one.
                    feedback = alt.feedbackEndpoint?.takeIf { endpoint.syncType == UacSyncType.ASYNCHRONOUS }
                        ?.let { feedbackPlan(it, baseIntervalUs) },
                ),
            )
        }
        return StreamingPlanResult.Refused(StreamingRefusal.PACKET_TOO_SMALL)
    }

    /**
     * A UAC1 endpoint has a new value every 2^bRefresh ms, a UAC2 endpoint at every poll. Polling follows
     * bInterval either way, since usbdevfs schedules an isochronous URB by the endpoint's descriptor.
     */
    private fun feedbackPlan(feedback: UacEndpoint, baseIntervalUs: Int): FeedbackPlan {
        val pollUs = baseIntervalUs shl (feedback.interval - 1)
        val updateUs = maxOf(pollUs, (feedback.refreshPeriodMs ?: 0) * 1000)
        return FeedbackPlan(
            endpointAddress = feedback.address,
            maxPacketBytes = feedback.maxPacketBytes,
            serviceIntervalUs = pollUs,
            packetsPerUrb = (TARGET_URB_US / pollUs).coerceIn(1, MAX_PACKETS_PER_URB),
            timeoutUs = maxOf(FEEDBACK_TIMEOUT_PERIODS * updateUs, MIN_FEEDBACK_TIMEOUT_US),
        )
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
