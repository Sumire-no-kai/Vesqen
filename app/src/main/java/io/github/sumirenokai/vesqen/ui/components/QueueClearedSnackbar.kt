package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The notice after Clear queue, which stays one tap (#136 R7): Undo puts the queue back (#165). The
 * action keeps the snackbar's own colour; the theme's primary is too dark on its dark surface.
 */
@Composable
fun QueueClearedSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    Snackbar(
        modifier = modifier.widthIn(max = 420.dp).testTag("vesqen.queue-cleared"),
        action = {
            TextButton(
                onClick = data::performAction,
                colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.queue-cleared.undo"),
            ) {
                Text(data.visuals.actionLabel.orEmpty())
            }
        },
    ) {
        Text(data.visuals.message)
    }
}
