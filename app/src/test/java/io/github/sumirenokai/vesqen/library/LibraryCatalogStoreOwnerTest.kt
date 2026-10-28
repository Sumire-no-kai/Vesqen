package io.github.sumirenokai.vesqen.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #165: the app reaches the library database through one helper that the application owns. A
 * second construction would open another connection to the same file, which the next schema
 * change must not run under, and its owner could close it under the others.
 */
class LibraryCatalogStoreOwnerTest {
    @Test fun `only the application constructs the library database helper`() {
        val constructors = PRODUCTION_SOURCE_SETS.map { File("src/$it") }.filter(File::isDirectory)
            .flatMap { sourceSet -> sourceSet.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList() }
            .filter { CONSTRUCTOR_CALL.containsMatchIn(it.readText()) }
            .map(File::invariantSeparatorsPath)

        assertEquals(listOf("src/main/java/io/github/sumirenokai/vesqen/VesqenApplication.kt"), constructors)
    }

    private companion object {
        val PRODUCTION_SOURCE_SETS = listOf("main", "github", "play", "debug", "profile")
        val CONSTRUCTOR_CALL = Regex("""(?<!class )\bLibraryCatalogStore\(""")
    }
}
