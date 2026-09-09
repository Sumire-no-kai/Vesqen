package io.github.sumirenokai.vesqen.diagnostics

import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetryExportPolicy
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/** Stable, privacy-filtered JSON representation of one stopped diagnostic recording. */
object DiagnosticJsonExporter {
    const val SCHEMA_VERSION = 1

    /** Writes and flushes a complete document but deliberately leaves the caller-owned stream open. */
    fun write(recording: DiagnosticRecording, output: OutputStream) {
        val sink = JsonSink(BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)))
        val sessionAliases = SessionAliases()
        val usbAliases = UsbIdentityAliases()
        sink.objectValue {
            number("schemaVersion", SCHEMA_VERSION.toLong())
            objectField("recording") {
                string("id", recording.id)
                objectField("startedAt") { timestamp(recording.startedAt) }
                objectField("stoppedAt") { timestamp(recording.stoppedAt) }
                string("termination", recording.termination.jsonName())
                objectField("limits") {
                    number("maxSnapshots", recording.limits.maxSnapshots.toLong())
                    number("maxEvents", recording.limits.maxEvents.toLong())
                }
                objectField("retention") {
                    number("snapshotCount", recording.snapshots.size.toLong())
                    number("eventCount", recording.events.size.toLong())
                    number("droppedSnapshots", recording.droppedSnapshotCount)
                    number("droppedEvents", recording.droppedEventCount)
                    number("observedEventSequenceGaps", recording.observedEventSequenceGapCount)
                }
                arrayField("snapshots", recording.snapshots) { snapshot ->
                    snapshotValue(snapshot, sessionAliases, usbAliases)
                }
                arrayField("events", recording.events) { event ->
                    eventValue(event, sessionAliases)
                }
            }
        }
        sink.raw("\n")
        sink.flush()
    }

    private fun JsonSink.ObjectFields.timestamp(timestamp: DiagnosticTimestamp) {
        number("epochMs", timestamp.epochMs)
        number("elapsedRealtimeMs", timestamp.elapsedRealtimeMs)
    }

    private fun JsonSink.snapshotValue(
        snapshot: TelemetrySnapshot,
        aliases: SessionAliases,
        usbAliases: UsbIdentityAliases,
    ) {
        objectValue {
            number("capturedAtEpochMs", snapshot.capturedAtEpochMs)
            number("capturedAtElapsedRealtimeMs", snapshot.capturedAtElapsedRealtimeMs)
            nullableString("playbackSession", aliases.alias(snapshot.playbackSessionId))
            arrayField("metrics", snapshot.metrics.sortedBy { it.id.value }) { metric ->
                metricValue(metric, usbAliases)
            }
        }
    }

    private fun JsonSink.metricValue(metric: TelemetryMetric, usbAliases: UsbIdentityAliases) {
        val evidence = metric.evidence
        objectValue {
            string("id", metric.id.value)
            string("section", metric.section.jsonName())
            string("confidence", evidence.confidence.jsonName())
            number("observedAtEpochMs", evidence.observedAtEpochMs)
            number("observedAtElapsedRealtimeMs", evidence.observedAtElapsedRealtimeMs)
            source("source", evidence.source)
            member("reading") {
                val reading = evidence.reading
                if (reading == null) {
                    nullValue()
                } else {
                    readingValue(metric, reading, usbAliases)
                }
            }
            when (evidence) {
                is TelemetryEvidence.Measured -> Unit
                is TelemetryEvidence.Derived -> {
                    objectField("window") {
                        number("startedAtEpochMs", evidence.window.startedAtEpochMs)
                        number("endedAtEpochMs", evidence.window.endedAtEpochMs)
                        number("startedAtElapsedRealtimeMs", evidence.window.startedAtElapsedRealtimeMs)
                        number("endedAtElapsedRealtimeMs", evidence.window.endedAtElapsedRealtimeMs)
                        number("durationMs", evidence.window.durationMs)
                    }
                    string("calculationId", evidence.calculationId)
                    arrayField("inputMetricIds", evidence.inputMetricIds.sortedBy { it.value }) { id ->
                        stringValue(id.value)
                    }
                    objectField("operands") {
                        evidence.operands.toSortedMap().forEach { (name, value) -> number(name, value) }
                    }
                }
                is TelemetryEvidence.Estimated -> {
                    string("methodId", evidence.methodId)
                    arrayField("inputMetricIds", evidence.inputMetricIds.sortedBy { it.value }) { id ->
                        stringValue(id.value)
                    }
                }
                is TelemetryEvidence.Unavailable -> string("unavailableReason", evidence.reason.jsonName())
            }
        }
    }

    private fun JsonSink.ObjectFields.source(name: String, source: TelemetryDataSource?) {
        if (source == null) {
            nullField(name)
        } else {
            objectField(name) {
                // source.detail is intentionally excluded: it may contain paths or exception text.
                string("id", source.id.value)
            }
        }
    }

    private fun JsonSink.readingValue(
        metric: TelemetryMetric,
        reading: TelemetryReading,
        usbAliases: UsbIdentityAliases,
    ) {
        val descriptor = TelemetryMetricCatalog.descriptor(metric.id)
        objectValue {
            when (reading) {
                is TelemetryReading.Text -> {
                    if (
                        descriptor.exportPolicy != TelemetryExportPolicy.INCLUDE ||
                        reading.value.looksLikePrivateDiagnosticText(
                            allowPublicMime = metric.id == TelemetryMetricCatalog.SOURCE_CODEC_MIME ||
                                metric.id == TelemetryMetricCatalog.DECODER_INPUT_MIME,
                        )
                    ) {
                        string("kind", "redacted")
                    } else {
                        string("kind", "text")
                        string("value", reading.value)
                    }
                }
                is TelemetryReading.Flag -> {
                    string("kind", "flag")
                    boolean("value", reading.value)
                }
                is TelemetryReading.Integer -> {
                    string("kind", "integer")
                    number("value", reading.value)
                    string("unit", reading.unit.jsonName())
                }
                is TelemetryReading.Decimal -> {
                    string("kind", "decimal")
                    number("value", reading.value)
                    string("unit", reading.unit.jsonName())
                }
                is TelemetryReading.UsbInventory -> {
                    string("kind", "usb_inventory")
                    val inventory = reading.value
                    number("hostDeviceCount", inventory.hostDevices.size.toLong())
                    arrayField("hostDevices", inventory.hostDevices) { device ->
                        val audioInterfaces = device.audioInterfaces.sortedWith(
                            compareBy(
                                { it.interfaceClass },
                                { it.interfaceSubclass },
                                { it.interfaceProtocol },
                            ),
                        )
                        objectValue {
                            number(
                                "deviceOrdinal",
                                usbAliases.hostDeviceOrdinal(device.snapshotKey),
                            )
                            number("vendorId", device.vendorId.toLong())
                            number("productId", device.productId.toLong())
                            boolean("permissionGranted", device.permissionGranted)
                            number("audioInterfaceCount", audioInterfaces.size.toLong())
                            arrayField("audioInterfaces", audioInterfaces) { audioInterface ->
                                objectValue {
                                    number("interfaceClass", audioInterface.interfaceClass.toLong())
                                    number("interfaceSubclass", audioInterface.interfaceSubclass.toLong())
                                    number("interfaceProtocol", audioInterface.interfaceProtocol.toLong())
                                }
                            }
                        }
                    }
                    number("audioOutputEndpointCount", inventory.audioOutputEndpoints.size.toLong())
                    arrayField(
                        "audioOutputEndpoints",
                        inventory.audioOutputEndpoints,
                    ) { endpoint ->
                        objectValue {
                            number(
                                "endpointOrdinal",
                                usbAliases.audioEndpointOrdinal(endpoint.snapshotKey),
                            )
                            string(
                                "type",
                                endpoint.type.takeIf(USB_ENDPOINT_TYPE_ALLOWLIST::contains) ?: "usb_other",
                            )
                            arrayField("sampleRatesHz", endpoint.sampleRatesHz.distinct().sorted()) { rate ->
                                raw(rate.toString())
                            }
                            boolean("arbitrarySampleRate", endpoint.arbitrarySampleRate)
                            arrayField("channelCounts", endpoint.channelCounts.distinct().sorted()) { count ->
                                raw(count.toString())
                            }
                            boolean("arbitraryChannelCount", endpoint.arbitraryChannelCount)
                            arrayField("encodings", endpoint.encodings.distinct().sorted()) { encoding ->
                                stringValue(
                                    if (encoding.looksLikePrivateDiagnosticText()) "[redacted]" else encoding,
                                )
                            }
                            boolean("arbitraryEncoding", endpoint.arbitraryEncoding)
                        }
                    }
                }
            }
        }
    }

    private fun JsonSink.eventValue(event: TelemetryEvent, aliases: SessionAliases) {
        objectValue {
            number("sequence", event.sequence)
            string("kind", event.kind.jsonName())
            string("severity", event.severity.jsonName())
            number("occurredAtEpochMs", event.occurredAtEpochMs)
            number("occurredAtElapsedRealtimeMs", event.occurredAtElapsedRealtimeMs)
            string("code", event.code)
            nullableString("playbackSession", aliases.alias(event.playbackSessionId))
            arrayField("relatedMetricIds", event.relatedMetricIds.sortedBy { it.value }) { id ->
                stringValue(id.value)
            }
        }
    }

    internal fun snapshotFragment(
        snapshot: TelemetrySnapshot,
        sessionAliases: SessionAliases,
        usbAliases: UsbIdentityAliases,
    ): String = jsonFragment { sink ->
        sink.snapshotValue(snapshot, sessionAliases, usbAliases)
    }

    internal fun eventFragment(
        event: TelemetryEvent,
        sessionAliases: SessionAliases,
    ): String = jsonFragment { sink ->
        sink.eventValue(event, sessionAliases)
    }

    private fun jsonFragment(write: (JsonSink) -> Unit): String {
        val destination = StringWriter()
        val sink = JsonSink(BufferedWriter(destination))
        write(sink)
        sink.flush()
        return destination.toString()
    }
}

/** Stateful privacy aliases keep identities stable within one persisted recording only. */
internal class DiagnosticJsonFragmentEncoder {
    private val sessionAliases = SessionAliases()
    private val usbAliases = UsbIdentityAliases()

    fun snapshot(snapshot: TelemetrySnapshot): String = DiagnosticJsonExporter.snapshotFragment(
        snapshot,
        sessionAliases,
        usbAliases,
    )

    fun event(event: TelemetryEvent): String = DiagnosticJsonExporter.eventFragment(
        event,
        sessionAliases,
    )
}

internal fun writeDiagnosticRecording(
    recording: DiagnosticRecording,
    output: OutputStream,
): DiagnosticExportResult = try {
    DiagnosticJsonExporter.write(recording, output)
    DiagnosticExportResult.Success(
        snapshotCount = recording.snapshots.size,
        eventCount = recording.events.size,
    )
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    DiagnosticExportResult.Failure(DiagnosticExportFailure.WRITE_FAILED)
}

internal fun OutputStream.cancellationChecked(job: Job?): OutputStream {
    if (job == null) return this
    val destination = this
    return object : OutputStream() {
        override fun write(value: Int) {
            job.ensureActive()
            destination.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            job.ensureActive()
            destination.write(buffer, offset, length)
        }

        override fun flush() {
            job.ensureActive()
            destination.flush()
        }
    }
}

internal class SessionAliases {
    private val aliases = linkedMapOf<String, String>()

    fun alias(rawSessionId: String?): String? {
        if (rawSessionId == null) return null
        return aliases.getOrPut(rawSessionId) { "session-${aliases.size + 1}" }
    }
}

internal class UsbIdentityAliases {
    private val hostDevices = linkedMapOf<String, Long>()
    private val audioEndpoints = linkedMapOf<String, Long>()

    fun hostDeviceOrdinal(snapshotKey: String): Long =
        hostDevices.getOrPut(snapshotKey) { hostDevices.size.toLong() + 1 }

    fun audioEndpointOrdinal(snapshotKey: String): Long =
        audioEndpoints.getOrPut(snapshotKey) { audioEndpoints.size.toLong() + 1 }
}

private fun String.looksLikePrivateDiagnosticText(allowPublicMime: Boolean = false): Boolean {
    val candidate = trim()
    return candidate.contains('\n') ||
        candidate.contains('\r') ||
        (candidate.contains('/') && !(allowPublicMime && PUBLIC_MIME_VALUE.matches(candidate))) ||
        candidate.contains('\\') ||
        WINDOWS_PATH.containsMatchIn(candidate) ||
        URI_SCHEME.containsMatchIn(candidate) ||
        EXCEPTION_TEXT.containsMatchIn(candidate) ||
        USB_VENDOR_PRODUCT_PAIR.containsMatchIn(candidate)
}

private val PUBLIC_MIME_VALUE = Regex("(?i)^[a-z0-9.+-]+/[a-z0-9.+-]+$")
private val WINDOWS_PATH = Regex("(?i)(?:^|\\s|[=:])[A-Za-z]:[\\\\/]")
private val URI_SCHEME = Regex("(?i)\\b[a-z][a-z0-9+.-]*://")
private val EXCEPTION_TEXT = Regex("(?i)(?:exception|stacktrace|(?:^|\\s)at\\s+[A-Za-z0-9_$.]+\\([^)]*\\))")
private val USB_VENDOR_PRODUCT_PAIR = Regex(
    "(?i)(?:\\b(?:vid|pid|vendor(?:_?id)?|product(?:_?id)?)\\s*[=: -]\\s*(?:0x)?[0-9a-f]{1,5}\\b|" +
        "\\b[0-9a-f]{4}:[0-9a-f]{4}\\b)",
)
private val USB_ENDPOINT_TYPE_ALLOWLIST = setOf("usb_device", "usb_accessory", "usb_headset")

private fun Enum<*>.jsonName(): String = name.lowercase(Locale.ROOT)

private class JsonSink(private val writer: BufferedWriter) {
    fun objectValue(block: ObjectFields.() -> Unit) {
        raw("{")
        ObjectFields().block()
        raw("}")
    }

    fun <T> arrayValue(values: Iterable<T>, value: JsonSink.(T) -> Unit) {
        raw("[")
        var first = true
        values.forEach { item ->
            if (!first) raw(",")
            first = false
            value(item)
        }
        raw("]")
    }

    fun stringValue(value: String) {
        raw("\"")
        value.forEach { character ->
            when (character) {
                '\"' -> raw("\\\"")
                '\\' -> raw("\\\\")
                '\b' -> raw("\\b")
                '\u000c' -> raw("\\f")
                '\n' -> raw("\\n")
                '\r' -> raw("\\r")
                '\t' -> raw("\\t")
                else -> if (character.code < JSON_CONTROL_CHARACTER_LIMIT) {
                    raw("\\u")
                    raw(character.code.toString(16).padStart(4, '0'))
                } else {
                    writer.append(character)
                }
            }
        }
        raw("\"")
    }

    fun nullValue() = raw("null")

    fun raw(value: String) {
        writer.write(value)
    }

    fun flush() = writer.flush()

    inner class ObjectFields {
        private var first = true

        fun string(name: String, value: String) = member(name) { stringValue(value) }

        fun nullableString(name: String, value: String?) = member(name) {
            if (value == null) nullValue() else stringValue(value)
        }

        fun nullField(name: String) = member(name) { nullValue() }

        fun boolean(name: String, value: Boolean) = member(name) { raw(value.toString()) }

        fun number(name: String, value: Long) = member(name) { raw(value.toString()) }

        fun number(name: String, value: Double) = member(name) {
            require(value.isFinite()) { "JSON diagnostics cannot contain non-finite numbers" }
            raw(java.lang.Double.toString(value))
        }

        fun objectField(name: String, block: ObjectFields.() -> Unit) = member(name) {
            objectValue(block)
        }

        fun <T> arrayField(name: String, values: Iterable<T>, value: JsonSink.(T) -> Unit) =
            member(name) { arrayValue(values, value) }

        fun member(name: String, value: JsonSink.() -> Unit) {
            if (!first) raw(",")
            first = false
            stringValue(name)
            raw(":")
            value()
        }
    }

    private companion object {
        const val JSON_CONTROL_CHARACTER_LIMIT = 0x20
    }
}
