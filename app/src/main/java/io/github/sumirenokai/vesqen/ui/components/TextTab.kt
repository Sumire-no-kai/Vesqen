package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * B · Paper & Sound §5: a text tab, the selected one in ink over a 2 dp ink rule, the rest muted;
 * no pills or filled blocks. A selected tab scrolls itself into view in a row that scrolls sideways.
 */
@Composable
internal fun TextTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) requester.bringIntoView()
    }
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .bringIntoViewRequester(requester)
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .drawBehind {
                if (selected) {
                    val rule = 2.dp.toPx()
                    drawRect(ink, topLeft = Offset(0f, size.height - rule), size = Size(size.width, rule))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (selected) ink else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
