package io.github.sumirenokai.vesqen.usb.engine

import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UsbAudioTopology
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlanResult
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlanner
import io.github.sumirenokai.vesqen.usb.plan.StreamingRefusal
import io.github.sumirenokai.vesqen.usb.plan.UsbBusSpeed

/**
 * Everything settled before an interface is claimed: the playback function, the bus speed and the
 * plan. A native library that did not load is reported before planning, with the loader's reason,
 * so it cannot pass for an unsupported bus speed (#276).
 */
sealed interface StreamPreflight {
    data class Ready(val function: UacFunction, val speed: UsbBusSpeed, val plan: StreamingPlan) : StreamPreflight

    data object NoPlaybackFunction : StreamPreflight

    data class LibraryUnavailable(val reason: String) : StreamPreflight

    data class Refused(val speed: UsbBusSpeed, val reason: StreamingRefusal) : StreamPreflight

    companion object {
        /** [speed] is what [UsbStreamEngine.busSpeed] answered for this device. */
        fun run(
            topology: UsbAudioTopology,
            speed: UsbStreamEngine.SpeedQuery,
            sampleRateHz: Int,
            bitResolution: Int,
        ): StreamPreflight {
            val function = topology.functions.firstOrNull { candidate ->
                candidate.streamingInterfaces.any { streaming -> streaming.alternateSettings.any { it.isPlayback } }
            } ?: return NoPlaybackFunction
            val known = when (speed) {
                is UsbStreamEngine.SpeedQuery.LibraryUnavailable -> return LibraryUnavailable(speed.reason)
                is UsbStreamEngine.SpeedQuery.Answered -> speed.speed
            }
            return when (val result = StreamingPlanner.plan(function, known, sampleRateHz, bitResolution)) {
                is StreamingPlanResult.Planned -> Ready(function, known, result.plan)
                is StreamingPlanResult.Refused -> Refused(known, result.reason)
            }
        }
    }
}
