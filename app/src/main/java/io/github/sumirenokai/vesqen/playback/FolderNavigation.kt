package io.github.sumirenokai.vesqen.playback

internal data class FolderNavigation(val previous: Int? = null, val next: Int? = null)

/** Queue rows already follow the real playback order, including shuffle and duplicate entries. */
internal fun folderNavigation(queue: List<PlaybackQueueItem>, repeat: PlaybackRepeatMode): FolderNavigation {
    val current = queue.indexOfFirst(PlaybackQueueItem::isCurrent)
    val currentFolder = queue.getOrNull(current)?.folderKey ?: return FolderNavigation()
    val starts = queue.indices.filter { i -> i == 0 || queue[i].folderKey != queue[i - 1].folderKey }
    val group = starts.indexOfLast { it <= current }
    val wrap = repeat == PlaybackRepeatMode.ALL

    fun target(step: Int): Int? {
        for (distance in 1 until starts.size) {
            val candidate = group + step * distance
            if (!wrap && candidate !in starts.indices) return null
            val first = starts[Math.floorMod(candidate, starts.size)]
            val key = queue[first].folderKey
            if (key != null && key != currentFolder) return queue[first].listIndex
        }
        return null
    }
    return FolderNavigation(previous = target(-1), next = target(1))
}
