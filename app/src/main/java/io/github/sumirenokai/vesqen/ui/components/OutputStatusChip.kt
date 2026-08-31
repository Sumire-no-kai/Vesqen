package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii

@Composable
fun OutputStatusChip(
    declaration: OutputDeclaration,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val label = when (declaration) {
        OutputDeclaration.SYSTEM_MIXED -> androidx.compose.ui.res.stringResource(R.string.system_mixed)
    }
    val description = androidx.compose.ui.res.stringResource(R.string.output_status_description, label)
    val openChainLabel = if (onClick == null) {
        null
    } else {
        androidx.compose.ui.res.stringResource(R.string.open_playback_chain)
    }
    val touchTarget = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = openChainLabel,
                onClick = onClick,
            )
    }

    Box(
        modifier = modifier
            .then(touchTarget)
            .semantics {
                contentDescription = if (openChainLabel == null) description else "$description. $openChainLabel"
                if (onClick != null) role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Route,
                    contentDescription = null,
                    modifier = Modifier.defaultMinSize(minWidth = 14.dp, minHeight = 14.dp),
                )
                Text(text = label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
