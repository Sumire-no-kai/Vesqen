package io.github.sumirenokai.vesqen.updates

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

internal interface UpdateInstaller {
    fun canInstall(): Boolean
    fun requestPermission()
    fun archiveIdentity(apk: File): ApkIdentity
    fun install(apk: File, release: UpdateRelease)
}

/** One operation owns the staging file. Playback never waits for this scope or its mutex. */
internal class GitHubAppUpdater(
    private val scope: CoroutineScope,
    private val preferences: UpdatePreferencesStore,
    private val transport: UpdateTransport,
    private val installer: UpdateInstaller,
    private val installed: ApkIdentity,
    private val androidApi: Int,
    private val directory: File,
    private val endpoint: String,
    channel: UpdateChannel,
    source: UpdateInstallationSource,
    installerPackage: String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val parse: (String, UpdateChannel) -> UpdateRelease? = UpdateManifest::parse,
) : AppUpdater {
    private val operation = Mutex()
    private val preferencesLock = Any()
    private val automaticSettingRequest = java.util.concurrent.atomic.AtomicLong()
    private var initialized = false
    private val mutable = MutableStateFlow(preferences.read().let {
        UpdateSnapshot(channel = channel, automaticChecksEnabled = it.automatic && source != UpdateInstallationSource.GOOGLE_PLAY,
            skippedVersionCode = it.skippedVersion, lastSuccessfulCheckEpochMs = it.lastSuccess,
            installationSource = source, installerPackageName = installerPackage,
            state = if (source == UpdateInstallationSource.DIRECT) UpdateState.Idle
                else UpdateState.ManagedExternally(source, installerPackage))
    })
    override val snapshot = mutable.asStateFlow()
    private val apk = File(directory, "update.apk")
    private val partial = File(directory, "update.part")

    private fun runOperation(block: suspend () -> Unit) {
        // Acquire before dispatch, so repeated taps cannot queue duplicate work.
        if (!operation.tryLock()) return
        scope.launch {
            try {
                if (!initialized) { delete(partial); delete(apk); initialized = true }
                block()
            }
            catch (failure: CancellationException) { throw failure }
            catch (failure: UpdateOperationException) { state(UpdateState.Failed(failure.reason, currentRelease())) }
            catch (failure: IOException) { state(UpdateState.Failed(UpdateFailure.STORAGE_UNAVAILABLE, currentRelease())) }
            finally { operation.unlock() }
        }
    }

    private fun state(value: UpdateState) { mutable.update { it.copy(state = value) } }
    private fun updatePreferences(change: (UpdatePreferences) -> UpdatePreferences) = synchronized(preferencesLock) {
        val next = change(preferences.read())
        preferences.write(next)
        mutable.update { it.copy(automaticChecksEnabled = next.automatic, skippedVersionCode = next.skippedVersion,
            lastSuccessfulCheckEpochMs = next.lastSuccess) }
    }

    /** #70 may claim the daily request and supply its response through acceptUsageResponse. */
    fun onForeground(usageRequestExpected: Boolean = false) = runOperation {
        val ready = snapshot.value.state as? UpdateState.ReadyToInstall
        if (ready != null) { state(ready.copy(requiresInstallPermission = !installer.canInstall())); return@runOperation }
        if (!usageRequestExpected && automaticCheckDue(clock(), preferences.read()) && !isInstalling()) check(automatic = true)
    }

    fun acceptUsageResponse(manifest: String) = runOperation {
        if (snapshot.value.installationSource == UpdateInstallationSource.GOOGLE_PLAY || !preferences.read().automatic || isInstalling() || snapshot.value.state is UpdateState.ReadyToInstall) return@runOperation
        accept(parse(manifest, snapshot.value.channel))
    }

    override fun checkNow() = runOperation { if (!isInstalling()) check(automatic = false) }

    private suspend fun check(automatic: Boolean) {
        if (snapshot.value.installationSource == UpdateInstallationSource.GOOGLE_PLAY) return
        if (automatic) updatePreferences { it.copy(lastAutomaticAttempt = clock()) }
        state(UpdateState.Checking)
        val text = try { transport.manifest(endpoint) }
            catch (failure: IOException) { throw UpdateOperationException(UpdateFailure.NETWORK_UNAVAILABLE, failure) }
        accept(parse(text, snapshot.value.channel))
    }

    private fun accept(release: UpdateRelease?) {
        updatePreferences { it.copy(lastSuccess = clock()) }
        state(when {
            release == null || release.versionCode <= installed.versionCode -> UpdateState.UpToDate
            release.minimumAndroidApi > androidApi -> UpdateState.Failed(UpdateFailure.UNSUPPORTED_ANDROID_VERSION, release)
            else -> UpdateState.Available(release, release.versionCode == preferences.read().skippedVersion)
        })
    }

    override fun skipVersion(versionCode: Long) = runOperation {
        val available = snapshot.value.state as? UpdateState.Available ?: return@runOperation
        if (available.release.versionCode != versionCode) return@runOperation
        updatePreferences { it.copy(skippedVersion = versionCode) }
        state(available.copy(skipped = true))
    }

    override fun setAutomaticChecksEnabled(enabled: Boolean) {
        val request = automaticSettingRequest.incrementAndGet()
        scope.launch {
            try {
                synchronized(preferencesLock) {
                    if (request == automaticSettingRequest.get() && snapshot.value.installationSource != UpdateInstallationSource.GOOGLE_PLAY)
                        updatePreferences { it.copy(automatic = enabled) }
                }
            } catch (failure: UpdateOperationException) { state(UpdateState.Failed(failure.reason, currentRelease())) }
        }
    }

    override fun downloadUpdate() = runOperation {
        if (snapshot.value.installationSource == UpdateInstallationSource.GOOGLE_PLAY) return@runOperation
        val phase = snapshot.value.state
        val release = when (phase) {
            is UpdateState.Available -> phase.release
            is UpdateState.Failed -> phase.release
            else -> null
        } ?: return@runOperation
        if (release.minimumAndroidApi > androidApi) return@runOperation
        if (!directory.isDirectory && !directory.mkdirs()) throw UpdateOperationException(UpdateFailure.STORAGE_UNAVAILABLE)
        delete(apk)
        var lastFailure = UpdateFailure.DOWNLOAD_FAILED
        for (url in release.apkUrls) {
            delete(partial)
            try {
                state(UpdateState.Downloading(release, 0, null))
                transport.download(url, partial) { bytes, total -> state(UpdateState.Downloading(release, bytes, total)) }
                state(UpdateState.Verifying(release))
                verify(partial, release)
                if (!partial.renameTo(apk)) throw UpdateOperationException(UpdateFailure.STORAGE_UNAVAILABLE)
                state(UpdateState.ReadyToInstall(release, !installer.canInstall()))
                return@runOperation
            } catch (failure: UpdateOperationException) {
                lastFailure = failure.reason
                if (failure.reason in setOf(UpdateFailure.INSUFFICIENT_STORAGE, UpdateFailure.STORAGE_UNAVAILABLE)) throw failure
            } catch (_: IOException) { lastFailure = UpdateFailure.DOWNLOAD_FAILED }
            finally { delete(partial) }
        }
        state(UpdateState.Failed(lastFailure, release))
    }

    private fun verify(file: File, release: UpdateRelease) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256)
            throw UpdateOperationException(UpdateFailure.HASH_MISMATCH)
        validateApkIdentity(installed, installer.archiveIdentity(file), release)
    }

    override fun installUpdate() = runOperation {
        val ready = snapshot.value.state as? UpdateState.ReadyToInstall ?: return@runOperation
        if (!installer.canInstall()) { installer.requestPermission(); return@runOperation }
        state(UpdateState.Verifying(ready.release))
        try {
            verify(apk, ready.release)
            state(UpdateState.Installing(ready.release))
            installer.install(apk, ready.release)
        } finally { delete(apk) }
    }

    fun installationResult(release: UpdateRelease, failure: UpdateFailure?) {
        state(if (failure == null) UpdateState.UpToDate else UpdateState.Failed(failure, release))
    }

    fun restoreInstallation(release: UpdateRelease) { state(UpdateState.Installing(release)) }
    fun initializationFailure(reason: UpdateFailure) { state(UpdateState.Failed(reason, currentRelease())) }

    private fun currentRelease(): UpdateRelease? = when (val state = snapshot.value.state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Verifying -> state.release
        is UpdateState.ReadyToInstall -> state.release
        is UpdateState.Installing -> state.release
        is UpdateState.Failed -> state.release
        else -> null
    }
    private fun isInstalling() = snapshot.value.state is UpdateState.Installing
    private fun delete(file: File) {
        if (file.exists() && !file.delete()) throw UpdateOperationException(UpdateFailure.STORAGE_UNAVAILABLE)
    }
}
