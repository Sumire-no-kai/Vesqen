package io.github.sumirenokai.vesqen.verification

import io.github.sumirenokai.vesqen.playback.AudioFormatSummary
import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import io.github.sumirenokai.vesqen.playback.UsbHardwareIdentity
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import java.util.Locale

enum class OutputVerificationResult {
    VERIFIED,
    FAILED,
    NOT_TESTED,
    MISSING_DEVICE,
    DEFERRED,
}

data class VerificationPcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: String,
) {
    init {
        require(sampleRateHz > 0) { "Verification sample rate must be positive" }
        require(channelCount > 0) { "Verification channel count must be positive" }
        require(encoding.isNotBlank()) { "Verification encoding cannot be blank" }
    }

    internal fun normalized(): VerificationPcmFormat = copy(
        encoding = encoding.trim().lowercase(Locale.ROOT),
    )
}

data class OutputVerificationRecord(
    val recordId: String,
    val result: OutputVerificationResult,
    val verifiedAtEpochMs: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val baseApkSha256: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidApiLevel: Int,
    val buildFingerprintSha256: String,
    val dacVendorId: Int,
    val dacProductId: Int,
    val dacName: String,
    val dacDescriptorVersion: String,
    val sourceFormat: VerificationPcmFormat,
    val sinkFormat: VerificationPcmFormat,
    val testVectorSha256: String,
    val methodId: String,
    val signalPoint: String,
    val evidenceReference: String,
) {
    init {
        require(recordId.matches(STABLE_ID)) { "Verification record ids must be stable" }
        require(verifiedAtEpochMs >= 0) { "Verification timestamps cannot be negative" }
        require(appVersionName.isNotBlank()) { "Verification app version cannot be blank" }
        require(appVersionCode > 0) { "Verification app version code must be positive" }
        require(baseApkSha256.isSha256()) { "Verification APK hash must be SHA-256" }
        require(deviceManufacturer.isNotBlank()) { "Verification device manufacturer cannot be blank" }
        require(deviceModel.isNotBlank()) { "Verification device model cannot be blank" }
        require(androidApiLevel >= 26) { "Compatibility records require a supported Android API" }
        require(result != OutputVerificationResult.VERIFIED || androidApiLevel >= 34) {
            "Official bit-perfect verification requires Android 14+"
        }
        require(buildFingerprintSha256.isSha256()) { "Verification ROM fingerprint hash must be SHA-256" }
        require(dacVendorId in 0..0xffff && dacProductId in 0..0xffff) {
            "Verification USB ids must be unsigned 16-bit values"
        }
        require(dacName.isNotBlank()) { "Verification DAC name cannot be blank" }
        require(dacDescriptorVersion.isNotBlank()) { "Verification DAC descriptor version cannot be blank" }
        require(testVectorSha256.isSha256()) { "Verification test-vector hash must be SHA-256" }
        require(methodId.matches(STABLE_ID)) { "Verification method ids must be stable" }
        require(signalPoint.matches(STABLE_ID)) { "Verification signal-point ids must be stable" }
        require(evidenceReference.isNotBlank()) { "Verification evidence reference cannot be blank" }
    }

    private companion object {
        val STABLE_ID = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*")
    }
}

data class OutputVerificationRuntimeIdentity(
    val appVersionName: String,
    val appVersionCode: Long,
    val baseApkSha256: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidApiLevel: Int,
    val buildFingerprintSha256: String,
) {
    init {
        require(appVersionName.isNotBlank())
        require(appVersionCode > 0)
        require(baseApkSha256.isSha256())
        require(deviceManufacturer.isNotBlank())
        require(deviceModel.isNotBlank())
        require(androidApiLevel > 0)
        require(buildFingerprintSha256.isSha256())
    }
}

data class OutputVerificationContext(
    val runtime: OutputVerificationRuntimeIdentity,
    val dac: UsbHardwareIdentity,
    val dacName: String,
    val sourceFormat: VerificationPcmFormat,
    val sinkFormat: VerificationPcmFormat,
) {
    init {
        require(dacName.isNotBlank()) { "Verification DAC name cannot be blank" }
    }
}

data class OutputVerificationMatch(val record: OutputVerificationRecord)

object OutputVerificationMatcher {
    fun match(
        context: OutputVerificationContext,
        records: List<OutputVerificationRecord>,
    ): OutputVerificationMatch? = records.firstOrNull { record ->
        record.result == OutputVerificationResult.VERIFIED &&
            record.appVersionName == context.runtime.appVersionName &&
            record.appVersionCode == context.runtime.appVersionCode &&
            record.baseApkSha256.equals(context.runtime.baseApkSha256, ignoreCase = true) &&
            record.deviceManufacturer == context.runtime.deviceManufacturer &&
            record.deviceModel == context.runtime.deviceModel &&
            record.androidApiLevel == context.runtime.androidApiLevel &&
            record.buildFingerprintSha256.equals(
                context.runtime.buildFingerprintSha256,
                ignoreCase = true,
            ) &&
            record.dacVendorId == context.dac.vendorId &&
            record.dacProductId == context.dac.productId &&
            record.dacName == context.dacName &&
            record.dacDescriptorVersion == context.dac.descriptorVersion &&
            record.sourceFormat.normalized() == context.sourceFormat.normalized() &&
            record.sinkFormat.normalized() == context.sinkFormat.normalized()
    }?.let(::OutputVerificationMatch)
}

fun resolveOutputDeclaration(
    status: UsbOutputStatus,
    verification: OutputVerificationMatch?,
): OutputDeclaration = if (status.phase == UsbOutputPhase.ACTIVE && verification != null) {
    OutputDeclaration.BIT_PERFECT_VERIFIED
} else {
    status.declaration
}

fun OutputVerificationContext(
    runtime: OutputVerificationRuntimeIdentity,
    status: UsbOutputStatus,
): OutputVerificationContext? {
    if (status.phase != UsbOutputPhase.ACTIVE) return null
    return OutputVerificationContext(
        runtime = runtime,
        dac = status.hardwareIdentity ?: return null,
        dacName = status.deviceName ?: return null,
        sourceFormat = status.sourceFormat?.toVerificationFormat() ?: return null,
        sinkFormat = status.sinkFormat?.toVerificationFormat() ?: return null,
    )
}

private fun AudioFormatSummary.toVerificationFormat() = VerificationPcmFormat(
    sampleRateHz = sampleRateHz,
    channelCount = channelCount,
    encoding = encoding,
)

internal fun String.isSha256(): Boolean = matches(Regex("[0-9a-fA-F]{64}"))
