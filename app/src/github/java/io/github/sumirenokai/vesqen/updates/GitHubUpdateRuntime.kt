package io.github.sumirenokai.vesqen.updates

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import io.github.sumirenokai.vesqen.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Distribution composition root. Move this source set to the GitHub flavor when #47 lands. */
class GitHubUpdateRuntime(application: Application, scope: CoroutineScope) {
    internal val installer = AndroidUpdateInstaller(application)
    private val source = installationSource(application)
    private val directory = File(application.noBackupFilesDir, "updates")
    private val engine = GitHubAppUpdater(scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        preferences = AndroidUpdatePreferences(application, source.first == UpdateInstallationSource.DIRECT),
        transport = HttpsUpdateTransport(allocatableBytes = { path ->
            val storage = application.getSystemService(StorageManager::class.java)
            storage.getAllocatableBytes(storage.getUuidForPath(path))
        }), installer = installer, installed = installer.installedIdentity(),
        androidApi = Build.VERSION.SDK_INT, directory = directory,
        endpoint = BuildConfig.UPDATE_MANIFEST_BASE_URL + defaultUpdateChannel(BuildConfig.VERSION_NAME).name.lowercase() + ".json",
        channel = defaultUpdateChannel(BuildConfig.VERSION_NAME), source = source.first, installerPackage = source.second)
    val updater: AppUpdater get() = engine

    /** #70 sets this when its daily request will provide the manifest; no separate GET then. */
    var usageRequestExpected: suspend () -> Boolean = { false }
    fun acceptUsageResponse(manifest: String) = engine.acceptUsageResponse(manifest)

    init {
        installer.onResult = engine::installationResult
        try {
            installer.savedRelease()?.let { release ->
                if (installer.activeSession()) engine.restoreInstallation(release)
                else installer.finish(if (installer.installedIdentity().versionCode >= release.versionCode) null
                    else UpdateFailure.INSTALL_FAILED)
            }
        } catch (failure: UpdateOperationException) { engine.initializationFailure(failure.reason) }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (activity is UpdateInstallActivity) return
                if (started++ == 0) scope.launch { engine.onForeground(usageRequestExpected()) }
            }
            override fun onActivityStopped(activity: Activity) {
                if (activity !is UpdateInstallActivity) started--
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

internal fun installationSource(context: Context): Pair<UpdateInstallationSource, String?> = try {
    val installer = if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else { @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName) }
    classifyInstaller(installer, context.packageName) to installer
} catch (_: PackageManager.NameNotFoundException) { UpdateInstallationSource.DIRECT to null }
  catch (_: SecurityException) { UpdateInstallationSource.DIRECT to null }

internal fun classifyInstaller(installer: String?, ownPackage: String? = null): UpdateInstallationSource = when (installer) {
    ownPackage, null -> UpdateInstallationSource.DIRECT
    "com.android.vending" -> UpdateInstallationSource.GOOGLE_PLAY
    "org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify", "com.machiav3lli.fdroid",
    "dev.imranr.obtainium", "dev.imranr.obtainium.fdroid", "com.aurora.store" -> UpdateInstallationSource.OTHER_UPDATER
    // An installer can open an APK without taking responsibility for future updates.
    else -> UpdateInstallationSource.DIRECT
}

private class AndroidUpdatePreferences(context: Context, private val defaultAutomatic: Boolean) : UpdatePreferencesStore {
    private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    override fun read() = UpdatePreferences(preferences.getBoolean("automatic", defaultAutomatic),
        preferences.getLong("skipped", -1).takeIf { it > 0 }, preferences.getLong("success", -1).takeIf { it >= 0 },
        preferences.getLong("attempt", -1).takeIf { it >= 0 })
    override fun write(value: UpdatePreferences) {
        if (!preferences.edit().putBoolean("automatic", value.automatic).putLong("skipped", value.skippedVersion ?: -1)
                .putLong("success", value.lastSuccess ?: -1).putLong("attempt", value.lastAutomaticAttempt ?: -1).commit())
            throw UpdateOperationException(UpdateFailure.STORAGE_UNAVAILABLE)
    }
}
