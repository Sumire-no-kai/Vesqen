package io.github.sumirenokai.vesqen.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.licenses.ThirdPartyLicense
import io.github.sumirenokai.vesqen.licenses.ThirdPartyLicenses
import io.github.sumirenokai.vesqen.licenses.ThirdPartyLicensesResult
import io.github.sumirenokai.vesqen.ui.components.PaperCard
import io.github.sumirenokai.vesqen.ui.components.PaperDivider
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** #37: the build's generated catalog, with every license text and NOTICE in full. */
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val result by produceState<ThirdPartyLicensesResult?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { ThirdPartyLicenses.load { path -> context.assets.open(path) } }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = (result as? ThirdPartyLicensesResult.Loaded)?.entries?.firstOrNull { it.id == selectedId }
    BackHandler(enabled = selected != null) { selectedId = null }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag(if (selected == null) "vesqen.licenses" else "vesqen.licenses.detail"),
            contentPadding = PaddingValues(bottom = VesqenSpacing.xl),
        ) {
            if (selected == null) {
                item { SettingsDetailHeader(stringResource(R.string.licenses_title), "vesqen.licenses.back", onBack) }
                when (val loaded = result) {
                    null -> item { LicensesMessage(stringResource(R.string.licenses_loading)) }
                    is ThirdPartyLicensesResult.Unavailable -> item {
                        LicensesMessage(stringResource(R.string.licenses_unavailable), Modifier.testTag("vesqen.licenses.unavailable"))
                    }
                    is ThirdPartyLicensesResult.Loaded -> {
                        item { LicensesMessage(stringResource(R.string.licenses_intro)) }
                        item {
                            PaperCard(Modifier.padding(horizontal = VesqenSpacing.lg)) {
                                loaded.entries.forEachIndexed { index, entry ->
                                    if (index > 0) PaperDivider()
                                    SettingsRow(
                                        title = entry.name,
                                        description = licenseSummary(entry),
                                        onClick = { selectedId = entry.id },
                                        modifier = Modifier.testTag("vesqen.licenses.entry.$index"),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                item { SettingsDetailHeader(selected.name, "vesqen.licenses.detail.back") { selectedId = null } }
                if (selected.version.isNotBlank()) item { LicensesMessage(selected.version) }
                selected.licenses.forEachIndexed { index, license ->
                    item { LicenseText(license.name, license.text, Modifier.testTag("vesqen.licenses.text.$index")) }
                }
                selected.notices.forEach { notice ->
                    item { LicenseText(stringResource(R.string.licenses_notice), notice.text) }
                }
            }
        }
    }
}

internal fun licenseSummary(entry: ThirdPartyLicense): String =
    (listOf(entry.version) + entry.licenses.map { it.name }.distinct()).filter(String::isNotBlank).joinToString(" · ")

@Composable
private fun LicensesMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.sm),
    )
}

@Composable
private fun LicenseText(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(start = VesqenSpacing.lg, end = VesqenSpacing.lg, top = VesqenSpacing.lg)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(bottom = VesqenSpacing.xs)
                .semantics { heading() },
        )
        SelectionContainer {
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
