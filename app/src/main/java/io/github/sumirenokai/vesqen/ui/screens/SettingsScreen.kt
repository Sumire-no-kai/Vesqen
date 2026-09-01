package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

@Composable
fun SettingsScreen(
    onOpenPlaybackChain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.settings"),
            contentPadding = PaddingValues(
                horizontal = VesqenSpacing.lg,
                vertical = VesqenSpacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
        ) {
            item {
                Text(
                    text = stringResource(R.string.destination_settings),
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            item {
                SettingsSectionLabel(stringResource(R.string.settings_audio_output))
            }
            item {
                SettingsActionRow(
                    icon = { Icon(Icons.Filled.AccountTree, contentDescription = null) },
                    title = stringResource(R.string.settings_playback_chain),
                    body = stringResource(R.string.settings_playback_chain_body),
                    onClick = onOpenPlaybackChain,
                    modifier = Modifier.testTag("vesqen.settings.playback-chain"),
                )
            }
            item {
                SettingsSectionLabel(stringResource(R.string.settings_about))
            }
            item {
                SettingsInfoRow(
                    icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    title = stringResource(R.string.settings_local_first),
                    body = stringResource(R.string.settings_local_first_body),
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = VesqenSpacing.xs),
    )
}

@Composable
private fun SettingsActionRow(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        SettingsRowContent(icon = icon, title = title, body = body, showChevron = true)
    }
}

@Composable
private fun SettingsInfoRow(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        SettingsRowContent(icon = icon, title = title, body = body, showChevron = false)
    }
}

@Composable
private fun SettingsRowContent(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    showChevron: Boolean,
) {
    Row(
        modifier = Modifier.padding(VesqenSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.width(VesqenSpacing.sm))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(VesqenSpacing.xs))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
