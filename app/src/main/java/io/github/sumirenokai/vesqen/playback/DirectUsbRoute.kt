package io.github.sumirenokai.vesqen.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import io.github.sumirenokai.vesqen.telemetry.TelemetryMediaItemExtras
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import io.github.sumirenokai.vesqen.usb.descriptor.UacDescriptorParser
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UacParseResult
import io.github.sumirenokai.vesqen.usb.engine.StreamPreflight
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamEngine
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.AndroidUsbLink
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import io.github.sumirenokai.vesqen.usb.session.DirectUsbSession
import io.github.sumirenokai.vesqen.usb.session.TakeoverMarker
import io.github.sumirenokai.vesqen.usb.session.TakeoverMarkerStore
import io.github.sumirenokai.vesqen.usb.session.UsbDeviceIdentity
import java.util.concurrent.Executors

/** What strict mode found when it looked at Vesqen's own USB path for a source (M7-4). */
internal sealed interface DirectUsbInspection {
    /** One USB audio device with permission, and a plan that carries the source. */
    data class Ready(val deviceName: String, val plan: StreamingPlan) : DirectUsbInspection

    data object NoDevice : DirectUsbInspection

    data object SeveralDevices : DirectUsbInspection

    data object PermissionNeeded : DirectUsbInspection

    /** [code] is the stable decision suffix, [formatRefused] true when only this source's format is the problem. */
    data class Refused(val code: String, val formatRefused: Boolean = false) : DirectUsbInspection
}

/**
 * Vesqen's own USB output as the coordinator drives it: one device, its permission, its plan and the
 * [DirectUsbSession] between takeover and release. Calls come on the main thread unless noted.
 */
@UnstableApi
internal interface DirectUsbRoute {
    interface Listener {
        /** Media3 opened an output on the session. Called on the playback thread. */
        fun onOutputOpened() = Unit

        /** The session failed, or the format gate refused what Media3 configured. Any thread. */
        fun onFailed(failure: DirectUsbFailure) = Unit
    }

    /** What the strict sink plays through: the current session's outputs. */
    val provider: AudioOutputProvider

    /** From a finished takeover until release. The selecting sink reads it on the playback thread. */
    val streaming: Boolean

    var listener: Listener?

    /** Playback thread: Media3 is configuring the sink for the item that [config] names (#282). */
    fun onSinkConfigured(config: AudioSink.AudioSinkConfig)

    fun inspect(source: SourcePcmFormat): DirectUsbInspection

    /** Shows the system's permission dialog for the device; only for a user's own action. */
    fun requestPermission(onResult: (granted: Boolean) -> Unit)

    /** Takes over the device [inspect] planned for. [onDone] runs on the main thread, with null on success. */
    fun takeOver(onDone: (DirectUsbFailure?) -> Unit)

    /** Gives the device back. Safe to call at any time and more than once. */
    fun release()

    fun close()
}

/**
 * The production route. It looks at `UsbManager` itself on every inspection rather than trusting
 * what an event said, keeps one connection from inspection to release, and starts at the D4
 * volume (about -30 dB) since no remembered volume exists yet.
 */
@UnstableApi
internal class AndroidDirectUsbRoute(context: Context) : DirectUsbRoute {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "usb-control") }
    private val markers = PreferencesTakeoverMarkerStore(appContext)

    private class Prepared(val device: UsbDevice, val connection: UsbDeviceConnection, val function: UacFunction, val plan: StreamingPlan)

    private var prepared: Prepared? = null
    private var session: DirectUsbSession? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    @Volatile
    private var sessionProvider: DirectUsbAudioOutputProvider? = null

    @Volatile
    private var configuringSource: TelemetrySourceFacts? = null

    @Volatile
    override var streaming = false
        private set

    @Volatile
    override var listener: DirectUsbRoute.Listener? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val callback = permissionCallback ?: return
            permissionCallback = null
            callback(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val detached = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (detached.deviceId == prepared?.device?.deviceId) session?.onDeviceDetached()
        }
    }

    init {
        ContextCompat.registerReceiver(appContext, permissionReceiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            appContext,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override val provider: AudioOutputProvider = object : AudioOutputProvider {
        private fun current() = sessionProvider

        override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
            current()?.getFormatSupport(formatConfig)
                ?: AudioOutputProvider.FormatSupport.Builder().setFormatSupportLevel(AudioOutputProvider.FORMAT_UNSUPPORTED).build()

        override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig =
            current()?.getOutputConfig(formatConfig) ?: throw AudioOutputProvider.ConfigurationException("No own USB session")

        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
            current()?.getAudioOutput(config) ?: throw AudioOutputProvider.InitializationException()

        override fun addListener(listener: AudioOutputProvider.Listener) = Unit

        override fun removeListener(listener: AudioOutputProvider.Listener) = Unit

        override fun release() = Unit
    }

    override fun onSinkConfigured(config: AudioSink.AudioSinkConfig) {
        configuringSource = config.mediaPeriodId?.takeIf { !config.timeline.isEmpty }?.let { period ->
            val index = config.timeline.getIndexOfPeriod(period.periodUid).takeIf { it != C.INDEX_UNSET }
                ?: return@let null
            val window = config.timeline.getWindow(config.timeline.getPeriod(index, Timeline.Period()).windowIndex, Timeline.Window())
            TelemetryMediaItemExtras.read(window.mediaItem.mediaMetadata.extras)
        }
    }

    override fun inspect(source: SourcePcmFormat): DirectUsbInspection {
        val devices = usbManager.deviceList.values.filter(UsbDevice::isUsbAudioDevice)
        val device = when (devices.size) {
            0 -> return DirectUsbInspection.NoDevice
            1 -> devices.single()
            else -> return DirectUsbInspection.SeveralDevices
        }
        if (!usbManager.hasPermission(device)) return DirectUsbInspection.PermissionNeeded
        val held = prepared
        if (held != null && streaming && held.device.deviceId == device.deviceId &&
            held.plan.sampleRateHz == source.sampleRateHz && held.plan.bitResolution >= source.bitDepth
        ) {
            return DirectUsbInspection.Ready(device.displayName(), held.plan)
        }
        release()
        val connection = usbManager.openDevice(device) ?: return DirectUsbInspection.Refused("open_failed")
        val raw = connection.rawDescriptors
        val parsed = raw?.let(UacDescriptorParser::parse)
        if (parsed !is UacParseResult.Parsed) {
            connection.close()
            return DirectUsbInspection.Refused("descriptors_refused")
        }
        val speed = UsbStreamEngine.busSpeed(connection.fileDescriptor)
        // The deepest depth the device offers at this rate: tracks of other depths then share the
        // DAC's setting, 16-bit samples gaining zeros below them (#65).
        for (bits in WIRE_DEPTHS.filter { it >= source.bitDepth }) {
            when (val preflight = StreamPreflight.run(parsed.topology, speed, source.sampleRateHz, bits)) {
                is StreamPreflight.Ready -> {
                    prepared = Prepared(device, connection, preflight.function, preflight.plan)
                    return DirectUsbInspection.Ready(device.displayName(), preflight.plan)
                }
                StreamPreflight.NoPlaybackFunction -> return DirectUsbInspection.Refused("no_playback_function").also { connection.close() }
                is StreamPreflight.LibraryUnavailable -> return DirectUsbInspection.Refused("engine_unavailable").also { connection.close() }
                is StreamPreflight.Refused -> Unit
            }
        }
        connection.close()
        return DirectUsbInspection.Refused("no_matching_format", formatRefused = true)
    }

    override fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        val device = usbManager.deviceList.values.filter(UsbDevice::isUsbAudioDevice).singleOrNull() ?: return onResult(false)
        permissionCallback = onResult
        usbManager.requestPermission(
            device,
            // Mutable: the system adds the device and the answer to this intent.
            PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(ACTION_PERMISSION).setPackage(appContext.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    override fun takeOver(onDone: (DirectUsbFailure?) -> Unit) {
        val target = prepared ?: return onDone(DirectUsbFailure(DirectUsbFailure.Reason.USB_TRANSFER_FAILED, "nothing was inspected"))
        lateinit var next: DirectUsbSession
        next = DirectUsbSession(
            device = UsbDeviceIdentity(target.device.deviceId, target.device.vendorId, target.device.productId),
            link = AndroidUsbLink(target.device, target.connection),
            function = target.function,
            markers = markers,
            control = control,
            listener = object : DirectUsbSession.Listener {
                // A session given back on purpose may still report; only the current one counts.
                override fun onFailed(failure: DirectUsbFailure) {
                    main.post { if (session === next) listener?.onFailed(failure) }
                }
            },
        )
        session = next
        next.takeOver(target.plan, START_VOLUME) { result ->
            main.post {
                if (session !== next) return@post
                when (result) {
                    is DirectUsbSession.Takeover.Ready -> {
                        sessionProvider = DirectUsbAudioOutputProvider(
                            target.plan,
                            openOutput = { next.openOutput()?.also { listener?.onOutputOpened() } },
                            source = { configuringSource },
                            onFailure = { failure -> listener?.onFailed(failure) },
                        )
                        streaming = true
                        onDone(null)
                    }
                    is DirectUsbSession.Takeover.Failed -> {
                        release()
                        onDone(result.failure)
                    }
                }
            }
        }
    }

    override fun release() {
        streaming = false
        sessionProvider = null
        val current = session
        session = null
        val held = prepared
        prepared = null
        if (current != null) current.release() else held?.connection?.close()
    }

    override fun close() {
        release()
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
        runCatching { appContext.unregisterReceiver(detachReceiver) }
        control.shutdown()
    }

    private fun UsbDevice.displayName(): String = productName?.takeIf(String::isNotBlank) ?: "USB audio $deviceId"

    private companion object {
        const val ACTION_PERMISSION = "io.github.sumirenokai.vesqen.playback.USB_PERMISSION"

        /** D4 without a remembered volume: about -30 dB, in the UAC unit of 1/256 dB. */
        const val START_VOLUME = -30 * 256
        val WIRE_DEPTHS = listOf(32, 24, 16)
    }
}

/** Survives the process, so a later start can give back a device a crash left claimed (M7-4). */
private class PreferencesTakeoverMarkerStore(context: Context) : TakeoverMarkerStore {
    private val preferences = context.getSharedPreferences("direct_usb_takeover", Context.MODE_PRIVATE)

    override fun write(marker: TakeoverMarker) = preferences.edit(commit = true) {
        putInt(DEVICE_ID, marker.device.deviceId)
        putInt(VENDOR_ID, marker.device.vendorId)
        putInt(PRODUCT_ID, marker.device.productId)
        marker.volumeBefore?.let { putInt(VOLUME_BEFORE, it) } ?: remove(VOLUME_BEFORE)
        marker.mutedBefore?.let { putBoolean(MUTED_BEFORE, it) } ?: remove(MUTED_BEFORE)
    }

    override fun clear() = preferences.edit(commit = true) { clear() }

    private companion object {
        const val DEVICE_ID = "device_id"
        const val VENDOR_ID = "vendor_id"
        const val PRODUCT_ID = "product_id"
        const val VOLUME_BEFORE = "volume_before"
        const val MUTED_BEFORE = "muted_before"
    }
}

/**
 * The own path's failures in the existing strict vocabulary, which device reports also use. The
 * decision code keeps the exact reason; dedicated failure kinds and their texts come with M7-4's
 * user-facing step, together with the report vocabulary.
 */
internal fun DirectUsbFailure.toStrictFailure(): Pair<UsbOutputFailure, String> = when (reason) {
    DirectUsbFailure.Reason.SOURCE_FORMAT_UNKNOWN -> UsbOutputFailure.SOURCE_FORMAT_UNKNOWN
    DirectUsbFailure.Reason.SOURCE_FORMAT_UNSUPPORTED,
    DirectUsbFailure.Reason.DECODER_PRECISION_LOST,
    DirectUsbFailure.Reason.NO_MATCHING_DIRECT_FORMAT -> UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED
    DirectUsbFailure.Reason.DEVICE_DISCONNECTED -> UsbOutputFailure.DEVICE_DISCONNECTED
    DirectUsbFailure.Reason.INTERFACE_CLAIM_FAILED,
    DirectUsbFailure.Reason.VOLUME_CONTROL_FAILED,
    DirectUsbFailure.Reason.SAMPLE_RATE_SET_FAILED,
    DirectUsbFailure.Reason.SAMPLE_RATE_READBACK_MISMATCH,
    DirectUsbFailure.Reason.USB_ENGINE_UNAVAILABLE,
    DirectUsbFailure.Reason.USB_TRANSFER_FAILED,
    DirectUsbFailure.Reason.PCM_NOT_EXACT -> UsbOutputFailure.PLATFORM_ERROR
} to "direct_usb.${reason.name.lowercase()}"
