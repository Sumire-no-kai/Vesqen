package io.github.sumirenokai.vesqen.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.service.ServiceState
import io.github.sumirenokai.vesqen.ui.components.PaperCard
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.pageTitle
import io.github.sumirenokai.vesqen.usage.UsageRegionPolicy
import io.github.sumirenokai.vesqen.usage.UsageSettingsStatus
import io.github.sumirenokai.vesqen.usage.UsageStatistics
import io.github.sumirenokai.vesqen.usage.UsageStatisticsSnapshot

/**
 * #70: the explanation is due whenever the runtime asks for it (first launch, or a region change
 * that resets it), and only when pings can actually be sent. Without a configured server Vesqen
 * never sends statistics, so it does not ask about them either; nor once the owner retired them (#96).
 */
internal fun usageIntroductionDue(snapshot: UsageStatisticsSnapshot): Boolean =
    snapshot.status == UsageSettingsStatus.READY && snapshot.endpointConfigured && snapshot.introductionRequired &&
        snapshot.service != ServiceState.RETIRED

/** Settings page for usage statistics: the same explanation as the introduction, with the switch. */
@Composable
fun UsageStatisticsScreen(usageStatistics: UsageStatistics, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val snapshot by usageStatistics.snapshot.collectAsStateWithLifecycle()
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.usage"),
            contentPadding = PaddingValues(bottom = VesqenSpacing.xl),
        ) {
            item { SettingsDetailHeader(stringResource(R.string.usage_title), "vesqen.usage.back", onBack) }
            item { UsageExplanation(Modifier.padding(horizontal = VesqenSpacing.lg)) }
            item {
                PaperCard(Modifier.padding(start = VesqenSpacing.lg, end = VesqenSpacing.lg, top = VesqenSpacing.lg)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.usage_switch),
                        checked = snapshot.enabled,
                        enabled = snapshot.status == UsageSettingsStatus.READY,
                        // In a consent region, switching on here records consent; off withdraws it.
                        onCheckedChange = usageStatistics::setEnabled,
                        modifier = Modifier.testTag("vesqen.usage.switch"),
                    )
                }
            }
            if (snapshot.service == ServiceState.PAUSED) {
                item { UsageNote(stringResource(R.string.usage_paused), Modifier.padding(start = VesqenSpacing.lg, end = VesqenSpacing.lg, top = VesqenSpacing.sm)) }
            }
            if (snapshot.status == UsageSettingsStatus.STORAGE_UNAVAILABLE) {
                item { UsageNote(stringResource(R.string.usage_storage_unavailable), Modifier.padding(horizontal = VesqenSpacing.lg)) }
            }
        }
    }
}

/**
 * First-launch explanation. Where statistics start on, the switch is on the same page; where
 * consent comes first, both answers carry equal weight and nothing is preselected.
 */
@Composable
fun UsageIntroductionScreen(usageStatistics: UsageStatistics, modifier: Modifier = Modifier) {
    val snapshot by usageStatistics.snapshot.collectAsStateWithLifecycle()
    val askFirst = snapshot.regionPolicy != UsageRegionPolicy.DEFAULT_ENABLED
    var send by rememberSaveable { mutableStateOf(true) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .widthIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.xl)
                    .testTag("vesqen.usage-intro"),
                verticalArrangement = Arrangement.spacedBy(VesqenSpacing.lg),
            ) {
                Text(
                    text = stringResource(R.string.usage_title),
                    style = MaterialTheme.typography.displayMedium.pageTitle(),
                    modifier = Modifier.semantics { heading() },
                )
                UsageExplanation()
                if (askFirst) {
                    Column(verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm)) {
                        UsageChoice(R.string.usage_accept, "vesqen.usage-intro.accept") { usageStatistics.completeIntroduction(true) }
                        UsageChoice(R.string.usage_decline, "vesqen.usage-intro.decline") { usageStatistics.completeIntroduction(false) }
                    }
                } else {
                    PaperCard {
                        SettingsSwitchRow(
                            title = stringResource(R.string.usage_switch),
                            checked = send,
                            onCheckedChange = { send = it },
                            modifier = Modifier.testTag("vesqen.usage-intro.switch"),
                        )
                    }
                    Button(
                        onClick = { usageStatistics.completeIntroduction(send) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("vesqen.usage-intro.continue"),
                    ) { Text(stringResource(R.string.usage_continue)) }
                }
            }
        }
    }
}

@Composable
private fun UsageChoice(@StringRes label: Int, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .testTag(tag),
    ) { Text(stringResource(label)) }
}

/** Exactly the fields #84 sends; keep this list in step with the usage ping. */
@Composable
private fun UsageExplanation(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm)) {
        Text(stringResource(R.string.usage_body), style = MaterialTheme.typography.bodyLarge)
        Column(verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs)) {
            listOf(
                R.string.usage_field_app,
                R.string.usage_field_device,
                R.string.usage_field_bit_perfect,
                R.string.usage_field_recent_usb,
                R.string.usage_field_first,
            ).forEach { field ->
                Row(horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.xs)) {
                    Text("·", style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(field), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        UsageNote(stringResource(R.string.usage_not_sent))
        UsageNote(stringResource(R.string.usage_turn_off))
    }
}

@Composable
private fun UsageNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
