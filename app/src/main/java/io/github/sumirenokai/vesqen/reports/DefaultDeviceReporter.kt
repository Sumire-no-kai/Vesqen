package io.github.sumirenokai.vesqen.reports

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Selection changes invalidate old previews; only the explicitly previewed artifact may be sent. */
internal class DefaultDeviceReporter(
    private val scope: CoroutineScope,
    private val capture: suspend (DeviceReportOptions) -> DeviceReportData,
    private val sharer: DeviceReportSharer,
    private val uploader: DeviceReportUploader = UnconfiguredDeviceReportUploader,
) : DeviceReporter {
    private val lock = Any()
    private var revision = 0L
    private var operation: Job? = null
    private val mutable = MutableStateFlow(DeviceReportSnapshot())
    override val snapshot = mutable.asStateFlow()

    override fun setOptions(options: DeviceReportOptions) = synchronized(lock) {
        if (options == mutable.value.options) return@synchronized
        revision++
        operation?.cancel()
        mutable.value = DeviceReportSnapshot(options)
    }

    override fun discard() = synchronized(lock) {
        revision++
        operation?.cancel()
        mutable.value = mutable.value.copy(state = DeviceReportState.Editing)
    }

    override fun generate() = synchronized(lock) {
        if (mutable.value.state is DeviceReportState.Generating || mutable.value.state is DeviceReportState.Sending) return@synchronized
        val request = ++revision
        val options = mutable.value.options
        mutable.value = mutable.value.copy(state = DeviceReportState.Generating)
        operation = scope.launch {
            try {
                val artifact = DeviceReportGenerator.generate(capture(options), options)
                publish(request, DeviceReportState.Preview(artifact))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { publish(request, DeviceReportState.Failed(DeviceReportFailure.GENERATION_FAILED)) }
            catch (_: SecurityException) { publish(request, DeviceReportState.Failed(DeviceReportFailure.GENERATION_FAILED)) }
        }
    }

    override fun send(delivery: DeviceReportDelivery) = synchronized(lock) {
        val report = when (val state = mutable.value.state) {
            is DeviceReportState.Preview -> state.report
            is DeviceReportState.Failed -> state.report
            else -> null
        } ?: return@synchronized
        val request = revision
        mutable.value = mutable.value.copy(state = DeviceReportState.Sending(report, delivery))
        operation = scope.launch {
            val failure = try {
                if (delivery == DeviceReportDelivery.UPLOAD) uploader.upload(report)
                else sharer.share(report, delivery == DeviceReportDelivery.EMAIL)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { if (delivery == DeviceReportDelivery.UPLOAD) DeviceReportFailure.UPLOAD_FAILED else DeviceReportFailure.SHARE_FAILED }
            publish(request, if (failure == null) DeviceReportState.Preview(report) else DeviceReportState.Failed(failure, report))
        }
    }

    private fun publish(request: Long, state: DeviceReportState) = synchronized(lock) {
        if (request == revision) mutable.value = mutable.value.copy(state = state)
    }
}
