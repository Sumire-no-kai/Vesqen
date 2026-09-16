package io.github.sumirenokai.vesqen.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeAudioScannerPolicyTest {
    @Test
    fun `provider loading cursor is never treated as a complete directory snapshot`() {
        assertFalse(
            directoryQueryIsComplete(
                providerIsLoading = true,
                providerHasError = false,
            ),
        )
        assertTrue(
            directoryQueryIsComplete(
                providerIsLoading = false,
                providerHasError = false,
            ),
        )
    }

    @Test
    fun `provider error cursor is never treated as a complete directory snapshot`() {
        assertFalse(
            directoryQueryIsComplete(
                providerIsLoading = false,
                providerHasError = true,
            ),
        )
    }
}
