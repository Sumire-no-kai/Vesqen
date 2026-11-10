package io.github.sumirenokai.vesqen.usb.session

import io.github.sumirenokai.vesqen.usb.control.Uac1ControlClient
import io.github.sumirenokai.vesqen.usb.descriptor.UacControlAccess
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction

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
        val interfaces = functions.flatMap { function ->
            listOf(function.controlInterface) + function.streamingInterfaces.map { it.interfaceNumber }
        }.distinct()
        var complete = interfaces.isNotEmpty()
        var settingsRestored = marker.volumeBefore == null
        try {
            for (number in interfaces) {
                val succeeded = attempt { link.claim(number) }
                steps += CleanupStep("interface $number reclaimed", succeeded)
                if (succeeded) claimed += number else complete = false
            }
            functions.flatMap { it.streamingInterfaces }.distinctBy { it.interfaceNumber }.forEach { stream ->
                if (stream.interfaceNumber in claimed) {
                    val succeeded = attempt { link.selectAlternate(stream.interfaceNumber, 0) }
                    steps += CleanupStep("alternate setting 0 on interface ${stream.interfaceNumber}", succeeded)
                }
            }
            if (marker.volumeBefore != null) {
                val candidates = functions.flatMap { function ->
                    function.featureUnits.filter { it.masterVolume != UacControlAccess.ABSENT && it.masterVolume != UacControlAccess.READ_ONLY }
                        .map { function.controlInterface to it.id }
                }.filter { (number, unit) ->
                    (marker.controlInterface == null || marker.controlInterface == number) &&
                        (marker.volumeUnitId == null || marker.volumeUnitId == unit)
                }
                val target = candidates.singleOrNull()
                settingsRestored = target != null && target.first in claimed && attempt {
                    val client = Uac1ControlClient(link.control)
                    val (number, unit) = checkNotNull(target)
                    val muted = marker.mutedBefore == null ||
                        (client.setMute(unit, number, marker.mutedBefore) && client.mute(unit, number) == marker.mutedBefore)
                    val volume = client.setVolume(unit, number, marker.volumeBefore) &&
                        client.volume(unit, number) == marker.volumeBefore
                    muted && volume
                }
                steps += CleanupStep("volume restored", settingsRestored)
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
