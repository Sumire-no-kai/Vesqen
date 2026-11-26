package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.DSD_MAX_GAIN_DB
import io.github.sumirenokai.vesqen.playback.DSD_MIN_GAIN_DB
import io.github.sumirenokai.vesqen.playback.DSD_GAIN_STEP_DB
import io.github.sumirenokai.vesqen.playback.DsdConversionSettings
import io.github.sumirenokai.vesqen.playback.DsdOutputRateMode
import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.playback.dsdGainStep
import io.github.sumirenokai.vesqen.ui.LocalScreenOffKeepAlive
import io.github.sumirenokai.vesqen.ui.components.OutputStatusChip
import io.github.sumirenokai.vesqen.ui.components.PaperDivider
import io.github.sumirenokai.vesqen.ui.openBackgroundPowerSettings
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import io.github.sumirenokai.vesqen.ui.theme.VesqenDataStyle
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy
import java.util.Locale

/** The DSD settings, provided by the activity; null where nothing provides them (tests, previews). */
internal val LocalDsdConversionSettings = staticCompositionLocalOf<DsdConversionSettings?> { null }

/**
 * Every output setting on one page (#249, owner 2026-11-26), opened from the Settings row and from
 * Now's output-mode dialog. The output modes, the strict status and the direct USB rows moved here
 * from Settings unchanged. The DSD settings apply only under System output, since strict output does
 * not play DSD, so they stay adjustable whichever mode is chosen.
 */
@Composable
fun AudioOutputScreen(
    outputStatus: UsbOutputStatus,
    onSetUsbOutputMode: (UsbOutputMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    outputModeSelectionEnabled: Boolean = true,
    onExplainStrictUsbUnavailable: () -> Unit = {},
    onOpenPremium: (() -> Unit)? = null,
) {
    val unsupportedPlatform = outputStatus.strictUnavailablePlatform
    val strictSelected = outputStatus.mode == UsbOutputMode.STRICT_BIT_PERFECT
    val motionMillis = rememberVesqenMotionPolicy().stateChangeMillis
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag("vesqen.audio-output"),
            contentPadding = PaddingValues(bottom = VesqenSpacing.xl),
        ) {
            item {
                SettingsDetailHeader(stringResource(R.string.settings_audio_output), "vesqen.audio-output.back", onBack)
            }
            item {
                SettingsGroup(
                    title = stringResource(R.string.player_output_mode),
                    modifier = Modifier.testTag("vesqen.audio-output.section.output-mode"),
                    footer = {
                        if (!outputModeSelectionEnabled) SettingsNote(stringResource(R.string.playback_controls_connecting))
                        AnimatedVisibility(
                            visible = strictSelected,
                            enter = fadeIn(tween(motionMillis)) + expandVertically(tween(motionMillis)),
                            exit = fadeOut(tween(motionMillis)) + shrinkVertically(tween(motionMillis)),
                        ) { StrictOutputStatusBox(outputStatus, onOpenPremium) }
                    },
                ) {
                    Column(Modifier.selectableGroup().testTag("vesqen.settings.output-modes")) {
                        SettingsRadioRow(
                            title = stringResource(R.string.settings_system_output),
                            description = stringResource(R.string.settings_system_output_body),
                            selected = outputStatus.mode == UsbOutputMode.SYSTEM,
                            enabled = outputModeSelectionEnabled,
                            onClick = { onSetUsbOutputMode(UsbOutputMode.SYSTEM) },
                            modifier = Modifier.testTag("vesqen.settings.output.system"),
                        )
                        PaperDivider()
                        SettingsRadioRow(
                            title = stringResource(R.string.settings_strict_usb_output),
                            description = unsupportedPlatform?.let {
                                stringResource(R.string.settings_strict_usb_platform_unavailable, it.androidRelease, it.apiLevel)
                            } ?: stringResource(
                                if (outputStatus.directUsbAvailable) {
                                    R.string.settings_strict_usb_output_body_direct
                                } else {
                                    R.string.settings_strict_usb_output_body
                                },
                            ),
                            selected = strictSelected,
                            enabled = outputModeSelectionEnabled && unsupportedPlatform == null,
                            onClick = { onSetUsbOutputMode(UsbOutputMode.STRICT_BIT_PERFECT) },
                            onUnavailableClick = onExplainStrictUsbUnavailable.takeIf {
                                outputModeSelectionEnabled && unsupportedPlatform != null
                            },
                            modifier = Modifier.testTag("vesqen.settings.output.strict-usb"),
                        )
                    }
                    if (outputStatus.directUsbAvailable) {
                        PaperDivider()
                        DirectUsbInformationRow()
                    }
                    if (strictSelected && outputStatus.directUsbAvailable) ScreenOffRows()
                }
            }
            item { DsdGainGroup() }
            item { DsdRateGroup() }
        }
    }
}

/** Off by default: DSD plays at its recorded level. On, a slider sets the gain. */
@Composable
private fun DsdGainGroup() {
    val settings = LocalDsdConversionSettings.current ?: return
    val values by settings.values.collectAsStateWithLifecycle()
    val motionMillis = rememberVesqenMotionPolicy().stateChangeMillis
    SettingsGroup(
        title = stringResource(R.string.settings_dsd_gain_group),
        modifier = Modifier.testTag("vesqen.audio-output.section.dsd-gain"),
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_dsd_gain),
            description = stringResource(R.string.settings_dsd_gain_body),
            checked = values.gainEnabled,
            onCheckedChange = settings::setGainEnabled,
            modifier = Modifier.testTag("vesqen.audio-output.dsd-gain"),
        )
        AnimatedVisibility(
            visible = values.gainEnabled,
            enter = fadeIn(tween(motionMillis)) + expandVertically(tween(motionMillis)),
            exit = fadeOut(tween(motionMillis)) + shrinkVertically(tween(motionMillis)),
        ) {
            Column {
                PaperDivider()
                DsdGainSlider(values.gainDb, settings::setGainDb)
                Text(
                    text = stringResource(R.string.settings_dsd_gain_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = VesqenSpacing.md, end = VesqenSpacing.md, bottom = VesqenSpacing.sm),
                )
            }
        }
    }
}

@Composable
private fun DsdRateGroup() {
    val settings = LocalDsdConversionSettings.current ?: return
    val values by settings.values.collectAsStateWithLifecycle()
    SettingsGroup(
        title = stringResource(R.string.settings_dsd_rate_group),
        modifier = Modifier.testTag("vesqen.audio-output.section.dsd-rate"),
        footer = { SettingsNote(stringResource(R.string.settings_dsd_note)) },
    ) {
        Column(Modifier.selectableGroup()) {
            SettingsRadioRow(
                title = stringResource(R.string.settings_dsd_rate_uniform),
                description = stringResource(R.string.settings_dsd_rate_uniform_body),
                selected = values.rateMode == DsdOutputRateMode.UNIFORM,
                enabled = true,
                onClick = { settings.setRateMode(DsdOutputRateMode.UNIFORM) },
                modifier = Modifier.testTag("vesqen.audio-output.dsd-rate.uniform"),
            )
            PaperDivider()
            SettingsRadioRow(
                title = stringResource(R.string.settings_dsd_rate_multiple),
                description = stringResource(R.string.settings_dsd_rate_multiple_body),
                selected = values.rateMode == DsdOutputRateMode.MULTIPLE,
                enabled = true,
                onClick = { settings.setRateMode(DsdOutputRateMode.MULTIPLE) },
                modifier = Modifier.testTag("vesqen.audio-output.dsd-rate.multiple"),
            )
        }
    }
}

/**
 * Drawn like the DAC volume bar. It moves in 0.5 dB steps and saves only when a drag or a TalkBack
 * adjustment ends, because each save opens the next DSD track again. Above 130 % text the value
 * moves above the slider so the slider keeps room for its steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DsdGainSlider(gainDb: Float, onSet: (Float) -> Unit) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown = dragged ?: gainDb
    val label = stringResource(R.string.settings_dsd_gain_level)
    val state = stringResource(R.string.usb_volume_state, dsdGainNumber(shown))
    val played = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)
    val readoutAbove = LocalDensity.current.fontScale > 1.3f
    val readoutStyle = VesqenDataStyle.copy(fontSize = 12.sp)
    Column(Modifier.padding(horizontal = VesqenSpacing.md)) {
        if (readoutAbove) {
            Text(
                text = stringResource(R.string.chain_unit_decibels, dsdGainNumber(shown)),
                style = readoutStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = VesqenSpacing.xs).clearAndSetSemantics {},
            )
        }
        Row(Modifier.fillMaxWidth().height(DirectUsbVolumeBarHeight), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                modifier = Modifier
                    .weight(1f)
                    .height(DirectUsbVolumeBarHeight)
                    .testTag("vesqen.audio-output.dsd-gain-level")
                    .semantics {
                        contentDescription = label
                        stateDescription = state
                    },
                value = shown,
                onValueChange = { dragged = dsdGainStep(it) },
                onValueChangeFinished = {
                    dragged?.let(onSet)
                    dragged = null
                },
                valueRange = DSD_MIN_GAIN_DB..DSD_MAX_GAIN_DB,
                steps = ((DSD_MAX_GAIN_DB - DSD_MIN_GAIN_DB) / DSD_GAIN_STEP_DB).toInt() - 1,
                thumb = {
                    Box(Modifier.size(width = 10.dp, height = DirectUsbVolumeBarHeight), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(10.dp).background(played, CircleShape))
                    }
                },
                track = { sliderState ->
                    Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                        val fraction = ((sliderState.value - sliderState.valueRange.start) /
                            (sliderState.valueRange.endInclusive - sliderState.valueRange.start)).coerceIn(0f, 1f)
                        val rtl = layoutDirection == LayoutDirection.Rtl
                        val start = Offset(if (rtl) size.width else 0f, size.height / 2f)
                        val end = Offset(if (rtl) 0f else size.width, size.height / 2f)
                        drawLine(rail, start, end, size.height)
                        drawLine(played, start, start + (end - start) * fraction, size.height)
                    }
                },
            )
            if (!readoutAbove) {
                // Sized for the longest value, so the slider keeps its width while the number changes.
                Box(Modifier.padding(start = VesqenSpacing.sm).clearAndSetSemantics {}, contentAlignment = Alignment.CenterEnd) {
                    Text(stringResource(R.string.chain_unit_decibels, dsdGainNumber(DSD_MAX_GAIN_DB)), style = readoutStyle, maxLines = 1, modifier = Modifier.alpha(0f))
                    Text(
                        text = stringResource(R.string.chain_unit_decibels, dsdGainNumber(shown)),
                        style = readoutStyle,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("vesqen.audio-output.dsd-gain-value"),
                    )
                }
            }
        }
    }
}

/** "+3.0": the gain is always added, so it carries its sign. */
internal fun dsdGainNumber(gainDb: Float): String = String.format(Locale.getDefault(), "%+.1f", gainDb)

@Composable
private fun SettingsRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onUnavailableClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    enabled -> Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                    onUnavailableClick != null -> Modifier.clickable(onClick = onUnavailableClick, role = Role.Button)
                    else -> Modifier.selectable(selected = selected, enabled = false, onClick = onClick, role = Role.RadioButton)
                },
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.sm)
            .alpha(if (enabled) 1f else .56f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = LocalVesqenColors.current.radioIdle,
            ),
        )
        Spacer(Modifier.width(VesqenSpacing.sm))
        SettingsRowText(title, description, Modifier.weight(1f))
    }
}

/** D13 (#333): the keep-alive switch and, on vivo-family phones, the system setting that avoids it. */
@Composable
private fun ScreenOffRows() {
    val keepAlive = LocalScreenOffKeepAlive.current ?: return
    val enabled by keepAlive.enabled.collectAsStateWithLifecycle()
    PaperDivider()
    SettingsSwitchRow(
        title = stringResource(R.string.screen_off_keep_alive_title),
        description = stringResource(R.string.screen_off_keep_alive_body),
        checked = enabled,
        onCheckedChange = keepAlive::setEnabled,
        modifier = Modifier.testTag("vesqen.settings.screen-off-keep-alive"),
    )
    if (keepAlive.vivoFamily) {
        val context = LocalContext.current
        PaperDivider()
        SettingsRow(
            title = stringResource(R.string.background_power_title),
            description = stringResource(R.string.background_power_body),
            onClick = { openBackgroundPowerSettings(context) },
            modifier = Modifier.testTag("vesqen.settings.background-power"),
        )
    }
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = VesqenSpacing.xxs, top = 10.dp),
    )
}

/**
 * B §5: appears under the output choices while strict USB is selected. After a trial ends it offers
 * Pro features, which open above this page so Back returns here.
 */
@Composable
private fun StrictOutputStatusBox(status: UsbOutputStatus, onOpenPremium: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .border(1.dp, LocalVesqenColors.current.hairline, RoundedCornerShape(VesqenRadii.control))
            .padding(14.dp)
            .testTag("vesqen.settings.output.strict-status"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutputStatusChip(declaration = status.declaration)
        Text(text = strictUsbOutputBody(status), style = MaterialTheme.typography.bodySmall)
        if (onOpenPremium != null && status.failure == UsbOutputFailure.TRIAL_ENDED) {
            TextButton(
                onClick = onOpenPremium,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.audio-output.open-premium"),
            ) { Text(stringResource(R.string.premium_open)) }
        }
    }
}

/** The first-use facts remain available after the service has remembered the decision. */
@Composable
private fun DirectUsbInformationRow() {
    var show by rememberSaveable { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.usb_direct_intro_title),
        description = stringResource(R.string.usb_direct_info_summary),
        onClick = { show = true },
        modifier = Modifier.testTag("vesqen.settings.direct-usb-info"),
    )
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(stringResource(R.string.usb_direct_intro_title)) },
            text = {
                Text(stringResource(R.string.usb_direct_intro_body), modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = { show = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}
