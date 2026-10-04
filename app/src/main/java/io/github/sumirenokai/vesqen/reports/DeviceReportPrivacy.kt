package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection

/** The single privacy boundary for persistence and export. Never export raw diagnostic strings. */
internal object DeviceReportPrivacy {
    private val containers = setOf("flac", "wave", "wav", "aiff", "mpeg_audio", "mp4", "adts", "ogg")
    private val mimes = setOf("audio/mp4", "audio/wav", "audio/x-wav", "audio/aiff", "audio/flac", "audio/raw", "audio/alac", "audio/mpeg", "audio/mp4a-latm", "audio/aac", "audio/opus", "audio/vorbis", "audio/ac3", "audio/eac3", "audio/ac4", "audio/true-hd")
    private val encodings = setOf("pcm-8", "pcm-16", "pcm-24", "pcm-32", "pcm-float")
    private val codecs = setOf("FLAC", "ALAC", "PCM", "MP3", "AAC", "Opus", "Vorbis")
    private val platformEncodings = setOf("pcm_8", "pcm_16", "pcm_24", "pcm_32", "pcm_float", "ac3", "e_ac3", "dts", "dts_hd")
    private fun platformEncoding(value: String) = value in platformEncodings || value.matches(Regex("encoding_[0-9]{1,10}"))
    private val routeTypes = setOf("built_in_speaker", "built_in_speaker_safe", "dock", "fm", "ip", "bus", "remote_submix", "built_in_earpiece", "speaker", "earpiece", "wired_headset", "wired_headphones", "line_analog", "line_digital", "hdmi", "hdmi_arc", "hdmi_earc", "usb_device", "usb_accessory", "usb_headset", "bluetooth", "bluetooth_a2dp", "bluetooth_sco", "ble_headset", "ble_speaker", "ble_broadcast", "hearing_aid", "other", "unknown")
    private val sources = setOf("library.metadata", "media3.analytics", "media3.decoder_counters", "media3.player", "media3.data_source", "media3.current_media_data_source", "media3.audio_track", "android.media_codec", "android.process", "android.runtime", "android.power", "android.build", "android.battery", "android.audio_route", "android.system_media_route", "android.direct_playback_support", "android.mixer_attributes", "android.usb_public_api", "vesqen.configuration", "vesqen.output_coordinator", "vesqen.external_output_verification")
    private val calculations = setOf("decoder.path_from_public_runtime_facts", "processing.compare_decoder_input_and_audio_track_rates", "media3.position_from_last_event", "rate.data_source_bytes_per_window", "rate.current_media_bytes_per_window", "rate.decoder_input_buffers_per_window", "rate.decoder_output_buffers_per_window", "audio_track.request_format_pcm_data_rate", "rate.process_cpu_one_core", "power.whole_device_current_times_voltage")
    private val operandNames = setOf("bytes.delta", "window.seconds", "cpu.delta_ms", "window.elapsed_ms", "counter.delta")
    private val mac = Regex("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}")
    private val fileSuffix = Regex("(?i)\\.(flac|alac|wav|wave|aiff?|mp3|mp4|m4a|aac|opus|ogg|wma|mkv|txt|json|log|db|sqlite|jpg|png|pdf|zip|apk)(?:\\b|$)")

    fun fileName(raw: String?): String? = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf {
        it.isNotBlank() && it.length <= 255 && it !in setOf(".", "..") &&
            it.none { c -> c.isISOControl() || c in ":%" } && !mac.containsMatchIn(it)
    }

    fun format(raw: FailedTrackFormat): FailedTrackFormat {
        val container = raw.container?.takeIf(containers::contains)
        val mime = raw.codecMime?.takeIf(mimes::contains)
        val sanitized = raw.copy(
            container = container, codecMime = mime, codecLabel = raw.codecLabel?.takeIf(codecs::contains),
            sampleRateHz = raw.sampleRateHz?.takeIf { it in 1..1_536_000 },
            bitDepth = raw.bitDepth?.takeIf { it in 1..64 },
            channelCount = raw.channelCount?.takeIf { it in 1..64 },
        )
        return sanitized.copy(privacyFiltered = raw.privacyFiltered || sanitized != raw)
    }

    fun error(raw: ReportErrorEvent): ReportErrorEvent = raw.copy(format = raw.format?.let(::format), fileName = fileName(raw.fileName))

    fun document(data: DeviceReportData, options: DeviceReportOptions): Map<String, Any?> {
        val routeNames = data.telemetry?.metrics
            ?.filter { it.id in setOf(Metrics.ROUTE_SELECTED_SYSTEM_NAME, Metrics.ROUTE_ANTICIPATED_NAME, Metrics.ROUTE_BLUETOOTH_CONNECTED_NAMES) }
            ?.flatMap { (it.evidence.reading as? TelemetryReading.Text)?.value?.lines().orEmpty() }
            ?.filter(String::isNotBlank).orEmpty()
        val forbiddenNames = buildSet {
            data.history.events.mapNotNullTo(this) { it.fileName?.takeIf(String::isNotBlank) }
            addAll(routeNames)
        }
        fun label(value: String): String? = value.takeIf {
            it.length in 1..160 && it.all { c -> c.isLetterOrDigit() || c in " ._()+-" } &&
                !mac.containsMatchIn(it) && !fileSuffix.containsMatchIn(it) && forbiddenNames.none { name -> it.contains(name, ignoreCase = true) }
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
        if (options.chainEvidence) result["chainEvidence"] = snapshot(data, capabilitiesOnly = false)
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
                    "confidence" to "MEASURED", "source" to format.source.name,
                    "container" to format.container, "codecMime" to format.codecMime, "codecLabel" to format.codecLabel,
                    "sampleRateHz" to format.sampleRateHz, "bitDepth" to format.bitDepth,
                    "channelCount" to format.channelCount, "privacyFiltered" to format.privacyFiltered,
                    "missingFieldReason" to if (format.privacyFiltered) "SOURCE_DID_NOT_REPORT_OR_PRIVACY_FILTERED" else "SOURCE_DID_NOT_REPORT",
                ).apply {
                    if (options.includeFileNames) {
                        val name = fileName(event.fileName)?.takeUnless { name -> routeNames.any { name.contains(it, ignoreCase = true) } }
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
            "source" to evidence.source?.id?.value?.takeIf(sources::contains),
            "sourceRedacted" to (evidence.source?.id?.value?.let { it !in sources } == true),
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
                        "ordinal" to index + 1, "type" to endpoint.type.takeIf(routeTypes::contains),
                        "sampleRatesHz" to endpoint.sampleRatesHz, "channelCounts" to endpoint.channelCounts,
                        "encodings" to endpoint.encodings.map { it.takeIf(::platformEncoding) },
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
                    put("methodId", evidence.methodId.takeIf(calculations::contains))
                    put("methodRedacted", evidence.methodId !in calculations)
                    put("inputMetricIds", evidence.inputMetricIds.map { it.value }.sorted())
                }
                is TelemetryEvidence.Derived -> {
                    put("calculationId", evidence.calculationId.takeIf(calculations::contains))
                    put("calculationRedacted", evidence.calculationId !in calculations)
                    put("inputMetricIds", evidence.inputMetricIds.map { it.value }.sorted())
                    put("operands", evidence.operands.entries.mapIndexed { index, (name, value) ->
                        mapOf("name" to name.takeIf(operandNames::contains), "ordinal" to index, "value" to value)
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
        val allowed = when (id) {
            "source.container" -> value in containers
            "source.codec_mime", "decoder.input_mime" -> value in mimes
            "source.codec_label" -> value in codecs
            "decoder.output_encoding", "playback.audio_track_encoding" -> value in encodings
            "decoder.output_channel_config", "playback.audio_track_channel_mask" -> value.matches(Regex("0x[0-9a-fA-F]{1,8}"))
            "route.selected_system_type", "route.anticipated_type" -> value in routeTypes
            "route.connected_types", "route.bluetooth_connected_types" -> value.split(",").map(String::trim).all(routeTypes::contains)
            "route.request_format_direct_modes" -> value.split(",").all { it in setOf("none", "offload", "offload_gapless", "bitstream") }
            "route.anticipated_preferred_mixer_profile" -> value.split(",").all {
                it in setOf("default", "bit_perfect_capability") || platformEncoding(it) ||
                    it.matches(Regex("[0-9]{1,7}hz")) || it.matches(Regex("mask_0x[0-9a-f]{1,8}"))
            }
            "route.output_declaration" -> value in setOf("SYSTEM MIXED", "DIRECT SUPPORTED", "BIT-PERFECT AVAILABLE", "BIT-PERFECT REQUESTED", "BIT-PERFECT ACTIVE", "BIT-PERFECT VERIFIED", "BIT-PERFECT FAILED", "SYSTEM_MIXED", "DIRECT_SUPPORTED", "BIT_PERFECT_AVAILABLE", "BIT_PERFECT_REQUESTED", "BIT_PERFECT_ACTIVE", "BIT_PERFECT_VERIFIED", "BIT_PERFECT_FAILED")
            "playback.state" -> value in setOf("idle", "buffering", "ready", "ended")
            else -> false
        }
        return if (allowed) mapOf("value" to value) else mapOf("redactedReason" to "UNREVIEWED_OR_PRIVATE_TEXT")
    }
}
