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
import io.github.sumirenokai.vesqen.service.ServiceSwitchGate
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** GitHub composition root. #47 can move this source set into the distribution flavor. */
internal class AndroidUsageRuntime(
    application: Application,
    scope: CoroutineScope,
    acceptUpdate: (String) -> Unit,
    missingUpdate: () -> Unit,
    serviceSwitch: ServiceSwitchGate,
) {
    private val audio = application.getSystemService(AudioManager::class.java)
    private val engine = DefaultUsageStatistics(
        CoroutineScope(scope.coroutineContext + Dispatchers.IO), AndroidUsagePreferences(application),
        region = { usageRegion(application) }, endpointConfigured = BuildConfig.USAGE_ENDPOINT.isNotBlank(),
        online = { online(application) }, facts = {
            UsageFacts(BuildConfig.VERSION_NAME, "github", usageText(Build.VERSION.RELEASE), usageText(Build.MANUFACTURER),
                usageText(Build.MODEL), usageText(Build.DISPLAY), bitPerfectMixer(audio))
        }, transport = HttpsUsageTransport(BuildConfig.USAGE_ENDPOINT), acceptUpdate = acceptUpdate,
        missingUpdate = missingUpdate, serviceSwitch = serviceSwitch,
    )
    val statistics: UsageStatistics get() = engine
    suspend fun onForeground(): Boolean = engine.onForeground()
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { it.isSink && it.type in usbAudioTypes }) engine.recordUsbAudioConnection()
        }
    }
    init { audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }

}

private val usbAudioTypes = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET)

/**
 * System locales, the default subscription's SIM and network country, and from Android 11 every
 * slot's network country. Public country codes only: no phone-state permission or identifiers.
 * Android 8-10 cannot read the other slots, so a second SIM there is covered by locale and network.
 */
private fun usageRegion(context: Context): UsageRegionPolicy {
    val locales = android.content.res.Resources.getSystem().configuration.locales
    val countries = buildList {
        (0 until locales.size()).mapTo(this) { locales[it].country }
        context.getSystemService(TelephonyManager::class.java)?.let { telephony ->
            telephonyCountry { telephony.simCountryIso }?.let(::add)
            telephonyCountry { telephony.networkCountryIso }?.let(::add)
            if (Build.VERSION.SDK_INT >= 30) {
                val slots = try { telephony.activeModemCount } catch (_: UnsupportedOperationException) { 0 }
                for (slot in 0 until slots) telephonyCountry { telephony.getNetworkCountryIso(slot) }?.let(::add)
            }
        }
    }
    return UsageRegionRules.evaluate(countries)
}

private inline fun telephonyCountry(read: () -> String?): String? = try { read() }
    catch (_: SecurityException) { null }
    catch (_: UnsupportedOperationException) { null }
    catch (_: IllegalArgumentException) { null }

private fun online(context: Context): Boolean = try {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    manager.getNetworkCapabilities(manager.activeNetwork)?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true
} catch (_: SecurityException) { false }

private fun bitPerfectMixer(audio: AudioManager): Boolean? {
    if (Build.VERSION.SDK_INT < 34) return null
    return try {
        // Only a USB output can offer the bit-perfect mixer; without one there is nothing to observe.
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in usbAudioTypes }
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
