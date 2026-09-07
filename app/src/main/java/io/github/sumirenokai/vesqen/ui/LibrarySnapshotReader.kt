package io.github.sumirenokai.vesqen.ui

import io.github.sumirenokai.vesqen.library.LibraryCatalogSnapshot

/** Main-thread coordinator; a suspended read cannot undo a newer read or permission change. */
internal class LibrarySnapshotReader {
    private var generation = 0L

    fun invalidate() {
        generation++
    }

    suspend fun read(
        load: suspend () -> LibraryCatalogSnapshot,
        publish: (LibraryCatalogSnapshot) -> Unit,
    ) {
        val requestGeneration = ++generation
        val snapshot = load()
        if (generation == requestGeneration) publish(snapshot)
    }
}
