package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import kotlin.math.roundToInt

@Composable
internal fun LibraryAlphabetIndex(sections: Map<String, Int>, listState: LazyListState, modifier: Modifier = Modifier) {
    val labels = remember { ('A'..'Z').map(Char::toString) + "#" }
    val scope = rememberCoroutineScope()
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    val jump by rememberUpdatedState<(String) -> Unit>({ label ->
        sections[label]?.let { index ->
            if (selected != label) {
                selected = label
                jumpJob?.cancel()
                jumpJob = scope.launch { listState.scrollToItem(index) }
            }
        }
    })
    LaunchedEffect(selected) { if (selected != null) { delay(700); selected = null } }
    val density = LocalDensity.current
    val accessibility = LocalContext.current.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    var touchExploration by remember(accessibility) { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibility) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    val jumpLabel = stringResource(R.string.jump_to_letter)
    val activeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val inactiveColor = activeColor.copy(alpha = .25f)
    val selectedColor = MaterialTheme.colorScheme.primary
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER } }
    var touchY by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        if (maxHeight < 420.dp || density.fontScale > 1.2f || touchExploration) {
            // A 48 dp button leaves no room for the default 12 dp side padding around "A-Z".
            TextButton(
                onClick = { showPicker = true },
                modifier = Modifier.size(48.dp).semantics { contentDescription = jumpLabel },
                contentPadding = PaddingValues(0.dp),
            ) { Text("A-Z", maxLines = 1, softWrap = false) }
        } else {
            // #35: a slim strip along the edge, like the system contacts index. Drags are
            // forgiving, so the letters do not need a wide column taken from every row.
            Canvas(Modifier.width(IndexWidth).fillMaxHeight().testTag("vesqen.library.alphabet")
                .pointerInput(sections) {
                    detectTapGestures { position ->
                        touchY = position.y
                        jump(labels[(position.y / size.height * labels.size).toInt().coerceIn(labels.indices)])
                    }
                }
                .pointerInput(sections) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        touchY = change.position.y
                        jump(labels[(change.position.y / size.height * labels.size).toInt().coerceIn(labels.indices)])
                    }
                }) {
                paint.textSize = with(density) { 11.dp.toPx() }
                val rowHeight = size.height / labels.size
                labels.forEachIndexed { index, label ->
                    paint.color = when {
                        label == selected -> selectedColor
                        label in sections -> activeColor
                        else -> inactiveColor
                    }.toArgb()
                    drawContext.canvas.nativeCanvas.drawText(label, size.width / 2f, rowHeight * (index + .5f) - (paint.ascent() + paint.descent()) / 2f, paint)
                }
            }
            selected?.let { letter -> LetterBubble(letter, touchY, constraints.maxHeight) }
        }
    }
    if (showPicker) {
        AlertDialog(onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.jump_to_letter)) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(labels.size) { index ->
                        val label = labels[index]
                        TextButton(onClick = { jump(label); showPicker = false }, enabled = label in sections, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private val IndexWidth = 24.dp
private val BubbleSize = 56.dp

/**
 * The selected letter beside the finger, over the list. It takes no layout space, so the strip
 * stays [IndexWidth] wide while it shows.
 */
@Composable
private fun LetterBubble(letter: String, touchY: Float, maxHeightPx: Int) {
    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val bubble = measurable.measure(Constraints.fixed(BubbleSize.roundToPx(), BubbleSize.roundToPx()))
                layout(0, 0) {
                    val top = (touchY - bubble.height / 2f).roundToInt().coerceIn(0, (maxHeightPx - bubble.height).coerceAtLeast(0))
                    bubble.place(-(bubble.width + VesqenSpacing.sm.roundToPx() + IndexWidth.roundToPx() / 2), top - maxHeightPx / 2)
                }
            }
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .border(1.dp, LocalVesqenColors.current.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.headlineMedium)
    }
}
