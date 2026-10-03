package io.github.sumirenokai.vesqen.updates

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

internal class AndroidUpdateInstaller(private val context: Context) : UpdateInstaller {
    private val manager = context.packageManager
    private val sessions = manager.packageInstaller
    private val pending = context.getSharedPreferences("update_install", Context.MODE_PRIVATE)
    var onResult: (UpdateRelease, UpdateFailure?) -> Unit = { _, _ -> }

    override fun canInstall(): Boolean = manager.canRequestPackageInstalls()
    override fun requestPermission() {
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (failure: android.content.ActivityNotFoundException) {
            throw UpdateOperationException(UpdateFailure.INSTALL_FAILED, failure)
        }
    }

    override fun archiveIdentity(apk: File): ApkIdentity =
        manager.getPackageArchiveInfo(apk.absolutePath, signatureFlags(archive = true))?.identity()
            ?: throw UpdateOperationException(UpdateFailure.INVALID_APK)

    fun installedIdentity(): ApkIdentity = manager.getPackageInfo(context.packageName, signatureFlags()).identity()

    override fun install(apk: File, release: UpdateRelease) {
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        var sessionId: Int? = null
        try {
            sessionId = sessions.createSession(parameters)
            val token = UUID.randomUUID().toString()
            // Record ownership before copying bytes, so process death leaves a reclaimable session.
            if (!pending.edit().putInt("session", sessionId).putString("token", token)
                    .putString("release", UpdateManifest.encodeRelease(release)).commit())
                throw UpdateOperationException(UpdateFailure.STORAGE_UNAVAILABLE)
            sessions.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, UpdateInstallActivity::class.java).apply {
                    data = Uri.parse("vesqen-update://install/$token")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
                    // Delegate only to the system installer, for this explicit user command.
                    val mode = if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                        else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    setPendingIntentCreatorBackgroundActivityStartMode(mode)
                }.toBundle() else null
                val result = PendingIntent.getActivity(context, sessionId, callback, flags, options)
                session.commit(result.intentSender)
            }
        } catch (failure: IOException) {
            abandon(sessionId)
            throw UpdateOperationException(UpdateFailure.INSTALL_FAILED, failure)
        } catch (failure: SecurityException) {
            abandon(sessionId)
            throw UpdateOperationException(UpdateFailure.INSTALL_FAILED, failure)
        } catch (failure: UpdateOperationException) {
            abandon(sessionId)
            throw failure
        }
    }

    private fun abandon(sessionId: Int?) {
        if (sessionId != null && sessions.getSessionInfo(sessionId) != null) sessions.abandonSession(sessionId)
        if (!pending.edit().clear().commit()) throw UpdateOperationException(UpdateFailure.STORAGE_UNAVAILABLE)
    }

    fun savedRelease(): UpdateRelease? {
        val text = pending.getString("release", null) ?: return null
        return try { UpdateManifest.readRelease(JSONObject(text)) }
            catch (failure: JSONException) { throw UpdateOperationException(UpdateFailure.INVALID_MANIFEST, failure) }
            catch (failure: IllegalArgumentException) { throw UpdateOperationException(UpdateFailure.INVALID_MANIFEST, failure) }
    }

    fun activeSession(): Boolean = sessions.getSessionInfo(pending.getInt("session", -1))?.isSealed == true
    fun accepts(intent: Intent): Boolean = pending.contains("token") &&
        intent.data?.toString() == "vesqen-update://install/${pending.getString("token", null)}" &&
        intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == pending.getInt("session", -2)

    fun finish(failure: UpdateFailure?) {
        val release = savedRelease() ?: return
        abandon(pending.getInt("session", -1).takeIf { it >= 0 })
        onResult(release, failure)
    }

    private fun signatureFlags(archive: Boolean = false): Int {
        @Suppress("DEPRECATION")
        val legacy = PackageManager.GET_SIGNATURES
        if (Build.VERSION.SDK_INT < 28) return legacy
        // Android 9's getPackageArchiveInfo collects certificates only when GET_SIGNATURES is
        // present. Also request SigningInfo so we compare current signers, not rotation history.
        return PackageManager.GET_SIGNING_CERTIFICATES or
            if (archive && Build.VERSION.SDK_INT == 28) legacy else 0
    }

    private fun PackageInfo.identity(): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else {
            @Suppress("DEPRECATION") signatures
        }
        return ApkIdentity(packageName, if (Build.VERSION.SDK_INT >= 28) longVersionCode else {
            @Suppress("DEPRECATION") versionCode.toLong()
        }, signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet())
    }
}

/** An explicit, non-exported system-install callback; it contains no app dialog or user copy. */
class UpdateInstallActivity : Activity() {
    private val installer get() = (application as io.github.sumirenokai.vesqen.VesqenApplication).updateRuntime.installer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!installer.accepts(intent)) { finish(); return }
        if (savedInstanceState != null) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) { installer.finish(UpdateFailure.INSTALL_FAILED); finish() }
                else {
                    @Suppress("DEPRECATION")
                    startActivityForResult(confirmation, 1)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { installer.finish(null); finish() }
            PackageInstaller.STATUS_FAILURE_ABORTED -> { installer.finish(UpdateFailure.INSTALL_CANCELLED); finish() }
            PackageInstaller.STATUS_FAILURE_STORAGE -> { installer.finish(UpdateFailure.INSUFFICIENT_STORAGE); finish() }
            else -> { installer.finish(UpdateFailure.INSTALL_FAILED); finish() }
        }
    }

    @Deprecated("System installer result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_CANCELED && installer.accepts(intent))
            installer.finish(UpdateFailure.INSTALL_CANCELLED)
        finish()
    }
}
