package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii

internal enum class OutputStatusTreatment {
    NEUTRAL,
    SIGNAL_OUTLINE,
    SIGNAL_FILL,
    SIGNAL_TONAL,
    ERROR,
}

internal enum class OutputStatusCue(val tag: String) {
    ROUTE("route"),
    AVAILABILITY("availability"),
    REQUESTED("requested"),
    ACTIVITY("activity"),
    VERIFIED("verified"),
    FAILURE("failure"),
}

internal data class OutputStatusVisualSpec(
    val treatment: OutputStatusTreatment,
    val cue: OutputStatusCue,
    val acceptsNeutralColorOverride: Boolean = false,
)

internal fun outputStatusVisualSpec(declaration: OutputDeclaration): OutputStatusVisualSpec = when (declaration) {
    OutputDeclaration.SYSTEM_MIXED -> OutputStatusVisualSpec(
        treatment = OutputStatusTreatment.NEUTRAL,
        cue = OutputStatusCue.ROUTE,
        acceptsNeutralColorOverride = true,
    )
    OutputDeclaration.BIT_PERFECT_AVAILABLE -> OutputStatusVisualSpec(
        treatment = OutputStatusTreatment.SIGNAL_OUTLINE,
        cue = OutputStatusCue.AVAILABILITY,
    )
    OutputDeclaration.BIT_PERFECT_REQUESTED -> OutputStatusVisualSpec(
        treatment = OutputStatusTreatment.NEUTRAL,
        cue = OutputStatusCue.REQUESTED,
        acceptsNeutralColorOverride = true,
    )
    OutputDeclaration.BIT_PERFECT_ACTIVE -> OutputStatusVisualSpec(
        treatment = OutputStatusTreatment.SIGNAL_FILL,
        cue = OutputStatusCue.ACTIVITY,
    )
    OutputDeclaration.BIT_PERFECT_VERIFIED -> OutputStatusVisualSpec(
        treatment = OutputStatusTreatment.SIGNAL_TONAL,
        cue = OutputStatusCue.VERIFIED,
    )
    OutputDeclaration.BIT_PERFECT_FAILED -> OutputStatusVisualSpec(
        treatment = OutputStatusTreatment.ERROR,
        cue = OutputStatusCue.FAILURE,
    )
}

@Composable
fun OutputStatusChip(
    declaration: OutputDeclaration,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    neutralContainerColor: Color? = null,
    neutralContentColor: Color? = null,
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
    val visualSpec = outputStatusVisualSpec(declaration)
    val defaultContainerColor = when (visualSpec.treatment) {
        OutputStatusTreatment.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh
        OutputStatusTreatment.SIGNAL_OUTLINE -> Color.Transparent
        OutputStatusTreatment.SIGNAL_FILL -> MaterialTheme.colorScheme.primary
        OutputStatusTreatment.SIGNAL_TONAL -> MaterialTheme.colorScheme.primaryContainer
        OutputStatusTreatment.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val defaultContentColor = when (visualSpec.treatment) {
        OutputStatusTreatment.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        OutputStatusTreatment.SIGNAL_OUTLINE -> MaterialTheme.colorScheme.primary
        OutputStatusTreatment.SIGNAL_FILL -> MaterialTheme.colorScheme.onPrimary
        OutputStatusTreatment.SIGNAL_TONAL -> MaterialTheme.colorScheme.onPrimaryContainer
        OutputStatusTreatment.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    // Protected surfaces such as Now may provide their own neutral material. Evidence colors are
    // fixed semantic roles and must never be flattened back to that neutral surface.
    val resolvedContainerColor = if (visualSpec.acceptsNeutralColorOverride) {
        neutralContainerColor ?: defaultContainerColor
    } else defaultContainerColor
    val resolvedContentColor = if (visualSpec.acceptsNeutralColorOverride) {
        neutralContentColor ?: defaultContentColor
    } else defaultContentColor
    val border = when (visualSpec.treatment) {
        OutputStatusTreatment.SIGNAL_OUTLINE,
        OutputStatusTreatment.SIGNAL_TONAL -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else -> null
    }
    val icon = when (visualSpec.cue) {
        OutputStatusCue.ROUTE -> Icons.Filled.Route
        OutputStatusCue.AVAILABILITY -> Icons.Filled.RadioButtonUnchecked
        OutputStatusCue.REQUESTED -> Icons.Filled.HourglassTop
        OutputStatusCue.ACTIVITY -> Icons.Filled.FiberManualRecord
        OutputStatusCue.VERIFIED -> Icons.Filled.VerifiedUser
        OutputStatusCue.FAILURE -> Icons.Filled.Error
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
            border = border,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .testTag("vesqen.output-status.cue.${visualSpec.cue.tag}"),
                )
                Text(text = label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
