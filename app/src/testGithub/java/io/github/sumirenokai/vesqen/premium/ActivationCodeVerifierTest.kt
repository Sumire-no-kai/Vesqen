package io.github.sumirenokai.vesqen.premium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationCodeVerifierTest {
    // Shared with server/usage/test/activation-code.test.js: the owner's tool signed these with a
    // throwaway key (id 7) whose private half was deleted, so both sides read one format.
    private val vectorKey = ActivationCodeVerifier.publicKey(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEyDd+AYOVFuNa5vr80dCh/gblFQu3+LACGNdsXOv9B+xE3z9rihVy6gOG42pMv6+hcdyMaBauDrBqTTSi7DSQiA==",
    )
    private val first = "043G2000000G278PHVTMZGE5NFP674D4NJNW1J79H4S7Q92ZPJCSHAGMZ0H0TJ94XFXH26FEX52MAGGK68GH80V8MSMB3M3EWPKZHGY4T1TF13KB1H84A"
    private val second = "043G2000001027B32H09NH3GWBYK3GK5KB6C8DKVGCA1Z9A1B27H9KTZE5G0CBYSREPV3TJYGN0467N2QBWZQJH54F3PKM56DKZH4TDHNVRJ01V91BD6T"

    private fun verifier(revoked: Set<Pair<Int, Long>> = emptySet()) = ActivationCodeVerifier(mapOf(7 to vectorKey), revoked)

    @Test fun `codes from the owner's tool verify with their serials`() {
        assertEquals(ActivationCodeVerifier.Outcome.Genuine(7, 1, first), verifier().check(first))
        assertEquals(ActivationCodeVerifier.Outcome.Genuine(7, 2, second), verifier().check(second))
    }

    @Test fun `codes copied in groups, lowercase or across lines still verify`() {
        val copied = ActivationCodeVerifier.display(first).lowercase().replace("-", " - ").chunked(40).joinToString("\n")
        assertEquals(ActivationCodeVerifier.Outcome.Genuine(7, 1, first), verifier().check(copied))
        // Crockford base32 reads O as zero and I or L as one.
        assertEquals(ActivationCodeVerifier.Outcome.Genuine(7, 1, first), verifier().check(first.replace('0', 'O').replace('1', 'L')))
    }

    @Test fun `an altered, shortened or foreign code is refused`() {
        val altered = first.substring(0, 30) + (if (first[30] == 'A') 'B' else 'A') + first.substring(31)
        assertTrue(verifier().check(altered) !is ActivationCodeVerifier.Outcome.Genuine)
        assertEquals(ActivationCodeVerifier.Outcome.Malformed, verifier().check(first.drop(1)))
        assertEquals(ActivationCodeVerifier.Outcome.Malformed, verifier().check(first.dropLast(1) + "U"))
        assertEquals(ActivationCodeVerifier.Outcome.Malformed, verifier().check(""))
        // A key this build does not trust, such as the debug test key in a release build.
        assertEquals(ActivationCodeVerifier.Outcome.NotGenuine, ActivationCodeVerifier(emptyMap(), emptySet()).check(first))
    }

    @Test fun `a revoked serial is reported as revoked and the next serial still works`() {
        val revoked = ActivationCodeVerifier.parseRevoked(sequenceOf("# refunded", "", "7:1  # order 12"))
        assertEquals(setOf(7 to 1L), revoked)
        assertEquals(ActivationCodeVerifier.Outcome.Revoked(7, 1, first), verifier(revoked).check(first))
        assertEquals(ActivationCodeVerifier.Outcome.Genuine(7, 2, second), verifier(revoked).check(second))
    }

    @Test fun `the book keeps a genuine code, shows it in groups and re-checks it on start`() {
        val store = MemoryStore()
        var changes = 0
        val book = ActivationCodeBook(store, verifier()) { changes++ }
        assertEquals(ActivationState.None, book.state.value)
        assertEquals(ActivationResult.MALFORMED, book.activate("not a code"))
        assertNull(store.kept)
        assertEquals(ActivationResult.ACTIVATED, book.activate(ActivationCodeVerifier.display(first)))
        assertEquals(first, store.kept)
        assertEquals(ActivationState.Active(ActivationCodeVerifier.display(first)), book.state.value)
        assertEquals(1, changes)
        // A later version that revokes the serial shows it as revoked instead of unlocked.
        assertEquals(ActivationState.Revoked(ActivationCodeVerifier.display(first)), ActivationCodeBook(store, verifier(setOf(7 to 1L))) {}.state.value)
        // A build that no longer trusts the key forgets the unlock.
        assertEquals(ActivationState.None, ActivationCodeBook(store, ActivationCodeVerifier(emptyMap(), emptySet())) {}.state.value)
    }

    @Test fun `a revoked code never replaces a working one`() {
        val store = MemoryStore()
        val book = ActivationCodeBook(store, verifier(setOf(7 to 1L))) {}
        assertEquals(ActivationResult.ACTIVATED, book.activate(second))
        assertEquals(ActivationResult.REVOKED, book.activate(first))
        assertEquals(second, store.kept)
        assertEquals(ActivationResult.REVOKED, book.lastResult.value)
    }

    private class MemoryStore : KeptCodeStore {
        var kept: String? = null
        override fun read() = kept
        override fun write(compact: String) { kept = compact }
    }
}
