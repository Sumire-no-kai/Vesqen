package io.github.sumirenokai.vesqen.updates

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class GitHubAppUpdaterTest {
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val directory = Files.createTempDirectory("update-test").toFile()
    private val bytes = "test apk".toByteArray()
    private val release = UpdateRelease("1.0.0-beta.2", 11, 26,
        listOf("https://example.org/one.apk", "https://example.org/two.apk"),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        mapOf(UpdateLanguage.ENGLISH to "Notes", UpdateLanguage.SIMPLIFIED_CHINESE to "说明"))
    private var time = 100L
    private val store = object : UpdatePreferencesStore {
        var value = UpdatePreferences(true)
        override fun read() = value
        override fun write(value: UpdatePreferences) { this.value = value }
    }
    private val installer = object : UpdateInstaller {
        var permission = true
        var requests = 0
        var installs = 0
        var identity = ApkIdentity("app", 11, setOf("signer"))
        override fun canInstall() = permission
        override fun requestPermission() { requests++ }
        override fun archiveIdentity(apk: File) = identity
        override fun install(apk: File, release: UpdateRelease) { installs++ }
    }
    private val transport = object : UpdateTransport {
        var checks = 0
        var urls = mutableListOf<String>()
        var offline = false
        var failedMirror: String? = null
        var gate: CompletableDeferred<Unit>? = null
        var corruptMirror: String? = null
        override suspend fun manifest(url: String): String {
            checks++
            gate?.await()
            if (offline) throw IOException("private endpoint must not reach state")
            return "manifest"
        }
        override suspend fun download(url: String, target: File, progress: (Long, Long?) -> Unit) {
            urls += url
            target.writeBytes(if (url == corruptMirror) byteArrayOf(1) else bytes)
            if (url == failedMirror) throw IOException("network down")
            progress(bytes.size.toLong(), bytes.size.toLong())
        }
    }
    private fun updater(candidate: UpdateRelease? = release, source: UpdateInstallationSource = UpdateInstallationSource.DIRECT) =
        GitHubAppUpdater(scope, store, transport, installer, ApkIdentity("app", 10, setOf("signer")), 35,
            directory, "https://example.org/beta.json", UpdateChannel.BETA, source, null, { time }, { _, _ -> candidate })

    @After fun cleanup() { scope.cancel(); directory.deleteRecursively() }

    @Test fun automaticCheckIsDailyManualCheckBypassesThrottleAndSkipPersists() {
        val updater = updater()
        updater.onForeground(); updater.onForeground()
        assertEquals(1, transport.checks)
        updater.skipVersion(11)
        assertEquals(11L, store.value.skippedVersion)
        updater.checkNow()
        assertEquals(2, transport.checks)
        assertTrue((updater.snapshot.value.state as UpdateState.Available).skipped)
        time += UPDATE_CHECK_INTERVAL_MS
        updater.onForeground(); assertEquals(2, transport.checks)
        time++; updater.onForeground(); assertEquals(3, transport.checks)
        time = 0; updater.onForeground(); assertEquals(3, transport.checks)
    }

    @Test fun offlineAttemptIsThrottledButDoesNotClaimSuccessfulCheck() {
        transport.offline = true
        val updater = updater()
        updater.onForeground(); updater.onForeground()
        assertEquals(1, transport.checks)
        assertNull(store.value.lastSuccess)
        assertEquals(UpdateState.Failed(UpdateFailure.NETWORK_UNAVAILABLE), updater.snapshot.value.state)
    }

    @Test fun usageResponseSharesTheManifestWithoutAnotherRequest() {
        val updater = updater()
        updater.onForeground(usageRequestExpected = true)
        assertEquals(0, transport.checks)
        updater.acceptUsageResponse("manifest")
        assertEquals(time, store.value.lastSuccess)
        updater.onForeground(); assertEquals(0, transport.checks)
    }

    @Test fun repeatedTapsCannotQueueRequestsAndSwitchCanChangeDuringRequest() = runBlocking {
        transport.gate = CompletableDeferred()
        val updater = updater()
        updater.checkNow(); updater.checkNow(); updater.downloadUpdate()
        updater.setAutomaticChecksEnabled(false)
        assertFalse(updater.snapshot.value.automaticChecksEnabled)
        assertEquals(1, transport.checks)
        transport.gate!!.complete(Unit)
        assertEquals(1, transport.checks)
    }

    @Test fun mirrorsAreTriedInOrderAndEveryMirrorIsVerified() {
        for (corrupt in listOf(false, true)) {
            transport.urls.clear()
            transport.failedMirror = if (corrupt) null else release.apkUrls[0]
            transport.corruptMirror = if (corrupt) release.apkUrls[0] else null
            val updater = updater()
            updater.checkNow(); updater.downloadUpdate()
            assertEquals(release.apkUrls, transport.urls)
            assertTrue(updater.snapshot.value.state is UpdateState.ReadyToInstall)
            assertFalse(File(directory, "update.part").exists())
        }
    }

    @Test fun wrongSignerAndWrongVersionNeverReachInstaller() {
        for ((identity, reason) in listOf(
            ApkIdentity("other.app", 11, setOf("signer")) to UpdateFailure.PACKAGE_MISMATCH,
            ApkIdentity("app", 10, setOf("signer")) to UpdateFailure.VERSION_MISMATCH,
            ApkIdentity("app", 12, setOf("signer")) to UpdateFailure.VERSION_MISMATCH,
            ApkIdentity("app", 11, setOf("other")) to UpdateFailure.SIGNATURE_MISMATCH,
            ApkIdentity("app", 11, emptySet()) to UpdateFailure.SIGNATURE_MISMATCH)) {
            installer.identity = identity
            val updater = updater()
            updater.checkNow(); updater.downloadUpdate(); updater.installUpdate()
            assertEquals(UpdateState.Failed(reason, release), updater.snapshot.value.state)
            assertFalse(File(directory, "update.part").exists())
            assertFalse(File(directory, "update.apk").exists())
            assertEquals(0, installer.installs)
        }
    }

    @Test fun permissionRequiresExplicitSecondInstallAndApkIsRechecked() {
        installer.permission = false
        val updater = updater()
        updater.checkNow(); updater.downloadUpdate(); updater.installUpdate()
        assertEquals(1, installer.requests); assertEquals(0, installer.installs)
        installer.permission = true
        updater.onForeground(); assertEquals(0, installer.installs)
        File(directory, "update.apk").writeText("modified")
        updater.installUpdate()
        assertEquals(UpdateState.Failed(UpdateFailure.HASH_MISMATCH, release), updater.snapshot.value.state)
        assertEquals(0, installer.installs)
        assertFalse(File(directory, "update.apk").exists())
    }

    @Test fun downloadAndInstallRequireSeparateUserCommands() {
        val updater = updater()
        updater.checkNow(); assertTrue(transport.urls.isEmpty())
        updater.downloadUpdate(); assertEquals(0, installer.installs)
        updater.installUpdate(); assertEquals(1, installer.installs)
        assertTrue(updater.snapshot.value.state is UpdateState.Installing)
        updater.installationResult(release, UpdateFailure.INSTALL_CANCELLED)
        assertEquals(UpdateState.Failed(UpdateFailure.INSTALL_CANCELLED, release), updater.snapshot.value.state)
    }

    @Test fun oldVersionsUnsupportedApiAndPlayInstallCannotDownload() {
        val old = updater(release.copy(versionCode = 10)); old.checkNow(); old.downloadUpdate()
        assertEquals(UpdateState.UpToDate, old.snapshot.value.state)
        val unsupported = updater(release.copy(minimumAndroidApi = 36)); unsupported.checkNow(); unsupported.downloadUpdate()
        assertTrue(unsupported.snapshot.value.state is UpdateState.Failed)
        val play = updater(source = UpdateInstallationSource.GOOGLE_PLAY); play.checkNow(); play.downloadUpdate()
        assertTrue(play.snapshot.value.state is UpdateState.ManagedExternally)
        assertTrue(transport.urls.isEmpty())
    }

    @Test fun linksAndInstallersHaveExplicitBoundaries() {
        assertEquals(UpdateInstallationSource.GOOGLE_PLAY, classifyInstaller("com.android.vending"))
        assertEquals(UpdateInstallationSource.OTHER_UPDATER, classifyInstaller("dev.imranr.obtainium"))
        assertEquals(UpdateInstallationSource.DIRECT, classifyInstaller(null))
        assertEquals(UpdateInstallationSource.DIRECT, classifyInstaller("app", "app"))
        assertTrue(isAllowedUpdateNotesLink("https://vesqen.sumirenokai.com/privacy/"))
        assertTrue(isAllowedUpdateNotesLink("https://github.com/Sumire-no-kai/Vesqen"))
        for (url in listOf("http://github.com/", "https://github.com.evil.test/", "https://user@github.com/", "javascript:alert(1)"))
            assertFalse(url, isAllowedUpdateNotesLink(url))
    }
}
