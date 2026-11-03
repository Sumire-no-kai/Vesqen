package io.github.sumirenokai.vesqen.usb.descriptor

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Descriptors built from the UAC 1.0 and 2.0 layouts. Real devices' dumps join these as they are
 * recorded in docs/M7_DEVICE_ACCEPTANCE.md.
 */
class UacDescriptorParserTest {
    @Test
    fun `the device identity comes from the device descriptor`() {
        val topology = parsed(uac1Device())

        assertEquals(0x0110, topology.usbVersionBcd)
        assertEquals(0x1234, topology.vendorId)
        assertEquals(0x5678, topology.productId)
        assertEquals(listOf(1), topology.configurations.map(UacConfiguration::value))
    }

    @Test
    fun `a uac1 playback setting lists its format rates and feedback endpoint`() {
        val function = parsed(uac1Device()).functions.single()
        assertEquals(UacVersion.UAC1, function.version)
        assertEquals(0, function.controlInterface)

        val playback = function.streamingInterfaces.first { it.interfaceNumber == 1 }
        assertEquals(listOf(0, 1), playback.alternateSettings.map(UacAltSetting::alternateSetting))
        assertNull(playback.alternateSettings[0].format)
        assertFalse(playback.alternateSettings[0].isPlayback)

        val alt = playback.alternateSettings[1]
        assertTrue(alt.isPlayback)
        assertEquals(1, alt.terminalLink)
        assertEquals(
            UacFormat(UacPcmKind.LINEAR_PCM, true, 2, 2, 16, SampleRates.Discrete(listOf(44_100, 48_000))),
            alt.format,
        )
        val data = alt.dataEndpoint!!
        assertEquals(0x01, data.address)
        assertTrue(data.isIsochronous)
        assertEquals(UacSyncType.ASYNCHRONOUS, data.syncType)
        assertEquals(192, data.maxPacketBytes)
        assertEquals(1, data.interval)
        assertEquals(0x81, alt.feedbackEndpoint!!.address)
        assertTrue(alt.endpointSetsSampleRate)
        assertTrue(alt.streamingBlockers.isEmpty())
    }

    @Test
    fun `uac1 can describe a continuous rate range`() {
        val alt = onlyPlayback(
            device(0x0110) + configuration() + uac1Control() +
                interfaceOf(1, 1, SUBCLASS_STREAMING) + uac1General(link = 1) +
                classInterface(2, 1, 2, 2, 16, 0, *le24(8_000), *le24(96_000)) +
                endpoint(0x01, ISO_ADAPTIVE, 192, 1),
        )

        assertEquals(SampleRates.Continuous(8_000, 96_000), alt.format!!.sampleRates)
        assertEquals(UacSyncType.ADAPTIVE, alt.dataEndpoint!!.syncType)
        assertNull(alt.feedbackEndpoint)
        assertTrue(alt.streamingBlockers.isEmpty())
    }

    @Test
    fun `uac1 volume is present but whether it can be written stays unknown`() {
        val unit = parsed(uac1Device()).functions.single().featureUnitsAfter(1).single()

        assertEquals(2, unit.id)
        assertEquals(UacControlAccess.PRESENT_ACCESS_UNKNOWN, unit.masterVolume)
        assertEquals(UacControlAccess.PRESENT_ACCESS_UNKNOWN, unit.masterMute)
        assertEquals(listOf(UacControlAccess.PRESENT_ACCESS_UNKNOWN, UacControlAccess.ABSENT), unit.channelVolumes)
    }

    @Test
    fun `the microphone stream is capture and has no feature unit on the playback path`() {
        val function = parsed(uac1Device()).functions.single()
        val capture = function.streamingInterfaces.first { it.interfaceNumber == 2 }.alternateSettings.last()

        assertTrue(capture.isCapture)
        assertFalse(capture.isPlayback)
        assertEquals(listOf(2), function.featureUnitsAfter(1).map(UacFeatureUnit::id))
        assertTrue(function.featureUnitsAfter(capture.terminalLink!!).isEmpty())
    }

    @Test
    fun `uac2 rates stay unknown until a clock request while channels come from the general descriptor`() {
        val function = parsed(uac2Device()).functions.single()
        assertEquals(UacVersion.UAC2, function.version)

        val alternates = function.streamingInterfaces.single().alternateSettings
        assertEquals(listOf(0, 1, 2), alternates.map(UacAltSetting::alternateSetting))
        assertEquals(
            UacFormat(UacPcmKind.LINEAR_PCM, true, 2, 3, 24, SampleRates.RuntimeUnknown),
            alternates[1].format,
        )
        assertEquals(
            UacFormat(UacPcmKind.LINEAR_PCM, true, 2, 4, 32, SampleRates.RuntimeUnknown),
            alternates[2].format,
        )
        assertEquals(0x81, alternates[1].feedbackEndpoint!!.address)
        assertEquals(UacEndpointUsage.FEEDBACK, alternates[1].feedbackEndpoint!!.usage)
        assertTrue(alternates[1].streamingBlockers.isEmpty())
    }

    @Test
    fun `uac2 states whether each control can be written`() {
        val unit = parsed(uac2Device()).functions.single().featureUnitsAfter(1).single()

        assertEquals(UacControlAccess.WRITABLE, unit.masterVolume)
        assertEquals(UacControlAccess.WRITABLE, unit.masterMute)
        assertEquals(listOf(UacControlAccess.READ_ONLY, UacControlAccess.ABSENT), unit.channelVolumes)
    }

    /** D4 treats a device with only a mute control as having a fixed volume. */
    @Test
    fun `a mute control alone is not a volume control`() {
        val uac1 = parsed(
            device(0x0110) + configuration() +
                interfaceOf(0, 0, SUBCLASS_CONTROL) + uac1Header(1) +
                classInterface(2, 1, *le16(0x0101), 0, 2, 0, 0, 0, 0) +
                classInterface(6, 2, 1, 1, 0b01, 0) +
                classInterface(3, 3, *le16(0x0301), 0, 2, 0),
        ).functions.single().featureUnitsAfter(1).single()
        assertEquals(UacControlAccess.PRESENT_ACCESS_UNKNOWN, uac1.masterMute)
        assertEquals(UacControlAccess.ABSENT, uac1.masterVolume)

        val uac2 = parsed(
            uac2Device(featureUnit = classInterface(6, 2, 1, *le32(0b0011), *le32(0), 0)),
        ).functions.single().featureUnitsAfter(1).single()
        assertEquals(UacControlAccess.WRITABLE, uac2.masterMute)
        assertEquals(UacControlAccess.ABSENT, uac2.masterVolume)
    }

    @Test
    fun `a uac2 control with the forbidden access value counts as absent`() {
        val unit = parsed(
            uac2Device(featureUnit = classInterface(6, 2, 1, *le32(0b1000), *le32(0), 0)),
        ).functions.single().featureUnitsAfter(1).single()

        assertEquals(UacControlAccess.ABSENT, unit.masterVolume)
    }

    @Test
    fun `the clock chain is followed through a selector to its sources`() {
        val function = parsed(uac2Device()).functions.single()

        assertEquals(
            listOf(
                UacClockEntity.Source(
                    10,
                    UacClockEntity.SourceType.INTERNAL_PROGRAMMABLE,
                    UacControlAccess.WRITABLE,
                    UacControlAccess.READ_ONLY,
                ),
            ),
            function.clockSourcesFor(1),
        )
        assertEquals(
            UacClockEntity.Selector(11, listOf(10), UacControlAccess.WRITABLE),
            function.clockEntities.first { it.id == 11 },
        )
    }

    @Test
    fun `a fixed clock is reported as read only rather than settable`() {
        val source = parsed(
            uac2Device(clockSource = classInterface(0x0A, 10, 0b01, 0b0001, 0, 0)),
        ).functions.single().clockSourcesFor(1).single()

        assertEquals(UacClockEntity.SourceType.INTERNAL_FIXED, source.type)
        assertEquals(UacControlAccess.READ_ONLY, source.frequency)
        assertEquals(UacControlAccess.ABSENT, source.validity)
    }

    @Test
    fun `a device with no feature unit has none on the path`() {
        val function = parsed(
            device(0x0200) + configuration() +
                interfaceOf(0, 0, SUBCLASS_CONTROL) + uac1Header(1) +
                classInterface(2, 1, *le16(0x0101), 0, 2, 0, 0, 0, 0) +
                classInterface(3, 3, *le16(0x0301), 0, 1, 0),
        ).functions.single()

        assertTrue(function.featureUnitsAfter(1).isEmpty())
    }

    @Test
    fun `a stream routed through a selector still finds the feature unit behind it`() {
        val function = parsed(
            device(0x0200) + configuration() +
                interfaceOf(0, 0, SUBCLASS_CONTROL) + uac1Header(1) +
                classInterface(2, 1, *le16(0x0101), 0, 2, 0, 0, 0, 0) +
                classInterface(6, 2, 1, 1, 0b10, 0) +
                classInterface(5, 4, 1, 2, 0) +
                classInterface(3, 3, *le16(0x0301), 0, 4, 0),
        ).functions.single()

        assertEquals(listOf(2), function.featureUnitsAfter(1).map(UacFeatureUnit::id))

        val behindSelector = parsed(
            device(0x0200) + configuration() +
                interfaceOf(0, 0, SUBCLASS_CONTROL) + uac1Header(1) +
                classInterface(2, 1, *le16(0x0101), 0, 2, 0, 0, 0, 0) +
                classInterface(5, 4, 1, 1, 0) +
                classInterface(6, 2, 4, 1, 0b10, 0) +
                classInterface(3, 3, *le16(0x0301), 0, 2, 0),
        ).functions.single()
        assertEquals(listOf(2), behindSelector.featureUnitsAfter(1).map(UacFeatureUnit::id))
    }

    @Test
    fun `a device that describes a loop does not hang the path search`() {
        val function = parsed(
            device(0x0200) + configuration() +
                interfaceOf(0, 0, SUBCLASS_CONTROL) + uac1Header(1) +
                classInterface(6, 2, 4, 1, 0b10, 0) +
                classInterface(6, 4, 2, 1, 0b10, 0) +
                classInterface(3, 3, *le16(0x0301), 0, 2, 0),
        ).functions.single()

        assertTrue(function.featureUnitsAfter(1).isEmpty())
    }

    @Test
    fun `a clock loop does not hang the chain search`() {
        val function = parsed(
            uac2Device(clockSource = classInterface(0x0C, 10, 11, 0, 0)),
        ).functions.single()

        assertTrue(function.clockSourcesFor(1).isEmpty())
    }

    @Test
    fun `other classes around the audio function are skipped`() {
        val raw = device(0x0200) + configuration() +
            interfaceOf(3, 0, 0, classCode = 0x03) +
            byteArrayOf(9, 0x21, 1, 1, 0, 1, 0x22, 40, 0) +
            endpoint(0x85, 0x03, 8, 10) +
            classInterface(1, 0xFF, 0xFF) +
            uac1Control() + interfaceOf(1, 1, SUBCLASS_STREAMING) + uac1General(1) + uac1Format() +
            endpoint(0x01, ISO_ADAPTIVE, 192, 1)

        val function = parsed(raw).functions.single()

        assertEquals(listOf(1), function.streamingInterfaces.map(UacStreamingInterface::interfaceNumber))
    }

    @Test
    fun `a device with both configurations keeps them apart`() {
        val topology = parsed(uac1Device() + configuration(value = 2) + uac2Function())

        assertEquals(listOf(1, 2), topology.configurations.map(UacConfiguration::value))
        assertEquals(UacVersion.UAC1, topology.configurations[0].functions.single().version)
        assertEquals(UacVersion.UAC2, topology.configurations[1].functions.single().version)
    }

    @Test
    fun `streaming interfaces belong to the function that names them`() {
        val secondControl = interfaceOf(2, 0, SUBCLASS_CONTROL)
        val named = parsed(
            device(0x0110) + configuration() +
                interfaceOf(0, 0, SUBCLASS_CONTROL) + uac1Header(1) +
                secondControl + uac1Header(3) +
                interfaceOf(1, 1, SUBCLASS_STREAMING) + interfaceOf(3, 1, SUBCLASS_STREAMING),
        ).functions
        assertEquals(listOf(1), named[0].streamingInterfaces.map(UacStreamingInterface::interfaceNumber))
        assertEquals(listOf(3), named[1].streamingInterfaces.map(UacStreamingInterface::interfaceNumber))

        val associated = parsed(
            device(0x0200) + configuration() +
                byteArrayOf(8, 0x0B, 0, 2, 1, 0, 0x20, 0) + byteArrayOf(8, 0x0B, 2, 2, 1, 0, 0x20, 0) +
                interfaceOf(0, 0, SUBCLASS_CONTROL, protocol = 0x20) +
                interfaceOf(2, 0, SUBCLASS_CONTROL, protocol = 0x20) +
                interfaceOf(1, 1, SUBCLASS_STREAMING, protocol = 0x20) +
                interfaceOf(3, 1, SUBCLASS_STREAMING, protocol = 0x20),
        ).functions
        assertEquals(listOf(1), associated[0].streamingInterfaces.map(UacStreamingInterface::interfaceNumber))
        assertEquals(listOf(3), associated[1].streamingInterfaces.map(UacStreamingInterface::interfaceNumber))

        val neither = parsed(
            device(0x0200) + configuration() +
                interfaceOf(0, 0, SUBCLASS_CONTROL, protocol = 0x20) +
                interfaceOf(1, 1, SUBCLASS_STREAMING, protocol = 0x20),
        ).functions.single()
        assertEquals(listOf(1), neither.streamingInterfaces.map(UacStreamingInterface::interfaceNumber))
    }

    @Test
    fun `a device with no audio function parses to no functions`() {
        val topology = parsed(device(0x0200) + configuration() + interfaceOf(0, 0, 0, classCode = 0x03))

        assertTrue(topology.functions.isEmpty())
    }

    @Test
    fun `uac3 is recorded as another version without guessing its layout`() {
        val function = parsed(
            device(0x0320) + configuration() +
                interfaceOf(0, 0, SUBCLASS_CONTROL, protocol = 0x30) +
                classInterface(2, 1, 2, 3) +
                interfaceOf(1, 1, SUBCLASS_STREAMING, protocol = 0x30) +
                classInterface(1, 1, 2, 3) +
                endpoint(0x01, ISO_ADAPTIVE, 192, 1),
        ).functions.single()

        assertEquals(UacVersion.OTHER, function.version)
        assertEquals(0x30, function.interfaceProtocol)
        assertTrue(function.inputTerminals.isEmpty())
        val alt = function.streamingInterfaces.single().alternateSettings.single()
        assertNull(alt.format)
        assertTrue(UacStreamingBlocker.NO_FORMAT in alt.streamingBlockers)
    }

    @Test
    fun `high speed multiple transactions are recorded and block streaming`() {
        val alt = onlyPlayback(uac1With(endpoint(0x01, ISO_ADAPTIVE, 0x0800 or 512, 1)))

        assertEquals(512, alt.dataEndpoint!!.maxPacketBytes)
        assertEquals(1, alt.dataEndpoint!!.additionalTransactions)
        assertEquals(setOf(UacStreamingBlocker.MULTIPLE_TRANSACTIONS), alt.streamingBlockers)
    }

    @Test
    fun `a longer service interval is kept as declared and is not a blocker`() {
        val alt = onlyPlayback(uac1With(endpoint(0x01, ISO_ADAPTIVE, 192, 4)))

        assertEquals(4, alt.dataEndpoint!!.interval)
        assertTrue(alt.streamingBlockers.isEmpty())
    }

    @Test
    fun `an interval the spec does not allow blocks streaming`() {
        assertEquals(
            setOf(UacStreamingBlocker.BAD_INTERVAL),
            onlyPlayback(uac1With(endpoint(0x01, ISO_ADAPTIVE, 192, 0))).streamingBlockers,
        )
        assertEquals(
            setOf(UacStreamingBlocker.BAD_INTERVAL),
            onlyPlayback(uac1With(endpoint(0x01, ISO_ADAPTIVE, 192, 17))).streamingBlockers,
        )
    }

    @Test
    fun `implicit feedback blocks streaming`() {
        val alt = onlyPlayback(uac1With(endpoint(0x01, ISO_ASYNC or (0b10 shl 4), 192, 1)))

        assertEquals(UacEndpointUsage.IMPLICIT_FEEDBACK_DATA, alt.dataEndpoint!!.usage)
        assertTrue(UacStreamingBlocker.IMPLICIT_FEEDBACK in alt.streamingBlockers)
    }

    @Test
    fun `an asynchronous endpoint with no feedback blocks streaming`() {
        assertEquals(
            setOf(UacStreamingBlocker.ASYNCHRONOUS_WITHOUT_FEEDBACK),
            onlyPlayback(uac1With(endpoint(0x01, ISO_ASYNC, 192, 1))).streamingBlockers,
        )
    }

    @Test
    fun `a uac1 feedback endpoint is found by the address the data endpoint names`() {
        val alt = onlyPlayback(
            uac1With(
                endpoint(0x01, ISO_ASYNC, 192, 1, refresh = 0, synchAddress = 0x81) +
                    endpoint(0x81, 0x01, 3, 1, refresh = 5, synchAddress = 0),
            ),
        )

        assertEquals(0x01, alt.dataEndpoint!!.address)
        assertEquals(0x81, alt.feedbackEndpoint!!.address)
        assertTrue(alt.streamingBlockers.isEmpty())
    }

    @Test
    fun `a bulk endpoint blocks streaming`() {
        assertEquals(
            setOf(UacStreamingBlocker.NOT_ISOCHRONOUS),
            onlyPlayback(uac1With(endpoint(0x01, 0x02, 64, 1))).streamingBlockers,
        )
    }

    @Test
    fun `a zero packet size blocks streaming`() {
        assertEquals(
            setOf(UacStreamingBlocker.EMPTY_PACKETS),
            onlyPlayback(uac1With(endpoint(0x01, ISO_ADAPTIVE, 0, 1))).streamingBlockers,
        )
    }

    @Test
    fun `formats outside the first version are blocked`() {
        val mono = onlyPlayback(uac1With(endpoint(0x01, ISO_ADAPTIVE, 192, 1), format = uac1Format(channels = 1)))
        assertEquals(setOf(UacStreamingBlocker.NOT_STEREO), mono.streamingBlockers)

        val float = onlyPlayback(uac1With(endpoint(0x01, ISO_ADAPTIVE, 192, 1), general = classInterface(1, 1, 0, *le16(3))))
        assertEquals(setOf(UacStreamingBlocker.NOT_LINEAR_PCM), float.streamingBlockers)

        val typeThree = onlyPlayback(uac1With(endpoint(0x01, ISO_ADAPTIVE, 192, 1), format = classInterface(2, 3)))
        assertFalse(typeThree.format!!.isTypeI)
        assertTrue(UacStreamingBlocker.NOT_LINEAR_PCM in typeThree.streamingBlockers)

        val layout = onlyPlayback(uac1With(endpoint(0x01, ISO_ADAPTIVE, 192, 1), format = uac1Format(subslot = 2, bits = 24)))
        assertEquals(setOf(UacStreamingBlocker.BAD_SAMPLE_LAYOUT), layout.streamingBlockers)
    }

    @Test
    fun `uac2 raw data and other format bits are not linear pcm`() {
        val raw = 1 shl 31
        listOf(raw or 1, 0b1000).forEach { formats ->
            val alt = parsed(uac2Device(formats = formats)).functions.single()
                .streamingInterfaces.single().alternateSettings[1]
            assertEquals(UacPcmKind.OTHER, alt.format!!.kind)
            assertTrue(UacStreamingBlocker.NOT_LINEAR_PCM in alt.streamingBlockers)
        }
    }

    @Test
    fun `a setting with an endpoint and no format blocks streaming`() {
        val alt = onlyPlayback(
            device(0x0110) + configuration() + uac1Control() +
                interfaceOf(1, 1, SUBCLASS_STREAMING) + endpoint(0x01, ISO_ADAPTIVE, 192, 1),
        )

        assertNull(alt.format)
        assertEquals(setOf(UacStreamingBlocker.NO_FORMAT), alt.streamingBlockers)
    }

    @Test
    fun `a format with no endpoint blocks streaming`() {
        val alt = onlyPlayback(uac1With(endpoints = byteArrayOf()))

        assertFalse(alt.isPlayback)
        assertEquals(setOf(UacStreamingBlocker.NO_DATA_ENDPOINT), alt.streamingBlockers)
    }

    @Test
    fun `malformed descriptors are refused with where they went wrong`() {
        val prefix = device(0x0110) + configuration() + uac1Control()
        val prefixLength = prefix.size
        mapOf(
            "empty" to (byteArrayOf() to 0),
            "only a length byte" to (byteArrayOf(18) to 0),
            "length zero" to (byteArrayOf(0, 1) to 0),
            "length one" to (byteArrayOf(1, 1) to 0),
            "the first descriptor is not a device" to (configuration() to 0),
            "a long first descriptor that is not a device" to (descriptor(0x21, *IntArray(16)) to 0),
            "short device" to (byteArrayOf(4, 1, 0, 2) to 0),
            "runs past the end" to (device(0x0110) + byteArrayOf(9, 2, 0) to 18),
            "short configuration" to (device(0x0110) + byteArrayOf(4, 2, 0, 0) to 18),
            "second device" to (device(0x0110) + device(0x0110) to 18),
            "interface outside a configuration" to (device(0x0110) + interfaceOf(0, 0, SUBCLASS_CONTROL) to 18),
            "short interface" to (prefix + byteArrayOf(4, 4, 0, 0) to prefixLength),
            "short association" to (device(0x0110) + configuration() + byteArrayOf(4, 0x0B, 0, 1) to 27),
        ).forEach { (name, case) ->
            val (raw, offset) = case
            val result = UacDescriptorParser.parse(raw)
            assertTrue(name, result is UacParseResult.Malformed)
            assertEquals(name, offset, (result as UacParseResult.Malformed).offset)
        }
    }

    @Test
    fun `class specific descriptors shorter than their layout are refused`() {
        val control = device(0x0110) + configuration() + interfaceOf(0, 0, SUBCLASS_CONTROL)
        val uac2Control = device(0x0200) + configuration() + interfaceOf(0, 0, SUBCLASS_CONTROL, protocol = 0x20)
        val stream = control + interfaceOf(1, 1, SUBCLASS_STREAMING)
        val uac2Stream = uac2Control + interfaceOf(1, 1, SUBCLASS_STREAMING, protocol = 0x20)
        listOf(
            control + classInterface(1, 0, 1, 0, 0),
            control + classInterface(1, 0, 1, 0, 0, 3, 1),
            control + classInterface(2, 1),
            control + classInterface(3, 1),
            control + classInterface(4, 1, 2, 1),
            control + classInterface(5, 1, 2, 1),
            control + classInterface(6, 2, 1),
            control + classInterface(6, 2, 1, 0, 0, 0),
            control + classInterface(6, 2, 1, 4, 3),
            control + classInterface(7, 2, 1, 0, 2, 1),
            control + classInterface(8, 2, 1),
            uac2Control + classInterface(2, 1),
            uac2Control + classInterface(3, 1),
            uac2Control + classInterface(4, 1, 2, 1),
            uac2Control + classInterface(6, 2, 1, 0),
            uac2Control + classInterface(7, 2),
            uac2Control + classInterface(8, 2, 1),
            uac2Control + classInterface(0x0A, 10, 3),
            uac2Control + classInterface(0x0B, 11),
            uac2Control + classInterface(0x0B, 11, 3, 1),
            uac2Control + classInterface(0x0C, 12),
            uac2Control + classInterface(0x0D, 13),
            stream + classInterface(1, 1),
            stream + classInterface(2),
            stream + classInterface(2, 1, 2, 2),
            stream + classInterface(2, 1, 2, 2, 16, 2, *le24(48_000)),
            stream + classInterface(2, 1, 2, 2, 16, 0, *le24(48_000)),
            stream + byteArrayOf(4, 5, 1, 1),
            stream + endpoint(0x01, ISO_ADAPTIVE, 192, 1) + byteArrayOf(4, 0x25, 1, 1),
            uac2Stream + classInterface(1, 1),
            uac2Stream + classInterface(2),
            uac2Stream + classInterface(2, 1, 3),
        ).forEachIndexed { index, raw ->
            assertTrue("Case $index", UacDescriptorParser.parse(raw) is UacParseResult.Malformed)
        }
    }

    /** The bytes fit the buffer, but the leftover would otherwise be dropped without a word. */
    @Test
    fun `a feature unit with a partial control entry is refused`() {
        val uac1 = device(0x0110) + configuration() + interfaceOf(0, 0, SUBCLASS_CONTROL) +
            classInterface(6, 2, 1, 2, 3, 0, 2, 0)
        val uac2 = device(0x0200) + configuration() + interfaceOf(0, 0, SUBCLASS_CONTROL, protocol = 0x20) +
            classInterface(6, 2, 1, *le32(0b1111), 0, 0)

        listOf(uac1, uac2).forEach { raw ->
            assertTrue(UacDescriptorParser.parse(raw) is UacParseResult.Malformed)
        }
    }

    @Test
    fun `every truncation of a real layout is handled`() {
        listOf(uac1Device(), uac2Device()).forEach { whole ->
            (0 until whole.size).forEach { size ->
                // Cutting at a descriptor boundary is a shorter valid dump; the rest must be refused.
                UacDescriptorParser.parse(whole.copyOf(size))
            }
        }
        assertTrue(UacDescriptorParser.parse(uac1Device().copyOf(19)) is UacParseResult.Malformed)
    }

    /** The bytes are the device's, and a device can send anything. */
    @Test
    fun `corrupted descriptors never crash the parser`() {
        val random = Random(71)
        val samples = listOf(uac1Device(), uac2Device())
        repeat(20_000) {
            val raw = samples.random(random).copyOf()
            repeat(random.nextInt(1, 8)) { raw[random.nextInt(raw.size)] = random.nextInt(256).toByte() }
            when (val result = UacDescriptorParser.parse(raw)) {
                is UacParseResult.Parsed -> result.topology.functions.forEach { function ->
                    function.streamingInterfaces.flatMap(UacStreamingInterface::alternateSettings).forEach { alt ->
                        alt.streamingBlockers
                        alt.terminalLink?.let { link ->
                            function.featureUnitsAfter(link)
                            function.clockSourcesFor(link)
                        }
                    }
                    assertFalse(result.topology.describe().isEmpty())
                }
                is UacParseResult.Malformed -> assertTrue(result.offset in 0..raw.size)
            }
        }
        repeat(20_000) {
            UacDescriptorParser.parse(random.nextBytes(random.nextInt(0, 300)))
        }
    }

    @Test
    fun `the report names what acceptance records need and leaves out identifiers`() {
        val report = parsed(uac2Device()).describe()

        listOf(
            "VID 1234", "PID 5678", "UAC2", "playback", "24 bits in 3 bytes", "rates unknown until claimed",
            "ASYNCHRONOUS", "Feedback endpoint 0x81", "master volume WRITABLE", "INTERNAL_PROGRAMMABLE",
            "Speed: unknown",
        ).forEach { assertTrue(it, it in report) }
        assertFalse("/dev/bus/usb" in report)
    }

    @Test
    fun `the report states what blocks a setting`() {
        val report = parsed(uac1With(endpoint(0x01, ISO_ADAPTIVE, 0x0800 or 512, 1))).describe()

        assertTrue("MULTIPLE_TRANSACTIONS" in report)
    }

    private fun parsed(raw: ByteArray): UsbAudioTopology =
        (UacDescriptorParser.parse(raw) as UacParseResult.Parsed).topology

    private fun onlyPlayback(raw: ByteArray): UacAltSetting =
        parsed(raw).functions.single().streamingInterfaces.single().alternateSettings.single()

    private fun uac1With(
        endpoints: ByteArray,
        general: ByteArray = uac1General(1),
        format: ByteArray = uac1Format(),
    ) = device(0x0110) + configuration() + uac1Control() +
        interfaceOf(1, 1, SUBCLASS_STREAMING) + general + format + endpoints

    private fun uac1Device() = device(0x0110) + configuration() +
        interfaceOf(0, 0, SUBCLASS_CONTROL) + uac1Header(1, 2) +
        classInterface(2, 1, *le16(0x0101), 0, 2, 3, 0, 0, 0) +
        classInterface(6, 2, 1, 1, 0b11, 0b10, 0b00, 0) +
        classInterface(3, 3, *le16(0x0301), 0, 2, 0) +
        classInterface(2, 4, *le16(0x0201), 0, 1, 0, 0, 0, 0) +
        classInterface(3, 5, *le16(0x0101), 0, 4, 0) +
        interfaceOf(1, 0, SUBCLASS_STREAMING) +
        interfaceOf(1, 1, SUBCLASS_STREAMING) + uac1General(1) +
        uac1Format(rates = intArrayOf(44_100, 48_000)) +
        endpoint(0x01, ISO_ASYNC, 192, 1, refresh = 0, synchAddress = 0x81) +
        classEndpoint(0b1, 0, 0, 0) +
        endpoint(0x81, ISO_FEEDBACK, 3, 1, refresh = 5, synchAddress = 0) +
        interfaceOf(2, 0, SUBCLASS_STREAMING) +
        interfaceOf(2, 1, SUBCLASS_STREAMING) + uac1General(5) + uac1Format(channels = 1) +
        endpoint(0x82, 0x0D, 96, 1)

    private fun uac1Control() = interfaceOf(0, 0, SUBCLASS_CONTROL) + uac1Header(1)

    private fun uac1Header(vararg interfaces: Int) =
        classInterface(1, *le16(0x0100), *le16(0), interfaces.size, *interfaces)

    private fun uac1General(link: Int) = classInterface(1, link, 0, *le16(1))

    private fun uac1Format(
        channels: Int = 2,
        subslot: Int = 2,
        bits: Int = 16,
        rates: IntArray = intArrayOf(48_000),
    ) = classInterface(2, 1, channels, subslot, bits, rates.size, *rates.flatMap { le24(it).asList() }.toIntArray())

    private fun uac2Device(
        featureUnit: ByteArray = classInterface(6, 2, 1, *le32(0b1111), *le32(0b0100), *le32(0), 0),
        clockSource: ByteArray = classInterface(0x0A, 10, 0b11, 0b0111, 0, 0),
        formats: Int = 1,
    ) = device(0x0200) + configuration() + uac2Function(featureUnit, clockSource, formats)

    private fun uac2Function(
        featureUnit: ByteArray = classInterface(6, 2, 1, *le32(0b1111), *le32(0b0100), *le32(0), 0),
        clockSource: ByteArray = classInterface(0x0A, 10, 0b11, 0b0111, 0, 0),
        formats: Int = 1,
    ) = byteArrayOf(8, 0x0B, 0, 2, 1, 0, 0x20, 0) +
        interfaceOf(0, 0, SUBCLASS_CONTROL, protocol = 0x20) +
        classInterface(1, *le16(0x0200), 0x08, *le16(0), 0) +
        clockSource +
        classInterface(0x0B, 11, 1, 10, 0b11, 0) +
        classInterface(2, 1, *le16(0x0101), 0, 11, 2, *le32(3), 0, *le16(0), 0) +
        featureUnit +
        classInterface(3, 3, *le16(0x0302), 0, 2, 11, *le16(0), 0) +
        interfaceOf(1, 0, SUBCLASS_STREAMING, protocol = 0x20) +
        uac2Alternate(1, subslot = 3, bits = 24, formats) +
        uac2Alternate(2, subslot = 4, bits = 32, formats)

    private fun uac2Alternate(alternate: Int, subslot: Int, bits: Int, formats: Int) =
        interfaceOf(1, alternate, SUBCLASS_STREAMING, protocol = 0x20) +
            classInterface(1, 1, 0, 1, *le32(formats), 2, *le32(3), 0) +
            classInterface(2, 1, subslot, bits) +
            endpoint(0x01, ISO_ASYNC, 1024, 1) +
            classEndpoint(0, 0, 0, 0, 0) +
            endpoint(0x81, ISO_FEEDBACK, 4, 4)

    private companion object {
        const val SUBCLASS_CONTROL = 1
        const val SUBCLASS_STREAMING = 2
        const val ISO_ASYNC = 0x05
        const val ISO_ADAPTIVE = 0x09
        const val ISO_FEEDBACK = 0x11

        fun le16(value: Int) = intArrayOf(value and 0xFF, value shr 8 and 0xFF)

        fun le24(value: Int) = intArrayOf(value and 0xFF, value shr 8 and 0xFF, value shr 16 and 0xFF)

        fun le32(value: Int) =
            intArrayOf(value and 0xFF, value ushr 8 and 0xFF, value ushr 16 and 0xFF, value ushr 24 and 0xFF)

        fun descriptor(type: Int, vararg body: Int) =
            ByteArray(body.size + 2) { index ->
                when (index) {
                    0 -> body.size + 2
                    1 -> type
                    else -> body[index - 2]
                }.toByte()
            }

        fun device(usbBcd: Int) = descriptor(
            0x01, *le16(usbBcd), 0, 0, 0, 64, *le16(0x1234), *le16(0x5678), *le16(0x0100), 1, 2, 3, 1,
        )

        fun configuration(value: Int = 1) = descriptor(0x02, *le16(0), 2, value, 0, 0x80, 50)

        fun interfaceOf(number: Int, alternate: Int, subclass: Int, protocol: Int = 0, classCode: Int = 1) =
            descriptor(0x04, number, alternate, 2, classCode, subclass, protocol, 0)

        fun classInterface(subtype: Int, vararg body: Int) = descriptor(0x24, subtype, *body)

        fun classEndpoint(vararg body: Int) = descriptor(0x25, 1, *body)

        fun endpoint(address: Int, attributes: Int, maxPacket: Int, interval: Int) =
            descriptor(0x05, address, attributes, *le16(maxPacket), interval)

        fun endpoint(address: Int, attributes: Int, maxPacket: Int, interval: Int, refresh: Int, synchAddress: Int) =
            descriptor(0x05, address, attributes, *le16(maxPacket), interval, refresh, synchAddress)
    }
}
