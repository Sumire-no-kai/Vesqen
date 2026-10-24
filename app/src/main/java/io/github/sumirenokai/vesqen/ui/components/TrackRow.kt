package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack

@Composable
fun TrackRow(
    track: AudioTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
    trailingContent: (@Composable () -> Unit)? = null,
    moreIcon: Painter = rememberVectorPainter(Icons.Filled.MoreVert),
    artworkFallback: Painter = rememberTwinPathsPainter(),
) {
    val title = track.title.ifBlank { stringResource(R.string.unknown_title) }
    val subtitle = androidx.compose.runtime.remember(track.artist, track.album) { track.displaySubtitle() }
        .ifBlank { stringResource(R.string.unknown_artist) }
    val playbackState = if (!isCurrent) null else stringResource(if (isPlaying) R.string.playing else R.string.paused)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag("vesqen.library.track.${track.id}")
            // TalkBack reads the merged title and subtitle; a row description on top would repeat them.
            // TalkBack skipped the play marker's description, so the current row states it itself (#35).
            // A disabled clickable (order editing) would make TalkBack announce the row as disabled
            // and ignore its move actions; the row stays one merged node instead.
            .then(if (enabled) Modifier.clickable(onClick = onPlay) else Modifier.semantics(mergeDescendants = true) {})
            .semantics {
                playbackState?.let { stateDescription = it }
                if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtwork(track = track, targetSize = 48.dp, modifier = Modifier.size(48.dp), fallbackPainter = artworkFallback)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        if (trailingContent != null) trailingContent() else RowIconButton(
            onClick = onMore,
            modifier = Modifier.size(48.dp).testTag("vesqen.library.track.${track.id}.more"),
        ) {
            Icon(
                painter = moreIcon,
                contentDescription = stringResource(R.string.more_track_actions, title),
            )
        }
    }
}

/**
 * Material 3's IconButton as these rows use it: the same size, circle clip, bounded ripple and
 * button role, without its transparent container and its tooltip semantics. IconButton remembers
 * its own interaction source, so `clickable` built the ripple and an interaction collector for
 * every row as it scrolled in; without one, `clickable` builds them on the first press, hover or
 * focus. With the shared fallback cover, that took slow library frames on the Honor from about 4 %
 * to 2 % (#50).
 */
@Composable
private fun RowIconButton(onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(CircleShape)
            .clickable(interactionSource = null, indication = ripple(), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
