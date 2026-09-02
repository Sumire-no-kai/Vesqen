package io.github.sumirenokai.vesqen.ui

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
    val outputType: AudioOutputType,
)

data class AudioRouteState(
    val connectedOutputs: Set<AudioOutputType>,
    val activeRoute: ActiveAudioRoute?,
)

class ConnectedAudioOutputs(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var onRouteChanged: ((AudioRouteState) -> Unit)? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var router2: MediaRouter2? = null
    private var router2Callback: MediaRouter2.ControllerCallback? = null
    private var legacyRouter: MediaRouter? = null
    private var legacyRouterCallback: MediaRouter.Callback? = null

    fun read(): Set<AudioOutputType> = readState().connectedOutputs

    fun readState(): AudioRouteState = AudioRouteState(
        connectedOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapTo(linkedSetOf()) { device -> device.type.toOutputType() },
        activeRoute = readActiveRoute(),
    )

    fun start(onChanged: (AudioRouteState) -> Unit) {
        if (onRouteChanged != null) return
        onRouteChanged = onChanged
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = publish()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = publish()
        }.also { audioManager.registerAudioDeviceCallback(it, mainHandler) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startRouter2()
        } else {
            startLegacyRouter()
        }
        publish()
    }

    fun stop() {
        audioDeviceCallback?.let(audioManager::unregisterAudioDeviceCallback)
        audioDeviceCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val callback = router2Callback
            if (callback != null) router2?.unregisterControllerCallback(callback)
            router2Callback = null
            router2 = null
        } else {
            val callback = legacyRouterCallback
            if (callback != null) legacyRouter?.removeCallback(callback)
            legacyRouterCallback = null
            legacyRouter = null
        }
        onRouteChanged = null
    }

    private fun publish() {
        onRouteChanged?.invoke(readState())
    }

    private fun readActiveRoute(): ActiveAudioRoute? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> readRouter2RouteWithType()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> readRouter2RouteWithoutType()
        else -> readLegacyRoute()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun readRouter2RouteWithType(): ActiveAudioRoute? = selectedRouter2Route()?.let { route ->
        ActiveAudioRoute(
            name = route.name.toString(),
            outputType = route.type.toOutputType(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readRouter2RouteWithoutType(): ActiveAudioRoute? = selectedRouter2Route()?.let { route ->
        ActiveAudioRoute(
            name = route.name.toString(),
            outputType = AudioOutputType.OTHER,
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun selectedRouter2Route(): MediaRoute2Info? = (router2 ?: MediaRouter2.getInstance(appContext))
        .systemController
        .selectedRoutes
        .firstOrNull()

    @Suppress("DEPRECATION")
    private fun readLegacyRoute(): ActiveAudioRoute? {
        @Suppress("DEPRECATION")
        val route = (legacyRouter ?: appContext.getSystemService(MediaRouter::class.java))
            .getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        @Suppress("DEPRECATION")
        return route?.let {
            ActiveAudioRoute(
                name = it.getName(appContext).toString(),
                outputType = when (it.deviceType) {
                    MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> AudioOutputType.BLUETOOTH
                    MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> AudioOutputType.PHONE_SPEAKER
                    else -> AudioOutputType.OTHER
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

    @Suppress("DEPRECATION")
    private fun startLegacyRouter() {
        val activeRouter = appContext.getSystemService(MediaRouter::class.java)
        legacyRouter = activeRouter
        legacyRouterCallback = object : MediaRouter.SimpleCallback() {
            override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = publish()

            override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = publish()

            override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = publish()
        }.also { callback ->
            activeRouter.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback)
        }
    }
}

private fun Int.toOutputType(): AudioOutputType = when (this) {
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
