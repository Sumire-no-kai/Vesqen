package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp

/**
 * Starts a sheet's content 14 dp higher, over the lower padding of Material's drag handle. The handle
 * keeps its 48 dp touch target, 22 dp above and below the bar, but that padding left about 48 dp
 * between the bar and a sheet's heading, more than the space under the heading, so the heading looked
 * detached from its sheet (owner, 2026-11-28). Sheet headings keep the middle, under the bar, free of
 * controls, so the overlap covers no control of either. Apply it first, to the sheet's whole content.
 */
internal fun Modifier.underSheetHandle(): Modifier = layout { measurable, constraints ->
    val overlap = HandleOverlap.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - overlap).coerceAtLeast(0)) {
        placeable.place(0, -overlap)
    }
}

private val HandleOverlap = 14.dp
