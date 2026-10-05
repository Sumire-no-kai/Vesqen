package io.github.sumirenokai.vesqen.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.reports.DeviceReportArtifact
import io.github.sumirenokai.vesqen.reports.DeviceReportDelivery
import io.github.sumirenokai.vesqen.reports.DeviceReportFailure
import io.github.sumirenokai.vesqen.reports.DeviceReportOptions
import io.github.sumirenokai.vesqen.reports.DeviceReportState
import io.github.sumirenokai.vesqen.reports.DeviceReporter
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

/** One preview item holds about this many characters, so long reports stay fast to scroll. */
private const val PreviewChunkLength = 4_000

/**
 * #69: the user picks the groups, reads the complete report exactly as it will be sent, and only
 * then shares, emails or (when this build has a server) uploads it. Nothing is sent implicitly.
 */
@Composable
fun DeviceReportScreen(
    deviceReporter: DeviceReporter,
    uploadAvailable: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by deviceReporter.snapshot.collectAsStateWithLifecycle()
    val state = snapshot.state
    val report = state.report
    // Back from a preview returns to the choices instead of leaving with an unsent report.
    BackHandler(enabled = report != null) { deviceReporter.discard() }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.report"),
            contentPadding = PaddingValues(bottom = VesqenSpacing.xl),
        ) {
            item {
                SettingsDetailHeader(
                    title = stringResource(R.string.report_title),
                    backTag = "vesqen.report.back",
                    onBack = if (report != null) deviceReporter::discard else onBack,
                )
            }
            if (report == null) {
                item { ReportText(stringResource(R.string.report_intro)) }
                item { ReportOptions(snapshot.options, enabled = state !is DeviceReportState.Generating, onChange = deviceReporter::setOptions) }
                item { ReportText(stringResource(R.string.report_never), muted = true) }
                if (state is DeviceReportState.Failed) item { ReportStatus(stringResource(reportFailureLabel(state.reason)), "vesqen.report.failure") }
                item {
                    Column(Modifier.padding(horizontal = VesqenSpacing.lg), verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm)) {
                        if (state is DeviceReportState.Generating) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("vesqen.report.progress"))
                            Text(stringResource(R.string.report_generating), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = deviceReporter::generate,
                            enabled = state !is DeviceReportState.Generating,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("vesqen.report.create"),
                        ) { Text(stringResource(R.string.report_create)) }
                    }
                }
            } else {
                reportPreview(report)
                item { ReportResult(state) }
                item { ReportActions(state, uploadAvailable, deviceReporter) }
            }
        }
    }
}

private val DeviceReportState.report: DeviceReportArtifact?
    get() = when (this) {
        is DeviceReportState.Preview -> report
        is DeviceReportState.Sending -> report
        is DeviceReportState.Sent -> report
        is DeviceReportState.Failed -> report
        DeviceReportState.Editing, DeviceReportState.Generating -> null
    }

@Composable
private fun ReportOptions(options: DeviceReportOptions, enabled: Boolean, onChange: (DeviceReportOptions) -> Unit) {
    SettingsCard(Modifier.padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.sm)) {
        ReportOption(R.string.report_basic, R.string.report_basic_body, checked = true, enabled = false, tag = "basic") {}
        SettingsDivider()
        ReportOption(R.string.report_audio, R.string.report_audio_body, options.audioCapabilities, enabled, "audio") {
            onChange(options.copy(audioCapabilities = it))
        }
        SettingsDivider()
        ReportOption(R.string.report_chain, R.string.report_chain_body, options.chainEvidence, enabled, "chain") {
            onChange(options.copy(chainEvidence = it))
        }
        SettingsDivider()
        ReportOption(R.string.report_errors, R.string.report_errors_body, options.recentErrors, enabled, "errors") {
            onChange(options.copy(recentErrors = it))
        }
        SettingsDivider()
        ReportOption(R.string.report_formats, R.string.report_formats_body, options.failedTrackFormats, enabled, "formats") {
            // File names only describe failed-track formats, so they go with them.
            onChange(options.copy(failedTrackFormats = it, includeFileNames = it && options.includeFileNames))
        }
        ReportOption(
            R.string.report_file_names, R.string.report_file_names_body, options.includeFileNames,
            enabled && options.failedTrackFormats, "file-names", indent = true,
        ) { onChange(options.copy(includeFileNames = it)) }
    }
}

@Composable
private fun ReportOption(
    @StringRes title: Int,
    @StringRes body: Int,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    indent: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = 64.dp)
            .padding(start = if (indent) 52.dp else VesqenSpacing.xs, end = VesqenSpacing.md, top = VesqenSpacing.xs, bottom = VesqenSpacing.xs)
            .alpha(if (enabled || checked) 1f else .56f)
            .testTag("vesqen.report.option.$tag"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(VesqenSpacing.xs))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The complete report, character for character, as it will be shared or uploaded. */
private fun androidx.compose.foundation.lazy.LazyListScope.reportPreview(report: DeviceReportArtifact) {
    item {
        val context = LocalContext.current
        val size = remember(report) { Formatter.formatShortFileSize(context, report.copyBytes().size.toLong()) }
        Row(
            modifier = Modifier.padding(start = VesqenSpacing.lg, end = VesqenSpacing.lg, top = VesqenSpacing.sm, bottom = VesqenSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.report_preview), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.report_size, size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    itemsIndexed(report.previewText.chunked(PreviewChunkLength)) { index, chunk ->
        SelectionContainer {
            Text(
                text = chunk,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .padding(horizontal = VesqenSpacing.lg)
                    .testTag("vesqen.report.preview.$index"),
            )
        }
    }
}

@Composable
private fun ReportResult(state: DeviceReportState) {
    when (state) {
        is DeviceReportState.Sending -> Column(Modifier.padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.sm)) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("vesqen.report.progress"))
            Text(stringResource(R.string.report_sending), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = VesqenSpacing.xs))
        }
        is DeviceReportState.Sent -> {
            val receipt = state.reportId
            if (state.delivery == DeviceReportDelivery.UPLOAD && receipt != null) {
                Column(Modifier.padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.sm), verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs)) {
                    Text(stringResource(R.string.report_uploaded), style = MaterialTheme.typography.bodyLarge)
                    SelectionContainer {
                        Text(stringResource(R.string.report_receipt, receipt), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("vesqen.report.receipt"))
                    }
                    Text(stringResource(R.string.report_receipt_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // Success here means Android accepted the share request, not that anything arrived.
                ReportStatus(stringResource(R.string.report_shared), "vesqen.report.shared")
            }
        }
        is DeviceReportState.Failed -> ReportStatus(stringResource(reportFailureLabel(state.reason)), "vesqen.report.failure")
        else -> Unit
    }
}

@Composable
private fun ReportActions(state: DeviceReportState, uploadAvailable: Boolean, deviceReporter: DeviceReporter) {
    val idle = state !is DeviceReportState.Sending
    Column(Modifier.padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.sm), verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm)) {
        if (uploadAvailable) {
            Button(
                onClick = { deviceReporter.send(DeviceReportDelivery.UPLOAD) },
                enabled = idle,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("vesqen.report.upload"),
            ) { Text(stringResource(R.string.report_upload)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.sm)) {
            OutlinedButton(
                onClick = { deviceReporter.send(DeviceReportDelivery.SHARE) },
                enabled = idle,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.report.share"),
            ) { Text(stringResource(R.string.report_share)) }
            OutlinedButton(
                onClick = { deviceReporter.send(DeviceReportDelivery.EMAIL) },
                enabled = idle,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.report.email"),
            ) { Text(stringResource(R.string.report_email)) }
        }
        TextButton(
            onClick = deviceReporter::discard,
            enabled = idle,
            modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.report.start-over"),
        ) { Text(stringResource(R.string.report_start_over)) }
    }
}

@Composable
private fun ReportText(text: String, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.xs),
    )
}

@Composable
private fun ReportStatus(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.sm)
            .testTag(tag),
    )
}

@StringRes
internal fun reportFailureLabel(failure: DeviceReportFailure): Int = when (failure) {
    DeviceReportFailure.GENERATION_FAILED -> R.string.report_failure_generation
    DeviceReportFailure.SHARE_FAILED -> R.string.report_failure_share
    DeviceReportFailure.NO_SHARE_APPLICATION -> R.string.report_failure_no_app
    DeviceReportFailure.UPLOAD_NOT_CONFIGURED -> R.string.report_failure_not_configured
    DeviceReportFailure.UPLOAD_FAILED -> R.string.report_failure_upload
}
