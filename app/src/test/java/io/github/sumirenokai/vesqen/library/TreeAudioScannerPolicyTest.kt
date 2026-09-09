package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeAudioScannerPolicyTest {
    @Test
    fun `provider loading cursor is never treated as a complete directory snapshot`() {
        assertFalse(directoryQueryIsComplete(providerIsLoading = true))
        assertTrue(directoryQueryIsComplete(providerIsLoading = false))
    }
}
