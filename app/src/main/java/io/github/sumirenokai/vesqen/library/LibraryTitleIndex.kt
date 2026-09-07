package io.github.sumirenokai.vesqen.library

import android.icu.text.AlphabeticIndex
import java.util.Locale

/** Only identity and title affect title collation, not listening history or favorite membership. */
internal fun libraryTitleKeys(tracks: List<AudioTrack>): List<Pair<Long, String>> =
    tracks.map { it.id to it.title }

/** Keep the last order during a rebuild, but never show stale metadata or removed tracks. */
internal fun projectLibraryTitleOrder(order: List<AudioTrack>, current: List<AudioTrack>): List<AudioTrack> {
    val remaining = current.associateByTo(LinkedHashMap()) { it.id }
    return buildList {
        order.forEach { previous -> remaining.remove(previous.id)?.let(::add) }
        addAll(remaining.values)
    }
}

/** Built off the UI thread. ICU supplies matching Chinese pinyin buckets and collation. */
internal data class LibraryTitleIndex(
    val tracks: List<AudioTrack>,
    val sections: Map<String, Int>,
) {
    companion object {
        fun build(tracks: List<AudioTrack>): LibraryTitleIndex {
            val alphabet = AlphabeticIndex<Any>(Locale.SIMPLIFIED_CHINESE).addLabels(Locale.ENGLISH)
            val buckets = alphabet.buildImmutableIndex()
            val collator = alphabet.collator
            collator.strength = android.icu.text.Collator.SECONDARY
            val entries = tracks.map { track ->
                val title = track.title.trim()
                val label = buckets.getBucket(buckets.getBucketIndex(title))?.label.orEmpty()
                val group = label.takeIf {
                    title.firstOrNull()?.isLetter() == true && it.length == 1 && it[0] in 'A'..'Z'
                } ?: "#"
                Triple(track, group, collator.getCollationKey(title))
            }.sortedWith(
                compareBy<Triple<AudioTrack, String, android.icu.text.CollationKey>> {
                    if (it.second == "#") "[" else it.second
                }.thenBy { it.third }.thenBy { it.first.id },
            )
            val sections = linkedMapOf<String, Int>()
            entries.forEachIndexed { index, entry -> sections.putIfAbsent(entry.second, index) }
            return LibraryTitleIndex(entries.map { it.first }, sections)
        }
    }
}

/** Reorder only the supplied members; unavailable or concurrently added members keep their slots. */
internal fun mergeLibraryOrder(current: List<Long>, requested: List<Long>): List<Long> {
    require(requested.distinct().size == requested.size) { "Duplicate library order member" }
    val membership = current.toHashSet()
    val retained = requested.filter { it in membership }
    val selected = retained.toHashSet()
    val replacement = retained.iterator()
    return current.map { if (it in selected) replacement.next() else it }
}
