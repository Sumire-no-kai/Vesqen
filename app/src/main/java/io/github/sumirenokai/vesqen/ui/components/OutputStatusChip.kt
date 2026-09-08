package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
) {
    val label = when (declaration) {
        OutputDeclaration.SYSTEM_MIXED -> androidx.compose.ui.res.stringResource(R.string.system_mixed)
        OutputDeclaration.BIT_PERFECT_AVAILABLE ->
            androidx.compose.ui.res.stringResource(R.string.bit_perfect_available)
        OutputDeclaration.BIT_PERFECT_REQUESTED ->
            androidx.compose.ui.res.stringResource(R.string.bit_perfect_requested)
        OutputDeclaration.BIT_PERFECT_ACTIVE ->
            androidx.compose.ui.res.stringResource(R.string.bit_perfect_active)
        OutputDeclaration.BIT_PERFECT_VERIFIED ->
            androidx.compose.ui.res.stringResource(R.string.bit_perfect_verified)
        OutputDeclaration.BIT_PERFECT_FAILED ->
            androidx.compose.ui.res.stringResource(R.string.bit_perfect_failed)
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
    val verified = declaration == OutputDeclaration.BIT_PERFECT_VERIFIED
    val resolvedContainerColor = containerColor ?: when {
        verified -> MaterialTheme.colorScheme.tertiaryContainer
        declaration == OutputDeclaration.BIT_PERFECT_FAILED -> MaterialTheme.colorScheme.errorContainer
        declaration == OutputDeclaration.BIT_PERFECT_ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val resolvedContentColor = contentColor ?: when {
        verified -> MaterialTheme.colorScheme.onTertiaryContainer
        declaration == OutputDeclaration.BIT_PERFECT_FAILED -> MaterialTheme.colorScheme.onErrorContainer
        declaration == OutputDeclaration.BIT_PERFECT_ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
            color = resolvedContainerColor,
            contentColor = resolvedContentColor,
            border = if (verified) BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary) else null,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (verified) Icons.Filled.VerifiedUser else Icons.Filled.Route,
                    contentDescription = null,
                    modifier = Modifier.defaultMinSize(minWidth = 14.dp, minHeight = 14.dp),
                )
                Text(text = label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
