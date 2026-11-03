package io.github.sumirenokai.vesqen.usb.descriptor

/** Plain text for acceptance records. It holds no serial number or usbfs path. */
fun UsbAudioTopology.describe(): String = buildString {
    appendLine("USB %x.%02x  VID %04x  PID %04x".format(usbVersionBcd shr 8, usbVersionBcd and 0xFF, vendorId, productId))
    appendLine("Speed: unknown until USBDEVFS_GET_SPEED in M7-2")
    configurations.forEach { configuration ->
        appendLine("Configuration ${configuration.value}")
        if (configuration.functions.isEmpty()) appendLine("  No audio function")
        configuration.functions.forEach { describe(it) }
    }
}

private fun StringBuilder.describe(function: UacFunction) {
    appendLine("  ${function.version} (protocol 0x%02x), control interface ${function.controlInterface}".format(function.interfaceProtocol))
    function.streamingInterfaces.forEach { streaming ->
        appendLine("    Streaming interface ${streaming.interfaceNumber}")
        streaming.alternateSettings.forEach { describe(function, it) }
    }
}

private fun StringBuilder.describe(function: UacFunction, alt: UacAltSetting) {
    val direction = when {
        alt.isPlayback -> "playback"
        alt.isCapture -> "capture"
        else -> "no data"
    }
    appendLine("      Alt ${alt.alternateSetting}: $direction")
    alt.format?.let { format ->
        val rates = when (val sampleRates = format.sampleRates) {
            is SampleRates.Discrete -> sampleRates.ratesHz.joinToString(prefix = "rates ", postfix = " Hz")
            is SampleRates.Continuous -> "rates ${sampleRates.minHz}..${sampleRates.maxHz} Hz"
            SampleRates.RuntimeUnknown -> "rates unknown until claimed"
        }
        appendLine(
            "        ${format.kind}, Type I: ${format.isTypeI}, ${format.channels} ch, " +
                "${format.bitResolution} bits in ${format.subslotBytes} bytes, $rates",
        )
    }
    alt.dataEndpoint?.let { appendLine("        Data ${describe(it)}") }
    alt.feedbackEndpoint?.let { appendLine("        Feedback ${describe(it)}") }
    if (alt.endpointSetsSampleRate) appendLine("        The endpoint accepts a sampling frequency request")
    val link = alt.terminalLink ?: return
    if (alt.isPlayback) {
        val units = function.featureUnitsAfter(link)
        if (units.isEmpty()) appendLine("        No feature unit on the path")
        units.forEach { unit ->
            appendLine(
                "        Feature unit ${unit.id}: master volume ${unit.masterVolume}, master mute ${unit.masterMute}, " +
                    "channel volumes ${unit.channelVolumes}",
            )
        }
    }
    function.clockSourcesFor(link).forEach { source ->
        appendLine("        Clock ${source.id}: ${source.type}, frequency ${source.frequency}, validity ${source.validity}")
    }
    if (alt.isPlayback && alt.streamingBlockers.isNotEmpty()) {
        appendLine("        Not usable by M7's first version: ${alt.streamingBlockers}")
    }
}

private fun describe(endpoint: UacEndpoint) =
    "endpoint 0x%02x: %s, %s, %s, %d bytes, %d extra transactions, bInterval %d".format(
        endpoint.address,
        if (endpoint.isIsochronous) "isochronous" else "not isochronous",
        endpoint.syncType,
        endpoint.usage,
        endpoint.maxPacketBytes,
        endpoint.additionalTransactions,
        endpoint.interval,
    )
