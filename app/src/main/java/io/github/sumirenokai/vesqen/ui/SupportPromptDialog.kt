package io.github.sumirenokai.vesqen.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

/**
 * #151: the GitHub build's occasional question. It says plainly what support pays for and that it
 * unlocks nothing. [onSupport] returns false when no browser could open the page; the dialog then
 * stays and says so.
 */
@Composable
internal fun SupportPromptDialog(
    onSupport: () -> Boolean,
    onLater: () -> Unit,
    onNever: () -> Unit,
) {
    var browserUnavailable by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier.testTag("vesqen.support-prompt"),
        // Back or a tap outside is the same as "Later".
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.support_prompt_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.support_prompt_body))
                if (browserUnavailable) {
                    Spacer(Modifier.height(VesqenSpacing.sm))
                    Text(
                        text = stringResource(R.string.privacy_policy_no_browser),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("vesqen.support-prompt.no-browser"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { browserUnavailable = !onSupport() },
                modifier = Modifier.testTag("vesqen.support-prompt.support"),
            ) { Text(stringResource(R.string.support_prompt_support)) }
        },
        // Two buttons in this slot become two items of the dialog's wrapping button row, so large
        // text moves a button to its own line instead of squeezing it.
        dismissButton = {
            TextButton(
                onClick = onNever,
                modifier = Modifier.testTag("vesqen.support-prompt.never"),
            ) { Text(stringResource(R.string.support_prompt_never)) }
            TextButton(
                onClick = onLater,
                modifier = Modifier.testTag("vesqen.support-prompt.later"),
            ) { Text(stringResource(R.string.support_prompt_later)) }
        },
    )
}
