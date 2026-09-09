package io.github.sumirenokai.vesqen.verification

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.playback.AudioFormatSummary
import io.github.sumirenokai.vesqen.playback.UsbHardwareIdentity
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidOutputVerificationRepositoryTest {
    private val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "m4-verification-${UUID.randomUUID()}",
    ).also { check(it.mkdirs()) }
    private val registryFile = File(directory, "registry.json")
    private val keyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(2048)
        generateKeyPair()
    }
    private val runtime = OutputVerificationRuntimeIdentity(
        appVersionName = "0.4.0-beta.1",
        appVersionCode = 9,
        baseApkSha256 = "a".repeat(64),
        deviceManufacturer = "Example",
        deviceModel = "Reference Phone",
        androidApiLevel = 35,
        buildFingerprintSha256 = "b".repeat(64),
    )

    @After
    fun removeTestFiles() {
        directory.listFiles()?.forEach(File::delete)
        directory.delete()
    }

    @Test
    fun signedRegistryPersistsAndMatchesOnlyExactActiveOutput() = runBlocking {
        val repository = repository()

        val result = repository.import(ByteArrayInputStream(signedDocument(keyPair)))

        assertEquals(OutputVerificationImportResult.Success(1, 1), result)
        assertTrue(registryFile.isFile)
        assertNotNull(repository.match(activeStatus()))

        val reloaded = repository()
        reloaded.load()
        assertTrue(reloaded.state.value is OutputVerificationRegistryState.Ready)
        assertNotNull(reloaded.match(activeStatus()))
    }

    @Test
    fun loadRecoversTheLegacyAtomicBackupAfterAnInterruptedReplacement() = runBlocking {
        val repository = repository()
        assertTrue(repository.import(ByteArrayInputStream(signedDocument(keyPair))) is OutputVerificationImportResult.Success)
        val backup = File(registryFile.path + ".bak")
        assertTrue(registryFile.renameTo(backup))

        val reloaded = repository()
        reloaded.load()

        assertTrue(reloaded.state.value is OutputVerificationRegistryState.Ready)
        assertNotNull(reloaded.match(activeStatus()))
        assertTrue(registryFile.isFile)
    }

    @Test
    fun invalidSignatureCannotReplaceExistingRegistry() = runBlocking {
        val repository = repository()
        assertTrue(repository.import(ByteArrayInputStream(signedDocument(keyPair))) is OutputVerificationImportResult.Success)
        val persisted = registryFile.readBytes()
        val otherKey = KeyPairGenerator.getInstance("RSA").run {
            initialize(2048)
            generateKeyPair()
        }

        val result = repository.import(ByteArrayInputStream(signedDocument(otherKey)))

        assertEquals(
            OutputVerificationImportResult.Failure(OutputVerificationImportFailure.SIGNATURE_MISMATCH),
            result,
        )
        assertTrue(persisted.contentEquals(registryFile.readBytes()))
        assertNotNull(repository.match(activeStatus()))
    }

    @Test
    fun repeatedMatchesReuseTheImmutableProcessRuntimeIdentity() = runBlocking {
        val resolutions = AtomicInteger()
        val repository = AndroidOutputVerificationRepository(
            registryFile = registryFile,
            runtimeIdentityProvider = OutputVerificationRuntimeIdentityProvider {
                resolutions.incrementAndGet()
                runtime
            },
            signingKeysProvider = OutputVerificationSigningKeysProvider { listOf(keyPair.public) },
        )

        assertTrue(repository.import(ByteArrayInputStream(signedDocument(keyPair))) is OutputVerificationImportResult.Success)
        assertNotNull(repository.match(activeStatus()))
        assertNotNull(repository.match(activeStatus()))

        assertEquals(1, resolutions.get())
    }

    private fun repository() = AndroidOutputVerificationRepository(
        registryFile = registryFile,
        runtimeIdentityProvider = OutputVerificationRuntimeIdentityProvider { runtime },
        signingKeysProvider = OutputVerificationSigningKeysProvider { listOf(keyPair.public) },
    )

    private fun activeStatus() = UsbOutputStatus(
        mode = UsbOutputMode.STRICT_BIT_PERFECT,
        phase = UsbOutputPhase.ACTIVE,
        deviceName = "Reference DAC",
        hardwareIdentity = UsbHardwareIdentity(0x1234, 0x5678, "2.10"),
        sourceFormat = AudioFormatSummary(96_000, 2, "pcm 24-bit"),
        sinkFormat = AudioFormatSummary(96_000, 2, "pcm 24-bit"),
        decisionCode = "strict_usb.active",
        generation = 1,
    )

    private fun signedDocument(keys: KeyPair): ByteArray {
        val format = JSONObject()
            .put("sampleRateHz", 96_000)
            .put("channelCount", 2)
            .put("encoding", "pcm 24-bit")
        val record = JSONObject()
            .put("recordId", "m4.reference_96k24")
            .put("result", "VERIFIED")
            .put("verifiedAtEpochMs", 1_788_800_000_000L)
            .put("appVersionName", runtime.appVersionName)
            .put("appVersionCode", runtime.appVersionCode)
            .put("baseApkSha256", runtime.baseApkSha256)
            .put("deviceManufacturer", runtime.deviceManufacturer)
            .put("deviceModel", runtime.deviceModel)
            .put("androidApiLevel", runtime.androidApiLevel)
            .put("buildFingerprintSha256", runtime.buildFingerprintSha256)
            .put("dacVendorId", 0x1234)
            .put("dacProductId", 0x5678)
            .put("dacName", "Reference DAC")
            .put("dacDescriptorVersion", "2.10")
            .put("sourceFormat", format)
            .put("sinkFormat", JSONObject(format.toString()))
            .put("testVectorSha256", "c".repeat(64))
            .put("methodId", "digital_capture.sample_compare")
            .put("signalPoint", "usb_digital_pcm")
            .put("evidenceReference", "evidence/m4-reference-96k24")
        val payload = JSONObject()
            .put("schemaVersion", 1)
            .put("records", JSONArray().put(record))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(payload)
            sign()
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("signatureAlgorithm", "SHA256withRSA")
            .put("payload", Base64.encodeToString(payload, Base64.NO_WRAP))
            .put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }
}
