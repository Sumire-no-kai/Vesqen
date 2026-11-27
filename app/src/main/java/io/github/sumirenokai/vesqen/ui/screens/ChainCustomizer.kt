package io.github.sumirenokai.vesqen.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardGroup
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardPreferences
import io.github.sumirenokai.vesqen.ui.chain.chainMetricMatchRanges
import io.github.sumirenokai.vesqen.ui.chain.chainMetricMatches
import io.github.sumirenokai.vesqen.ui.chain.telemetryMetricLabel
import io.github.sumirenokai.vesqen.ui.chain.telemetrySectionLabel
import io.github.sumirenokai.vesqen.ui.components.underSheetHandle
import io.github.sumirenokai.vesqen.ui.components.SearchPill
import io.github.sumirenokai.vesqen.ui.components.SheetWithoutNavigationBand
import io.github.sumirenokai.vesqen.ui.components.TextTab
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import io.github.sumirenokai.vesqen.ui.theme.VesqenDataStyle
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy
import kotlin.math.abs

private enum class ChainCustomizerTab { METRICS, LAYOUT }

/** Every metric by section, in catalog order: the Metrics tab is an index, so toggling never moves a row. */
private val CatalogSections: List<Pair<TelemetrySection, List<TelemetryMetricId>>> =
    TelemetrySection.entries.map { section ->
        section to TelemetryMetricCatalog.descriptors.filter { it.section == section }.map { it.id }
    }

private val SectionSetSaver = listSaver<Set<TelemetrySection>, String>(
    save = { sections -> sections.map(TelemetrySection::name) },
    restore = { names -> names.mapNotNull { name -> TelemetrySection.entries.firstOrNull { it.name == name } }.toSet() },
)

/**
 * The advanced dashboard's customizer, option A of the 2026-11-09 proposal (owner, 2026-11-24).
 * Metrics chooses what the dashboard shows from a folded, searchable index with one checkbox per
 * row. Layout pins and orders only what is shown, in the dashboard's own groups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChainCustomizerSheet(
    preferences: ChainDashboardPreferences,
    onPreferencesChanged: (ChainDashboardPreferences) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Full height from the start: the tabs and the search results change the content's height.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetWithoutNavigationBand()
        ChainCustomizerContent(
            preferences = preferences,
            onPreferencesChanged = onPreferencesChanged,
            onResetRequest = { confirmReset = true },
            onDismiss = onDismiss,
            modifier = Modifier.underSheetHandle().fillMaxSize(),
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.chain_reset_title)) },
            text = { Text(stringResource(R.string.chain_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onReset()
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.chain.reset-confirm"),
                ) {
                    Text(stringResource(R.string.reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** The sheet's content: the title, the two tabs and the chosen tab's list. */
@Composable
internal fun ChainCustomizerContent(
    preferences: ChainDashboardPreferences,
    onPreferencesChanged: (ChainDashboardPreferences) -> Unit,
    onResetRequest: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(ChainCustomizerTab.METRICS) }
    Column(modifier = modifier.testTag("vesqen.chain.customizer")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = VesqenSpacing.lg, end = VesqenSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chain_customize_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.close))
            }
        }
        val hairline = LocalVesqenColors.current.hairline
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val y = size.height - .5.dp.toPx()
                    drawLine(hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                .padding(horizontal = VesqenSpacing.lg)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TextTab(
                label = stringResource(R.string.chain_customize_tab_metrics),
                selected = tab == ChainCustomizerTab.METRICS,
                onClick = { tab = ChainCustomizerTab.METRICS },
                modifier = Modifier.testTag("vesqen.chain.customizer.tab.metrics"),
            )
            TextTab(
                label = stringResource(R.string.chain_customize_tab_layout),
                selected = tab == ChainCustomizerTab.LAYOUT,
                onClick = { tab = ChainCustomizerTab.LAYOUT },
                modifier = Modifier.testTag("vesqen.chain.customizer.tab.layout"),
            )
        }
        when (tab) {
            ChainCustomizerTab.METRICS -> ChainMetricsChooser(
                preferences = preferences,
                onPreferencesChanged = onPreferencesChanged,
                onResetRequest = onResetRequest,
                modifier = Modifier.weight(1f),
            )
            ChainCustomizerTab.LAYOUT -> ChainLayoutEditor(
                preferences = preferences,
                onPreferencesChanged = onPreferencesChanged,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun rememberMetricLabels(): Map<TelemetryMetricId, String> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        TelemetryMetricCatalog.descriptors.associate { it.id to telemetryMetricLabel(context, it.id) }
    }
}

@Composable
private fun rememberSectionLabels(): Map<TelemetrySection, String> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        TelemetrySection.entries.associateWith { telemetrySectionLabel(context, it) }
    }
}

/** Metrics: the search pill, then the sections folded into one card, or the search results by section. */
@Composable
private fun ChainMetricsChooser(
    preferences: ChainDashboardPreferences,
    onPreferencesChanged: (ChainDashboardPreferences) -> Unit,
    onResetRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable(stateSaver = SectionSetSaver) { mutableStateOf(emptySet<TelemetrySection>()) }
    val labels = rememberMetricLabels()
    val sectionLabels = rememberSectionLabels()
    val selected = preferences.selectedMetricIds.toSet()
    val pinned = preferences.pinnedMetricIds.toSet()
    // Hides the keyboard and leaves focus in the field: clearing focus moved it to the sheet's
    // drag handle, which shows its tooltip (Android 9).
    val keyboard = LocalSoftwareKeyboardController.current
    val searchLabel = stringResource(R.string.chain_customize_search)
    val metricRow: @Composable (TelemetryMetricId, CardPart, RowRule) -> Unit = { id, part, rule ->
        val shown = id in selected
        ChainCustomizerMetricRow(
            metricId = id,
            label = highlighted(labels.getValue(id), query),
            shown = shown,
            // The dashboard is never empty: the last shown metric keeps its box.
            enabled = !shown || selected.size > 1,
            pinned = id in pinned,
            part = part,
            rule = rule,
            onShownChange = { onPreferencesChanged(preferences.withMetricShown(id, it)) },
        )
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("vesqen.chain.customizer.metrics"),
        contentPadding = PaddingValues(
            start = VesqenSpacing.lg,
            top = VesqenSpacing.md,
            end = VesqenSpacing.lg,
            bottom = VesqenSpacing.lg,
        ),
    ) {
        item(key = "search") {
            SearchPill(
                query = query,
                onQueryChange = { query = it },
                label = searchLabel,
                modifier = Modifier.padding(bottom = VesqenSpacing.md).testTag("vesqen.chain.customizer.search"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
        }
        if (query.isBlank()) {
            CatalogSections.forEachIndexed { index, (section, ids) ->
                val open = section in expanded
                val lastSection = index == CatalogSections.lastIndex
                item(key = "section:${section.name}") {
                    ChainCustomizerSectionRow(
                        section = section,
                        label = sectionLabels.getValue(section),
                        shown = ids.count(selected::contains),
                        total = ids.size,
                        expanded = open,
                        part = cardPart(first = index == 0, last = lastSection && !open),
                        rule = if (lastSection && !open) RowRule.None else RowRule.Inset,
                        onToggle = { expanded = if (open) expanded - section else expanded + section },
                    )
                }
                if (open) {
                    itemsIndexed(ids, key = { _, id -> "metric:${id.value}" }) { rowIndex, id ->
                        val lastRow = rowIndex == ids.lastIndex
                        metricRow(
                            id,
                            cardPart(first = false, last = lastRow && lastSection),
                            when {
                                lastRow && lastSection -> RowRule.None
                                // The next section starts a new block inside the same card.
                                lastRow -> RowRule.Full
                                else -> RowRule.Inset
                            },
                        )
                    }
                }
            }
            item(key = "reset") {
                TextButton(
                    onClick = onResetRequest,
                    modifier = Modifier
                        .padding(top = VesqenSpacing.sm)
                        .heightIn(min = 48.dp)
                        .testTag("vesqen.chain.reset"),
                ) {
                    Text(stringResource(R.string.chain_reset_dashboard), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        } else {
            val results = CatalogSections.mapNotNull { (section, ids) ->
                ids.filter { chainMetricMatches(query, labels.getValue(it), sectionLabels.getValue(section)) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { section to it }
            }
            if (results.isEmpty()) {
                item(key = "no-results") {
                    Text(
                        text = stringResource(R.string.chain_customize_no_results, query.trim()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = VesqenSpacing.sm).testTag("vesqen.chain.customizer.no-results"),
                    )
                }
            }
            results.forEachIndexed { index, (section, ids) ->
                val lastSection = index == results.lastIndex
                item(key = "result-section:${section.name}") {
                    ChainCustomizerEyebrow(sectionLabels.getValue(section), cardPart(first = index == 0, last = false))
                }
                itemsIndexed(ids, key = { _, id -> "result:${id.value}" }) { rowIndex, id ->
                    val lastRow = rowIndex == ids.lastIndex
                    metricRow(
                        id,
                        cardPart(first = false, last = lastRow && lastSection),
                        when {
                            lastRow && lastSection -> RowRule.None
                            lastRow -> RowRule.Full
                            else -> RowRule.Inset
                        },
                    )
                }
            }
        }
    }
}

private fun highlighted(label: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(label)
    val ranges = chainMetricMatchRanges(query, label)
    return buildAnnotatedString {
        append(label)
        ranges.forEach { addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), it.first, it.last + 1) }
    }
}

/** A section of the index: its name, how many of its metrics are shown, and the fold. */
@Composable
private fun ChainCustomizerSectionRow(
    section: TelemetrySection,
    label: String,
    shown: Int,
    total: Int,
    expanded: Boolean,
    part: CardPart,
    rule: RowRule,
    onToggle: () -> Unit,
) {
    val description = stringResource(R.string.chain_customize_section_shown, label, shown, total)
    val state = stringResource(if (expanded) R.string.now_notes_expanded else R.string.now_notes_collapsed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .paperCardPart(part, rule)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = description
                stateDescription = state
            }
            .padding(start = VesqenSpacing.md)
            .testTag("vesqen.chain.customizer.section.${section.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(vertical = VesqenSpacing.sm),
        )
        Text(
            text = "$shown / $total",
            style = VesqenDataStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = VesqenSpacing.xs).testTag("vesqen.chain.customizer.count.${section.name.lowercase()}"),
        )
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The section name above its search results. */
@Composable
private fun ChainCustomizerEyebrow(label: String, part: CardPart) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .paperCardPart(part, RowRule.None)
            .padding(start = VesqenSpacing.md, top = VesqenSpacing.sm, end = VesqenSpacing.md, bottom = VesqenSpacing.xxs)
            .semantics { heading() },
    )
}

/** One metric of the index: the whole row is its checkbox (#35). A pinned metric keeps a small pin. */
@Composable
private fun ChainCustomizerMetricRow(
    metricId: TelemetryMetricId,
    label: AnnotatedString,
    shown: Boolean,
    enabled: Boolean,
    pinned: Boolean,
    part: CardPart,
    rule: RowRule,
    onShownChange: (Boolean) -> Unit,
) {
    // TalkBack skips a marker icon inside a merged row, so the row says "pinned" itself.
    val description = if (pinned) {
        stringResource(R.string.chain_customize_metric_pinned, label.text)
    } else {
        label.text
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .paperCardPart(part, rule)
            .toggleable(value = shown, enabled = enabled, role = Role.Checkbox, onValueChange = onShownChange)
            .semantics { contentDescription = description }
            .padding(start = VesqenSpacing.md)
            .testTag("vesqen.chain.customizer.select.${metricId.value}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(vertical = VesqenSpacing.sm),
        )
        if (pinned) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = null,
                modifier = Modifier.padding(start = VesqenSpacing.xs).size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Checkbox(
                checked = shown,
                onCheckedChange = null,
                enabled = enabled,
                colors = CheckboxDefaults.colors(uncheckedColor = LocalVesqenColors.current.radioIdle),
            )
        }
    }
}

private const val PinnedGroupName = "pinned"
private const val HeadingKeyPrefix = "heading:"
private const val RowKeyPrefix = "row:"

private fun headingKey(section: TelemetrySection?): String = HeadingKeyPrefix + (section?.name ?: PinnedGroupName)

private fun rowKey(id: TelemetryMetricId): String = RowKeyPrefix + id.value

private fun sectionOfHeadingKey(key: String): TelemetrySection? =
    TelemetrySection.entries.firstOrNull { headingKey(it) == key }

/**
 * Layout: the dashboard's groups as it shows them, pinned metrics first, holding only shown
 * metrics. A 48 dp handle at the end of each row and section heading drags it within its group;
 * dragging a heading folds every group to its heading first, so a section moves past one line per
 * section. Only the drop saves; TalkBack moves with Move up and Move down.
 */
@Composable
private fun ChainLayoutEditor(
    preferences: ChainDashboardPreferences,
    onPreferencesChanged: (ChainDashboardPreferences) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = rememberMetricLabels()
    val sectionLabels = rememberSectionLabels()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val motionPolicy = rememberVesqenMotionPolicy()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val edgePx = with(density) { 48.dp.toPx() }
    val speedPx = with(density) { 1000.dp.toPx() }
    val handleAreaPx = with(density) { (48.dp + VesqenSpacing.lg).toPx() }
    val liftPx = with(density) { 6.dp.toPx() }
    val upLabel = stringResource(R.string.move_up)
    val downLabel = stringResource(R.string.move_down)
    val pinnedTitle = stringResource(R.string.chain_pinned_metrics)

    // The order shown during a drag; the drop saves it.
    var preview by remember { mutableStateOf<ChainDashboardPreferences?>(null) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var liftY by remember { mutableFloatStateOf(0f) }
    var foldSpacerPx by remember { mutableIntStateOf(0) }
    var reorderViewport by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var revealAfterDrop by remember { mutableStateOf<String?>(null) }
    val currentPreferences by rememberUpdatedState(preferences)
    val save by rememberUpdatedState(onPreferencesChanged)
    val shown = preview ?: preferences
    val groups = remember(shown) { shown.dashboardGroups() }
    val sectionDragged = dragged?.startsWith(HeadingKeyPrefix) == true

    SideEffect {
        reorderViewport?.let { (index, offset) ->
            // After the new order is composed; keyed rows would otherwise pull the list along.
            listState.requestScrollToItem(index, offset)
            reorderViewport = null
        }
    }
    LaunchedEffect(revealAfterDrop) {
        val key = revealAfterDrop ?: return@LaunchedEffect
        // The groups open again under the dropped section; bring its heading back into view.
        val index = layoutItemKeys(groups).indexOf(key)
        if (index >= 0) listState.scrollToItem(index)
        revealAfterDrop = null
    }
    LaunchedEffect(dragged) {
        val key = dragged ?: return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (dragged == key) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(.05f)
            previousFrame = frame
            val layout = listState.layoutInfo
            // A row lifted beside an edge stays until the finger moves toward that edge.
            val edgeFraction = when {
                dragY < layout.viewportStartOffset + edgePx && dragY < liftY - touchSlop ->
                    -((layout.viewportStartOffset + edgePx - dragY) / edgePx).coerceIn(0f, 1f)
                dragY > layout.viewportEndOffset - edgePx && dragY > liftY + touchSlop ->
                    ((dragY - layout.viewportEndOffset + edgePx) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (edgeFraction != 0f) listState.scrollBy(edgeFraction * speedPx * seconds)
            val order = preview ?: continue
            val visible = listState.layoutInfo.visibleItemsInfo
            val section = sectionOfHeadingKey(key)
            if (section != null) {
                // Until the fold is laid out, the headings are still spread among the rows.
                if (visible.any { (it.key as? String)?.startsWith(RowKeyPrefix) == true }) continue
                val sections = order.dashboardGroups().mapNotNull(ChainDashboardGroup::section)
                val target = visible
                    .filter { sectionOfHeadingKey(it.key as? String ?: "") != null }
                    .minByOrNull { abs(it.offset + it.size / 2f - dragY) }
                    ?.let { sectionOfHeadingKey(it.key as String) } ?: continue
                val targetIndex = sections.indexOf(target)
                if (targetIndex >= 0 && targetIndex != sections.indexOf(section)) {
                    reorderViewport = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    preview = order.withSectionMoved(section, targetIndex)
                }
            } else {
                val group = order.dashboardGroups().firstOrNull { ids -> ids.metricIds.any { rowKey(it) == key } } ?: continue
                val memberKeys = group.metricIds.map(::rowKey)
                val target = visible
                    .filter { it.key in memberKeys }
                    .minByOrNull { abs(it.offset + it.size / 2f - dragY) }
                    ?.key ?: continue
                val from = memberKeys.indexOf(key)
                val to = memberKeys.indexOf(target)
                if (from >= 0 && to >= 0 && from != to) {
                    reorderViewport = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    preview = order.withMetricMoved(group.metricIds[from], to)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .testTag("vesqen.chain.customizer.layout")
            .pointerInput(handleAreaPx, rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val onHandle = if (rtl) down.position.x <= handleAreaPx else down.position.x >= size.width - handleAreaPx
                    if (!onHandle) return@awaitEachGesture
                    val y = down.position.y + listState.layoutInfo.viewportStartOffset
                    val item = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return@awaitEachGesture
                    val key = item.key as? String ?: return@awaitEachGesture
                    val section = sectionOfHeadingKey(key)
                    if (section == null && !key.startsWith(RowKeyPrefix)) return@awaitEachGesture
                    down.consume()
                    var origin = item.offset + item.size / 2f
                    val start = currentPreferences
                    if (section != null) {
                        // Fold to headings with the dragged one still under the finger: the others
                        // close up around it from the top of the list.
                        val position = start.dashboardGroups().mapNotNull(ChainDashboardGroup::section).indexOf(section)
                        val spacer = item.offset - position * item.size
                        foldSpacerPx = spacer.coerceAtLeast(0)
                        if (spacer < 0) origin = position * item.size + item.size / 2f
                        reorderViewport = 0 to 0
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    preview = start
                    dragY = origin
                    liftY = origin
                    dragged = key
                    try {
                        while (true) {
                            // First in line: neither the list's scroll nor the sheet's drag sees it.
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            dragY = origin + change.position.y - down.position.y
                        }
                    } finally {
                        val result = preview
                        dragged = null
                        preview = null
                        if (result != null && result != currentPreferences) save(result)
                        if (section != null) revealAfterDrop = key
                    }
                }
            },
        contentPadding = PaddingValues(
            start = VesqenSpacing.lg,
            top = VesqenSpacing.xs,
            end = VesqenSpacing.lg,
            bottom = VesqenSpacing.lg,
        ),
    ) {
        fun Modifier.lifted(key: String, scope: LazyItemScope): Modifier {
            val isLifted = dragged == key
            return with(scope) {
                this@lifted
                    .zIndex(if (isLifted) 1f else 0f)
                    .animateItem(
                        fadeInSpec = null,
                        fadeOutSpec = null,
                        placementSpec = if (isLifted || motionPolicy.reduceMotion) null else spring(
                            stiffness = Spring.StiffnessMediumLow,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        ),
                    )
                    .graphicsLayer {
                        if (isLifted) {
                            translationY = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                                ?.let { dragY - it.offset - it.size / 2f } ?: 0f
                            shadowElevation = liftPx
                            shape = RoundedCornerShape(VesqenRadii.surface)
                        }
                    }
            }
        }
        if (sectionDragged) {
            item(key = "fold-spacer") { Spacer(Modifier.height(with(density) { foldSpacerPx.toDp() })) }
        }
        val sections = groups.mapNotNull(ChainDashboardGroup::section)
        groups.forEach { group ->
            val section = group.section
            if (sectionDragged && section == null) return@forEach
            val key = headingKey(section)
            item(key = key) {
                val position = sections.indexOf(section)
                ChainLayoutHeading(
                    title = section?.let(sectionLabels::getValue) ?: pinnedTitle,
                    handleTag = section?.let { "vesqen.chain.customizer.drag.section.${it.name.lowercase()}" },
                    lifted = dragged == key,
                    moves = if (section == null || preview != null) emptyList() else buildList {
                        if (position > 0) {
                            add(CustomAccessibilityAction(upLabel) { onPreferencesChanged(preferences.withSectionMoved(section, position - 1)); true })
                        }
                        if (position < sections.lastIndex) {
                            add(CustomAccessibilityAction(downLabel) { onPreferencesChanged(preferences.withSectionMoved(section, position + 1)); true })
                        }
                    },
                    modifier = Modifier
                        .lifted(key, this)
                        .testTag("vesqen.chain.customizer.layout.heading.${(section?.name ?: PinnedGroupName).lowercase()}"),
                )
            }
            if (sectionDragged) return@forEach
            itemsIndexed(group.metricIds, key = { _, id -> rowKey(id) }) { index, id ->
                val rowKey = rowKey(id)
                val isLifted = dragged == rowKey
                val last = index == group.metricIds.lastIndex
                ChainLayoutRow(
                    metricId = id,
                    label = labels.getValue(id),
                    pinned = section == null,
                    part = if (isLifted) CardPart.Single else cardPart(first = index == 0, last = last),
                    rule = if (last || isLifted) RowRule.None else RowRule.Inset,
                    moves = if (preview != null) emptyList() else buildList {
                        if (index > 0) {
                            add(CustomAccessibilityAction(upLabel) { onPreferencesChanged(preferences.withMetricMoved(id, index - 1)); true })
                        }
                        if (!last) {
                            add(CustomAccessibilityAction(downLabel) { onPreferencesChanged(preferences.withMetricMoved(id, index + 1)); true })
                        }
                    },
                    onPinnedChange = { onPreferencesChanged(preferences.withMetricPinned(id, it)) },
                    modifier = Modifier.lifted(rowKey, this),
                )
            }
            item(key = "gap:${section?.name ?: PinnedGroupName}") { Spacer(Modifier.height(VesqenSpacing.xs)) }
        }
    }
}

/** The keys [ChainLayoutEditor] gives its items when no section is being dragged, in order. */
private fun layoutItemKeys(groups: List<ChainDashboardGroup>): List<String> = groups.flatMap { group ->
    listOf(headingKey(group.section)) + group.metricIds.map(::rowKey) + "gap:${group.section?.name ?: PinnedGroupName}"
}

/** A group's heading, drawn like the dashboard's own: the name, then a hairline to the handle. */
@Composable
private fun ChainLayoutHeading(
    title: String,
    /** The handle's test tag; the pinned group has no handle, as it always comes first. */
    handleTag: String?,
    lifted: Boolean,
    moves: List<CustomAccessibilityAction>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (lifted) {
                    Modifier
                        .clip(RoundedCornerShape(VesqenRadii.surface))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(start = VesqenSpacing.md)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {
                heading()
                customActions = moves
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        HorizontalDivider(Modifier.weight(1f), color = LocalVesqenColors.current.hairline)
        if (handleTag != null) DragHandle(handleTag)
    }
}

/** A shown metric: its name, the pin, and the handle. */
@Composable
private fun ChainLayoutRow(
    metricId: TelemetryMetricId,
    label: String,
    pinned: Boolean,
    part: CardPart,
    rule: RowRule,
    moves: List<CustomAccessibilityAction>,
    onPinnedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .paperCardPart(part, rule)
            .padding(start = VesqenSpacing.md)
            .testTag("vesqen.chain.customizer.layout.row.${metricId.value}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = VesqenSpacing.sm)
                .semantics(mergeDescendants = true) { customActions = moves }
                .testTag("vesqen.chain.customizer.layout.label.${metricId.value}"),
        )
        IconToggleButton(
            checked = pinned,
            onCheckedChange = onPinnedChange,
            modifier = Modifier.size(48.dp).testTag("vesqen.chain.customizer.pin.${metricId.value}"),
        ) {
            // An unpinned pin is drawn in ink, not grey, so it never reads as disabled.
            Icon(
                imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = stringResource(R.string.chain_pin),
                tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        DragHandle("vesqen.chain.customizer.drag.row.${metricId.value}")
    }
}

/** The drag affordance; the list's pointer handler owns the gesture, and TalkBack moves with actions. */
@Composable
private fun DragHandle(testTag: String) {
    Box(Modifier.size(48.dp).testTag(testTag), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Where a lazy item sits in a paper card drawn across several items. */
private enum class CardPart { Top, Middle, Bottom, Single }

/** The hairline under an item inside a card. */
private enum class RowRule { None, Inset, Full }

private fun cardPart(first: Boolean, last: Boolean): CardPart = when {
    first && last -> CardPart.Single
    first -> CardPart.Top
    last -> CardPart.Bottom
    else -> CardPart.Middle
}

/**
 * Draws this item's slice of one paper card (B §5: paper-raised, 1 dp hairline border, 16 dp
 * corners) so a lazy list can rule many rows inside a single card, and clips the press state to
 * the slice's corners (the concentric rule).
 */
@Composable
private fun Modifier.paperCardPart(part: CardPart, rule: RowRule): Modifier {
    val fill = MaterialTheme.colorScheme.surface
    val line = LocalVesqenColors.current.hairline
    val radius: Dp = VesqenRadii.surface
    val top = part == CardPart.Top || part == CardPart.Single
    val bottom = part == CardPart.Bottom || part == CardPart.Single
    val shape = RoundedCornerShape(
        topStart = if (top) radius else 0.dp,
        topEnd = if (top) radius else 0.dp,
        bottomEnd = if (bottom) radius else 0.dp,
        bottomStart = if (bottom) radius else 0.dp,
    )
    return clip(shape).drawBehind {
        val stroke = 1.dp.toPx()
        val half = stroke / 2
        val r = radius.toPx()
        drawRect(fill)
        // The card's outline, stretched past the edges this slice continues across, so only its
        // own sides and corners land inside the clip.
        val outlineTop = if (top) half else -r - stroke
        val outlineBottom = if (bottom) size.height - half else size.height + r + stroke
        drawRoundRect(
            color = line,
            topLeft = Offset(half, outlineTop),
            size = Size(size.width - stroke, outlineBottom - outlineTop),
            cornerRadius = CornerRadius(r - half),
            style = Stroke(stroke),
        )
        if (rule != RowRule.None) {
            val inset = if (rule == RowRule.Inset) VesqenSpacing.md.toPx() else 0f
            val y = size.height - half
            val (startX, endX) = if (layoutDirection == LayoutDirection.Rtl) 0f to size.width - inset else inset to size.width
            drawLine(line, Offset(startX, y), Offset(endX, y), stroke)
        }
    }
}
