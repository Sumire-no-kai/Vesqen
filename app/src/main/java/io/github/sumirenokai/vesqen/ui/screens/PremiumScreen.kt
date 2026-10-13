package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.premium.PremiumAccess
import io.github.sumirenokai.vesqen.premium.PremiumState
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

/**
 * #119: what is free, what Pro features include, and where the trial stands. Purchase and
 * activation entries join this page with the channel work (#120, #46).
 */
@Composable
fun PremiumScreen(premiumAccess: PremiumAccess, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // Days left move with the date, so read the clock again whenever the page opens.
    LaunchedEffect(premiumAccess) { premiumAccess.refresh() }
    val state by premiumAccess.state.collectAsStateWithLifecycle()
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.premium"),
            contentPadding = PaddingValues(bottom = VesqenSpacing.xl),
        ) {
            item { SettingsDetailHeader(stringResource(R.string.settings_premium), "vesqen.premium.back", onBack) }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = VesqenSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
                ) {
                    Text(
                        text = premiumStatusSentence(state),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("vesqen.premium.status"),
                    )
                    PremiumNote(stringResource(R.string.premium_free_features))
                    PremiumNote(stringResource(R.string.premium_paid_features))
                    PremiumNote(
                        stringResource(
                            if (state == PremiumState.FreeInBeta) R.string.premium_terms_beta else R.string.premium_terms,
                        ),
                    )
                }
            }
        }
    }
}

/** The short value beside "Pro features" in Settings. */
@Composable
internal fun premiumSettingsValue(state: PremiumState): String = when (state) {
    PremiumState.FreeInBeta -> stringResource(R.string.settings_premium_free_in_beta)
    PremiumState.TrialNotStarted -> stringResource(R.string.settings_premium_not_started)
    is PremiumState.Trial -> pluralStringResource(R.plurals.settings_premium_days_left, state.daysLeft, state.daysLeft)
    PremiumState.TrialEnded -> stringResource(R.string.settings_premium_trial_ended)
    PremiumState.Unlocked -> stringResource(R.string.settings_premium_unlocked)
}

@Composable
private fun premiumStatusSentence(state: PremiumState): String = when (state) {
    PremiumState.FreeInBeta -> stringResource(R.string.premium_status_free_in_beta)
    PremiumState.TrialNotStarted -> stringResource(R.string.premium_status_not_started)
    is PremiumState.Trial -> pluralStringResource(R.plurals.premium_status_days_left, state.daysLeft, state.daysLeft)
    PremiumState.TrialEnded -> stringResource(R.string.premium_status_trial_ended)
    PremiumState.Unlocked -> stringResource(R.string.premium_status_unlocked)
}

/** Shown once, the first time strict USB output is turned on (#119). */
@Composable
internal fun StrictOutputIntroductionDialog(
    freeInBeta: Boolean,
    onDismiss: () -> Unit,
    onOpenPremium: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("vesqen.premium.strict-intro"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.premium_strict_intro_title)) },
        text = {
            Text(
                stringResource(
                    if (freeInBeta) R.string.premium_strict_intro_body_beta else R.string.premium_strict_intro_body,
                ),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("vesqen.premium.strict-intro.ok"),
            ) { Text(stringResource(R.string.premium_strict_intro_ok)) }
        },
        dismissButton = {
            TextButton(
                onClick = onOpenPremium,
                modifier = Modifier.testTag("vesqen.premium.strict-intro.open"),
            ) { Text(stringResource(R.string.premium_open)) }
        },
    )
}

@Composable
private fun PremiumNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
