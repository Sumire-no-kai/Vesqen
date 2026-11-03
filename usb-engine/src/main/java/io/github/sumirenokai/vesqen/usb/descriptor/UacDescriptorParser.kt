package io.github.sumirenokai.vesqen.usb.descriptor

sealed interface UacParseResult {
    data class Parsed(val topology: UsbAudioTopology) : UacParseResult

    data class Malformed(val offset: Int, val reason: String) : UacParseResult
}

/**
 * Reads `UsbDeviceConnection.getRawDescriptors()` into a [UsbAudioTopology].
 *
 * The bytes come from the device, so they are untrusted: every length is checked before it is used,
 * and a descriptor that does not fit its own declared structure makes the whole result [UacParseResult.Malformed].
 * Descriptors this parser has no use for are skipped by their declared length.
 */
object UacDescriptorParser {
    fun parse(raw: ByteArray): UacParseResult = try {
        UacParseResult.Parsed(Walker(raw).walk())
    } catch (failure: MalformedDescriptor) {
        UacParseResult.Malformed(failure.offset, failure.reason)
    }
}

private class MalformedDescriptor(val offset: Int, val reason: String) : Exception()

private const val TYPE_DEVICE = 0x01
private const val TYPE_CONFIGURATION = 0x02
private const val TYPE_INTERFACE = 0x04
private const val TYPE_ENDPOINT = 0x05
private const val TYPE_INTERFACE_ASSOCIATION = 0x0B
private const val TYPE_CS_INTERFACE = 0x24
private const val TYPE_CS_ENDPOINT = 0x25

private const val CLASS_AUDIO = 0x01
private const val SUBCLASS_AUDIO_CONTROL = 0x01
private const val SUBCLASS_AUDIO_STREAMING = 0x02
private const val PROTOCOL_UAC1 = 0x00
private const val PROTOCOL_UAC2 = 0x20

private const val AS_GENERAL = 0x01
private const val AS_FORMAT_TYPE = 0x02
private const val EP_GENERAL = 0x01
private const val FORMAT_TYPE_I = 0x01

private fun versionOf(protocol: Int) = when (protocol) {
    PROTOCOL_UAC1 -> UacVersion.UAC1
    PROTOCOL_UAC2 -> UacVersion.UAC2
    else -> UacVersion.OTHER
}

/** One descriptor whose declared length has been checked against the buffer. */
private class Descriptor(private val raw: ByteArray, val offset: Int, val length: Int) {
    val type: Int get() = u8(1)

    fun u8(index: Int): Int = raw[offset + index].toInt() and 0xFF

    fun u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

    fun u24(index: Int): Int = u16(index) or (u8(index + 2) shl 16)

    fun u32(index: Int): Long = u16(index).toLong() or (u16(index + 2).toLong() shl 16)

    fun requireLength(minimum: Int, name: String) {
        if (length < minimum) throw MalformedDescriptor(offset, "$name needs $minimum bytes, has $length")
    }

    fun bytes(from: Int, count: Int): List<Int> = List(count) { u8(from + it) }
}

private class Walker(private val raw: ByteArray) {
    private val configurations = mutableListOf<ConfigurationBuilder>()
    private var configuration: ConfigurationBuilder? = null
    private var control: ControlBuilder? = null
    private var alt: AltBuilder? = null

    fun walk(): UsbAudioTopology {
        val device = next(0)
        if (device.type != TYPE_DEVICE) throw MalformedDescriptor(0, "The first descriptor is not a device")
        device.requireLength(18, "Device descriptor")
        var offset = device.length
        while (offset < raw.size) {
            val descriptor = next(offset)
            read(descriptor)
            offset += descriptor.length
        }
        return UsbAudioTopology(
            usbVersionBcd = device.u16(2),
            vendorId = device.u16(8),
            productId = device.u16(10),
            configurations = configurations.map(ConfigurationBuilder::build),
        )
    }

    private fun next(offset: Int): Descriptor {
        if (offset + 2 > raw.size) throw MalformedDescriptor(offset, "Truncated descriptor header")
        val length = raw[offset].toInt() and 0xFF
        if (length < 2) throw MalformedDescriptor(offset, "Descriptor declares length $length")
        if (offset + length > raw.size) throw MalformedDescriptor(offset, "Descriptor runs past the end")
        return Descriptor(raw, offset, length)
    }

    private fun read(descriptor: Descriptor) {
        when (descriptor.type) {
            TYPE_DEVICE -> throw MalformedDescriptor(descriptor.offset, "A second device descriptor")
            TYPE_CONFIGURATION -> {
                descriptor.requireLength(9, "Configuration descriptor")
                configuration = ConfigurationBuilder(descriptor.u8(5)).also(configurations::add)
                control = null
                alt = null
            }
            TYPE_INTERFACE_ASSOCIATION -> {
                descriptor.requireLength(8, "Interface association descriptor")
                if (descriptor.u8(4) == CLASS_AUDIO) {
                    currentConfiguration(descriptor).associations +=
                        descriptor.u8(2) until descriptor.u8(2) + descriptor.u8(3)
                }
            }
            TYPE_INTERFACE -> readInterface(descriptor)
            TYPE_ENDPOINT -> alt?.readEndpoint(descriptor)
            TYPE_CS_INTERFACE -> {
                descriptor.requireLength(3, "Class-specific interface descriptor")
                control?.read(descriptor)
                alt?.readInterface(descriptor)
            }
            TYPE_CS_ENDPOINT -> {
                descriptor.requireLength(3, "Class-specific endpoint descriptor")
                alt?.readClassEndpoint(descriptor)
            }
        }
    }

    private fun readInterface(descriptor: Descriptor) {
        descriptor.requireLength(9, "Interface descriptor")
        val config = currentConfiguration(descriptor)
        val number = descriptor.u8(2)
        val alternate = descriptor.u8(3)
        val protocol = descriptor.u8(7)
        control = null
        alt = null
        if (descriptor.u8(5) != CLASS_AUDIO) return
        when (descriptor.u8(6)) {
            SUBCLASS_AUDIO_CONTROL ->
                if (config.controls.none { it.interfaceNumber == number }) {
                    control = ControlBuilder(number, protocol).also(config.controls::add)
                }
            SUBCLASS_AUDIO_STREAMING -> {
                val streaming = config.streaming.getOrPut(number) {
                    StreamingBuilder(number, config.controls.lastOrNull())
                }
                alt = AltBuilder(alternate, protocol).also(streaming.alternates::add)
            }
        }
    }

    private fun currentConfiguration(descriptor: Descriptor) = configuration
        ?: throw MalformedDescriptor(descriptor.offset, "Descriptor outside a configuration")
}

private class ConfigurationBuilder(val value: Int) {
    val controls = mutableListOf<ControlBuilder>()
    val streaming = linkedMapOf<Int, StreamingBuilder>()
    val associations = mutableListOf<IntRange>()

    fun build(): UacConfiguration {
        val byControl = streaming.values.groupBy { stream -> ownerOf(stream) }
        return UacConfiguration(
            value = value,
            functions = controls.map { it.build(byControl[it].orEmpty().map(StreamingBuilder::build)) },
        )
    }

    /**
     * UAC1 names its streaming interfaces in the control header and UAC2 groups them with an
     * association. Devices that do neither get the control interface described before them.
     */
    private fun ownerOf(stream: StreamingBuilder): ControlBuilder? {
        val number = stream.interfaceNumber
        return controls.firstOrNull { number in it.collection }
            ?: controls.firstOrNull { control ->
                associations.any { control.interfaceNumber in it && number in it }
            }
            ?: stream.precedingControl
    }
}

private class StreamingBuilder(val interfaceNumber: Int, val precedingControl: ControlBuilder?) {
    val alternates = mutableListOf<AltBuilder>()

    fun build() = UacStreamingInterface(interfaceNumber, alternates.map(AltBuilder::build))
}

private class ControlBuilder(val interfaceNumber: Int, private val protocol: Int) {
    private val version = versionOf(protocol)
    val collection = mutableListOf<Int>()
    private val inputTerminals = mutableListOf<UacInputTerminal>()
    private val outputTerminals = mutableListOf<UacOutputTerminal>()
    private val featureUnits = mutableListOf<UacFeatureUnit>()
    private val otherUnits = mutableListOf<UacOtherUnit>()
    private val clockEntities = mutableListOf<UacClockEntity>()

    fun read(d: Descriptor) {
        when (version) {
            UacVersion.UAC1 -> readUac1(d)
            UacVersion.UAC2 -> readUac2(d)
            UacVersion.OTHER -> Unit
        }
    }

    private fun readUac1(d: Descriptor) {
        when (d.u8(2)) {
            0x01 -> {
                d.requireLength(8, "UAC1 header")
                val count = d.u8(7)
                d.requireLength(8 + count, "UAC1 header with $count interfaces")
                collection += d.bytes(8, count)
            }
            0x02 -> {
                d.requireLength(12, "UAC1 input terminal")
                inputTerminals += UacInputTerminal(d.u8(3), d.u16(4), clockId = null)
            }
            0x03 -> {
                d.requireLength(9, "UAC1 output terminal")
                outputTerminals += UacOutputTerminal(d.u8(3), d.u16(4), d.u8(7), clockId = null)
            }
            0x04, 0x05 -> otherUnits += unitWithPins(d, idAt = 3, countAt = 4, "UAC1 mixer or selector unit")
            0x06 -> featureUnits += uac1FeatureUnit(d)
            0x07, 0x08 -> otherUnits += unitWithPins(d, idAt = 3, countAt = 6, "UAC1 processing or extension unit")
        }
    }

    private fun readUac2(d: Descriptor) {
        when (d.u8(2)) {
            0x02 -> {
                d.requireLength(17, "UAC2 input terminal")
                inputTerminals += UacInputTerminal(d.u8(3), d.u16(4), clockId = d.u8(7))
            }
            0x03 -> {
                d.requireLength(12, "UAC2 output terminal")
                outputTerminals += UacOutputTerminal(d.u8(3), d.u16(4), d.u8(7), clockId = d.u8(8))
            }
            0x04, 0x05 -> otherUnits += unitWithPins(d, idAt = 3, countAt = 4, "UAC2 mixer or selector unit")
            0x06 -> featureUnits += uac2FeatureUnit(d)
            0x07 -> {
                d.requireLength(7, "UAC2 effect unit")
                otherUnits += UacOtherUnit(d.u8(3), listOf(d.u8(6)))
            }
            0x08, 0x09 -> otherUnits += unitWithPins(d, idAt = 3, countAt = 6, "UAC2 processing or extension unit")
            0x0A -> {
                d.requireLength(8, "UAC2 clock source")
                clockEntities += UacClockEntity.Source(
                    id = d.u8(3),
                    type = UacClockEntity.SourceType.entries[d.u8(4) and 0b11],
                    frequency = uac2Access(d.u8(5)),
                    validity = uac2Access(d.u8(5) shr 2),
                )
            }
            0x0B -> {
                d.requireLength(5, "UAC2 clock selector")
                val pins = d.u8(4)
                d.requireLength(7 + pins, "UAC2 clock selector with $pins inputs")
                clockEntities += UacClockEntity.Selector(d.u8(3), d.bytes(5, pins), uac2Access(d.u8(5 + pins)))
            }
            0x0C -> {
                d.requireLength(7, "UAC2 clock multiplier")
                clockEntities += UacClockEntity.Multiplier(d.u8(3), d.u8(4))
            }
            0x0D -> {
                d.requireLength(8, "UAC2 sample rate converter")
                otherUnits += UacOtherUnit(d.u8(3), listOf(d.u8(4)))
            }
        }
    }

    private fun unitWithPins(d: Descriptor, idAt: Int, countAt: Int, name: String): UacOtherUnit {
        d.requireLength(countAt + 1, name)
        val pins = d.u8(countAt)
        d.requireLength(countAt + 1 + pins, "$name with $pins inputs")
        return UacOtherUnit(d.u8(idAt), d.bytes(countAt + 1, pins))
    }

    private fun uac1FeatureUnit(d: Descriptor): UacFeatureUnit {
        d.requireLength(7, "UAC1 feature unit")
        val controlSize = d.u8(5)
        if (controlSize == 0) throw MalformedDescriptor(d.offset, "UAC1 feature unit with empty controls")
        if ((d.length - 7) % controlSize != 0) {
            throw MalformedDescriptor(d.offset, "UAC1 feature unit with a partial control entry")
        }
        val entries = (d.length - 7) / controlSize
        if (entries < 1) throw MalformedDescriptor(d.offset, "UAC1 feature unit without master controls")
        fun controls(entry: Int) = d.u8(6 + entry * controlSize)
        fun access(present: Boolean) =
            if (present) UacControlAccess.PRESENT_ACCESS_UNKNOWN else UacControlAccess.ABSENT
        return UacFeatureUnit(
            id = d.u8(3),
            sourceId = d.u8(4),
            masterMute = access(controls(0) and 0b01 != 0),
            masterVolume = access(controls(0) and 0b10 != 0),
            channelVolumes = List(entries - 1) { access(controls(it + 1) and 0b10 != 0) },
        )
    }

    private fun uac2FeatureUnit(d: Descriptor): UacFeatureUnit {
        d.requireLength(10, "UAC2 feature unit")
        if ((d.length - 6) % 4 != 0) {
            throw MalformedDescriptor(d.offset, "UAC2 feature unit with a partial control entry")
        }
        val entries = (d.length - 6) / 4
        fun controls(entry: Int) = d.u32(5 + entry * 4).toInt()
        return UacFeatureUnit(
            id = d.u8(3),
            sourceId = d.u8(4),
            masterMute = uac2Access(controls(0)),
            masterVolume = uac2Access(controls(0) shr 2),
            channelVolumes = List(entries - 1) { uac2Access(controls(it + 1) shr 2) },
        )
    }

    /** The spec forbids 0b10, and a control that cannot be trusted is treated as not being there. */
    private fun uac2Access(bits: Int) = when (bits and 0b11) {
        0b01 -> UacControlAccess.READ_ONLY
        0b11 -> UacControlAccess.WRITABLE
        else -> UacControlAccess.ABSENT
    }

    fun build(streaming: List<UacStreamingInterface>) = UacFunction(
        version = version,
        interfaceProtocol = protocol,
        controlInterface = interfaceNumber,
        inputTerminals = inputTerminals,
        outputTerminals = outputTerminals,
        featureUnits = featureUnits,
        otherUnits = otherUnits,
        clockEntities = clockEntities,
        streamingInterfaces = streaming,
    )
}

private class AltBuilder(private val alternate: Int, protocol: Int) {
    private val version = versionOf(protocol)
    private val endpoints = mutableListOf<UacEndpoint>()
    private var terminalLink: Int? = null
    private var kind: UacPcmKind? = null
    private var general: Pair<Int, Int>? = null
    private var format: UacFormat? = null
    private var endpointSetsSampleRate = false

    fun readInterface(d: Descriptor) {
        when (version) {
            UacVersion.UAC1 -> when (d.u8(2)) {
                AS_GENERAL -> {
                    d.requireLength(7, "UAC1 streaming general")
                    terminalLink = d.u8(3)
                    kind = when (d.u16(5)) {
                        0x0001 -> UacPcmKind.LINEAR_PCM
                        0x0003 -> UacPcmKind.FLOAT
                        else -> UacPcmKind.OTHER
                    }
                }
                AS_FORMAT_TYPE -> readUac1Format(d)
            }
            UacVersion.UAC2 -> when (d.u8(2)) {
                AS_GENERAL -> {
                    d.requireLength(16, "UAC2 streaming general")
                    terminalLink = d.u8(3)
                    val formats = d.u32(6)
                    kind = when {
                        formats and (1L shl 31) != 0L -> UacPcmKind.OTHER
                        formats and 0b001 != 0L -> UacPcmKind.LINEAR_PCM
                        formats and 0b100 != 0L -> UacPcmKind.FLOAT
                        else -> UacPcmKind.OTHER
                    }
                    general = d.u8(5) to d.u8(10)
                }
                AS_FORMAT_TYPE -> readUac2Format(d)
            }
            UacVersion.OTHER -> Unit
        }
    }

    private fun readUac1Format(d: Descriptor) {
        d.requireLength(4, "UAC1 format type")
        if (d.u8(3) != FORMAT_TYPE_I) {
            format = notTypeI()
            return
        }
        d.requireLength(8, "UAC1 Type I format")
        val rateCount = d.u8(7)
        val rates = if (rateCount == 0) {
            d.requireLength(14, "UAC1 continuous sample rates")
            SampleRates.Continuous(d.u24(8), d.u24(11))
        } else {
            d.requireLength(8 + rateCount * 3, "UAC1 format with $rateCount sample rates")
            SampleRates.Discrete(List(rateCount) { d.u24(8 + it * 3) })
        }
        format = UacFormat(
            kind = UacPcmKind.OTHER,
            isTypeI = true,
            channels = d.u8(4),
            subslotBytes = d.u8(5),
            bitResolution = d.u8(6),
            sampleRates = rates,
        )
    }

    private fun readUac2Format(d: Descriptor) {
        d.requireLength(4, "UAC2 format type")
        if (d.u8(3) != FORMAT_TYPE_I) {
            format = notTypeI()
            return
        }
        d.requireLength(6, "UAC2 Type I format")
        format = UacFormat(
            kind = UacPcmKind.OTHER,
            isTypeI = true,
            channels = 0,
            subslotBytes = d.u8(4),
            bitResolution = d.u8(5),
            sampleRates = SampleRates.RuntimeUnknown,
        )
    }

    private fun notTypeI() = UacFormat(
        kind = UacPcmKind.OTHER,
        isTypeI = false,
        channels = 0,
        subslotBytes = 0,
        bitResolution = 0,
        sampleRates = SampleRates.RuntimeUnknown,
    )

    fun readEndpoint(d: Descriptor) {
        d.requireLength(7, "Endpoint descriptor")
        val attributes = d.u8(3)
        val maxPacket = d.u16(4)
        endpoints += UacEndpoint(
            address = d.u8(2),
            isIsochronous = attributes and 0b11 == 0b01,
            syncType = UacSyncType.entries[(attributes shr 2) and 0b11],
            usage = UacEndpointUsage.entries[(attributes shr 4) and 0b11],
            maxPacketBytes = maxPacket and 0x7FF,
            additionalTransactions = (maxPacket shr 11) and 0b11,
            interval = d.u8(6),
            synchAddress = if (d.length >= 9) d.u8(8) else 0,
        )
    }

    fun readClassEndpoint(d: Descriptor) {
        if (d.u8(2) != EP_GENERAL || version != UacVersion.UAC1) return
        d.requireLength(7, "UAC1 endpoint general")
        endpointSetsSampleRate = d.u8(3) and 0b1 != 0
    }

    fun build(): UacAltSetting {
        val named = endpoints.mapNotNull { it.synchAddress.takeIf { address -> address != 0 } }
        val isFeedback = { endpoint: UacEndpoint ->
            endpoint.usage == UacEndpointUsage.FEEDBACK || endpoint.address in named
        }
        return UacAltSetting(
            alternateSetting = alternate,
            terminalLink = terminalLink,
            format = format?.let { parsed ->
                // UAC2 keeps the channel count and format type in the general descriptor.
                val kind = kind ?: return@let null
                val (formatType, channels) = general ?: (FORMAT_TYPE_I to parsed.channels)
                parsed.copy(kind = kind, isTypeI = parsed.isTypeI && formatType == FORMAT_TYPE_I, channels = channels)
            },
            dataEndpoint = endpoints.firstOrNull { !isFeedback(it) },
            feedbackEndpoint = endpoints.firstOrNull(isFeedback),
            endpointSetsSampleRate = endpointSetsSampleRate,
        )
    }
}
