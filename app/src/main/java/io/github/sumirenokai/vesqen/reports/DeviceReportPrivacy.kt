package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.chain.AppSegmentCondition
import io.github.sumirenokai.vesqen.chain.AppSegmentStatus
import io.github.sumirenokai.vesqen.chain.BluetoothSegmentStatus
import io.github.sumirenokai.vesqen.chain.RouteKind
import io.github.sumirenokai.vesqen.chain.SegmentReason
import io.github.sumirenokai.vesqen.chain.SourceCompression
import io.github.sumirenokai.vesqen.audio.AudioOutputType
import io.github.sumirenokai.vesqen.library.M1_AUDIO_FORMAT_MATRIX
import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import io.github.sumirenokai.vesqen.playback.telemetryLabel
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection

/**
 * Every text value a report may carry. Sets come from the producers wherever they are defined in
 * code (format matrix, enums), so a new producer value is either shared here or visibly redacted.
 * The server validates against the generated contract in contracts/device-report.
 */
internal object DeviceReportVocabulary {
    val appSegmentEnums = linkedMapOf(
        "appSegmentStatus" to AppSegmentStatus.entries.map { it.name },
        "sourceCompression" to SourceCompression.entries.map { it.name },
        "appSegmentCondition" to AppSegmentCondition.entries.map { it.name },
        "segmentReason" to SegmentReason.entries.map { it.name },
        "routeKind" to RouteKind.entries.map { it.name },
        "bluetoothSegmentStatus" to BluetoothSegmentStatus.entries.map { it.name },
    )
    /** playbackContainerFromFileName in PlaybackController. */
    val containers = setOf("flac", "wave", "wav", "aiff", "mpeg_audio", "mp4", "adts", "ogg")
    /** Library MIME hints plus the MIME types Media3 reports for decoder input. */
    val mimes = M1_AUDIO_FORMAT_MATRIX.flatMapTo(sortedSetOf()) { it.mimeHints } + setOf(
        "audio/mp4", "audio/raw", "audio/mpeg-L1", "audio/mpeg-L2", "audio/true-hd",
        "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/ac4",
    )
    /** codecLabel in LocalAudioMetadataReader, which returns the format matrix's display names. */
    val codecLabels = M1_AUDIO_FORMAT_MATRIX.mapTo(sortedSetOf()) { it.displayName } + "PCM"
    /** audioEncodingName in AndroidPlaybackTelemetry; unknown constants become "encoding-N". */
    val encodings = setOf("pcm-8", "pcm-16", "pcm-24", "pcm-32", "pcm-float", "ac3", "e-ac3", "dts", "dts-hd")
    val encodingPattern = Regex("encoding-[0-9]{1,10}")
    /** audioDeviceTypeName in AndroidOutputTelemetryProbe (USB inventory and mixer profiles). */
    val platformEncodings = setOf("pcm_8", "pcm_16", "pcm_24", "pcm_32", "pcm_float", "ac3", "e_ac3", "dts", "dts_hd")
    val platformEncodingPattern = Regex("encoding_[0-9]{1,10}")
    /** AudioOutputType for the selected route, AudioDeviceInfo type names for the others. */
    val routeTypes = AudioOutputType.entries.mapTo(sortedSetOf()) { it.name.lowercase() } + setOf(
        "built_in_speaker", "built_in_speaker_safe", "dock", "fm", "ip", "bus", "remote_submix", "built_in_earpiece",
        "wired_headset", "wired_headphones", "line_analog", "line_digital", "hdmi", "hdmi_arc", "hdmi_earc",
        "usb_device", "usb_accessory", "usb_headset", "bluetooth_a2dp", "bluetooth_sco", "ble_headset", "ble_speaker",
        "ble_broadcast", "hearing_aid", "unknown",
    )
    val outputDeclarations = OutputDeclaration.entries.mapTo(sortedSetOf()) { it.telemetryLabel }
    val directModes = setOf("none", "offload", "offload_gapless", "bitstream")
    val mixerProfileTokens = setOf("default", "bit_perfect_capability")
    val playbackStates = setOf("idle", "buffering", "ready", "ended")
    val sources = setOf("library.metadata", "media3.analytics", "media3.decoder_counters", "media3.player", "media3.data_source", "media3.current_media_data_source", "media3.audio_track", "android.media_codec", "android.process", "android.runtime", "android.power", "android.build", "android.battery", "android.audio_route", "android.system_media_route", "android.direct_playback_support", "android.mixer_attributes", "android.usb_public_api", "vesqen.configuration", "vesqen.output_coordinator", "vesqen.external_output_verification")
    /** Calculation ids of Derived evidence and method ids of Estimated evidence. */
    val calculations = setOf("decoder.path_from_public_runtime_facts", "processing.compare_decoder_input_and_audio_track_rates", "media3.position_from_last_event", "rate.data_source_bytes_per_window", "rate.current_media_bytes_per_window", "rate.decoder_input_buffers_per_window", "rate.decoder_output_buffers_per_window", "audio_track.request_format_pcm_data_rate", "rate.process_cpu_one_core", "power.whole_device_current_times_voltage", "library.cached_metadata", "library.filename_extension")
    val operandNames = setOf("bytes.delta", "window.seconds", "cpu.delta_ms", "window.elapsed_ms", "counter.delta")

    fun encoding(value: String) = value in encodings || value.matches(encodingPattern)
    fun platformEncoding(value: String) = value in platformEncodings || value.matches(platformEncodingPattern)
}

/** The single privacy boundary for persistence and export. Never export raw diagnostic strings. */
internal object DeviceReportPrivacy {
    private val vocabulary = DeviceReportVocabulary
    private val mac = Regex("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}")
    private val fileSuffix = Regex("(?i)\\.(flac|alac|wav|wave|aiff?|mp3|mp4|m4a|aac|opus|ogg|wma|mkv|txt|json|log|db|sqlite|jpg|png|pdf|zip|apk)(?:\\b|$)")

    fun fileName(raw: String?): String? = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf {
        it.isNotBlank() && it.length <= 255 && it !in setOf(".", "..") &&
            it.none { c -> c.isISOControl() || c in ":%" } && !mac.containsMatchIn(it)
    }

    fun format(raw: FailedTrackFormat): FailedTrackFormat {
        val container = raw.container?.takeIf(vocabulary.containers::contains)
        val mime = raw.codecMime?.takeIf(vocabulary.mimes::contains)
        val sanitized = raw.copy(
            container = container, codecMime = mime, codecLabel = raw.codecLabel?.takeIf(vocabulary.codecLabels::contains),
            sampleRateHz = raw.sampleRateHz?.takeIf { it in 1..1_536_000 },
            bitDepth = raw.bitDepth?.takeIf { it in 1..64 },
            channelCount = raw.channelCount?.takeIf { it in 1..64 },
        )
        return sanitized.copy(privacyFiltered = raw.privacyFiltered || sanitized != raw)
    }

    fun error(raw: ReportErrorEvent): ReportErrorEvent = raw.copy(format = raw.format?.let(::format), fileName = fileName(raw.fileName))

    fun document(data: DeviceReportData, options: DeviceReportOptions): Map<String, Any?> {
        // Route and Bluetooth names never leave textReading (they are not on any allowlist), so the
        // basic device facts need no cross-check against them: a speaker route is named after the
        // phone model, and such a check would wrongly drop the model. Only an opted-in file name is
        // checked, against connected Bluetooth device names ("type: name" lines).
        val bluetoothNames = (data.telemetry?.metric(Metrics.ROUTE_BLUETOOTH_CONNECTED_NAMES)
            ?.evidence?.reading as? TelemetryReading.Text)?.value?.lines()
            ?.mapNotNull { line -> line.substringAfter(": ", "").trim().takeIf { it.length >= 3 } }
            .orEmpty()
        fun label(value: String): String? = value.takeIf {
            it.length in 1..160 && it.all { c -> c.isLetterOrDigit() || c in " ._()+-" } &&
                !mac.containsMatchIn(it) && !fileSuffix.containsMatchIn(it)
        }
        val result = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "generatedAtEpochMs" to data.generatedAtEpochMs,
            "basic" to with(data.basic) { linkedMapOf(
                "appVersion" to label(appVersion), "versionCode" to versionCode, "buildType" to label(buildType),
                "manufacturer" to label(manufacturer), "model" to label(model), "androidVersion" to label(androidVersion),
                "androidApi" to androidApi, "romBuild" to label(romBuild), "redactedValuesAreNull" to true,
            ) },
        )
        if (options.audioCapabilities) result["audioCapabilities"] = snapshot(data, capabilitiesOnly = true)
        if (options.chainEvidence) {
            result["chainEvidence"] = snapshot(data, capabilitiesOnly = false)
            result["appSegment"] = deviceReportAppSegment(data.telemetry)
        }
        if (options.recentErrors) result["recentErrors"] = mapOf(
            "availability" to data.history.availability.name, "exitHistory" to data.history.exitHistory.name,
            "maxEvents" to ErrorHistoryStore.MAX_EVENTS, "maxAgeMs" to ErrorHistoryStore.MAX_AGE_MS,
            "events" to data.history.events.map { event -> mapOf(
                "kind" to event.kind.name, "occurredAtEpochMs" to event.occurredAtEpochMs,
                "occurredAtElapsedRealtimeMs" to event.occurredAtElapsedRealtimeMs,
                "platformCode" to event.platformCode, "strictFailure" to event.strictFailure?.name,
                "strictOrigin" to event.strictOrigin?.name,
                "confidence" to "MEASURED", "source" to when (event.kind) {
                    ReportErrorKind.PLAYBACK -> "media3.player"
                    ReportErrorKind.STRICT_OUTPUT -> "vesqen.output_coordinator"
                    ReportErrorKind.PROCESS_EXIT -> "android.historical_process_exit"
                },
            ) },
        )
        if (options.failedTrackFormats) result["failedTrackFormats"] = mapOf(
            "availability" to data.history.availability.name,
            "tracks" to data.history.events.filter { it.format != null }.map { event ->
                val format = format(requireNotNull(event.format))
                linkedMapOf<String, Any?>(
                    "occurredAtEpochMs" to event.occurredAtEpochMs,
                    "occurredAtElapsedRealtimeMs" to event.occurredAtElapsedRealtimeMs,
                    // Library facts are cached metadata (ESTIMATED, as in Audio Proof); a strict
                    // request's format is the one Vesqen itself asked Android for.
                    "confidence" to when (format.source) {
                        ErrorFormatSource.LIBRARY_METADATA -> "ESTIMATED"
                        ErrorFormatSource.STRICT_OUTPUT_REQUEST -> "MEASURED"
                    },
                    "source" to format.source.name,
                    "container" to format.container, "codecMime" to format.codecMime, "codecLabel" to format.codecLabel,
                    "sampleRateHz" to format.sampleRateHz, "bitDepth" to format.bitDepth,
                    "channelCount" to format.channelCount, "privacyFiltered" to format.privacyFiltered,
                    "missingFieldReason" to if (format.privacyFiltered) "SOURCE_DID_NOT_REPORT_OR_PRIVACY_FILTERED" else "SOURCE_DID_NOT_REPORT",
                ).apply {
                    if (options.includeFileNames) {
                        val name = fileName(event.fileName)
                            ?.takeUnless { name -> bluetoothNames.any { name.contains(it, ignoreCase = true) } }
                        put("fileName", name)
                        if (name == null) put("fileNameUnavailableReason", if (event.fileName == null) "NOT_RECORDED_OR_PRIVACY_FILTERED" else "PRIVACY_FILTERED")
                    }
                }
            },
        )
        return result
    }

    private fun snapshot(data: DeviceReportData, capabilitiesOnly: Boolean): Map<String, Any?> {
        val snapshot = data.telemetry ?: return mapOf("unavailableReason" to data.telemetryUnavailableReason.name)
        val selected = if (capabilitiesOnly) snapshot.metrics.filter { it.section in setOf(TelemetrySection.ROUTE, TelemetrySection.USB) } else snapshot.metrics
        // Retain dependencies even if they sit in a different section; metric IDs remain explicit.
        val ids = selected.mapTo(linkedSetOf()) { it.id }
        fun includeInputs(metric: TelemetryMetric) {
            val inputs = when (val evidence = metric.evidence) {
                is TelemetryEvidence.Derived -> evidence.inputMetricIds
                is TelemetryEvidence.Estimated -> evidence.inputMetricIds
                else -> emptySet()
            }
            inputs.forEach { id -> if (ids.add(id)) snapshot.metric(id)?.let(::includeInputs) }
        }
        selected.forEach(::includeInputs)
        return mapOf(
            "capturedAtEpochMs" to snapshot.capturedAtEpochMs,
            "capturedAtElapsedRealtimeMs" to snapshot.capturedAtElapsedRealtimeMs,
            "metrics" to snapshot.metrics.filter { it.id in ids }.sortedBy { it.id.value }.map(::metric),
        )
    }

    private fun metric(metric: TelemetryMetric): Map<String, Any?> {
        val evidence = metric.evidence
        return linkedMapOf<String, Any?>(
            "id" to metric.id.value, "confidence" to evidence.confidence.name,
            "observedAtEpochMs" to evidence.observedAtEpochMs,
            "observedAtElapsedRealtimeMs" to evidence.observedAtElapsedRealtimeMs,
            "source" to evidence.source?.id?.value?.takeIf(vocabulary.sources::contains),
            "sourceRedacted" to (evidence.source?.id?.value?.let { it !in vocabulary.sources } == true),
            "reading" to when (val reading = evidence.reading) {
                null -> null
                is TelemetryReading.Flag -> mapOf("value" to reading.value)
                is TelemetryReading.Integer -> mapOf("value" to reading.value, "unit" to reading.unit.name)
                is TelemetryReading.Decimal -> mapOf("value" to reading.value, "unit" to reading.unit.name)
                is TelemetryReading.Text -> textReading(metric.id.value, reading.value)
                is TelemetryReading.UsbInventory -> mapOf(
                    "hostDevices" to reading.value.hostDevices.mapIndexed { index, device -> mapOf(
                        "ordinal" to index + 1, "vendorId" to device.vendorId, "productId" to device.productId,
                        "permissionGranted" to device.permissionGranted,
                        "interfaces" to device.audioInterfaces.map { mapOf("class" to it.interfaceClass, "subclass" to it.interfaceSubclass, "protocol" to it.interfaceProtocol) },
                    ) },
                    "audioEndpoints" to reading.value.audioOutputEndpoints.mapIndexed { index, endpoint -> mapOf(
                        "ordinal" to index + 1, "type" to endpoint.type.takeIf(vocabulary.routeTypes::contains),
                        "sampleRatesHz" to endpoint.sampleRatesHz, "channelCounts" to endpoint.channelCounts,
                        "encodings" to endpoint.encodings.map { it.takeIf(vocabulary::platformEncoding) },
                        "arbitrarySampleRate" to endpoint.arbitrarySampleRate, "arbitraryChannelCount" to endpoint.arbitraryChannelCount,
                        "arbitraryEncoding" to endpoint.arbitraryEncoding,
                    ) },
                )
            },
        ).apply {
            when (evidence) {
                is TelemetryEvidence.Measured -> Unit
                is TelemetryEvidence.Unavailable -> put("unavailableReason", evidence.reason.name)
                is TelemetryEvidence.Estimated -> {
                    put("methodId", evidence.methodId.takeIf(vocabulary.calculations::contains))
                    put("methodRedacted", evidence.methodId !in vocabulary.calculations)
                    put("inputMetricIds", evidence.inputMetricIds.map { it.value }.sorted())
                }
                is TelemetryEvidence.Derived -> {
                    put("calculationId", evidence.calculationId.takeIf(vocabulary.calculations::contains))
                    put("calculationRedacted", evidence.calculationId !in vocabulary.calculations)
                    put("inputMetricIds", evidence.inputMetricIds.map { it.value }.sorted())
                    put("operands", evidence.operands.entries.mapIndexed { index, (name, value) ->
                        mapOf("name" to name.takeIf(vocabulary.operandNames::contains), "ordinal" to index, "value" to value)
                    })
                    put("window", with(evidence.window) { mapOf(
                        "startedAtEpochMs" to startedAtEpochMs, "endedAtEpochMs" to endedAtEpochMs,
                        "startedAtElapsedRealtimeMs" to startedAtElapsedRealtimeMs, "endedAtElapsedRealtimeMs" to endedAtElapsedRealtimeMs,
                    ) })
                }
            }
        }
    }

    private fun textReading(id: String, value: String): Map<String, Any?> {
        val words = vocabulary
        val allowed = when (id) {
            "source.container" -> value in words.containers
            "source.codec_mime", "decoder.input_mime" -> value in words.mimes
            "source.codec_label" -> value in words.codecLabels
            "decoder.output_encoding", "decoder.input_pcm_encoding", "playback.audio_track_encoding" -> words.encoding(value)
            "decoder.output_channel_config", "playback.audio_track_channel_mask" -> value.matches(Regex("0x[0-9a-fA-F]{1,8}"))
            "route.selected_system_type", "route.anticipated_type" -> value in words.routeTypes
            "route.connected_types", "route.bluetooth_connected_types" -> value.split(",").map(String::trim).all(words.routeTypes::contains)
            "route.request_format_direct_modes" -> value.split(",").all(words.directModes::contains)
            "route.anticipated_preferred_mixer_profile" -> value.split(",").all {
                it in words.mixerProfileTokens || words.platformEncoding(it) ||
                    it.matches(Regex("[0-9]{1,7}hz")) || it.matches(Regex("mask_0x[0-9a-f]{1,8}"))
            }
            "route.output_declaration" -> value in words.outputDeclarations
            "playback.state" -> value in words.playbackStates
            else -> false
        }
        return if (allowed) mapOf("value" to value) else mapOf("redactedReason" to "UNREVIEWED_OR_PRIVATE_TEXT")
    }
}
