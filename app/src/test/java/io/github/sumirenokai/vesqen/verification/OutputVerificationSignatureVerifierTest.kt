package io.github.sumirenokai.vesqen.verification

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputVerificationSignatureVerifierTest {
    private val trustedKeys = rsaKeyPair()
    private val payload = "canonical verification payload".toByteArray()

    @Test
    fun productionIssuerPublicKeyIsPinnedAndParseable() {
        val keys = PinnedOutputVerificationIssuerKeysProvider.publicKeysById()
        val keyId = PinnedOutputVerificationIssuerKeysProvider.OUTPUT_VERIFICATION_ISSUER_KEY_ID

        assertEquals(setOf(keyId), keys.keys)
        assertEquals("EC", keys.getValue(keyId).algorithm)
        assertEquals(
            "35619e5cc562b23282aa5bce0aa4e6ba6221e40b97522d96d91ea5c07daf0db4",
            MessageDigest.getInstance("SHA-256").digest(keys.getValue(keyId).encoded).toHex(),
        )
    }

    @Test
    fun trustedIssuerSignatureIsAccepted() {
        assertNull(
            OutputVerificationSignatureVerifier.failure(
                keyId = KEY_ID,
                signatureAlgorithm = "SHA256withRSA",
                payload = payload,
                signature = trustedKeys.sign(payload),
                publicKeysById = mapOf(KEY_ID to trustedKeys.public),
            ),
        )
    }

    @Test
    fun unknownIssuerIsRejectedEvenWithAValidSignature() {
        assertEquals(
            OutputVerificationImportFailure.UNKNOWN_SIGNING_KEY,
            OutputVerificationSignatureVerifier.failure(
                keyId = "vesqen.test.unknown",
                signatureAlgorithm = "SHA256withRSA",
                payload = payload,
                signature = trustedKeys.sign(payload),
                publicKeysById = mapOf(KEY_ID to trustedKeys.public),
            ),
        )
    }

    @Test
    fun signatureFromAnotherPrivateKeyIsRejected() {
        assertEquals(
            OutputVerificationImportFailure.SIGNATURE_MISMATCH,
            OutputVerificationSignatureVerifier.failure(
                keyId = KEY_ID,
                signatureAlgorithm = "SHA256withRSA",
                payload = payload,
                signature = rsaKeyPair().sign(payload),
                publicKeysById = mapOf(KEY_ID to trustedKeys.public),
            ),
        )
    }

    @Test
    fun algorithmSubstitutionIsRejected() {
        assertEquals(
            OutputVerificationImportFailure.SIGNATURE_MISMATCH,
            OutputVerificationSignatureVerifier.failure(
                keyId = KEY_ID,
                signatureAlgorithm = "SHA256withECDSA",
                payload = payload,
                signature = trustedKeys.sign(payload),
                publicKeysById = mapOf(KEY_ID to trustedKeys.public),
            ),
        )
    }

    private fun KeyPair.sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run {
        initSign(private)
        update(bytes)
        sign()
    }

    private fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(2048)
        generateKeyPair()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val KEY_ID = "vesqen.test.output_verification.2026_01"
    }
}
