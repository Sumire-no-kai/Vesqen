package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.diagnostics.JsonSink
import java.io.BufferedWriter
import java.io.StringWriter

internal object DeviceReportGenerator {
    fun generate(data: DeviceReportData, options: DeviceReportOptions): DeviceReportArtifact {
        val destination = StringWriter()
        val sink = JsonSink(BufferedWriter(destination))
        sink.reportValue(DeviceReportPrivacy.document(data, options))
        sink.raw("\n")
        sink.flush()
        return DeviceReportArtifact(destination.toString().toByteArray(Charsets.UTF_8))
    }

    private fun JsonSink.reportValue(value: Any?) {
        when (value) {
            null -> nullValue()
            is String -> stringValue(value)
            is Boolean -> raw(value.toString())
            is Int, is Long -> raw(value.toString())
            is Double -> { require(value.isFinite()); raw(value.toString()) }
            is Map<*, *> -> objectValue { value.forEach { (key, entry) -> member(key as String) { reportValue(entry) } } }
            is Iterable<*> -> arrayValue(value) { reportValue(it) }
            else -> error("Unsupported report value type")
        }
    }
}
