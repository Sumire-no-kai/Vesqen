package io.github.sumirenokai.vesqen.reports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Harness-controlled states only: never probes, records, shares or connects to a server. */
class FakeDeviceReporter(initial: DeviceReportSnapshot = DeviceReportSnapshot()) : DeviceReporter {
    private val mutable = MutableStateFlow(initial)
    override val snapshot = mutable.asStateFlow()
    fun emit(state: DeviceReportState) { mutable.value = mutable.value.copy(state = state) }
    override fun setOptions(options: DeviceReportOptions) {
        if (options != mutable.value.options) mutable.value = DeviceReportSnapshot(options)
    }
    override fun generate() = emit(DeviceReportState.Generating)
    override fun discard() = emit(DeviceReportState.Editing)
    override fun send(delivery: DeviceReportDelivery) {
        val report = when (val state = mutable.value.state) {
            is DeviceReportState.Preview -> state.report
            is DeviceReportState.Failed -> state.report
            is DeviceReportState.Sent -> state.report
            else -> null
        } ?: return
        emit(DeviceReportState.Sending(report, delivery))
    }
}

class FakeDeviceReportUploader : DeviceReportUploader {
    var failure: DeviceReportFailure? = null
    var reportId: String = "00000000-0000-4000-8000-000000000000"
    private val received = mutableListOf<ByteArray>()
    val uploads: List<ByteArray> get() = received.map { it.copyOf() }
    override suspend fun upload(report: DeviceReportArtifact): DeviceReportUploadResult {
        received += report.copyBytes()
        return failure?.let(DeviceReportUploadResult::Failed) ?: DeviceReportUploadResult.Uploaded(reportId)
    }
}
