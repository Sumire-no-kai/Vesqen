package io.github.sumirenokai.vesqen.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.ArtistSplitSettings
import io.github.sumirenokai.vesqen.library.DefaultArtistSeparators
import io.github.sumirenokai.vesqen.library.DefaultKeptArtistNames
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.StorageScanAccess
import io.github.sumirenokai.vesqen.library.parseArtistSeparators
import io.github.sumirenokai.vesqen.library.parseKeptArtistNames
import io.github.sumirenokai.vesqen.ui.StorageScanFlow
import io.github.sumirenokai.vesqen.ui.storageScanPermissionOffText
import io.github.sumirenokai.vesqen.ui.components.PaperDivider
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy

/**
 * #168: how the Artists page reads a tag that names several artists. The two lists open as dialogs
 * with one text field each, so the group needs no page of its own; while splitting is off they are
 * hidden, since they change nothing. [onEditList] opens a list's [ArtistSplitListDialog], which
 * Settings keeps outside its lazy list (#191 C4).
 */
@Composable
internal fun SettingsLibraryGroup(
    artistSplit: ArtistSplitSettings,
    onArtistSplitChange: (ArtistSplitSettings) -> Unit,
    onEditList: (ArtistSplitList) -> Unit,
    storageScan: StorageScanFlow? = null,
    storageSource: LibrarySource? = null,
    storageScanning: Boolean = false,
) {
    val motionMillis = rememberVesqenMotionPolicy().stateChangeMillis
    val none = stringResource(R.string.settings_artist_list_empty)
    SettingsGroup(
        title = stringResource(R.string.settings_library),
        modifier = Modifier.testTag("vesqen.settings.section.library"),
    ) {
        if (storageScan != null) {
            StorageScanRows(storageScan, storageSource, storageScanning)
            PaperDivider()
        }
        SettingsSwitchRow(
            title = stringResource(R.string.settings_artist_split),
            description = stringResource(R.string.settings_artist_split_body),
            checked = artistSplit.enabled,
            onCheckedChange = { onArtistSplitChange(artistSplit.copy(enabled = it)) },
            modifier = Modifier.testTag("vesqen.settings.artist-split"),
        )
        AnimatedVisibility(
            visible = artistSplit.enabled,
            enter = fadeIn(tween(motionMillis)) + expandVertically(tween(motionMillis)),
            exit = fadeOut(tween(motionMillis)) + shrinkVertically(tween(motionMillis)),
        ) {
            Column {
                PaperDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_artist_separators),
                    description = artistSplit.separators.joinToString(" ").ifEmpty { none },
                    onClick = { onEditList(ArtistSplitList.SEPARATORS) },
                    modifier = Modifier.testTag("vesqen.settings.artist-separators"),
                )
                PaperDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_artist_kept_names),
                    description = artistSplit.keptNames.joinToString(" · ").ifEmpty { none },
                    descriptionMaxLines = 2,
                    onClick = { onEditList(ArtistSplitList.KEPT_NAMES) },
                    modifier = Modifier.testTag("vesqen.settings.artist-kept-names"),
                )
            }
        }
    }
}

/**
 * #249: the GitHub build's whole-storage scan, the Library group's first row. The switch keeps the
 * user's choice while Android withdraws the permission; the text under it says what holds it back, and
 * a second row leads to the system page that changes it.
 */
@Composable
private fun StorageScanRows(flow: StorageScanFlow, source: LibrarySource?, scanning: Boolean) {
    val enabled by flow.scan.enabled.collectAsStateWithLifecycle()
    val access = flow.access
    val unavailable = access == StorageScanAccess.UNAVAILABLE
    val permissionOff = enabled && access == StorageScanAccess.NEEDS_PERMISSION
    val description = when {
        unavailable -> stringResource(R.string.settings_full_scan_unavailable)
        permissionOff -> stringResource(storageScanPermissionOffText())
        enabled && scanning -> stringResource(R.string.settings_full_scan_scanning)
        enabled && source?.scanState == LibraryScanState.PAUSED -> stringResource(R.string.library_source_paused)
        enabled && source != null -> pluralStringResource(R.plurals.library_source_track_count, source.trackCount, source.trackCount)
        enabled -> stringResource(R.string.settings_full_scan_body)
        else -> flow.note?.let { stringResource(it) } ?: stringResource(R.string.settings_full_scan_body)
    }
    SettingsSwitchRow(
        title = stringResource(R.string.settings_full_scan),
        description = description,
        descriptionColor = if (permissionOff) MaterialTheme.colorScheme.error else Color.Unspecified,
        checked = enabled && !unavailable,
        enabled = !unavailable,
        onCheckedChange = { on -> if (on) flow.turnOn() else flow.turnOff() },
        modifier = Modifier.testTag("vesqen.settings.storage-scan"),
    )
    val secondRow = when {
        unavailable -> null
        permissionOff -> R.string.settings_full_scan_allow_again
        // Android 11 and later: the permission stays allowed after the switch goes off, until removed there.
        !enabled && access == StorageScanAccess.GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            R.string.settings_full_scan_revoke
        !enabled && flow.storageRefused -> R.string.allow_music_access_in_settings
        else -> null
    }
    secondRow?.let { title ->
        PaperDivider()
        SettingsRow(
            title = stringResource(title),
            onClick = flow::openPermissionPage,
            modifier = Modifier.testTag("vesqen.settings.storage-scan.permission"),
        )
    }
}

internal enum class ArtistSplitList { SEPARATORS, KEPT_NAMES }

/**
 * Hosted by Settings outside its lazy list (#191 C4): inside a list item, a recreation that left the
 * item off-screen hid an open dialog, which then reopened by itself when the user scrolled back.
 */
@Composable
internal fun ArtistSplitListDialog(
    list: ArtistSplitList,
    artistSplit: ArtistSplitSettings,
    onArtistSplitChange: (ArtistSplitSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    when (list) {
        ArtistSplitList.SEPARATORS -> ArtistSplitTextDialog(
            title = stringResource(R.string.settings_artist_separators),
            body = stringResource(R.string.settings_artist_separators_body),
            text = artistSplit.separators.joinToString(" "),
            defaults = DefaultArtistSeparators.joinToString(" "),
            singleLine = true,
            onSave = { onArtistSplitChange(artistSplit.copy(separators = parseArtistSeparators(it))) },
            onDismiss = onDismiss,
            tag = "vesqen.settings.artist-separators.dialog",
        )
        ArtistSplitList.KEPT_NAMES -> ArtistSplitTextDialog(
            title = stringResource(R.string.settings_artist_kept_names),
            body = stringResource(R.string.settings_artist_kept_names_body),
            text = artistSplit.keptNames.joinToString("\n"),
            defaults = DefaultKeptArtistNames.joinToString("\n"),
            singleLine = false,
            onSave = { onArtistSplitChange(artistSplit.copy(keptNames = parseKeptArtistNames(it))) },
            onDismiss = onDismiss,
            tag = "vesqen.settings.artist-kept-names.dialog",
        )
    }
}

/** Separators are typed apart by spaces on one line, names one per line; nothing changes until Save. */
@Composable
private fun ArtistSplitTextDialog(
    title: String,
    body: String,
    text: String,
    defaults: String,
    singleLine: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    tag: String,
) {
    var value by rememberSaveable { mutableStateOf(text) }
    AlertDialog(
        modifier = Modifier.testTag(tag),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
            ) {
                Text(body)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(title) },
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else 3,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("$tag.input"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(value)
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tag.save"),
            ) { Text(stringResource(R.string.save)) }
        },
        // Two buttons in this slot become two items of the dialog's wrapping button row, as in the
        // support question, so large text moves a button to its own line.
        dismissButton = {
            TextButton(
                onClick = { value = defaults },
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tag.restore"),
            ) { Text(stringResource(R.string.settings_artist_split_restore)) }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.cancel)) }
        },
    )
}
