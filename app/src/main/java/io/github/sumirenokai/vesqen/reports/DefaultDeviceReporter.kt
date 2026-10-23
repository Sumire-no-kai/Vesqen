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
    /**
     * #113: told once per successful upload, with the data the uploaded report was made from. A
     * share or email counts only as handed to another app, which the person can still cancel.
     */
    private val delivered: (DeviceReportData) -> Unit = {},
) : DeviceReporter {
    private val lock = Any()
    private var revision = 0L
    private var operation: Job? = null
    /** The data behind the report in the current state; cleared with it. */
    private var reportData: DeviceReportData? = null
    private val mutable = MutableStateFlow(DeviceReportSnapshot())
    override val snapshot = mutable.asStateFlow()

    override fun setOptions(options: DeviceReportOptions) = synchronized(lock) {
        if (options == mutable.value.options) return@synchronized
        revision++
        operation?.cancel()
        reportData = null
        mutable.value = DeviceReportSnapshot(options)
    }

    override fun discard() = synchronized(lock) {
        revision++
        operation?.cancel()
        reportData = null
        mutable.value = mutable.value.copy(state = DeviceReportState.Editing)
    }

    override fun generate() = synchronized(lock) {
        if (mutable.value.state is DeviceReportState.Generating || mutable.value.state is DeviceReportState.Sending) return@synchronized
        val request = ++revision
        val options = mutable.value.options
        reportData = null
        mutable.value = mutable.value.copy(state = DeviceReportState.Generating)
        operation = scope.launch {
            try {
                val data = capture(options)
                val artifact = DeviceReportGenerator.generate(data, options)
                publish(request, DeviceReportState.Preview(artifact), data)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { publish(request, DeviceReportState.Failed(DeviceReportFailure.GENERATION_FAILED)) }
            catch (_: SecurityException) { publish(request, DeviceReportState.Failed(DeviceReportFailure.GENERATION_FAILED)) }
            // A telemetry or report invariant that does not hold must fail this report, not reach the
            // process-wide scope, where it would crash the app and stop playback.
            catch (_: IllegalStateException) { publish(request, DeviceReportState.Failed(DeviceReportFailure.GENERATION_FAILED)) }
            catch (_: IllegalArgumentException) { publish(request, DeviceReportState.Failed(DeviceReportFailure.GENERATION_FAILED)) }
        }
    }

    override fun send(delivery: DeviceReportDelivery) = synchronized(lock) {
        val report = when (val state = mutable.value.state) {
            is DeviceReportState.Preview -> state.report
            is DeviceReportState.Failed -> state.report
            is DeviceReportState.Sent -> state.report
            else -> null
        } ?: return@synchronized
        val request = revision
        val data = reportData
        mutable.value = mutable.value.copy(state = DeviceReportState.Sending(report, delivery))
        operation = scope.launch {
            val failureCode = if (delivery == DeviceReportDelivery.UPLOAD) DeviceReportFailure.UPLOAD_FAILED else DeviceReportFailure.SHARE_FAILED
            val sentState = try {
                if (delivery == DeviceReportDelivery.UPLOAD) {
                    when (val result = uploader.upload(report)) {
                        is DeviceReportUploadResult.Uploaded -> DeviceReportState.Sent(report, delivery, result.reportId)
                        is DeviceReportUploadResult.Failed -> DeviceReportState.Failed(result.reason, report)
                    }
                } else {
                    val failure = sharer.share(report, delivery == DeviceReportDelivery.EMAIL)
                    if (failure == null) DeviceReportState.Sent(report, delivery) else DeviceReportState.Failed(failure, report)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { DeviceReportState.Failed(failureCode, report) }
            catch (_: SecurityException) { DeviceReportState.Failed(failureCode, report) }
            // Only the server confirms receipt. After a share or email the records stay until they
            // expire, since the person may have backed out of the other app. A send cancelled by
            // discarding throws above and keeps them too, even if the bytes reached the server.
            if (delivery == DeviceReportDelivery.UPLOAD && sentState is DeviceReportState.Sent && data != null) delivered(data)
            publish(request, sentState)
        }
    }

    private fun publish(request: Long, state: DeviceReportState, data: DeviceReportData? = null) = synchronized(lock) {
        if (request == revision) {
            mutable.value = mutable.value.copy(state = state)
            if (data != null) reportData = data
        }
    }
}
