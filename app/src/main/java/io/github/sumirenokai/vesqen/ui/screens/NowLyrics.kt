package io.github.sumirenokai.vesqen.ui.screens

import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.lyrics.ImportedLyrics
import io.github.sumirenokai.vesqen.lyrics.LyricLine
import io.github.sumirenokai.vesqen.lyrics.Lyrics
import io.github.sumirenokai.vesqen.lyrics.LyricsTrackKey
import io.github.sumirenokai.vesqen.lyrics.TrackLyrics
import io.github.sumirenokai.vesqen.lyrics.lineIndexAt
import io.github.sumirenokai.vesqen.lyrics.lyricsKey
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** What Now and the track details need to show lyrics (#169). */
data class NowLyricsState(
    /** Lyrics instead of the cover; kept across tracks and restarts (owner decision 3). */
    val shown: Boolean = false,
    val imported: ImportedLyrics = ImportedLyrics(),
)

/**
 * A track's lyrics, read when first asked for (owner decision 12). Null while they load. The key
 * leaves out play counts and favorites, which rewrite the track on every play (#51); a new import
 * or removal changes the revision and reads them again.
 */
@Composable
internal fun rememberTrackLyrics(
    track: AudioTrack?,
    imported: ImportedLyrics,
    loadLyrics: suspend (AudioTrack) -> TrackLyrics,
): TrackLyrics? {
    val key = track?.lyricsKey(imported.revision)
    val latestTrack by rememberUpdatedState(track)
    val loaded by produceState<Pair<LyricsTrackKey, TrackLyrics>?>(initialValue = null, key) {
        val target = latestTrack ?: return@produceState
        value = target.lyricsKey(imported.revision) to loadLyrics(target)
    }
    // While a new import loads, the same track keeps its previous lyrics instead of going blank.
    val (loadedKey, lyrics) = loaded ?: return null
    return lyrics.takeIf { key != null && loadedKey.trackId == key.trackId && loadedKey.contentUri == key.contentUri }
}

/**
 * Takes the cover's place on Now. Only this region scrolls; the page itself never does. Reads only
 * the lyrics and the playback position, so a track change elsewhere recomposes nothing here (#51).
 */
@Composable
internal fun NowLyricsPane(
    lyrics: TrackLyrics?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    canSeek: Boolean,
    motionPolicy: VesqenMotionPolicy,
    onSeek: (Long) -> Unit,
    onImportLyrics: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.testTag("vesqen.now.lyrics")) {
        when (lyrics) {
            // A quiet pane while the file is read; the track's own controls stay usable.
            null -> Unit
            is TrackLyrics.Found -> when (val content = lyrics.lyrics) {
                is Lyrics.Synced -> SyncedLyrics(
                    lyrics = content,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    canSeek = canSeek,
                    motionPolicy = motionPolicy,
                    onSeek = onSeek,
                )
                is Lyrics.Plain -> PlainLyrics(content)
            }
            is TrackLyrics.None -> LyricsMessage(
                title = stringResource(R.string.lyrics_none),
                body = if (lyrics.folderHint) stringResource(R.string.lyrics_folder_hint) else null,
                onImportLyrics = onImportLyrics,
            )
            TrackLyrics.Unreadable -> LyricsMessage(
                title = stringResource(R.string.lyrics_unreadable),
                body = stringResource(R.string.lyrics_unreadable_body),
                onImportLyrics = onImportLyrics,
            )
        }
    }
}

@Composable
private fun SyncedLyrics(
    lyrics: Lyrics.Synced,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    canSeek: Boolean,
    motionPolicy: VesqenMotionPolicy,
    onSeek: (Long) -> Unit,
) {
    val currentIndex = rememberCurrentLyricLine(lyrics, positionMs, isPlaying)
    val listState = rememberLazyListState()
    val touchExploration = rememberTouchExplorationEnabled()
    // Any move of the list that following did not make is the listener's: a drag, a fling, or a
    // scroll by Switch Access, Voice Access or another accessibility service. Each one restarts
    // the pause, so it runs from the end of the scrolling. Following keeps its own state in snapshot
    // state and the check reads it together with the list's position, so a position read while
    // following scrolled is never compared with where following ended.
    var following by remember { mutableStateOf(false) }
    var followedPosition by remember { mutableStateOf(0 to 0) }
    var listenerScrolls by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val position = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            position.takeUnless { following || it == followedPosition }
        }.collect { moved -> if (moved != null) listenerScrolls++ }
    }
    var followPaused by remember { mutableStateOf(false) }
    LaunchedEffect(listenerScrolls) {
        if (listenerScrolls == 0) return@LaunchedEffect
        followPaused = true
        delay(FOLLOW_PAUSE_MILLIS)
        // Following resumes at the current line, not at the next line change.
        followPaused = false
    }
    var positioned by remember(lyrics) { mutableStateOf(false) }
    // TalkBack users move through the lines themselves; following would pull the focus away.
    LaunchedEffect(lyrics, currentIndex, touchExploration, followPaused) {
        if (touchExploration || followPaused) return@LaunchedEffect
        following = true
        try {
            // Item 0 is the leading spacer that lets the first line reach the middle.
            listState.centerItem(currentIndex.coerceAtLeast(0) + 1, animate = positioned && !motionPolicy.reduceMotion)
        } finally {
            followedPosition = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            following = false
        }
        positioned = true
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val edge = maxHeight / 2
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("vesqen.now.lyrics.lines"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "start") { Spacer(Modifier.height(edge)) }
            itemsIndexed(lyrics.lines) { index, line ->
                LyricLineRow(
                    line = line,
                    current = index == currentIndex,
                    canSeek = canSeek,
                    onSeek = { onSeek(line.timeMs.coerceIn(0L, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)) },
                    modifier = Modifier.testTag("vesqen.now.lyrics.line.$index"),
                )
            }
            item(key = "end") { Spacer(Modifier.height(edge)) }
        }
    }
}

/**
 * The current line comes from the last reported position. While playing, the leaf wakes once per
 * line instead of polling, and only while the activity is started: a stopped activity sets no
 * timers (#51). Playback runs at normal speed, so time since the report is added as is. After a
 * stop the old report says nothing about the pause or seek that may have happened meanwhile, so
 * the line waits for the fresh position the poll reports on return.
 */
@Composable
private fun rememberCurrentLyricLine(lyrics: Lyrics.Synced, positionMs: Long, isPlaying: Boolean): Int {
    var index by remember(lyrics) { mutableIntStateOf(lyrics.lineIndexAt(positionMs)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lyrics, positionMs, isPlaying) {
        val anchorPosition = positionMs
        val anchorTime = SystemClock.elapsedRealtime()
        index = lyrics.lineIndexAt(anchorPosition)
        if (!isPlaying) return@LaunchedEffect
        var started = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (started) return@repeatOnLifecycle
            started = true
            while (true) {
                val position = anchorPosition + (SystemClock.elapsedRealtime() - anchorTime)
                index = lyrics.lineIndexAt(position)
                val next = lyrics.lines.getOrNull(index + 1)?.timeMs ?: break
                delay((next - position).coerceAtLeast(1L))
            }
        }
    }
    return index
}

/**
 * Body sans, centred like the title. The current line is set in the body colour and bold, the others
 * in the secondary colour at the same size, so the highlight never relies on colour alone (owner
 * decision 2). Each line with its translations is one TalkBack stop, read in order; nothing is
 * announced when the line changes.
 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    current: Boolean,
    canSeek: Boolean,
    onSeek: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (line.text.isEmpty()) {
        // A pause between lines: room for the highlight to leave the last line, nothing to read.
        Spacer(modifier.height(VesqenSpacing.lg))
        return
    }
    val color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = canSeek, onClickLabel = stringResource(R.string.lyrics_play_from_line), onClick = onSeek)
            .heightIn(min = 48.dp)
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xs),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
            color = color,
            textAlign = TextAlign.Center,
        )
        line.translations.forEach { translation ->
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Text without times: no highlight, no following (PRD). */
@Composable
private fun PlainLyrics(lyrics: Lyrics.Plain) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("vesqen.now.lyrics.lines"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            if (line.isEmpty()) {
                Spacer(Modifier.height(VesqenSpacing.md))
            } else {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xxs)
                        .testTag("vesqen.now.lyrics.line.$index"),
                )
            }
        }
    }
}

@Composable
private fun LyricsMessage(title: String, body: String?, onImportLyrics: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(VesqenSpacing.md)
            .testTag("vesqen.now.lyrics.message"),
        verticalArrangement = Arrangement.spacedBy(VesqenSpacing.sm, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        body?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        onImportLyrics?.let { importLyrics ->
            OutlinedButton(
                onClick = importLyrics,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.now.lyrics.import"),
            ) { Text(stringResource(R.string.lyrics_import)) }
        }
    }
}

private suspend fun LazyListState.centerItem(index: Int, animate: Boolean) {
    // The first line change can come before the list has been measured.
    snapshotFlow { layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    val distance = (item.offset + item.size / 2 - viewportCenter).toFloat()
    if (animate) animateScrollBy(distance) else scrollBy(distance)
}

@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var enabled by remember(accessibility) { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

private const val FOLLOW_PAUSE_MILLIS = 4_000L
