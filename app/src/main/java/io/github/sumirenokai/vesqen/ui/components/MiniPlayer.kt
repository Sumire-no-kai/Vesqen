package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.sp
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot

internal val MiniPlayerHeight = 64.dp

// On wide windows the capsule stops growing and stays centred instead of stretching edge to edge.
internal val MiniPlayerMaxWidth = 560.dp

@Composable
fun MiniPlayer(
    snapshot: PlaybackSnapshot,
    currentTrack: AudioTrack?,
    onOpenNow: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = snapshot.title.ifBlank { stringResource(R.string.unknown_title) }
    val artist = snapshot.artist.ifBlank { stringResource(R.string.unknown_artist) }
    // B · Paper & Sound: a paper-raised capsule with an ink hairline edge and a soft warm shadow. It
    // keeps the PRD F3 content (artist line, previous / play / next); the output claim stays in Now's
    // liner notes and Chain.
    // Every curve inside shares a centre with the capsule ends (32 dp in from either end): the round
    // cover and the open-Now pill on the left, the next button on the right.
    val colors = LocalVesqenColors.current
    val shape = CircleShape
    // #35: above 150 % text the two lines no longer fit the 56 dp content height. The title stays
    // and the artist is still read out with it.
    val titleOnly = LocalDensity.current.fontScale > 1.5f
    val openNowLabel = stringResource(R.string.open_now_playing)
    Surface(
        modifier = modifier
            .widthIn(max = MiniPlayerMaxWidth)
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .shadow(6.dp, shape, ambientColor = colors.shadow, spotColor = colors.shadow)
            .testTag("vesqen.mini-player"),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, colors.hairline),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val openNowInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .testTag("vesqen.mini-player.open-now")
                    .clickable(
                        interactionSource = openNowInteraction,
                        indication = null,
                        onClickLabel = openNowLabel,
                        onClick = onOpenNow,
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                // The press state is a pill behind the cover and the text. Clipping the content to the
                // pill instead cut the ends of two lines at large text sizes (#143 Q12), and the inset
                // that kept the title clear of the curve cost 8 dp of title width (#143 Q13).
                Box(Modifier.matchParentSize().clip(CircleShape).indication(openNowInteraction, ripple()))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArtwork(
                        track = currentTrack,
                        targetSize = 44.dp,
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 21.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // On the title itself, so TalkBack reads "title, artist" once instead of the title twice.
                            modifier = if (titleOnly) Modifier.semantics { contentDescription = "$title, $artist" } else Modifier,
                        )
                        if (!titleOnly) Text(
                            text = artist,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            MiniPlayerControl(
                modifier = Modifier.testTag("vesqen.mini-player.previous"),
                onClick = onPrevious,
                enabled = snapshot.isControllerReady && snapshot.canSkipPrevious,
                colors = IconButtonDefaults.iconButtonColors(),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.previous),
                    modifier = Modifier.size(20.dp),
                )
            }
            MiniPlayerControl(
                modifier = Modifier.testTag("vesqen.mini-player.play-pause"),
                onClick = onPlayPause,
                enabled = snapshot.isControllerReady,
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(
                    imageVector = if (snapshot.showsPauseAction) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (snapshot.showsPauseAction) R.string.pause else R.string.play),
                    modifier = Modifier.size(22.dp),
                )
            }
            MiniPlayerControl(
                modifier = Modifier.testTag("vesqen.mini-player.next"),
                onClick = onNext,
                enabled = snapshot.isControllerReady && snapshot.canSkipNext,
                colors = IconButtonDefaults.iconButtonColors(),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.next),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * A 40 dp circle inside a 48 dp clickable (#143 Q20). Material's IconButton clicks and reports its
 * accessibility bounds at the 40 dp it draws and leaves the rest of the target to the touch layer's
 * expansion, so TalkBack, accessibility checks and some input paths saw a 40 dp button.
 */
@Composable
private fun MiniPlayerControl(
    onClick: () -> Unit,
    enabled: Boolean,
    colors: IconButtonColors,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (enabled) colors.containerColor else colors.disabledContainerColor)
                // From the centre, so the ripple sits in the circle wherever the 48 dp target is pressed.
                .indication(interactionSource, ripple(bounded = false, radius = 20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor,
                content = content,
            )
        }
    }
}
