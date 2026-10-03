package io.github.sumirenokai.vesqen.updates

import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.VesqenApplication
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit lab fixture only. The real application ID and its data are never an allowed target. */
class UpdateUpgradeDeviceTest {
    @Test fun checkDownloadVerifyAndRequestSystemUpgrade() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires two isolated APKs and an ephemeral localhost TLS fixture", arguments.getString("updateQa") == "true")
        val app = instrumentation.targetContext.applicationContext as VesqenApplication
        check(app.packageName == "io.github.sumirenokai.vesqen.devicetest")
        val directory = File(app.filesDir, "update-qa")
        val candidate = File(directory, "next.apk")
        val stateFile = File(directory, "state.txt")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val installer = app.updateRuntime.installer
        val identity = installer.installedIdentity()
        val next = installer.archiveIdentity(candidate)
        File(directory, "identity.txt").writeText("installed=$identity\narchive=$next")
        assertFalse("Installed signing identity must be available", identity.signers.isEmpty())
        assertEquals("Archive current signers must match, including Android 9", identity.signers, next.signers)
        assertEquals(identity.packageName, next.packageName)
        assertTrue(next.versionCode > identity.versionCode)
        val keys = KeyStore.getInstance("PKCS12").apply {
            File(directory, "localhost.p12").inputStream().use { load(it, "local-test".toCharArray()) }
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, "local-test".toCharArray())
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val tls = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, trust.trustManagers, null) }
        val server = tls.serverSocketFactory.createServerSocket(0, 4, java.net.InetAddress.getByName("localhost")) as SSLServerSocket
        val endpoint = "https://localhost:${server.localPort}"
        val release = UpdateRelease("1.0.0-beta.2", next.versionCode, 26,
            listOf("$endpoint/missing.apk", "$endpoint/update.apk"),
            MessageDigest.getInstance("SHA-256").digest(candidate.readBytes()).joinToString("") { "%02x".format(it) },
            mapOf(UpdateLanguage.ENGLISH to "Local upgrade fixture", UpdateLanguage.SIMPLIFIED_CHINESE to "本地升级测试"))
        val manifest = JSONObject().put("schemaVersion", 1).put("channel", "beta")
            .put("release", JSONObject(UpdateManifest.encodeRelease(release))).toString().toByteArray()
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = thread(isDaemon = true, name = "update-qa-tls") {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    while (!reader.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                    requests += path
                    val body = when (path) { "/beta.json" -> manifest; "/update.apk" -> candidate.readBytes(); else -> byteArrayOf() }
                    val status = if (path == "/missing.apk") "404 Not Found" else "200 OK"
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body)
                    }
                }
            } catch (failure: Exception) { if (!server.isClosed) serverFailure.set(failure) }
        }
        val preferences = object : UpdatePreferencesStore {
            var value = UpdatePreferences(false)
            override fun read() = value
            override fun write(value: UpdatePreferences) { this.value = value }
        }
        val updater = GitHubAppUpdater(scope, preferences,
            HttpsUpdateTransport { url: URL -> (url.openConnection() as HttpsURLConnection).apply { sslSocketFactory = tls.socketFactory } },
            installer, identity, android.os.Build.VERSION.SDK_INT, File(directory, "download"), "$endpoint/beta.json",
            UpdateChannel.BETA, UpdateInstallationSource.DIRECT, null)
        installer.onResult = updater::installationResult
        scope.launch { updater.snapshot.collect { File(directory, "progress.txt").writeText(it.state.toString()) } }
        try {
            updater.checkNow()
            await {
                check(updater.snapshot.value.state !is UpdateState.Failed) { updater.snapshot.value.state.toString() }
                updater.snapshot.value.state is UpdateState.Available
            }
            updater.downloadUpdate()
            await {
                check(updater.snapshot.value.state !is UpdateState.Failed) { updater.snapshot.value.state.toString() }
                updater.snapshot.value.state is UpdateState.ReadyToInstall
            }
            assertEquals(listOf("/beta.json", "/missing.apk", "/update.apk"), requests.toList())
            assertNull(serverFailure.get())
            stateFile.writeText("VERIFIED:${identity.versionCode}->${next.versionCode}")
            updater.installUpdate()
            if (!installer.canInstall()) {
                stateFile.appendText("\nWAITING_PERMISSION")
                await { installer.canInstall() }
                // A separate explicit command models the user's second tap after system permission.
                updater.installUpdate()
            }
            await { updater.snapshot.value.state is UpdateState.Installing || updater.snapshot.value.state is UpdateState.Failed }
            stateFile.appendText("\nSYSTEM_CONFIRMATION")
            if (arguments.getString("cancelInstall") == "true") {
                await { updater.snapshot.value.state is UpdateState.Failed }
                assertEquals(UpdateState.Failed(UpdateFailure.INSTALL_CANCELLED, release), updater.snapshot.value.state)
                stateFile.appendText("\nCANCELLED")
            } else {
                // Updating the target necessarily terminates this instrumentation process. The lab
                // runner must verify the installed version and retained marker in a fresh process.
                await { updater.snapshot.value.state is UpdateState.Failed }
                fail("Upgrade did not replace the host: ${updater.snapshot.value.state}")
            }
        } finally {
            scope.cancel()
            server.close()
            serverThread.join(2_000)
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(120_000) {
        while (!condition()) delay(50)
    }
}
