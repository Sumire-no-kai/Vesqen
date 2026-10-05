package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputFailureOrigin
import io.github.sumirenokai.vesqen.telemetry.TelemetryConfidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit

/**
 * Everything the report server must accept, generated from the app so the two cannot drift.
 * DeviceReportContractTest keeps contracts/device-report/vocabulary.json equal to [vocabulary].
 */
internal object DeviceReportContract {
    fun vocabulary(): Map<String, Any?> {
        val words = DeviceReportVocabulary
        return linkedMapOf(
            "schemaVersion" to 1,
            "limits" to linkedMapOf(
                "maxEvents" to ErrorHistoryStore.MAX_EVENTS,
                "maxAgeMs" to ErrorHistoryStore.MAX_AGE_MS,
            ),
            "enums" to linkedMapOf(
                "reportErrorKind" to ReportErrorKind.entries.map { it.name },
                "errorFormatSource" to ErrorFormatSource.entries.map { it.name },
                "errorHistoryAvailability" to ErrorHistoryAvailability.entries.map { it.name },
                "exitHistoryAvailability" to ExitHistoryAvailability.entries.map { it.name },
                "usbOutputFailure" to UsbOutputFailure.entries.map { it.name },
                "usbOutputFailureOrigin" to UsbOutputFailureOrigin.entries.map { it.name },
                "telemetryUnavailableReason" to TelemetryUnavailableReason.entries.map { it.name },
                "telemetryConfidence" to TelemetryConfidence.entries.map { it.name },
                "telemetryUnit" to TelemetryUnit.entries.map { it.name },
            ).apply { putAll(words.appSegmentEnums) },
            "text" to linkedMapOf(
                "containers" to words.containers.sorted(),
                "mimes" to words.mimes.sorted(),
                "codecLabels" to words.codecLabels.sorted(),
                "encodings" to words.encodings.sorted(),
                "encodingPattern" to words.encodingPattern.pattern,
                "platformEncodings" to words.platformEncodings.sorted(),
                "platformEncodingPattern" to words.platformEncodingPattern.pattern,
                "routeTypes" to words.routeTypes.sorted(),
                "outputDeclarations" to words.outputDeclarations.sorted(),
                "directModes" to words.directModes.sorted(),
                "mixerProfileTokens" to words.mixerProfileTokens.sorted(),
                "playbackStates" to words.playbackStates.sorted(),
                "sources" to words.sources.sorted(),
                "calculations" to words.calculations.sorted(),
                "operandNames" to words.operandNames.sorted(),
            ),
            "metrics" to TelemetryMetricCatalog.allIds.sortedBy { it.value }.map { id ->
                val descriptor = TelemetryMetricCatalog.descriptor(id)
                linkedMapOf(
                    "id" to id.value,
                    "section" to descriptor.section.name,
                    "valueKind" to descriptor.valueKind.name,
                    "unit" to descriptor.unit?.name,
                )
            },
        )
    }

    /** Two-space JSON for reviewable diffs; reports themselves stay compact (DeviceReportGenerator). */
    fun encode(value: Any?): String = StringBuilder().also { it.json(value, 0) }.append('\n').toString()

    private fun StringBuilder.json(value: Any?, depth: Int) {
        val indent = "  ".repeat(depth + 1)
        when (value) {
            null -> append("null")
            is String -> string(value)
            is Boolean, is Int, is Long -> append(value.toString())
            is Map<*, *> -> if (value.isEmpty()) append("{}") else {
                append("{\n")
                value.entries.forEachIndexed { index, (key, entry) ->
                    append(indent); string(key as String); append(": "); json(entry, depth + 1)
                    append(if (index < value.size - 1) ",\n" else "\n")
                }
                append("  ".repeat(depth)).append('}')
            }
            is List<*> -> if (value.isEmpty()) append("[]") else {
                append("[\n")
                value.forEachIndexed { index, entry ->
                    append(indent); json(entry, depth + 1)
                    append(if (index < value.size - 1) ",\n" else "\n")
                }
                append("  ".repeat(depth)).append(']')
            }
            else -> error("Unsupported contract value type")
        }
    }

    private fun StringBuilder.string(value: String) {
        append('"')
        value.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
