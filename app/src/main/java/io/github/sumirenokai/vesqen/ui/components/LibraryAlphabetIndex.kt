package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER } }
    BoxWithConstraints(modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
        if (maxHeight < 420.dp || density.fontScale > 1.2f || touchExploration) {
            TextButton(onClick = { showPicker = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = jumpLabel }) { Text("A-Z") }
        } else {
            Canvas(Modifier.fillMaxSize().testTag("vesqen.library.alphabet")
                .pointerInput(sections) { detectTapGestures { position -> jump(labels[(position.y / size.height * labels.size).toInt().coerceIn(labels.indices)]) } }
                .pointerInput(sections) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        jump(labels[(change.position.y / size.height * labels.size).toInt().coerceIn(labels.indices)])
                    }
                }) {
                paint.textSize = with(density) { 12.dp.toPx() }
                val rowHeight = size.height / labels.size
                labels.forEachIndexed { index, label ->
                    paint.color = (if (label in sections) activeColor else inactiveColor).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(label, size.width / 2f, rowHeight * (index + .5f) - (paint.ascent() + paint.descent()) / 2f, paint)
                }
            }
        }
        selected?.let { Text(it, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.TopCenter)) }
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
