package io.github.sumirenokai.vesqen.verification

import io.github.sumirenokai.vesqen.playback.AudioFormatSummary
import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import io.github.sumirenokai.vesqen.playback.UsbHardwareIdentity
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OutputVerificationMatcherTest {
    private val runtime = OutputVerificationRuntimeIdentity(
        appVersionName = "0.4.0-beta.1",
        appVersionCode = 9,
        baseApkSha256 = HASH_A,
        deviceManufacturer = "Example",
        deviceModel = "Reference Phone",
        androidApiLevel = 35,
        buildFingerprintSha256 = HASH_B,
    )
    private val status = UsbOutputStatus(
        mode = UsbOutputMode.STRICT_BIT_PERFECT,
        phase = UsbOutputPhase.ACTIVE,
        deviceName = "Reference DAC",
        hardwareIdentity = UsbHardwareIdentity(0x1234, 0x5678, "2.10"),
        sourceFormat = AudioFormatSummary(96_000, 2, "24-bit source"),
        sinkFormat = AudioFormatSummary(96_000, 2, "PCM 24-bit"),
        decisionCode = "strict_usb.active",
        generation = 1,
    )
    private val record = OutputVerificationRecord(
        recordId = "m4.reference_96k24",
        result = OutputVerificationResult.VERIFIED,
        verifiedAtEpochMs = 1_788_800_000_000,
        appVersionName = runtime.appVersionName,
        appVersionCode = runtime.appVersionCode,
        baseApkSha256 = runtime.baseApkSha256,
        deviceManufacturer = runtime.deviceManufacturer,
        deviceModel = runtime.deviceModel,
        androidApiLevel = runtime.androidApiLevel,
        buildFingerprintSha256 = runtime.buildFingerprintSha256,
        dacVendorId = 0x1234,
        dacProductId = 0x5678,
        dacName = "Reference DAC",
        dacDescriptorVersion = "2.10",
        sourceFormat = VerificationPcmFormat(96_000, 2, "24-bit SOURCE"),
        sinkFormat = VerificationPcmFormat(96_000, 2, "pcm 24-BIT"),
        testVectorSha256 = HASH_C,
        methodId = "digital_capture.sample_compare",
        signalPoint = "usb_digital_pcm",
        evidenceReference = "docs/evidence/m4-reference-96k24",
    )

    @Test
    fun `exact active runtime combination resolves verified declaration`() {
        val context = OutputVerificationContext(runtime, status)
        val match = OutputVerificationMatcher.match(requireNotNull(context), listOf(record))

        assertNotNull(match)
        assertEquals(OutputDeclaration.BIT_PERFECT_VERIFIED, resolveOutputDeclaration(status, match))
    }

    @Test
    fun `every compatibility-sensitive field fails closed`() {
        val context = requireNotNull(OutputVerificationContext(runtime, status))
        val mismatches = listOf(
            record.copy(appVersionCode = 10),
            record.copy(baseApkSha256 = HASH_D),
            record.copy(deviceModel = "Other Phone"),
            record.copy(androidApiLevel = 34),
            record.copy(buildFingerprintSha256 = HASH_D),
            record.copy(dacProductId = 0x5679),
            record.copy(dacName = "Other DAC"),
            record.copy(dacDescriptorVersion = "2.11"),
            record.copy(sourceFormat = record.sourceFormat.copy(sampleRateHz = 48_000)),
            record.copy(sinkFormat = record.sinkFormat.copy(encoding = "PCM float")),
            record.copy(result = OutputVerificationResult.FAILED),
        )

        mismatches.forEach { mismatch ->
            assertNull(mismatch.toString(), OutputVerificationMatcher.match(context, listOf(mismatch)))
        }
        assertEquals(OutputDeclaration.BIT_PERFECT_ACTIVE, resolveOutputDeclaration(status, null))
    }

    @Test
    fun `non-active output can never inherit a verification record`() {
        val applying = status.copy(phase = UsbOutputPhase.APPLYING)

        assertNull(OutputVerificationContext(runtime, applying))
        assertEquals(OutputDeclaration.BIT_PERFECT_REQUESTED, resolveOutputDeclaration(applying, null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `verified record cannot target Android below API 34`() {
        record.copy(androidApiLevel = 33)
    }

    private companion object {
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
