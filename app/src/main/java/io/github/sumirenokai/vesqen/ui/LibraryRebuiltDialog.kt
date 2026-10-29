package io.github.sumirenokai.vesqen.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R

/** #165: the catalog was found corrupt and rebuilt; see [io.github.sumirenokai.vesqen.library.LibraryRebuildNotice]. */
@Composable
internal fun LibraryRebuiltDialog(onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("vesqen.library.rebuilt-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_rebuilt_title)) },
        text = {
            Text(
                stringResource(R.string.library_rebuilt_body),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.library.rebuilt-ok"),
            ) { Text(stringResource(R.string.library_rebuilt_ok)) }
        },
    )
}
