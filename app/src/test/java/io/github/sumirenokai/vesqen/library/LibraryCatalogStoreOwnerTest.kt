package io.github.sumirenokai.vesqen.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #165: the application owns the only helper for the library database and keeps it open for the
 * process. A second helper would open another connection to the same file, which the next schema
 * change must not run under; a closed shared helper fails the other users' work in progress.
 *
 * CI runs no device tests and the project has no JVM SQLite, so these checks read the production
 * sources. `SharedLibraryCatalogStoreTest` checks the behavior on a device.
 */
class LibraryCatalogStoreOwnerTest {
    private val sources: Map<String, String> = PRODUCTION_SOURCE_SETS.map { File("src/$it") }
        .filter(File::isDirectory)
        .flatMap { sourceSet -> sourceSet.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList() }
        .associate { it.invariantSeparatorsPath to it.readText() }

    @Test fun `only the application constructs the library database helper`() {
        assertEquals(listOf(APPLICATION), sources.filterValues(CONSTRUCTION::containsMatchIn).keys.toList())
    }

    @Test fun `no production code closes the shared library database helper`() {
        val users = sources.filterValues { it.contains("LibraryCatalogStore") || it.contains("libraryCatalogStore") }
        assertTrue(users.keys.toString(), users.keys.containsAll(listOf(APPLICATION, CATALOG, HISTORY_RECORDER)))
        assertEquals(emptyList<String>(), users.filterValues(CLOSE::containsMatchIn).keys.toList())
    }

    private companion object {
        const val APPLICATION = "src/main/java/io/github/sumirenokai/vesqen/VesqenApplication.kt"
        const val CATALOG = "src/main/java/io/github/sumirenokai/vesqen/library/LibraryCatalog.kt"
        const val HISTORY_RECORDER = "src/main/java/io/github/sumirenokai/vesqen/playback/PlaybackHistoryRecorder.kt"
        val PRODUCTION_SOURCE_SETS = listOf("main", "github", "play", "debug", "profile")

        /** A call or a constructor reference; the class declaration itself is not one. */
        val CONSTRUCTION = Regex("""(?<!class )\bLibraryCatalogStore\(|::LibraryCatalogStore\b""")

        /** The catalog and the history recorder name it `store`; the application `libraryCatalogStore`. */
        val CLOSE = Regex("""\b(?:store|libraryCatalogStore)\??(?:\.close\(|::close\b|\.use\s*[({])""")
    }
}
