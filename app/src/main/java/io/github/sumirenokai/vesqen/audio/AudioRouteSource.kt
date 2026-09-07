package io.github.sumirenokai.vesqen.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter
import android.media.MediaRouter2
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

enum class AudioOutputType {
    PHONE_SPEAKER,
    WIRED_OR_USB,
    BLUETOOTH,
    OTHER,
}

/** Android's selected media route. It remains a system-routing fact, never direct-output proof. */
data class ActiveAudioRoute(
    val name: String,
    /** Null when Android exposes the selected route name but not a reliable route type. */
    val outputType: AudioOutputType?,
)

data class AudioRouteState(
    val connectedOutputs: Set<AudioOutputType>,
    val activeRoute: ActiveAudioRoute?,
)

/**
 * Application-scope, reference-counted source shared by M1 route UI and M2 telemetry. Hardware and
 * MediaRouter callbacks are registered exactly once while at least one listener is present.
 */
class AudioRouteSource(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val listeners = linkedSetOf<(AudioRouteState) -> Unit>()
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var router2: MediaRouter2? = null
    private var router2Callback: MediaRouter2.ControllerCallback? = null
    private var legacyRouter: MediaRouter? = null
    private var legacyRouterCallback: MediaRouter.Callback? = null

    fun readState(): AudioRouteState = AudioRouteState(
        connectedOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapTo(linkedSetOf()) { device -> device.type.toOutputType() },
        activeRoute = readActiveRoute(),
    )

    fun addListener(listener: (AudioRouteState) -> Unit) {
        val initialState = synchronized(lock) {
            if (!listeners.add(listener)) return
            if (listeners.size == 1) {
                try {
                    startInfrastructure()
                } catch (failure: Exception) {
                    listeners.remove(listener)
                    throw failure
                }
            }
            try {
                readState()
            } catch (failure: Exception) {
                // Some vendor MediaRouter implementations can fail while their callbacks still
                // register successfully. Roll the whole first-listener transaction back so a
                // later screen or telemetry subscription can retry from a clean lifecycle.
                listeners.remove(listener)
                if (listeners.isEmpty()) {
                    try {
                        stopInfrastructure()
                    } catch (cleanupFailure: Exception) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
                throw failure
            }
        }
        try {
            listener(initialState)
        } catch (failure: Exception) {
            // A listener does not become registered unless its required initial snapshot can be
            // delivered. This also prevents an unexpectedly throwing consumer from leaking the
            // shared Android callbacks.
            try {
                removeListener(listener)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    fun removeListener(listener: (AudioRouteState) -> Unit) {
        synchronized(lock) {
            if (!listeners.remove(listener)) return
            if (listeners.isEmpty()) stopInfrastructure()
        }
    }

    private fun startInfrastructure() {
        var devicesRegistered = false
        try {
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = publish()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = publish()
            }.also {
                audioManager.registerAudioDeviceCallback(it, mainHandler)
                devicesRegistered = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startRouter2() else startLegacyRouter()
        } catch (failure: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) stopRouter2() else stopLegacyRouter()
            if (devicesRegistered) audioDeviceCallback?.let {
                runCatching { audioManager.unregisterAudioDeviceCallback(it) }
            }
            audioDeviceCallback = null
            throw failure
        }
    }

    private fun stopInfrastructure() {
        var firstFailure: Exception? = null
        audioDeviceCallback?.let { callback ->
            try {
                audioManager.unregisterAudioDeviceCallback(callback)
            } catch (failure: Exception) {
                firstFailure = failure
            }
        }
        audioDeviceCallback = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) stopRouter2() else stopLegacyRouter()
        } catch (failure: Exception) {
            if (firstFailure == null) firstFailure = failure
        }
        firstFailure?.let { throw it }
    }

    private fun publish() {
        val state = runCatching(::readState).getOrNull() ?: return
        val currentListeners = synchronized(lock) { listeners.toList() }
        currentListeners.forEach { listener -> runCatching { listener(state) } }
    }

    private fun readActiveRoute(): ActiveAudioRoute? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> readRouter2RouteWithType()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> readRouter2RouteWithoutType()
        else -> readLegacyRoute()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun readRouter2RouteWithType(): ActiveAudioRoute? = selectedRouter2Route()?.let { route ->
        ActiveAudioRoute(route.name.toString(), route.type.toOutputType())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readRouter2RouteWithoutType(): ActiveAudioRoute? = selectedRouter2Route()?.let { route ->
        ActiveAudioRoute(route.name.toString(), null)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun selectedRouter2Route(): MediaRoute2Info? = (router2 ?: MediaRouter2.getInstance(appContext))
        .systemController
        .selectedRoutes
        .firstOrNull()

    @Suppress("DEPRECATION")
    private fun readLegacyRoute(): ActiveAudioRoute? {
        val route = (legacyRouter ?: appContext.getSystemService(MediaRouter::class.java))
            .getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        return route?.let {
            ActiveAudioRoute(
                name = it.getName(appContext).toString(),
                outputType = when (it.deviceType) {
                    MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> AudioOutputType.BLUETOOTH
                    MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> AudioOutputType.PHONE_SPEAKER
                    else -> null
                },
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startRouter2() {
        val activeRouter = MediaRouter2.getInstance(appContext)
        router2 = activeRouter
        router2Callback = object : MediaRouter2.ControllerCallback() {
            override fun onControllerUpdated(controller: MediaRouter2.RoutingController) {
                if (controller.id == activeRouter.systemController.id) publish()
            }
        }.also { callback ->
            activeRouter.registerControllerCallback(ContextCompat.getMainExecutor(appContext), callback)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun stopRouter2() {
        router2Callback?.let { callback -> router2?.unregisterControllerCallback(callback) }
        router2Callback = null
        router2 = null
    }

    @Suppress("DEPRECATION")
    private fun startLegacyRouter() {
        val activeRouter = appContext.getSystemService(MediaRouter::class.java)
        legacyRouter = activeRouter
        legacyRouterCallback = object : MediaRouter.SimpleCallback() {
            override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = publish()
            override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = publish()
            override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = publish()
        }.also { callback -> activeRouter.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback) }
    }

    @Suppress("DEPRECATION")
    private fun stopLegacyRouter() {
        legacyRouterCallback?.let { callback -> legacyRouter?.removeCallback(callback) }
        legacyRouterCallback = null
        legacyRouter = null
    }
}

fun Int.toOutputType(): AudioOutputType = when (this) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    -> AudioOutputType.PHONE_SPEAKER

    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_HDMI_EARC,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
    -> AudioOutputType.WIRED_OR_USB

    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    -> AudioOutputType.BLUETOOTH

    else -> AudioOutputType.OTHER
}
