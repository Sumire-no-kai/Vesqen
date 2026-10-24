package io.github.sumirenokai.vesqen.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy

internal enum class OutputStatusTreatment {
    NEUTRAL,
    SIGNAL_OUTLINE,
    SIGNAL_FILL,
    WARNING,
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
)

// B · Paper & Sound §2.5. Fill, outline, icon and text together tell the states apart; colour
// alone never raises a claim.
internal fun outputStatusVisualSpec(declaration: OutputDeclaration): OutputStatusVisualSpec = when (declaration) {
    OutputDeclaration.SYSTEM_MIXED -> OutputStatusVisualSpec(OutputStatusTreatment.NEUTRAL, OutputStatusCue.ROUTE)
    OutputDeclaration.BIT_PERFECT_AVAILABLE ->
        OutputStatusVisualSpec(OutputStatusTreatment.SIGNAL_OUTLINE, OutputStatusCue.AVAILABILITY)
    OutputDeclaration.BIT_PERFECT_REQUESTED ->
        OutputStatusVisualSpec(OutputStatusTreatment.SIGNAL_OUTLINE, OutputStatusCue.REQUESTED)
    OutputDeclaration.BIT_PERFECT_ACTIVE ->
        OutputStatusVisualSpec(OutputStatusTreatment.SIGNAL_FILL, OutputStatusCue.ACTIVITY)
    OutputDeclaration.BIT_PERFECT_VERIFIED ->
        OutputStatusVisualSpec(OutputStatusTreatment.SIGNAL_OUTLINE, OutputStatusCue.VERIFIED)
    OutputDeclaration.BIT_PERFECT_FAILED ->
        OutputStatusVisualSpec(OutputStatusTreatment.WARNING, OutputStatusCue.FAILURE)
}

@Composable
internal fun outputDeclarationLabel(declaration: OutputDeclaration): String =
    stringResource(outputDeclarationLabelResource(declaration))

/** The chip's code, also used wherever a declaration is written out, such as the dashboard card. */
@StringRes
internal fun outputDeclarationLabelResource(declaration: OutputDeclaration): Int = when (declaration) {
    OutputDeclaration.SYSTEM_MIXED -> R.string.system_mixed
    OutputDeclaration.BIT_PERFECT_AVAILABLE -> R.string.bit_perfect_available
    OutputDeclaration.BIT_PERFECT_REQUESTED -> R.string.bit_perfect_requested
    OutputDeclaration.BIT_PERFECT_ACTIVE -> R.string.bit_perfect_active
    OutputDeclaration.BIT_PERFECT_VERIFIED -> R.string.bit_perfect_verified
    OutputDeclaration.BIT_PERFECT_FAILED -> R.string.bit_perfect_failed
}

@Composable
fun OutputStatusChip(
    declaration: OutputDeclaration,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val label = outputDeclarationLabel(declaration)
    val description = stringResource(R.string.output_status_label)
    val openChainLabel = if (onClick == null) null else stringResource(R.string.open_playback_chain)
    // The 48 dp touch area is taller than the pill, so the press state is drawn on the pill itself
    // instead of as a square-cornered box around it. It grows from the pill's centre: a press is
    // reported in the touch area's coordinates, which put a touch-point ripple about 10 dp below
    // the finger (#143 Q14).
    val interactionSource = remember { MutableInteractionSource() }
    val touchTarget = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = openChainLabel,
                onClick = onClick,
            )
    }
    val visualSpec = outputStatusVisualSpec(declaration)
    val colors = MaterialTheme.colorScheme
    val warning = LocalVesqenColors.current.warning
    val containerColor = when (visualSpec.treatment) {
        OutputStatusTreatment.NEUTRAL -> LocalVesqenColors.current.chipNeutral
        OutputStatusTreatment.SIGNAL_FILL -> colors.primary
        OutputStatusTreatment.SIGNAL_OUTLINE, OutputStatusTreatment.WARNING -> Color.Transparent
    }
    val contentColor = when (visualSpec.treatment) {
        OutputStatusTreatment.NEUTRAL -> colors.onSurface
        OutputStatusTreatment.SIGNAL_OUTLINE -> colors.primary
        OutputStatusTreatment.SIGNAL_FILL -> colors.onPrimary
        OutputStatusTreatment.WARNING -> warning
    }
    val border = when (visualSpec.treatment) {
        OutputStatusTreatment.SIGNAL_OUTLINE -> BorderStroke(1.5.dp, colors.primary)
        OutputStatusTreatment.WARNING -> BorderStroke(1.5.dp, warning)
        else -> null
    }
    val icon = when (visualSpec.cue) {
        OutputStatusCue.ROUTE -> Icons.Filled.MergeType
        OutputStatusCue.AVAILABILITY, OutputStatusCue.REQUESTED -> Icons.Filled.RadioButtonUnchecked
        OutputStatusCue.ACTIVITY -> Icons.Filled.FiberManualRecord
        OutputStatusCue.VERIFIED -> Icons.Filled.VerifiedUser
        OutputStatusCue.FAILURE -> Icons.Filled.Warning
    }
    // REQUESTED differs from AVAILABLE by its text and a breathing hollow dot (B §7, 1.2 s).
    val breathing = visualSpec.cue == OutputStatusCue.REQUESTED && !rememberVesqenMotionPolicy().reduceMotion
    val iconAlpha = if (breathing) {
        rememberInfiniteTransition(label = "vesqen.output-status.requested").animateFloat(
            initialValue = 1f,
            targetValue = .35f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "vesqen.output-status.requested.alpha",
        ).value
    } else 1f

    Box(
        modifier = modifier
            .then(touchTarget)
            // One stop that reads the field name, then the visible label, then the click label. The
            // description names the field only, because the merged text already says the declaration.
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (onClick != null) role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = if (onClick == null) {
                Modifier
            } else {
                Modifier.clip(CircleShape).indication(interactionSource, ripple(bounded = false))
            },
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            border = border,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(14.dp)
                        .alpha(iconAlpha)
                        .testTag("vesqen.output-status.cue.${visualSpec.cue.tag}"),
                )
                Text(text = label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
