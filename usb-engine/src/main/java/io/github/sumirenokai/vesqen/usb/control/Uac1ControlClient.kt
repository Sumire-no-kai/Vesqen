package io.github.sumirenokai.vesqen.usb.control

/** UsbDeviceConnection.controlTransfer, kept small so the request flows can run against a fake. */
fun interface UsbControlTransfer {
    fun transfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int
}

/** A Feature Unit's volume attributes, in the spec's 1/256 dB. */
data class UacVolumeRange(val minimum: Int, val maximum: Int, val resolution: Int) {
    /** The closest setting at or below [target], and the lowest one when the device cannot go that low. */
    fun settingAtOrBelow(target: Int): Int {
        val limited = target.coerceIn(minimum, maximum)
        return minimum + (limited - minimum) / resolution * resolution
    }
}

/**
 * UAC1 control requests. Each answer's length and value are checked, and a null result means the
 * device did not give a usable answer, which is different from answering "not supported" in its
 * descriptors. These requests need the interface or endpoint they address to be claimed.
 */
class Uac1ControlClient(private val usb: UsbControlTransfer, private val timeoutMs: Int = 500) {
    fun volumeRange(unitId: Int, interfaceNumber: Int): UacVolumeRange? {
        val minimum = readFeature(GET_MIN, VOLUME_CONTROL, unitId, interfaceNumber, 2)?.toSigned16() ?: return null
        val maximum = readFeature(GET_MAX, VOLUME_CONTROL, unitId, interfaceNumber, 2)?.toSigned16() ?: return null
        val resolution = readFeature(GET_RES, VOLUME_CONTROL, unitId, interfaceNumber, 2)?.toSigned16() ?: return null
        // 0x8000 means silence and is legal only as a current value.
        if (minimum == UAC1_VOLUME_SILENCE || minimum > maximum || resolution <= 0) return null
        return UacVolumeRange(minimum, maximum, resolution)
    }

    /** The current volume in 1/256 dB. [UAC1_VOLUME_SILENCE] is a legal answer. */
    fun volume(unitId: Int, interfaceNumber: Int): Int? =
        readFeature(GET_CUR, VOLUME_CONTROL, unitId, interfaceNumber, 2)?.toSigned16()

    fun setVolume(unitId: Int, interfaceNumber: Int, value: Int): Boolean =
        writeFeature(VOLUME_CONTROL, unitId, interfaceNumber, byteArrayOf(value.toByte(), (value shr 8).toByte()))

    fun mute(unitId: Int, interfaceNumber: Int): Boolean? =
        readFeature(GET_CUR, MUTE_CONTROL, unitId, interfaceNumber, 1)?.let { it[0].toInt() != 0 }

    fun setMute(unitId: Int, interfaceNumber: Int, muted: Boolean): Boolean =
        writeFeature(MUTE_CONTROL, unitId, interfaceNumber, byteArrayOf(if (muted) 1 else 0))

    /** Restore the saved level before unmuting, and verify writes before the recovery record is cleared. */
    fun restoreSettings(unit: Int, number: Int, level: Int, muted: Boolean?): Boolean {
        fun restoreMute(value: Boolean) = mute(unit, number) == value ||
            (setMute(unit, number, value) && mute(unit, number) == value)
        if (muted == true && !restoreMute(true)) return false
        if (!setVolume(unit, number, level) || volume(unit, number) != level) return false
        return muted != false || restoreMute(false)
    }

    fun sampleRate(endpointAddress: Int): Int? {
        val answer = ByteArray(3)
        val length = usb.transfer(TO_HOST_ENDPOINT, GET_CUR, SAMPLING_FREQ_VALUE, endpointAddress, answer, 3, timeoutMs)
        if (length != 3) return null
        return (answer[0].toInt() and 0xFF) or ((answer[1].toInt() and 0xFF) shl 8) or ((answer[2].toInt() and 0xFF) shl 16)
    }

    fun setSampleRate(endpointAddress: Int, hz: Int): Boolean {
        val body = byteArrayOf(hz.toByte(), (hz shr 8).toByte(), (hz shr 16).toByte())
        return usb.transfer(TO_DEVICE_ENDPOINT, SET_CUR, SAMPLING_FREQ_VALUE, endpointAddress, body, 3, timeoutMs) == 3
    }

    private fun readFeature(request: Int, control: Int, unitId: Int, interfaceNumber: Int, length: Int): ByteArray? {
        val answer = ByteArray(length)
        val read = usb.transfer(TO_HOST_INTERFACE, request, control shl 8, unitId shl 8 or interfaceNumber, answer, length, timeoutMs)
        return answer.takeIf { read == length }
    }

    private fun writeFeature(control: Int, unitId: Int, interfaceNumber: Int, body: ByteArray): Boolean = usb.transfer(
        TO_DEVICE_INTERFACE,
        SET_CUR,
        control shl 8,
        unitId shl 8 or interfaceNumber,
        body,
        body.size,
        timeoutMs,
    ) == body.size

    private fun ByteArray.toSigned16(): Int = (this[0].toInt() and 0xFF) or (this[1].toInt() shl 8)

    private companion object {
        const val TO_DEVICE_INTERFACE = 0x21
        const val TO_HOST_INTERFACE = 0xA1
        const val TO_DEVICE_ENDPOINT = 0x22
        const val TO_HOST_ENDPOINT = 0xA2
        const val SET_CUR = 0x01
        const val GET_CUR = 0x81
        const val GET_MIN = 0x82
        const val GET_MAX = 0x83
        const val GET_RES = 0x84
        const val MUTE_CONTROL = 0x01
        const val VOLUME_CONTROL = 0x02
        const val SAMPLING_FREQ_VALUE = 0x0100
    }
}

const val UAC1_VOLUME_SILENCE = -0x8000
