package io.github.sumirenokai.vesqen.usb.control

/** One subrange of a UAC2 RANGE answer, in Hz. A resolution of 0 offers [minimum] alone, as in Linux. */
data class UacRateRange(val minimum: Int, val maximum: Int, val resolution: Int) {
    operator fun contains(rateHz: Int): Boolean = when {
        rateHz < minimum || rateHz > maximum -> false
        resolution == 0 -> rateHz == minimum
        else -> (rateHz - minimum) % resolution == 0
    }
}

/**
 * UAC2 control requests (Audio Device Class 2.0, section 5.2): CUR and RANGE to Clock Sources, Clock
 * Selectors and Feature Units, all addressed through the AudioControl interface.
 */
class Uac2ControlClient(private val usb: UsbControlTransfer, private val timeoutMs: Int = 500) : UacFeatureControls {
    /** Linux uses the first subrange of a volume RANGE too; a device that lists more is rare. */
    override fun volumeRange(unitId: Int, interfaceNumber: Int): UacVolumeRange? {
        val ranges = readRange(VOLUME_CONTROL, unitId, interfaceNumber, 2, MAX_VOLUME_SUBRANGES) ?: return null
        val (minimum, maximum, resolution) = ranges.first().map { it.toShort().toInt() }
        if (minimum == UAC1_VOLUME_SILENCE || minimum > maximum || resolution <= 0) return null
        return UacVolumeRange(minimum, maximum, resolution)
    }

    override fun volume(unitId: Int, interfaceNumber: Int): Int? =
        readCur(VOLUME_CONTROL, unitId, interfaceNumber, 2)?.let { it.toShort().toInt() }

    override fun setVolume(unitId: Int, interfaceNumber: Int, value: Int): Boolean =
        writeCur(VOLUME_CONTROL, unitId, interfaceNumber, value, 2)

    override fun mute(unitId: Int, interfaceNumber: Int): Boolean? =
        readCur(MUTE_CONTROL, unitId, interfaceNumber, 1)?.let { it != 0L }

    override fun setMute(unitId: Int, interfaceNumber: Int, muted: Boolean): Boolean =
        writeCur(MUTE_CONTROL, unitId, interfaceNumber, if (muted) 1 else 0, 1)

    /**
     * Every rate the clock source offers, or null when it gave no usable answer. A value past
     * Int.MAX_VALUE Hz turns negative here and fails the check, like a range that ends below its start.
     */
    fun clockRates(clockId: Int, interfaceNumber: Int): List<UacRateRange>? =
        readRange(SAM_FREQ_CONTROL, clockId, interfaceNumber, 4, MAX_RATE_SUBRANGES)?.map { (minimum, maximum, resolution) ->
            UacRateRange(minimum.toInt(), maximum.toInt(), resolution.toInt())
        }?.takeIf { ranges -> ranges.all { it.minimum in 1..it.maximum && it.resolution >= 0 } }

    fun clockRate(clockId: Int, interfaceNumber: Int): Int? =
        readCur(SAM_FREQ_CONTROL, clockId, interfaceNumber, 4)?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()

    fun setClockRate(clockId: Int, interfaceNumber: Int, rateHz: Int): Boolean =
        writeCur(SAM_FREQ_CONTROL, clockId, interfaceNumber, rateHz, 4)

    fun clockValid(clockId: Int, interfaceNumber: Int): Boolean? =
        readCur(CLOCK_VALID_CONTROL, clockId, interfaceNumber, 1)?.let { it != 0L }

    /** The selector's current input, counted from 1 as the spec does. */
    fun clockSelection(selectorId: Int, interfaceNumber: Int): Int? =
        readCur(CLOCK_SELECTOR_CONTROL, selectorId, interfaceNumber, 1)?.toInt()?.takeIf { it >= 1 }

    private fun readCur(control: Int, entityId: Int, interfaceNumber: Int, length: Int): Long? =
        read(CUR, control, entityId, interfaceNumber, length)?.littleEndian(0, length)

    private fun writeCur(control: Int, entityId: Int, interfaceNumber: Int, value: Int, length: Int): Boolean {
        val body = ByteArray(length) { (value shr (8 * it)).toByte() }
        return usb.transfer(TO_DEVICE, CUR, control shl 8, entityId shl 8 or interfaceNumber, body, length, timeoutMs) == length
    }

    /** The subranges as unsigned [size]-byte triplets. The count comes first, then the whole answer. */
    private fun readRange(control: Int, entityId: Int, interfaceNumber: Int, size: Int, limit: Int): List<List<Long>>? {
        val count = read(RANGE, control, entityId, interfaceNumber, 2)?.littleEndian(0, 2)?.toInt() ?: return null
        if (count !in 1..limit) return null
        val length = 2 + count * 3 * size
        val answer = read(RANGE, control, entityId, interfaceNumber, length) ?: return null
        if (answer.littleEndian(0, 2).toInt() != count) return null
        return List(count) { index -> List(3) { part -> answer.littleEndian(2 + (index * 3 + part) * size, size) } }
    }

    private fun read(request: Int, control: Int, entityId: Int, interfaceNumber: Int, length: Int): ByteArray? {
        val answer = ByteArray(length)
        val read = usb.transfer(TO_HOST, request, control shl 8, entityId shl 8 or interfaceNumber, answer, length, timeoutMs)
        return answer.takeIf { read == length }
    }

    private fun ByteArray.littleEndian(offset: Int, length: Int): Long =
        (0 until length).fold(0L) { value, index -> value or ((this[offset + index].toLong() and 0xFF) shl (8 * index)) }

    private companion object {
        const val TO_DEVICE = 0x21
        const val TO_HOST = 0xA1
        const val CUR = 0x01
        const val RANGE = 0x02

        // Clock Source controls (A.17.1) and the Clock Selector's (A.17.2).
        const val SAM_FREQ_CONTROL = 0x01
        const val CLOCK_VALID_CONTROL = 0x02
        const val CLOCK_SELECTOR_CONTROL = 0x01

        // Feature Unit controls (A.17.7).
        const val MUTE_CONTROL = 0x01
        const val VOLUME_CONTROL = 0x02

        /** A sane bound: a DAC lists a handful of rates, and the answer must fit a control transfer. */
        const val MAX_RATE_SUBRANGES = 64
        const val MAX_VOLUME_SUBRANGES = 16
    }
}
