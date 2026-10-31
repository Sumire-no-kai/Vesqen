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
        // #194 round 3 B3: org.json reads an unquoted string up to a bracket or comma, quote included,
        // so a quote inside one starts no string and the brackets after it nest.
        assertFalse(jsonNestedWithin("""[0", ${"[".repeat(100)}"""))
        assertFalse(jsonNestedWithin("""{a":${"[".repeat(100)}"""))
        // A quote after a value without a comma is refused too; org.json would stop there anyway.
        assertFalse(jsonNestedWithin("""{"a":1 "b":2}"""))
    }

    @Test
    fun whitespaceBeforeAQuoteKeepsTheStringStart() {
        assertTrue(jsonNestedWithin("{\n  \"a\" :\t\"b\",\r\n  \"c\": [ \"d\" ]\n}"))
    }
}
