package io.github.sumirenokai.vesqen.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteJsonTest {
    @Test
    fun theServiceAnswersPass() {
        assertTrue(jsonNestedWithin("""{ "schemaVersion": 1, "usagePings": "enabled", "reportUploads": "enabled" }"""))
        assertTrue(
            jsonNestedWithin(
                """{"updateManifests":{"stable":{"schemaVersion":1,"release":{"apkUrls":["https://example.org/a.apk"],""" +
                    """"releaseNotes":{"en":"Fixes [a] {b} \"quoted\" \\ and # / 'x'"}}}}}""",
            ),
        )
    }

    @Test
    fun nestingPastTheLimitIsRefused() {
        assertTrue(jsonNestedWithin("[".repeat(16) + "]".repeat(16)))
        assertFalse(jsonNestedWithin("[".repeat(17) + "]".repeat(17)))
        // #194 S1: the size limits let a body nest tens of thousands deep.
        assertFalse(jsonNestedWithin("[".repeat(65_000)))
    }

    @Test
    fun bracketsInStringsDoNotCountButCannotHideNesting() {
        assertTrue(jsonNestedWithin("""{"a":"${"[".repeat(100)}"}"""))
        // An escaped quote does not end the string, so the brackets after it stay inside.
        assertTrue(jsonNestedWithin("""{"a":"\"${"[".repeat(100)}"}"""))
        // org.json reads single-quoted strings and comments, whose quotes could hide brackets from the count.
        assertFalse(jsonNestedWithin("""['"', ${"[".repeat(100)}"""))
        assertFalse(jsonNestedWithin("""[/* " */ ${"[".repeat(100)}"""))
        assertFalse(jsonNestedWithin("""[# "
${"[".repeat(100)}"""))
    }
}
