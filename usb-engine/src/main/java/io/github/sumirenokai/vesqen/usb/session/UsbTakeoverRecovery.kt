package io.github.sumirenokai.vesqen.usb.session

import io.github.sumirenokai.vesqen.usb.control.UacFeatureControls
import io.github.sumirenokai.vesqen.usb.descriptor.UacControlAccess
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UacVersion

/** Runs on the USB control executor, after the old owner has returned every native buffer. */
class UsbTakeoverRecovery(
    private val device: UsbDeviceIdentity,
    private val link: UsbLink,
    private val functions: List<UacFunction>,
    private val markers: TakeoverMarkerStore,
) {
    fun recover(): CleanupRecord {
        val steps = mutableListOf<CleanupStep>()
        val marker = markers.acquireRecovery(device)
        if (marker == null) {
            link.close()
            return CleanupRecord(listOf(CleanupStep("recovery ownership", false)))
        }
        val claimed = mutableListOf<Int>()
        val function = functions.singleOrNull {
            (it.version == UacVersion.UAC1 || it.version == UacVersion.UAC2) && it.streamingInterfaces.isNotEmpty() &&
                (marker.controlInterface == null || it.controlInterface == marker.controlInterface)
        }
        // Older records did not name the function. Never force-claim an unrelated audio function.
        var complete = function != null
        var settingsRestored = marker.volumeBefore == null
        try {
            if (function == null) {
                steps += CleanupStep("saved audio function identified", false)
            } else {
                val interfaces = (listOf(function.controlInterface) + function.streamingInterfaces.map { it.interfaceNumber }).distinct()
                for (number in interfaces) {
                    val succeeded = attempt { link.claim(number) }
                    steps += CleanupStep("interface $number reclaimed", succeeded)
                    if (succeeded) claimed += number else complete = false
                }
                function.streamingInterfaces.distinctBy { it.interfaceNumber }.forEach { stream ->
                    if (stream.interfaceNumber in claimed) {
                        val succeeded = attempt { link.selectAlternate(stream.interfaceNumber, 0) }
                        steps += CleanupStep("alternate setting 0 on interface ${stream.interfaceNumber}", succeeded)
                        complete = complete && succeeded
                    }
                }
                if (marker.volumeBefore != null) {
                    val target = function.featureUnits.singleOrNull {
                        it.masterVolume != UacControlAccess.ABSENT && it.masterVolume != UacControlAccess.READ_ONLY &&
                            (marker.volumeUnitId == null || marker.volumeUnitId == it.id)
                    }
                    val controls = UacFeatureControls.of(function.version, link.control)
                    settingsRestored = target != null && controls != null && function.controlInterface in claimed && attempt {
                        controls.restoreSettings(target.id, function.controlInterface, marker.volumeBefore, marker.mutedBefore)
                    }
                    steps += CleanupStep("volume restored", settingsRestored)
                }
            }
        } finally {
            claimed.asReversed().forEach { number ->
                val succeeded = attempt { link.release(number) }
                steps += CleanupStep("interface $number released", succeeded)
                complete = complete && succeeded
            }
            val closed = attempt { link.close(); true }
            steps += CleanupStep("connection closed", closed)
            steps += CleanupStep("takeover marker cleared", markers.finish(complete && closed, settingsRestored))
        }
        return CleanupRecord(steps.toList())
    }

    /** USB permission removal and platform I/O failures must still release the other interfaces. */
    private fun attempt(action: () -> Boolean): Boolean = try {
        action()
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
}
