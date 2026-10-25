package io.github.sumirenokai.vesqen.library

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

/** Built off the UI thread, in the [LibraryNameOrder] that the other Library lists share. */
internal data class LibraryTitleIndex(
    val tracks: List<AudioTrack>,
    val sections: Map<String, Int>,
) {
    companion object {
        fun build(tracks: List<AudioTrack>): LibraryTitleIndex {
            val names = LibraryNameOrder()
            val ordered = tracks.sortedWith(compareBy<AudioTrack, String>(names) { it.title }.thenBy { it.id })
            val sections = linkedMapOf<String, Int>()
            ordered.forEachIndexed { index, track -> sections.putIfAbsent(names.group(track.title), index) }
            return LibraryTitleIndex(ordered, sections)
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
