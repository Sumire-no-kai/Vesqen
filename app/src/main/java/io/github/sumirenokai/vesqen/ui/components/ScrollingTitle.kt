package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy

/**
 * A track title on one line, in Now and the mini player alike: one that does not fit scrolls slowly
 * (#143 Q13). Marquee ignores the system animation scale, so with animations off the title stays
 * still and ends in an ellipsis (#165).
 */
@Composable
fun ScrollingTitle(
    text: String,
    style: TextStyle,
    motionPolicy: VesqenMotionPolicy,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = if (motionPolicy.reduceMotion) TextOverflow.Ellipsis else TextOverflow.Clip,
        textAlign = textAlign,
        modifier = if (motionPolicy.reduceMotion) modifier else modifier.basicMarquee(),
    )
}
