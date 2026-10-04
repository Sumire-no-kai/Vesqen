package io.github.sumirenokai.vesqen.usage

import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import io.github.sumirenokai.vesqen.BuildConfig
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** GitHub composition root. #47 can move this source set into the distribution flavor. */
internal class AndroidUsageRuntime(application: Application, scope: CoroutineScope, acceptUpdate: (String) -> Unit) {
    private val audio = application.getSystemService(AudioManager::class.java)
    private val engine = DefaultUsageStatistics(
        CoroutineScope(scope.coroutineContext + Dispatchers.IO), AndroidUsagePreferences(application),
        region = { usageRegion(application) }, endpointConfigured = BuildConfig.USAGE_ENDPOINT.isNotBlank(),
        online = { online(application) }, facts = {
            UsageFacts(BuildConfig.VERSION_NAME, "github", Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL,
                Build.DISPLAY, bitPerfectMixer(audio))
        }, transport = HttpsUsageTransport(BuildConfig.USAGE_ENDPOINT), acceptUpdate = acceptUpdate,
    )
    val statistics: UsageStatistics get() = engine
    suspend fun onForeground(): Boolean = engine.onForeground()
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { it.isSink && it.type in USB_TYPES }) engine.recordUsbAudioConnection()
        }
    }
    init { audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }

    companion object { private val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET) }
}

private fun usageRegion(context: Context): UsageRegionPolicy {
    val locales = android.content.res.Resources.getSystem().configuration.locales
    val system = (0 until locales.size()).map { locales[it].country }
    val telephone = context.getSystemService(TelephonyManager::class.java)
    var simReadComplete = true
    val sim = try { telephone?.simCountryIso.orEmpty() }
        catch (_: SecurityException) { simReadComplete = false; "" }
        catch (_: UnsupportedOperationException) { simReadComplete = false; "" }
    // Public default-subscription country only; no phone-state permission, identifiers or hidden APIs.
    val allSimsObservable = try {
        @Suppress("DEPRECATION")
        (if (Build.VERSION.SDK_INT >= 30) telephone?.activeModemCount else telephone?.phoneCount) in listOf(null, 0, 1)
    } catch (_: SecurityException) { false } catch (_: UnsupportedOperationException) { false }
    return UsageRegionRules.evaluate(system, listOf(sim), allSimsObservable && simReadComplete)
}

private fun online(context: Context): Boolean = try {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    manager.getNetworkCapabilities(manager.activeNetwork)?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true
} catch (_: SecurityException) { false }

private fun bitPerfectMixer(audio: AudioManager): Boolean? {
    if (Build.VERSION.SDK_INT < 34) return null
    return try {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outputs.isEmpty()) null else outputs.any { output ->
            audio.getSupportedMixerAttributes(output).any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        }
    } catch (_: SecurityException) { null }
      catch (_: UnsupportedOperationException) { null }
      catch (_: IllegalStateException) { null }
      catch (_: IllegalArgumentException) { null }
}

private class AndroidUsagePreferences(context: Context) : UsagePreferencesStore {
    private val preferences = context.getSharedPreferences("usage_ping", Context.MODE_PRIVATE)
    override fun read(): UsagePreferences? {
        if (!preferences.contains("enabled")) return null
        return UsagePreferences(preferences.getBoolean("enabled", false),
            UsageConsent.entries.firstOrNull { it.name == preferences.getString("consent", null) } ?: UsageConsent.UNDECIDED,
            preferences.getBoolean("introduction", false), preferences.getLong("attempt", -1).takeIf { it >= 0 },
            preferences.getLong("usb", -1).takeIf { it >= 0 })
    }
    override fun write(value: UsagePreferences) {
        if (!preferences.edit().putBoolean("enabled", value.enabled).putString("consent", value.consent.name)
            .putBoolean("introduction", value.introductionCompleted).putLong("attempt", value.lastAttemptEpochMs ?: -1)
            .putLong("usb", value.lastUsbEpochMs ?: -1).commit()) throw IOException("Usage preferences unavailable")
    }
}
