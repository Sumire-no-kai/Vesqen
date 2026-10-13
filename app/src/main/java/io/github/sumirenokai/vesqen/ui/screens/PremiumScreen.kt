package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.sumirenokai.vesqen.premium.ActivationCodes
import io.github.sumirenokai.vesqen.premium.ActivationResult
import io.github.sumirenokai.vesqen.premium.ActivationState
import io.github.sumirenokai.vesqen.premium.PremiumAccess
import io.github.sumirenokai.vesqen.premium.PremiumState
import io.github.sumirenokai.vesqen.ui.components.PaperCard
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

/**
 * #119: what is free, what Pro features include, and where the trial stands. Purchase and
 * activation entries join this page with the channel work (#120, #46).
 */
@Composable
fun PremiumScreen(
    premiumAccess: PremiumAccess,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    activationCodes: ActivationCodes? = null,
) {
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
                            if (state == PremiumState.NotCharging) R.string.premium_terms_not_charging else R.string.premium_terms,
                        ),
                    )
                }
            }
            if (activationCodes != null) item { ActivationSection(activationCodes) }
        }
    }
}

/** The short value beside "Pro features" in Settings. */
@Composable
internal fun premiumSettingsValue(state: PremiumState): String = when (state) {
    PremiumState.NotCharging -> stringResource(R.string.settings_premium_not_charging)
    PremiumState.TrialNotStarted -> stringResource(R.string.settings_premium_not_started)
    is PremiumState.Trial -> pluralStringResource(R.plurals.settings_premium_days_left, state.daysLeft, state.daysLeft)
    PremiumState.TrialEnded -> stringResource(R.string.settings_premium_trial_ended)
    PremiumState.Unlocked -> stringResource(R.string.settings_premium_unlocked)
}

@Composable
private fun premiumStatusSentence(state: PremiumState): String = when (state) {
    PremiumState.NotCharging -> stringResource(R.string.premium_status_not_charging)
    PremiumState.TrialNotStarted -> stringResource(R.string.premium_status_not_started)
    is PremiumState.Trial -> pluralStringResource(R.plurals.premium_status_days_left, state.daysLeft, state.daysLeft)
    PremiumState.TrialEnded -> stringResource(R.string.premium_status_trial_ended)
    PremiumState.Unlocked -> stringResource(R.string.premium_status_unlocked)
}

/** Shown once, the first time strict USB output is turned on (#119). */
@Composable
internal fun StrictOutputIntroductionDialog(
    notCharging: Boolean,
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
                    if (notCharging) R.string.premium_strict_intro_body_not_charging else R.string.premium_strict_intro_body,
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

/** #120: the GitHub build's activation code, kept visible so it can be copied for a new phone. */
@Composable
private fun ActivationSection(activationCodes: ActivationCodes) {
    val activation by activationCodes.state.collectAsStateWithLifecycle()
    val lastResult by activationCodes.lastResult.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.padding(start = VesqenSpacing.lg, end = VesqenSpacing.lg, top = VesqenSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.premium_activation_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        when (val current = activation) {
            is ActivationState.Active -> {
                PaperCard {
                    SelectionContainer {
                        Text(
                            text = current.code,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .padding(VesqenSpacing.md)
                                .testTag("vesqen.premium.activation.code"),
                        )
                    }
                }
                PremiumNote(stringResource(R.string.premium_activation_active))
            }
            ActivationState.None, is ActivationState.Revoked -> {
                if (current is ActivationState.Revoked) PremiumNote(stringResource(R.string.premium_activation_revoked))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.premium_activation_hint)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vesqen.premium.activation.input"),
                )
                Button(
                    onClick = { activationCodes.activate(input) },
                    enabled = input.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("vesqen.premium.activation.activate"),
                ) { Text(stringResource(R.string.premium_activate)) }
                lastResult?.takeIf { it != ActivationResult.ACTIVATED }?.let { result ->
                    Text(
                        text = stringResource(
                            when (result) {
                                ActivationResult.MALFORMED -> R.string.premium_activation_malformed
                                ActivationResult.NOT_GENUINE -> R.string.premium_activation_not_genuine
                                ActivationResult.REVOKED -> R.string.premium_activation_revoked
                                ActivationResult.ACTIVATED -> R.string.premium_status_unlocked
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("vesqen.premium.activation.error"),
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
