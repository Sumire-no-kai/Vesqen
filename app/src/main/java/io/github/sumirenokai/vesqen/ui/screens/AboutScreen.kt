package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.ui.components.PaperCard
import io.github.sumirenokai.vesqen.ui.components.PaperDivider
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

/**
 * B · Paper & Sound About (#35): the mark and one plain sentence, the facts in one card, then
 * what stays on this phone. #39: nothing is backed up or moved to a new phone, so the page says so.
 */
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    onOpenPrivacyPolicy: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.about"),
            contentPadding = PaddingValues(bottom = VesqenSpacing.xl),
        ) {
            item { SettingsDetailHeader(stringResource(R.string.settings_about), "vesqen.about.back", onBack) }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_brand_mark),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
                    Text(
                        text = stringResource(R.string.about_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                PaperCard(Modifier.padding(horizontal = VesqenSpacing.lg)) {
                    SettingsRow(
                        title = stringResource(R.string.about_version),
                        value = stringResource(R.string.about_version_value, versionName, versionCode),
                    )
                    PaperDivider()
                    SettingsRow(
                        title = stringResource(R.string.about_developer),
                        value = stringResource(R.string.about_developer_value),
                    )
                    PaperDivider()
                    SettingsRow(
                        title = stringResource(R.string.about_license),
                        value = stringResource(R.string.about_license_value),
                    )
                }
            }
            item {
                SettingsGroup(
                    title = stringResource(R.string.about_data_title),
                    modifier = Modifier.testTag("vesqen.about.data"),
                ) {
                    Text(
                        text = stringResource(R.string.about_data_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = VesqenSpacing.md, vertical = 14.dp),
                    )
                    PaperDivider()
                    SettingsRow(
                        title = stringResource(R.string.privacy_policy_title),
                        onClick = onOpenPrivacyPolicy,
                        modifier = Modifier.testTag("vesqen.about.privacy"),
                    )
                }
            }
        }
    }
}
