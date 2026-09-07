package io.github.sumirenokai.vesqen.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.sumirenokai.vesqen.audio.AudioRouteSource
import io.github.sumirenokai.vesqen.audio.AudioRouteState
import java.util.concurrent.atomic.AtomicLong

internal enum class OutputTelemetrySignal {
    ROUTE_CHANGED,
    USB_ATTACHED,
    USB_DETACHED,
}

internal data class OutputTelemetrySnapshot(
    val observedAt: TelemetryInstant,
    val selectedSystemRouteName: String?,
    val selectedSystemRouteType: String?,
    val selectedSystemRouteTypeUnavailableReason: TelemetryUnavailableReason?,
    val anticipatedRouteName: String?,
    val anticipatedRouteType: String?,
    val connectedRouteTypes: String?,
    val connectedRouteTypesUnavailableReason: TelemetryUnavailableReason?,
    val routeUnavailableReason: TelemetryUnavailableReason?,
    val directSupported: Boolean?,
    val directModes: String?,
    val directUnavailableReason: TelemetryUnavailableReason?,
    val mixerProfileCount: Long?,
    val preferredMixerProfile: String?,
    val mixerUnavailableReason: TelemetryUnavailableReason?,
    val systemMusicVolumePercent: Double?,
    val systemMusicMuted: Boolean?,
    val usbHostSupported: Boolean,
    val usbAudioDeviceCount: Long?,
    val usbAudioDeviceCountUnavailableReason: TelemetryUnavailableReason?,
    val usbInventory: UsbInventoryReading?,
    val usbInventoryUnavailableReason: TelemetryUnavailableReason?,
    val bluetoothConnectedNames: String?,
    val bluetoothConnectedTypes: String?,
    val bluetoothUnavailableReason: TelemetryUnavailableReason?,
)

internal data class AudioTrackRequestFacts(
    val sampleRate: Int,
    val encoding: Int,
    val channelConfig: Int,
)

/**
 * Read-only system output and USB probe. This class never requests USB permission, opens a device,
 * claims an interface, selects a route, or sets/clears mixer attributes.
 */
internal class AndroidOutputTelemetryProbe(
    context: Context,
    private val clock: TelemetryClock,
    private val routeSource: AudioRouteSource,
    private val onSignal: (OutputTelemetrySignal) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val lifecycleLock = Any()
    private val captureLock = Any()
    private var started = false
    private val callbackLifecycle = OutputCallbackLifecycleGate()
    private var receiverRegistered = false
    private val invalidationGeneration = AtomicLong()
    @Volatile private var cached: OutputTelemetrySnapshot? = null
    private var cachedKey: OutputProbeKey? = null
    private var cachedGeneration: Long = -1
    private val routeChanges = OutputRouteChangeTracker<AudioRouteState>()
    private val routeListener: (AudioRouteState) -> Unit = { state ->
        val shouldSignal = synchronized(lifecycleLock) {
            callbackLifecycle.isAccepting && routeChanges.update(state)
        }
        if (shouldSignal) {
            invalidationGeneration.incrementAndGet()
            onSignal(OutputTelemetrySignal.ROUTE_CHANGED)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = intent?.usbDeviceExtra() ?: return
            if (!device.isUsbAudioDevice()) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> signal(OutputTelemetrySignal.USB_ATTACHED)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> signal(OutputTelemetrySignal.USB_DETACHED)
            }
        }
    }

    fun start() = synchronized(lifecycleLock) {
        if (started) return@synchronized
        var routeRegistered = false
        try {
            callbackLifecycle.start()
            routeSource.addListener(routeListener)
            routeRegistered = true
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                appContext,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
            invalidationGeneration.incrementAndGet()
            started = true
        } catch (failure: Exception) {
            if (receiverRegistered) runCatching { appContext.unregisterReceiver(usbReceiver) }
            receiverRegistered = false
            if (routeRegistered) runCatching { routeSource.removeListener(routeListener) }
            callbackLifecycle.stop()
            routeChanges.resetLifecycle()
            throw failure
        }
    }

    fun stop() = synchronized(lifecycleLock) {
        if (!started) return@synchronized
        callbackLifecycle.stop()
        var firstFailure: Exception? = null
        try {
            routeSource.removeListener(routeListener)
        } catch (failure: Exception) {
            firstFailure = failure
        }
        try {
            if (receiverRegistered) appContext.unregisterReceiver(usbReceiver)
        } catch (failure: Exception) {
            if (firstFailure == null) firstFailure = failure
        } finally {
            receiverRegistered = false
            started = false
            invalidationGeneration.incrementAndGet()
            routeChanges.resetLifecycle()
        }
        firstFailure?.let { throw it }
    }

    fun capture(
        audioTrackRequest: AudioTrackRequestFacts?,
        hasActivePlayback: Boolean,
    ): OutputTelemetrySnapshot = synchronized(captureLock) {
        val key = OutputProbeKey.from(audioTrackRequest, hasActivePlayback)
        val nowElapsedMs = clock.elapsedRealtimeMs()
        val generationAtStart = invalidationGeneration.get()
        cached?.takeIf { snapshot ->
            cachedGeneration == generationAtStart && cachedKey == key &&
                nowElapsedMs - snapshot.observedAt.elapsedRealtimeMs < MAX_CACHE_AGE_MS
        }?.let { return@synchronized it }
        val outputs = captureProbeValue {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            OutputDeviceEnumerationFacts(
                connectedRouteTypes = devices.map(AudioDeviceInfo::getType)
                    .map(::audioDeviceTypeName)
                    .distinct()
                    .sorted()
                    .joinToString(",")
                    .ifEmpty { "none" },
                usbEndpoints = devices.filter { it.type in USB_AUDIO_DEVICE_TYPES }
                    .sortedWith(compareBy({ it.type }, { it.id }))
                    .map(::usbAudioOutputEndpoint),
                bluetoothDevices = devices.filter { isBluetoothRouteType(audioDeviceTypeName(it.type)) },
            )
        }
        val anticipated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { audioManager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val anticipatedDevice = anticipated.firstOrNull()
        val routeUnavailableReason = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
                TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION
            anticipatedDevice == null -> TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
            else -> null
        }
        val direct = directSupport(audioTrackRequest, hasActivePlayback)
        val mixer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mixerSupport(anticipatedDevice)
        } else {
            MixerSupportFacts(reason = TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION)
        }
        val musicVolumeMaximum = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrNull()
            ?.takeIf { it > 0 }
        val musicVolume = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }
            .getOrNull()
            ?.takeIf { it >= 0 }
        val usbHostDevices = captureProbeValue {
            usbManager.deviceList.values.toList()
                .filter(UsbDevice::isUsbAudioDevice)
                .sortedWith(compareBy({ it.vendorId }, { it.productId }, { it.interfaceCount }))
                .map(::usbHostDevice)
        }
        val inventory = usbHostDevices.value?.let { hostDevices ->
            outputs.value?.let { outputFacts ->
                UsbInventoryReading(
                    hostDevices = hostDevices,
                    audioOutputEndpoints = outputFacts.usbEndpoints,
                )
            }
        }
        val selectedSystemRoute = synchronized(lifecycleLock) { routeChanges.current }?.activeRoute
        val bluetoothNames = captureProbeValue {
            outputs.value?.bluetoothDevices?.joinToString("\n") { device ->
                "${audioDeviceTypeName(device.type)}: ${device.productName}"
            }?.ifEmpty { "none" } ?: "none"
        }
        OutputTelemetrySnapshot(
            observedAt = clock.now(),
            selectedSystemRouteName = selectedSystemRoute?.name,
            selectedSystemRouteType = selectedSystemRoute?.outputType?.name?.lowercase(),
            selectedSystemRouteTypeUnavailableReason = when {
                selectedSystemRoute?.outputType != null -> null
                selectedSystemRoute == null -> TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
                Build.VERSION.SDK_INT in Build.VERSION_CODES.R until Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM
                else -> TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT
            },
            anticipatedRouteName = anticipatedDevice?.productName?.toString()?.takeIf(String::isNotBlank),
            anticipatedRouteType = anticipatedDevice?.type?.let(::audioDeviceTypeName),
            connectedRouteTypes = outputs.value?.connectedRouteTypes,
            connectedRouteTypesUnavailableReason = outputs.unavailableReason,
            routeUnavailableReason = routeUnavailableReason,
            directSupported = direct.supported,
            directModes = direct.modes,
            directUnavailableReason = direct.reason,
            mixerProfileCount = mixer.count,
            preferredMixerProfile = mixer.preferred,
            mixerUnavailableReason = mixer.reason,
            systemMusicVolumePercent = musicVolumeMaximum?.let { maximum ->
                musicVolume?.let { current -> current.toDouble() / maximum * 100.0 }
            },
            systemMusicMuted = runCatching { audioManager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull(),
            usbHostSupported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            usbAudioDeviceCount = usbHostDevices.value?.size?.toLong(),
            usbAudioDeviceCountUnavailableReason = usbHostDevices.unavailableReason,
            usbInventory = inventory,
            usbInventoryUnavailableReason = usbHostDevices.unavailableReason ?: outputs.unavailableReason,
            bluetoothConnectedNames = if (outputs.value != null) bluetoothNames.value else null,
            bluetoothConnectedTypes = outputs.value?.bluetoothDevices
                ?.map { audioDeviceTypeName(it.type) }?.distinct()?.sorted()?.joinToString(",")
                ?.ifEmpty { "none" },
            bluetoothUnavailableReason = outputs.unavailableReason ?: bluetoothNames.unavailableReason,
        ).also { snapshot ->
            cached = snapshot
            cachedKey = key
            cachedGeneration = generationAtStart
        }
    }

    private fun signal(signal: OutputTelemetrySignal) {
        val shouldSignal = synchronized(lifecycleLock) { callbackLifecycle.isAccepting }
        if (!shouldSignal) return
        invalidationGeneration.incrementAndGet()
        onSignal(signal)
    }

    @Suppress("DEPRECATION")
    private fun directSupport(
        config: AudioTrackRequestFacts?,
        hasActivePlayback: Boolean,
    ): DirectSupportFacts {
        if (!hasActivePlayback) {
            return DirectSupportFacts(reason = TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK)
        }
        if (config == null) {
            return DirectSupportFacts(reason = TelemetryUnavailableReason.WARMING_UP)
        }
        val format = runCatching {
            AudioFormat.Builder()
                .setEncoding(config.encoding)
                .setSampleRate(config.sampleRate)
                .setChannelMask(config.channelConfig)
                .build()
        }.getOrNull() ?: return DirectSupportFacts(reason = TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return DirectSupportFacts(reason = TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val supported = runCatching { AudioTrack.isDirectPlaybackSupported(format, MEDIA_ATTRIBUTES) }
                .getOrNull()
                ?: return DirectSupportFacts(reason = TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE)
            return DirectSupportFacts(
                supported = supported,
                reason = TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION,
            )
        }
        return directSupportFromApi33(format)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun directSupportFromApi33(format: AudioFormat): DirectSupportFacts {
        val support = runCatching { AudioManager.getDirectPlaybackSupport(format, MEDIA_ATTRIBUTES) }
            .getOrNull()
            ?: return DirectSupportFacts(reason = TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE)
        val modes = buildList {
            if (support and AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED != 0) add("offload")
            if (support and AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED != 0) add("offload_gapless")
            if (support and AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED != 0) add("bitstream")
        }
        return DirectSupportFacts(
            supported = support != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED,
            modes = modes.takeIf(List<String>::isNotEmpty)?.joinToString(",") ?: "none",
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun mixerSupport(device: AudioDeviceInfo?): MixerSupportFacts {
        if (device == null) {
            return MixerSupportFacts(reason = TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE)
        }
        return runCatching {
            val supported = audioManager.getSupportedMixerAttributes(device)
            val preferred = audioManager.getPreferredMixerAttributes(MEDIA_ATTRIBUTES, device)
            MixerSupportFacts(
                count = supported.size.toLong(),
                preferred = preferred?.let(::mixerProfileName),
                reason = if (preferred == null) TelemetryUnavailableReason.NOT_APPLICABLE else null,
            )
        }.getOrElse {
            MixerSupportFacts(reason = TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE)
        }
    }

    private fun usbHostDevice(device: UsbDevice): UsbHostDeviceReading {
        val interfaces = (0 until device.interfaceCount).mapNotNull { interfaceIndex ->
            val usbInterface = device.getInterface(interfaceIndex)
            usbInterface.takeIf { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }?.let {
                UsbAudioInterfaceReading(
                    interfaceClass = it.interfaceClass,
                    interfaceSubclass = it.interfaceSubclass,
                    interfaceProtocol = it.interfaceProtocol,
                )
            }
        }
        return UsbHostDeviceReading(
            snapshotKey = "host_device_${device.deviceId}",
            manufacturerName = runCatching { device.manufacturerName }.getOrNull(),
            productName = runCatching { device.productName }.getOrNull(),
            vendorId = device.vendorId,
            productId = device.productId,
            permissionGranted = usbManager.hasPermission(device),
            audioInterfaces = interfaces,
        )
    }

    private fun usbAudioOutputEndpoint(device: AudioDeviceInfo) = UsbAudioOutputEndpointReading(
        snapshotKey = "audio_endpoint_${device.id}",
        productName = device.productName?.toString()?.takeIf(String::isNotBlank),
        type = audioDeviceTypeName(device.type),
        sampleRatesHz = device.sampleRates.filter { it > 0 }.distinct().sorted(),
        arbitrarySampleRate = device.sampleRates.isEmpty(),
        channelCounts = device.channelCounts.filter { it > 0 }.distinct().sorted(),
        arbitraryChannelCount = device.channelCounts.isEmpty(),
        encodings = device.encodings.map(::platformEncodingName).distinct().sorted(),
        arbitraryEncoding = device.encodings.isEmpty(),
    )

    private companion object {
        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val USB_AUDIO_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        const val MAX_CACHE_AGE_MS = 5_000L
    }
}

/** Android-free lifecycle state shared by route and USB callback gates. Callers serialize access. */
internal class OutputCallbackLifecycleGate {
    var isAccepting: Boolean = false
        private set

    fun start() {
        isAccepting = true
    }

    fun stop() {
        isAccepting = false
    }
}

/** Suppresses only each registration's initial route, even when that route equals the last one. */
internal class OutputRouteChangeTracker<T> {
    var current: T? = null
        private set
    private var lifecycleInitialized = false

    fun update(newState: T): Boolean {
        val changed = newState != current
        val shouldSignal = lifecycleInitialized && changed
        current = newState
        lifecycleInitialized = true
        return shouldSignal
    }

    fun resetLifecycle() {
        lifecycleInitialized = false
    }
}

private data class OutputProbeKey(
    val sampleRate: Int?,
    val encoding: Int?,
    val channelConfig: Int?,
    val hasActivePlayback: Boolean,
) {
    companion object {
        fun from(config: AudioTrackRequestFacts?, hasActivePlayback: Boolean) = OutputProbeKey(
            sampleRate = config?.sampleRate,
            encoding = config?.encoding,
            channelConfig = config?.channelConfig,
            hasActivePlayback = hasActivePlayback,
        )
    }
}

private data class DirectSupportFacts(
    val supported: Boolean? = null,
    val modes: String? = null,
    val reason: TelemetryUnavailableReason? = null,
)

private data class MixerSupportFacts(
    val count: Long? = null,
    val preferred: String? = null,
    val reason: TelemetryUnavailableReason? = null,
)

private data class OutputDeviceEnumerationFacts(
    val connectedRouteTypes: String,
    val usbEndpoints: List<UsbAudioOutputEndpointReading>,
    val bluetoothDevices: List<AudioDeviceInfo>,
)

/** A successful empty enumeration is evidence; an exception is not a measured zero. */
internal data class ProbeValue<out T : Any>(
    val value: T?,
    val unavailableReason: TelemetryUnavailableReason?,
) {
    init {
        require((value == null) != (unavailableReason == null)) {
            "Probe values must be either available or explicitly unavailable"
        }
    }
}

internal inline fun <T : Any> captureProbeValue(block: () -> T): ProbeValue<T> = try {
    ProbeValue(value = block(), unavailableReason = null)
} catch (_: Exception) {
    ProbeValue(value = null, unavailableReason = TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE)
}

private fun UsbDevice.isUsbAudioDevice(): Boolean = isUsbAudioClass(
    deviceClass = deviceClass,
    interfaceClasses = (0 until interfaceCount).map { index -> getInterface(index).interfaceClass },
)

internal fun isUsbAudioClass(deviceClass: Int, interfaceClasses: List<Int>): Boolean =
    deviceClass == UsbConstants.USB_CLASS_AUDIO || UsbConstants.USB_CLASS_AUDIO in interfaceClasses

@Suppress("DEPRECATION")
private fun Intent.usbDeviceExtra(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
} else {
    getParcelableExtra(UsbManager.EXTRA_DEVICE)
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun mixerProfileName(attributes: AudioMixerAttributes): String {
    val behavior = when (attributes.mixerBehavior) {
        AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT -> "bit_perfect_capability"
        else -> "default"
    }
    val format = attributes.format
    return listOf(
        behavior,
        format.sampleRate.takeIf { it > 0 }?.let { "${it}hz" },
        format.encoding.takeIf { it != AudioFormat.ENCODING_INVALID }?.let(::platformEncodingName),
        format.channelMask.takeIf { it != 0 }?.let { "mask_0x${it.toUInt().toString(16)}" },
    ).filterNotNull().joinToString(",")
}

internal fun audioDeviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "built_in_earpiece"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built_in_speaker"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "built_in_speaker_safe"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "line_analog"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line_digital"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
    AudioDeviceInfo.TYPE_HDMI -> "hdmi"
    AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi_arc"
    AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi_earc"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_accessory"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
    AudioDeviceInfo.TYPE_DOCK -> "dock"
    AudioDeviceInfo.TYPE_FM -> "fm"
    AudioDeviceInfo.TYPE_IP -> "ip"
    AudioDeviceInfo.TYPE_BUS -> "bus"
    AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> "ble_broadcast"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote_submix"
    else -> "type_$type"
}

private fun platformEncodingName(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> "pcm_8"
    AudioFormat.ENCODING_PCM_16BIT -> "pcm_16"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "pcm_24"
    AudioFormat.ENCODING_PCM_32BIT -> "pcm_32"
    AudioFormat.ENCODING_PCM_FLOAT -> "pcm_float"
    AudioFormat.ENCODING_AC3 -> "ac3"
    AudioFormat.ENCODING_E_AC3 -> "e_ac3"
    AudioFormat.ENCODING_DTS -> "dts"
    AudioFormat.ENCODING_DTS_HD -> "dts_hd"
    else -> "encoding_$encoding"
}
