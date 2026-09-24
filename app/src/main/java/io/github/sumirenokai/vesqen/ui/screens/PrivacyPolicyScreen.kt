package io.github.sumirenokai.vesqen.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.ui.privacy.PolicyBlock
import io.github.sumirenokai.vesqen.ui.privacy.PolicyMarkdown
import io.github.sumirenokai.vesqen.ui.privacy.PolicySpan
import io.github.sumirenokai.vesqen.ui.privacy.withoutDocumentTitle
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows the privacy policy packaged from `docs/PRIVACY_POLICY*.md` in the app's language. The web
 * copy opens in the system browser, so this screen needs no network permission of its own.
 */
@Composable
fun PrivacyPolicyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val assetPath = stringResource(R.string.privacy_policy_asset)
    val webUrl = stringResource(R.string.privacy_policy_url)
    val document by produceState<PolicyDocumentState>(PolicyDocumentState.Loading, assetPath) {
        value = withContext(Dispatchers.IO) { loadPolicy(context.assets, assetPath) }
    }
    var browserUnavailable by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.privacy"),
            contentPadding = PaddingValues(
                horizontal = VesqenSpacing.lg,
                vertical = VesqenSpacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
        ) {
            item { PrivacyPolicyHeader(onBack = onBack) }
            if (webUrl.isNotBlank()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs)) {
                        OutlinedButton(
                            onClick = { browserUnavailable = !openInBrowser(context, webUrl) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("vesqen.privacy.web"),
                        ) {
                            Text(stringResource(R.string.privacy_policy_open_web))
                        }
                        if (browserUnavailable) {
                            Text(
                                text = stringResource(R.string.privacy_policy_no_browser),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            when (val state = document) {
                PolicyDocumentState.Loading -> Unit
                PolicyDocumentState.Unavailable -> item {
                    Text(
                        text = stringResource(R.string.privacy_policy_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is PolicyDocumentState.Ready -> items(state.blocks) { block -> PolicyBlockView(block) }
            }
        }
    }
}

@Composable
private fun PrivacyPolicyHeader(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp).testTag("vesqen.privacy.back"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Spacer(Modifier.width(VesqenSpacing.xs))
        Text(
            text = stringResource(R.string.privacy_policy_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun PolicyBlockView(block: PolicyBlock) {
    when (block) {
        is PolicyBlock.Heading -> Text(
            text = block.content.toAnnotatedString(),
            style = if (block.level <= 1) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            modifier = Modifier
                .padding(top = VesqenSpacing.sm)
                .semantics { heading() },
        )
        is PolicyBlock.Paragraph -> Text(
            text = block.content.toAnnotatedString(),
            style = MaterialTheme.typography.bodyMedium,
        )
        is PolicyBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs)) {
            block.items.forEach { item ->
                Row {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(end = VesqenSpacing.sm)
                            .clearAndSetSemantics {},
                    )
                    Text(
                        text = item.toAnnotatedString(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        is PolicyBlock.Note -> Surface(
            shape = RoundedCornerShape(VesqenRadii.surface),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = block.content.toAnnotatedString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(VesqenSpacing.md),
            )
        }
        // The first row names the columns. On a phone the rows read better stacked than as a grid.
        is PolicyBlock.Table -> Column {
            block.rows.drop(1).forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                Column(
                    modifier = Modifier.padding(vertical = VesqenSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs),
                ) {
                    row.firstOrNull()?.let { title ->
                        Text(text = title.toAnnotatedString(), style = MaterialTheme.typography.titleSmall)
                    }
                    row.drop(1).forEach { cell ->
                        Text(
                            text = cell.toAnnotatedString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun List<PolicySpan>.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    for (span in this@toAnnotatedString) {
        when {
            span.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) }
            span.code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(span.text) }
            else -> append(span.text)
        }
    }
}

private sealed interface PolicyDocumentState {
    data object Loading : PolicyDocumentState
    data object Unavailable : PolicyDocumentState
    data class Ready(val blocks: List<PolicyBlock>) : PolicyDocumentState
}

private fun loadPolicy(assets: AssetManager, path: String): PolicyDocumentState = try {
    val text = assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    PolicyDocumentState.Ready(PolicyMarkdown.parse(text).withoutDocumentTitle())
} catch (_: IOException) {
    PolicyDocumentState.Unavailable
}

/** Returns false when no installed app can show web pages, so the screen can say so. */
private fun openInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    true
} catch (_: ActivityNotFoundException) {
    false
}
