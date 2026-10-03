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
        var manifestFailure: UpdateFailure? = null
        var failedMirror: String? = null
        var gate: CompletableDeferred<Unit>? = null
        var corruptMirror: String? = null
        override suspend fun manifest(url: String): String {
            checks++
            gate?.await()
            if (offline) throw IOException("private endpoint must not reach state")
            manifestFailure?.let { throw UpdateOperationException(it) }
            return "manifest"
        }
        override suspend fun download(url: String, target: File, progress: (Long, Long?) -> Unit) {
            urls += url
            target.writeBytes(if (url == corruptMirror) byteArrayOf(1) else bytes)
            if (url == failedMirror) throw IOException("network down")
            progress(bytes.size.toLong(), bytes.size.toLong())
        }
    }
    private fun updater(candidate: UpdateRelease? = release, source: UpdateInstallationSource = UpdateInstallationSource.DIRECT,
                        parseFailure: UpdateFailure? = null) =
        GitHubAppUpdater(scope, store, transport, installer, ApkIdentity("app", 10, setOf("signer")), 35,
            directory, "https://example.org/beta.json", UpdateChannel.BETA, source, null, { time }, { _, _ ->
                parseFailure?.let { throw UpdateOperationException(it) }; candidate
            })

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
        assertEquals(time, store.value.lastAutomaticAttempt)
        assertEquals(UpdateState.Idle, updater.snapshot.value.state)
        updater.checkNow()
        assertEquals(2, transport.checks)
        assertEquals(UpdateState.Failed(UpdateFailure.NETWORK_UNAVAILABLE), updater.snapshot.value.state)
    }

    @Test fun automaticFailuresPreserveAvailableSkippedAndUpToDateStates() = runBlocking {
        for (candidate in listOf(release, null)) {
            for (reason in listOf(UpdateFailure.NETWORK_UNAVAILABLE, UpdateFailure.INVALID_MANIFEST)) {
                store.value = UpdatePreferences(true)
                transport.offline = false
                transport.manifestFailure = null
                transport.gate = null
                val updater = updater(candidate)
                updater.checkNow()
                updater.skipVersion(11)
                val previous = updater.snapshot.value
                val success = time
                time += UPDATE_CHECK_INTERVAL_MS + 1
                transport.offline = reason == UpdateFailure.NETWORK_UNAVAILABLE
                transport.manifestFailure = reason.takeUnless { transport.offline }
                transport.gate = CompletableDeferred()
                updater.onForeground()
                assertEquals("Automatic checks must not replace prior state with Checking", previous, updater.snapshot.value)
                transport.gate!!.complete(Unit)
                assertEquals(previous, updater.snapshot.value)
                assertEquals(success, store.value.lastSuccess)
                assertEquals(time, store.value.lastAutomaticAttempt)
                val checks = transport.checks
                updater.onForeground()
                assertEquals(checks, transport.checks)
                updater.checkNow()
                assertEquals(UpdateState.Failed(reason), updater.snapshot.value.state)
            }
        }
    }

    @Test fun automaticFailureDoesNotReplaceAnEarlierActionFailure() {
        val updater = updater()
        updater.initializationFailure(UpdateFailure.INSTALL_FAILED)
        val previous = updater.snapshot.value.state
        transport.offline = true
        updater.onForeground()
        assertEquals(previous, updater.snapshot.value.state)
        assertEquals(time, store.value.lastAutomaticAttempt)
        assertNull(store.value.lastSuccess)
    }

    @Test fun malformedManifestsAreSilentOnlyForAutomaticAndSharedUsageChecks() {
        val updater = updater(parseFailure = UpdateFailure.INVALID_MANIFEST)
        updater.onForeground()
        assertEquals(UpdateState.Idle, updater.snapshot.value.state)
        assertEquals(time, store.value.lastAutomaticAttempt)
        assertNull(store.value.lastSuccess)
        time++
        updater.acceptUsageResponse("malformed")
        assertEquals(UpdateState.Idle, updater.snapshot.value.state)
        assertEquals(time, store.value.lastAutomaticAttempt)
        assertNull(store.value.lastSuccess)
        updater.checkNow()
        assertEquals(UpdateState.Failed(UpdateFailure.INVALID_MANIFEST), updater.snapshot.value.state)
    }

    @Test fun unsupportedReleaseIsSilentForAutomaticChecksAndDoesNotClaimSuccess() {
        val candidate = release.copy(minimumAndroidApi = 36)
        val updater = updater(candidate)
        updater.onForeground()
        assertEquals(UpdateState.Idle, updater.snapshot.value.state)
        assertEquals(time, store.value.lastAutomaticAttempt)
        assertNull(store.value.lastSuccess)
        updater.checkNow()
        assertEquals(UpdateState.Failed(UpdateFailure.UNSUPPORTED_ANDROID_VERSION, candidate), updater.snapshot.value.state)
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

    @Test fun latestAutomaticSettingWinsEvenIfIoDispatchOrderChanges() {
        val queued = ArrayDeque<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        val updater = GitHubAppUpdater(CoroutineScope(scope.coroutineContext + dispatcher), store, transport, installer,
            ApkIdentity("app", 10, setOf("signer")), 35, directory, "https://example.org/beta.json", UpdateChannel.BETA,
            UpdateInstallationSource.DIRECT, null, { time }, { _, _ -> release })
        updater.setAutomaticChecksEnabled(false)
        updater.setAutomaticChecksEnabled(true)
        queued.removeLast().run()
        queued.removeFirst().run()
        assertTrue(store.value.automatic)
        assertTrue(updater.snapshot.value.automaticChecksEnabled)
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
        val play = updater(source = UpdateInstallationSource.GOOGLE_PLAY); play.checkNow(); play.downloadUpdate(); play.acceptUsageResponse("manifest")
        assertTrue(play.snapshot.value.state is UpdateState.ManagedExternally)
        assertFalse(play.snapshot.value.automaticChecksEnabled)
        assertTrue(transport.urls.isEmpty())
    }

    @Test fun onlyKnownStoresAndUpdatersOwnUpdates() {
        assertEquals(UpdateInstallationSource.GOOGLE_PLAY, classifyInstaller("com.android.vending"))
        for (installer in listOf("org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify",
            "com.machiav3lli.fdroid", "dev.imranr.obtainium", "dev.imranr.obtainium.fdroid", "com.aurora.store")) {
            assertEquals(installer, UpdateInstallationSource.OTHER_UPDATER, classifyInstaller(installer))
        }
    }

    @Test fun genericAndUnknownInstallersRemainDirectInstalls() {
        for (installer in listOf(null, "", "com.android.shell", "com.android.packageinstaller",
            "com.google.android.packageinstaller", "com.google.android.permissioncontroller",
            "com.android.permissioncontroller", "com.miui.packageinstaller", "com.samsung.android.packageinstaller",
            "com.vivo.packageinstaller", "com.huawei.appmarket.packageinstaller", "com.android.chrome",
            "org.mozilla.firefox", "com.google.android.documentsui", "com.mi.android.globalFileexplorer",
            "unknown.installer", "com.aurora.store.unrecognized")) {
            assertEquals(installer, UpdateInstallationSource.DIRECT, classifyInstaller(installer))
        }
        assertEquals(UpdateInstallationSource.DIRECT, classifyInstaller("app", "app"))
    }

    @Test fun notesLinksHaveExplicitBoundaries() {
        assertTrue(isAllowedUpdateNotesLink("https://vesqen.sumirenokai.com/privacy/"))
        assertTrue(isAllowedUpdateNotesLink("https://github.com/Sumire-no-kai/Vesqen"))
        for (url in listOf("http://github.com/", "https://github.com.evil.test/", "https://user@github.com/", "javascript:alert(1)"))
            assertFalse(url, isAllowedUpdateNotesLink(url))
    }
}
