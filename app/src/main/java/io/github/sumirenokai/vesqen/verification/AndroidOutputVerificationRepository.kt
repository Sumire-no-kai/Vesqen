package io.github.sumirenokai.vesqen.verification

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import android.util.Base64
import android.util.JsonReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
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
    SIGNATURE_MISMATCH,
    NO_SIGNING_CERTIFICATE,
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

fun interface OutputVerificationSigningKeysProvider {
    fun publicKeys(): List<PublicKey>
}

/**
 * Private, offline registry for maintainer-signed external verification records.
 *
 * The signature is checked against the certificate that signed the installed APK. A valid record
 * must then match the base APK hash, phone/ROM, DAC identity and active source/sink format exactly.
 * Importing a file is therefore not a user-controlled VERIFIED checkbox.
 */
class AndroidOutputVerificationRepository internal constructor(
    private val registryFile: File,
    private val runtimeIdentityProvider: OutputVerificationRuntimeIdentityProvider,
    private val signingKeysProvider: OutputVerificationSigningKeysProvider,
) : OutputVerificationRepository {
    constructor(context: Context) : this(
        registryFile = File(context.filesDir, REGISTRY_FILE_NAME),
        runtimeIdentityProvider = AndroidOutputVerificationRuntimeIdentityProvider(context.applicationContext),
        signingKeysProvider = AndroidOutputVerificationSigningKeysProvider(context.applicationContext),
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
            if (!registryFile.exists()) {
                mutableState.value = OutputVerificationRegistryState.Empty
                return
            }
            val bytes = try {
                registryFile.inputStream().use(::readBounded)
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
        val keys = runCatching(signingKeysProvider::publicKeys).getOrDefault(emptyList())
        if (keys.isEmpty()) {
            return OutputVerificationRegistryState.Invalid(
                OutputVerificationImportFailure.NO_SIGNING_CERTIFICATE,
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

private class AndroidOutputVerificationSigningKeysProvider(
    private val context: Context,
) : OutputVerificationSigningKeysProvider {
    @Suppress("DEPRECATION")
    override fun publicKeys(): List<PublicKey> {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageInfo.signatures.orEmpty()
        }
        val certificates = CertificateFactory.getInstance("X.509")
        return signatures.map { signature ->
            certificates.generateCertificate(ByteArrayInputStream(signature.toByteArray())).publicKey
        }
    }
}

private object SignedVerificationDocumentCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_RECORDS = 128

    fun decode(document: ByteArray, publicKeys: List<PublicKey>): List<OutputVerificationRecord> {
        val envelope = readEnvelope(document)
        if (envelope.schemaVersion != SCHEMA_VERSION) {
            throw VerificationDocumentException(OutputVerificationImportFailure.UNSUPPORTED_SCHEMA)
        }
        if (envelope.signatureAlgorithm !in ALLOWED_SIGNATURE_ALGORITHMS) {
            throw VerificationDocumentException(
                OutputVerificationImportFailure.UNSUPPORTED_SIGNATURE_ALGORITHM,
            )
        }
        val payload = decodeBase64(envelope.payload)
        val signature = decodeBase64(envelope.signature)
        val verified = publicKeys.any { key ->
            signatureAlgorithmFor(key) == envelope.signatureAlgorithm && runCatching {
                Signature.getInstance(envelope.signatureAlgorithm).run {
                    initVerify(key)
                    update(payload)
                    verify(signature)
                }
            }.getOrDefault(false)
        }
        if (!verified) {
            throw VerificationDocumentException(OutputVerificationImportFailure.SIGNATURE_MISMATCH)
        }
        return readPayload(payload)
    }

    private fun readEnvelope(document: ByteArray): SignedEnvelope = jsonReader(document) { reader ->
        var schemaVersion: Int? = null
        var algorithm: String? = null
        var payload: String? = null
        var signature: String? = null
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName().also { require(seen.add(it)) }
            when (name) {
                "schemaVersion" -> schemaVersion = reader.nextInt()
                "signatureAlgorithm" -> algorithm = reader.nextString()
                "payload" -> payload = reader.nextString()
                "signature" -> signature = reader.nextString()
                else -> throw IllegalArgumentException("Unknown envelope field")
            }
        }
        reader.endObject()
        SignedEnvelope(
            schemaVersion = requireNotNull(schemaVersion),
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
        if (schemaVersion != SCHEMA_VERSION) {
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

    private fun signatureAlgorithmFor(key: PublicKey): String? = when (key.algorithm.uppercase()) {
        "RSA" -> "SHA256withRSA"
        "EC", "ECDSA" -> "SHA256withECDSA"
        else -> null
    }

    private data class SignedEnvelope(
        val schemaVersion: Int,
        val signatureAlgorithm: String,
        val payload: String,
        val signature: String,
    )

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
