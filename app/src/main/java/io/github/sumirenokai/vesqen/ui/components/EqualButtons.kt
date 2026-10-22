package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

/**
 * Equal-width buttons side by side while every label fits on one line. Otherwise each button takes
 * a full line, so large text or a narrow window never breaks a label mid-word (#118, #35).
 */
@Composable
internal fun EqualButtons(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { buttons, constraints ->
        val gap = VesqenSpacing.sm.roundToPx()
        val width = constraints.maxWidth
        val share = (width - gap * (buttons.size - 1)) / buttons.size
        val sideBySide = buttons.all { it.maxIntrinsicWidth(constraints.maxHeight) <= share }
        val placeables = buttons.map { it.measure(Constraints.fixedWidth(if (sideBySide) share else width)) }
        val height = if (sideBySide) placeables.maxOf { it.height }
            else placeables.sumOf { it.height } + gap * (placeables.size - 1)
        layout(width, height) {
            var x = 0
            var y = 0
            placeables.forEach { button ->
                button.placeRelative(x, y)
                if (sideBySide) x += share + gap else y += button.height + gap
            }
        }
    }
}
