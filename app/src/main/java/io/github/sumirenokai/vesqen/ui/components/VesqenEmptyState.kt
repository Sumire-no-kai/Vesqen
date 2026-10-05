package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

/**
 * B §5 and §8: a serif title, one plain sentence and one action, with no placeholder artwork.
 * Actions that change something (add a folder, retry, create) use the primary button; actions
 * that only move elsewhere (clear search, back, browse) stay secondary, so a page keeps one
 * primary action.
 */
@Composable
fun VesqenEmptyState(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    primaryAction: Boolean = true,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(VesqenSpacing.md))
            if (primaryAction) {
                Button(onClick = onAction, modifier = Modifier.heightIn(min = 52.dp)) { Text(actionLabel) }
            } else {
                OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 44.dp)) { Text(actionLabel) }
            }
        }
    }
}
