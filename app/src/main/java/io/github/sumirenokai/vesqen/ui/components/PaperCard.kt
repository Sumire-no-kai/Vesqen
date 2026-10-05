package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

/** B §5: one paper-raised card with a hairline border that groups rows (Settings, sheets). */
@Composable
internal fun PaperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalVesqenColors.current.hairline),
    ) {
        Column(content = content)
    }
}

/** The hairline between rows of a [PaperCard], inset like the row text. */
@Composable
internal fun PaperDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = VesqenSpacing.md),
        thickness = 1.dp,
        color = LocalVesqenColors.current.hairline,
    )
}
