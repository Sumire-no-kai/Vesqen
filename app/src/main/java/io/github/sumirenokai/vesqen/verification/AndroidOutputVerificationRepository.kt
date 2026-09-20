package io.github.sumirenokai.vesqen.verification

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Base64
import android.util.JsonReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus

sealed interface OutputVerificationRegistryState {
    data object Loading : OutputVerificationRegistryState
    data object Empty : OutputVerificationRegistryState

    data class Ready(
        val records: List<OutputVerificationRecord>,
        val applicableInstallRecordCount: Int,
    ) : OutputVerificationRegistryState

    data class Invalid(val reason: OutputVerificationImportFailure) : OutputVerificationRegistryState
}

enum class OutputVerificationImportFailure {
    INPUT_TOO_LARGE,
    MALFORMED_DOCUMENT,
    UNSUPPORTED_SCHEMA,
    UNSUPPORTED_SIGNATURE_ALGORITHM,
    UNKNOWN_SIGNING_KEY,
    SIGNATURE_MISMATCH,
    NO_TRUSTED_ISSUER,
    IO_ERROR,
}

sealed interface OutputVerificationImportResult {
    data class Success(
        val recordCount: Int,
        val applicableInstallRecordCount: Int,
    ) : OutputVerificationImportResult

    data class Failure(val reason: OutputVerificationImportFailure) : OutputVerificationImportResult
}

interface OutputVerificationRepository {
    val state: StateFlow<OutputVerificationRegistryState>

    suspend fun load()

    suspend fun import(input: InputStream): OutputVerificationImportResult

    fun match(status: UsbOutputStatus): OutputVerificationMatch?
}

fun interface OutputVerificationRuntimeIdentityProvider {
    fun resolve(): OutputVerificationRuntimeIdentity
}

fun interface OutputVerificationIssuerKeysProvider {
    fun publicKeysById(): Map<String, PublicKey>
}

/**
 * Private, offline registry for maintainer-signed external verification records.
 *
 * The signature is checked against a dedicated, versioned verification issuer rather than the APK
 * update signer. A valid record must then match the base APK hash, phone/ROM, DAC identity and
 * active source/sink format exactly. Importing a file is therefore not a user-controlled VERIFIED
 * checkbox.
 */
class AndroidOutputVerificationRepository internal constructor(
    private val registryFile: File,
    private val runtimeIdentityProvider: OutputVerificationRuntimeIdentityProvider,
    private val issuerKeysProvider: OutputVerificationIssuerKeysProvider,
) : OutputVerificationRepository {
    constructor(context: Context) : this(
        registryFile = File(context.filesDir, REGISTRY_FILE_NAME),
        runtimeIdentityProvider = AndroidOutputVerificationRuntimeIdentityProvider(context.applicationContext),
        issuerKeysProvider = PinnedOutputVerificationIssuerKeysProvider,
    )

    private val mutation = Mutex()
    private val runtimeIdentity by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runtimeIdentityProvider.resolve()
    }
    private val mutableState = MutableStateFlow<OutputVerificationRegistryState>(
        OutputVerificationRegistryState.Loading,
    )
    override val state: StateFlow<OutputVerificationRegistryState> = mutableState.asStateFlow()

    override suspend fun load() {
        mutation.withLock {
            val bytes = try {
                AtomicFile(registryFile).openRead().use(::readBounded)
            } catch (_: FileNotFoundException) {
                mutableState.value = OutputVerificationRegistryState.Empty
                return
            } catch (_: InputTooLargeException) {
                mutableState.value = OutputVerificationRegistryState.Invalid(
                    OutputVerificationImportFailure.INPUT_TOO_LARGE,
                )
                return
            } catch (_: Exception) {
                mutableState.value = OutputVerificationRegistryState.Invalid(
                    OutputVerificationImportFailure.IO_ERROR,
                )
                return
            }
            mutableState.value = decodeState(bytes)
        }
    }

    override suspend fun import(input: InputStream): OutputVerificationImportResult = mutation.withLock {
        val bytes = try {
            input.use(::readBounded)
        } catch (_: InputTooLargeException) {
            return@withLock OutputVerificationImportResult.Failure(
                OutputVerificationImportFailure.INPUT_TOO_LARGE,
            )
        } catch (_: Exception) {
            return@withLock OutputVerificationImportResult.Failure(
                OutputVerificationImportFailure.IO_ERROR,
            )
        }
        val decoded = decodeState(bytes)
        if (decoded !is OutputVerificationRegistryState.Ready) {
            val reason = (decoded as? OutputVerificationRegistryState.Invalid)?.reason
                ?: OutputVerificationImportFailure.MALFORMED_DOCUMENT
            return@withLock OutputVerificationImportResult.Failure(reason)
        }
        try {
            registryFile.parentFile?.mkdirs()
            val atomicFile = AtomicFile(registryFile)
            val output = atomicFile.startWrite()
            try {
                output.write(bytes)
                atomicFile.finishWrite(output)
            } catch (failure: Exception) {
                atomicFile.failWrite(output)
                throw failure
            }
        } catch (_: Exception) {
            return@withLock OutputVerificationImportResult.Failure(
                OutputVerificationImportFailure.IO_ERROR,
            )
        }
        mutableState.value = decoded
        OutputVerificationImportResult.Success(
            recordCount = decoded.records.size,
            applicableInstallRecordCount = decoded.applicableInstallRecordCount,
        )
    }

    override fun match(status: UsbOutputStatus): OutputVerificationMatch? {
        val ready = mutableState.value as? OutputVerificationRegistryState.Ready ?: return null
        val runtime = runCatching { runtimeIdentity }.getOrNull() ?: return null
        val context = OutputVerificationContext(runtime, status) ?: return null
        return OutputVerificationMatcher.match(context, ready.records)
    }

    private fun decodeState(bytes: ByteArray): OutputVerificationRegistryState {
        val keys = runCatching(issuerKeysProvider::publicKeysById).getOrDefault(emptyMap())
        if (keys.isEmpty()) {
            return OutputVerificationRegistryState.Invalid(
                OutputVerificationImportFailure.NO_TRUSTED_ISSUER,
            )
        }
        val decoded = try {
            SignedVerificationDocumentCodec.decode(bytes, keys)
        } catch (failure: VerificationDocumentException) {
            return OutputVerificationRegistryState.Invalid(failure.reason)
        } catch (_: Exception) {
            return OutputVerificationRegistryState.Invalid(
                OutputVerificationImportFailure.MALFORMED_DOCUMENT,
            )
        }
        val runtime = try {
            runtimeIdentity
        } catch (_: Exception) {
            return OutputVerificationRegistryState.Invalid(OutputVerificationImportFailure.IO_ERROR)
        }
        val applicable = decoded.count { record -> record.matchesInstall(runtime) }
        return OutputVerificationRegistryState.Ready(decoded, applicable)
    }

    private fun OutputVerificationRecord.matchesInstall(runtime: OutputVerificationRuntimeIdentity): Boolean =
        appVersionName == runtime.appVersionName &&
            appVersionCode == runtime.appVersionCode &&
            baseApkSha256.equals(runtime.baseApkSha256, ignoreCase = true) &&
            deviceManufacturer == runtime.deviceManufacturer &&
            deviceModel == runtime.deviceModel &&
            androidApiLevel == runtime.androidApiLevel &&
            buildFingerprintSha256.equals(runtime.buildFingerprintSha256, ignoreCase = true)

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > MAX_DOCUMENT_BYTES) throw InputTooLargeException()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private class InputTooLargeException : Exception()

    private companion object {
        const val REGISTRY_FILE_NAME = "output-verification-registry.json"
        const val MAX_DOCUMENT_BYTES = 256 * 1024
    }
}

private class AndroidOutputVerificationRuntimeIdentityProvider(
    private val context: Context,
) : OutputVerificationRuntimeIdentityProvider {
    @Suppress("DEPRECATION")
    override fun resolve(): OutputVerificationRuntimeIdentity {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val source = requireNotNull(context.applicationInfo.sourceDir) { "Base APK path is unavailable" }
        return OutputVerificationRuntimeIdentity(
            appVersionName = requireNotNull(packageInfo.versionName),
            appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            },
            baseApkSha256 = File(source).inputStream().use(::sha256),
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            androidApiLevel = Build.VERSION.SDK_INT,
            buildFingerprintSha256 = sha256(Build.FINGERPRINT.toByteArray(StandardCharsets.UTF_8)),
        )
    }
}

/**
 * Production issuer keys are intentionally independent from Android app-signing certificates.
 * A key rotation adds a new id/public-key pair while retaining old keys for existing registries.
 * Failure to parse the pinned key makes imports fail closed instead of falling back to the APK
 * update signer.
 */
internal object PinnedOutputVerificationIssuerKeysProvider : OutputVerificationIssuerKeysProvider {
    private val keys by lazy(LazyThreadSafetyMode.PUBLICATION) {
        mapOf(
            OUTPUT_VERIFICATION_ISSUER_KEY_ID to KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(
                    java.util.Base64.getDecoder().decode(OUTPUT_VERIFICATION_ISSUER_PUBLIC_KEY_BASE64),
                ),
            ),
        )
    }

    override fun publicKeysById(): Map<String, PublicKey> = keys

    internal const val OUTPUT_VERIFICATION_ISSUER_KEY_ID =
        "vesqen.output_verification.2026_01"
    private const val OUTPUT_VERIFICATION_ISSUER_PUBLIC_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEgeeZWCweEYd3UpoSDtu6lNjnGZwP2gel1fvJ0UNwcM+UM8qDhi1/LiqXI838gE4mFyFDth3vs+DO2rDSGqgUVw=="
}

private object SignedVerificationDocumentCodec {
    private const val ENVELOPE_SCHEMA_VERSION = 2
    private const val PAYLOAD_SCHEMA_VERSION = 1
    private const val MAX_RECORDS = 128

    fun decode(document: ByteArray, publicKeysById: Map<String, PublicKey>): List<OutputVerificationRecord> {
        val envelope = readEnvelope(document)
        if (envelope.schemaVersion != ENVELOPE_SCHEMA_VERSION) {
            throw VerificationDocumentException(OutputVerificationImportFailure.UNSUPPORTED_SCHEMA)
        }
        val payload = decodeBase64(envelope.payload)
        val signature = decodeBase64(envelope.signature)
        OutputVerificationSignatureVerifier.failure(
            keyId = envelope.keyId,
            signatureAlgorithm = envelope.signatureAlgorithm,
            payload = payload,
            signature = signature,
            publicKeysById = publicKeysById,
        )?.let { reason -> throw VerificationDocumentException(reason) }
        return readPayload(payload)
    }

    private fun readEnvelope(document: ByteArray): SignedEnvelope = jsonReader(document) { reader ->
        var schemaVersion: Int? = null
        var keyId: String? = null
        var algorithm: String? = null
        var payload: String? = null
        var signature: String? = null
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName().also { require(seen.add(it)) }
            when (name) {
                "schemaVersion" -> schemaVersion = reader.nextInt()
                "keyId" -> keyId = reader.nextString().also { require(KEY_ID_PATTERN.matches(it)) }
                "signatureAlgorithm" -> algorithm = reader.nextString()
                "payload" -> payload = reader.nextString()
                "signature" -> signature = reader.nextString()
                else -> throw IllegalArgumentException("Unknown envelope field")
            }
        }
        reader.endObject()
        SignedEnvelope(
            schemaVersion = requireNotNull(schemaVersion),
            keyId = requireNotNull(keyId),
            signatureAlgorithm = requireNotNull(algorithm),
            payload = requireNotNull(payload),
            signature = requireNotNull(signature),
        )
    }

    private fun readPayload(payload: ByteArray): List<OutputVerificationRecord> = jsonReader(payload) { reader ->
        var schemaVersion: Int? = null
        var records: List<OutputVerificationRecord>? = null
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName().also { require(seen.add(it)) }
            when (name) {
                "schemaVersion" -> schemaVersion = reader.nextInt()
                "records" -> records = buildList {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        require(size < MAX_RECORDS) { "Too many verification records" }
                        add(reader.readRecord())
                    }
                    reader.endArray()
                }
                else -> throw IllegalArgumentException("Unknown payload field")
            }
        }
        reader.endObject()
        if (schemaVersion != PAYLOAD_SCHEMA_VERSION) {
            throw VerificationDocumentException(OutputVerificationImportFailure.UNSUPPORTED_SCHEMA)
        }
        requireNotNull(records).also { parsed ->
            require(parsed.map(OutputVerificationRecord::recordId).distinct().size == parsed.size) {
                "Duplicate verification record ids"
            }
        }
    }

    private fun JsonReader.readRecord(): OutputVerificationRecord {
        val fields = mutableMapOf<String, Any>()
        beginObject()
        while (hasNext()) {
            val name = nextName()
            require(!fields.containsKey(name)) { "Duplicate record field" }
            fields[name] = when (name) {
                "recordId", "result", "appVersionName", "baseApkSha256", "deviceManufacturer",
                "deviceModel", "buildFingerprintSha256", "dacName", "dacDescriptorVersion",
                "testVectorSha256", "methodId", "signalPoint", "evidenceReference" -> nextString()
                "verifiedAtEpochMs", "appVersionCode" -> nextLong()
                "androidApiLevel", "dacVendorId", "dacProductId" -> nextInt()
                "sourceFormat", "sinkFormat" -> readFormat()
                else -> throw IllegalArgumentException("Unknown verification record field")
            }
        }
        endObject()
        fun text(name: String) = fields[name] as? String ?: error("Missing $name")
        fun long(name: String) = fields[name] as? Long ?: error("Missing $name")
        fun int(name: String) = fields[name] as? Int ?: error("Missing $name")
        fun format(name: String) = fields[name] as? VerificationPcmFormat ?: error("Missing $name")
        return OutputVerificationRecord(
            recordId = text("recordId"),
            result = OutputVerificationResult.valueOf(text("result")),
            verifiedAtEpochMs = long("verifiedAtEpochMs"),
            appVersionName = text("appVersionName"),
            appVersionCode = long("appVersionCode"),
            baseApkSha256 = text("baseApkSha256"),
            deviceManufacturer = text("deviceManufacturer"),
            deviceModel = text("deviceModel"),
            androidApiLevel = int("androidApiLevel"),
            buildFingerprintSha256 = text("buildFingerprintSha256"),
            dacVendorId = int("dacVendorId"),
            dacProductId = int("dacProductId"),
            dacName = text("dacName"),
            dacDescriptorVersion = text("dacDescriptorVersion"),
            sourceFormat = format("sourceFormat"),
            sinkFormat = format("sinkFormat"),
            testVectorSha256 = text("testVectorSha256"),
            methodId = text("methodId"),
            signalPoint = text("signalPoint"),
            evidenceReference = text("evidenceReference"),
        )
    }

    private fun JsonReader.readFormat(): VerificationPcmFormat {
        var sampleRateHz: Int? = null
        var channelCount: Int? = null
        var encoding: String? = null
        val seen = mutableSetOf<String>()
        beginObject()
        while (hasNext()) {
            when (nextName().also { require(seen.add(it)) }) {
                "sampleRateHz" -> sampleRateHz = nextInt()
                "channelCount" -> channelCount = nextInt()
                "encoding" -> encoding = nextString()
                else -> throw IllegalArgumentException("Unknown verification format field")
            }
        }
        endObject()
        return VerificationPcmFormat(
            sampleRateHz = requireNotNull(sampleRateHz),
            channelCount = requireNotNull(channelCount),
            encoding = requireNotNull(encoding),
        )
    }

    private fun decodeBase64(value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        throw VerificationDocumentException(OutputVerificationImportFailure.MALFORMED_DOCUMENT)
    }

    private inline fun <T> jsonReader(bytes: ByteArray, block: (JsonReader) -> T): T = try {
        JsonReader(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).use { reader ->
            block(reader).also { require(reader.peek() == android.util.JsonToken.END_DOCUMENT) }
        }
    } catch (failure: VerificationDocumentException) {
        throw failure
    } catch (_: Exception) {
        throw VerificationDocumentException(OutputVerificationImportFailure.MALFORMED_DOCUMENT)
    }

    private data class SignedEnvelope(
        val schemaVersion: Int,
        val keyId: String,
        val signatureAlgorithm: String,
        val payload: String,
        val signature: String,
    )

    private val KEY_ID_PATTERN = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*")
}

internal object OutputVerificationSignatureVerifier {
    fun failure(
        keyId: String,
        signatureAlgorithm: String,
        payload: ByteArray,
        signature: ByteArray,
        publicKeysById: Map<String, PublicKey>,
    ): OutputVerificationImportFailure? {
        if (signatureAlgorithm !in ALLOWED_SIGNATURE_ALGORITHMS) {
            return OutputVerificationImportFailure.UNSUPPORTED_SIGNATURE_ALGORITHM
        }
        val publicKey = publicKeysById[keyId]
            ?: return OutputVerificationImportFailure.UNKNOWN_SIGNING_KEY
        val verified = signatureAlgorithmFor(publicKey) == signatureAlgorithm && runCatching {
            Signature.getInstance(signatureAlgorithm).run {
                initVerify(publicKey)
                update(payload)
                verify(signature)
            }
        }.getOrDefault(false)
        return if (verified) null else OutputVerificationImportFailure.SIGNATURE_MISMATCH
    }

    private fun signatureAlgorithmFor(key: PublicKey): String? = when (key.algorithm.uppercase()) {
        "RSA" -> "SHA256withRSA"
        "EC", "ECDSA" -> "SHA256withECDSA"
        else -> null
    }

    private val ALLOWED_SIGNATURE_ALGORITHMS = setOf("SHA256withRSA", "SHA256withECDSA")
}

private class VerificationDocumentException(
    val reason: OutputVerificationImportFailure,
) : Exception()

private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
    }
    return digest.digest().toHex()
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
