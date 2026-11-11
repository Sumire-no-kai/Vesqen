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
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import io.github.sumirenokai.vesqen.telemetry.TelemetryMediaItemExtras
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import io.github.sumirenokai.vesqen.usb.descriptor.SampleRates
import io.github.sumirenokai.vesqen.usb.descriptor.UacDescriptorParser
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UacParseResult
import io.github.sumirenokai.vesqen.usb.descriptor.UacSyncType
import io.github.sumirenokai.vesqen.usb.descriptor.UacVersion
import io.github.sumirenokai.vesqen.usb.descriptor.UsbAudioTopology
import io.github.sumirenokai.vesqen.usb.engine.StreamPreflight
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamEngine
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.plan.StreamingRefusal
import io.github.sumirenokai.vesqen.usb.session.AndroidUsbLink
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import io.github.sumirenokai.vesqen.usb.session.DirectUsbSession
import io.github.sumirenokai.vesqen.usb.session.TakeoverMarker
import io.github.sumirenokai.vesqen.usb.session.TakeoverMarkerStore
import io.github.sumirenokai.vesqen.usb.session.CleanupRecord
import io.github.sumirenokai.vesqen.usb.session.UsbTakeoverRecovery
import io.github.sumirenokai.vesqen.usb.session.UsbDeviceIdentity
import java.util.concurrent.Executors

/** What strict mode found when it looked at Vesqen's own USB path for a source (M7-4). */
internal sealed interface DirectUsbInspection {
    /** One USB audio device with permission, and a plan that carries the source. */
    data class Ready(val deviceName: String, val plan: StreamingPlan) : DirectUsbInspection

    data object NoDevice : DirectUsbInspection

    data object SeveralDevices : DirectUsbInspection

    /** The name needs no permission, so the reason can still say which device it is about. */
    data class PermissionNeeded(val deviceName: String) : DirectUsbInspection

    /**
     * [code] is the stable decision suffix, [formatRefused] true when only this source's format is the
     * problem. [details] holds what the DAC offers when it is.
     */
    data class Refused(
        val code: String,
        val formatRefused: Boolean = false,
        val deviceName: String? = null,
        val details: DirectUsbDetails? = null,
    ) : DirectUsbInspection
}

/**
 * 4d: why no depth could be planned for [source], from each depth's refusal. A limit of the device
 * itself (protocol version, asynchronous endpoints, bus speed) comes before the format, so a UAC2
 * dongle is not reported as lacking the track's sample rate.
 */
internal fun directRefusal(
    topology: UsbAudioTopology,
    refusals: Collection<StreamingRefusal>,
    source: SourcePcmFormat,
): DirectUsbInspection.Refused {
    val function = topology.functions.firstOrNull { candidate ->
        candidate.streamingInterfaces.any { streaming -> streaming.alternateSettings.any { it.isPlayback } }
    } ?: return DirectUsbInspection.Refused("no_playback_function")
    return when {
        StreamingRefusal.VERSION_NOT_IMPLEMENTED in refusals -> DirectUsbInspection.Refused(
            if (function.version == UacVersion.UAC2) "uac2_not_supported" else "uac_version_unsupported",
        )
        StreamingRefusal.ASYNCHRONOUS_NOT_IMPLEMENTED in refusals -> DirectUsbInspection.Refused("async_not_supported")
        StreamingRefusal.SPEED_UNSUPPORTED in refusals -> DirectUsbInspection.Refused("speed_unsupported")
        StreamingRefusal.PACKET_TOO_SMALL in refusals -> DirectUsbInspection.Refused("format_exceeds_bus", formatRefused = true)
        else -> {
            val details = source.toDirectDetails().withOffer(function)
            when {
                details.deviceSampleRatesHz.isEmpty() -> DirectUsbInspection.Refused("no_matching_format", formatRefused = true)
                details.deviceMaxBitsAtSourceRate == null ->
                    DirectUsbInspection.Refused("rate_unsupported", formatRefused = true, details = details)
                else -> DirectUsbInspection.Refused("depth_unsupported", formatRefused = true, details = details)
            }
        }
    }
}

/**
 * Whether this plan carries [source] unchanged: its rate, at least its depth (#65), and its channel
 * count, since strict output does not mix (#295).
 */
internal fun StreamingPlan.carries(source: SourcePcmFormat): Boolean =
    sampleRateHz == source.sampleRateHz && bitResolution >= source.bitDepth && channels == source.channelCount

/** What the source is, from the library's facts. */
internal fun TelemetrySourceFacts.toDirectDetails() = DirectUsbDetails(
    sourceCodec = codecLabel?.takeIf(String::isNotBlank),
    sourceSampleRateHz = sampleRateHz?.takeIf { it > 0 },
    sourceBitDepth = bitDepth?.takeIf { it > 0 },
    sourceChannelCount = channelCount?.takeIf { it > 0 },
)

/** What the source is, as the service planned for it. */
internal fun SourcePcmFormat.toDirectDetails() = DirectUsbDetails(
    sourceSampleRateHz = sampleRateHz,
    sourceBitDepth = bitDepth,
    sourceChannelCount = channelCount,
)

/**
 * Adds what [function]'s settings offer to these details: every rate, and the deepest depth at the
 * source's rate. Settings the own output cannot stream through at all are left out, and so are rates
 * a device cannot mean, such as 0 Hz or a range that ends below its start (#290).
 */
internal fun DirectUsbDetails.withOffer(function: UacFunction): DirectUsbDetails {
    val formats = function.streamingInterfaces.flatMap { it.alternateSettings }
        .filter { it.isPlayback && it.streamingBlockers.isEmpty() }
        .mapNotNull { it.format }
    val rate = sourceSampleRateHz
    return copy(
        deviceSampleRatesHz = formats.flatMap { it.sampleRates.ranges() }
            .filter { it.first in 1..it.last }
            .distinct()
            .sortedBy { it.first },
        deviceMaxBitsAtSourceRate = rate?.let { formats.filter { format -> rate in format.sampleRates }.maxOfOrNull { it.bitResolution } },
        deviceChannelCount = formats.map { it.channels }.distinct().singleOrNull(),
    )
}

internal fun UacFunction.protocolToken(): String = when (version) {
    UacVersion.UAC1 -> "uac1"
    UacVersion.UAC2 -> "uac2"
    UacVersion.OTHER -> "other"
}

/** How the plan's data endpoint keeps time with the device, as its descriptor declares. */
internal fun UacFunction.syncToken(plan: StreamingPlan): String {
    val endpoint = streamingInterfaces.firstOrNull { it.interfaceNumber == plan.interfaceNumber }
        ?.alternateSettings?.firstOrNull { it.alternateSetting == plan.alternateSetting }?.dataEndpoint
    return when (endpoint?.syncType) {
        UacSyncType.ADAPTIVE -> "adaptive"
        UacSyncType.ASYNCHRONOUS -> "asynchronous"
        UacSyncType.SYNCHRONOUS -> "synchronous"
        UacSyncType.NONE, null -> "none"
    }
}

private operator fun SampleRates.contains(rateHz: Int): Boolean = when (this) {
    is SampleRates.Discrete -> rateHz in ratesHz
    is SampleRates.Continuous -> rateHz in minHz..maxHz
    SampleRates.RuntimeUnknown -> false
}

private fun SampleRates.ranges(): List<IntRange> = when (this) {
    is SampleRates.Discrete -> ratesHz.map { it..it }
    is SampleRates.Continuous -> listOf(minHz..maxHz)
    SampleRates.RuntimeUnknown -> emptyList()
}

/**
 * Vesqen's own USB output as the coordinator drives it: one device, its permission, its plan and the
 * [DirectUsbSession] between takeover and release. Calls come on the main thread unless noted.
 */
internal data class DirectUsbRecoveryResult(
    val failure: UsbOutputFailure? = null,
    val code: String = "direct_usb.recovered",
    val cleanup: CleanupRecord? = null,
)

@UnstableApi
internal interface DirectUsbRoute {
    interface Listener {
        fun onReleased(cleanup: CleanupRecord) = Unit

        /** The route has posted a new output to the main thread. Register it before the next failure callback. */
        fun onOutputOpened(periodUid: Any, output: Any = periodUid) = Unit

        /** Main thread: media delivery, pause and retirement, bound to the output that observed them. */
        fun onOutputActive(periodUid: Any, output: Any = periodUid, active: Boolean) = Unit

        /**
         * The session failed, or the format gate refused what Media3 configured; [details] then name
         * the item the gate checked and what the DAC offers. Any thread.
         */
        fun onFailed(failure: DirectUsbFailure, details: DirectUsbDetails? = null, periodUid: Any? = null, output: Any? = null) = Unit
    }

    /** What the strict sink plays through: the current session's outputs. */
    val provider: AudioOutputProvider

    /** From a finished takeover until release. The selecting sink reads it on the playback thread. */
    val streaming: Boolean

    /** A current or unresolved takeover must give the device back before Android can play. */
    val recoveryPending: Boolean

    /** Completes on main after the previous service's cleanup and persisted recovery. No permission dialog. */
    fun recover(onDone: (DirectUsbRecoveryResult) -> Unit) = onDone(DirectUsbRecoveryResult())

    val lastCleanup: CleanupRecord? get() = null

    var listener: Listener?

    /** Playback thread: Media3 is configuring the sink for the item that [config] names (#282). */
    fun onSinkConfigured(config: AudioSink.AudioSinkConfig)

    /** User discontinuities invalidate even output-created notifications still queued on the main thread. */
    fun invalidateOutput() = Unit

    fun inspect(source: SourcePcmFormat): DirectUsbInspection

    /** USB Host lists an audio-class device, whether or not Android has an audio route for it (#293). */
    fun hasUsbAudioDevice(): Boolean

    /** While streaming, what Chain shows about the own output. Any thread. */
    fun readings(): DirectUsbReadings? = null

    /** Shows the system's permission dialog for the device; only for a user's own action. */
    fun requestPermission(onResult: (granted: Boolean) -> Unit)

    /** Takes over the device [inspect] planned for. [onDone] runs on the main thread, with null on success. */
    fun takeOver(onDone: (DirectUsbFailure?) -> Unit)

    /** Gives the device back. Safe to call at any time and more than once. */
    fun release(onReleased: ((Boolean) -> Unit)? = null)

    fun close()
}

/** Main-thread release barrier. The route drops a session before its control thread finishes cleanup. */
internal class PendingUsbReleases {
    private var outstanding = 0
    private var allSucceeded = true
    private val waiting = mutableListOf<(Boolean) -> Unit>()
    val pending: Boolean get() = outstanding > 0

    fun begin() { outstanding++ }

    fun await(callback: (Boolean) -> Unit) {
        if (pending) waiting += callback else callback(true)
    }

    fun complete(succeeded: Boolean) {
        allSucceeded = allSucceeded && succeeded
        if (--outstanding > 0) return
        val result = allSucceeded
        allSucceeded = true
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(result) }
    }
}

/**
 * The production route. It looks at `UsbManager` itself on every inspection rather than trusting
 * what an event said, keeps one connection from inspection to release, and starts at the D4
 * volume ([DirectUsbVolumeMemory]). The listener's changes go through [volumes].
 */
@UnstableApi
internal class AndroidDirectUsbRoute(
    context: Context,
    private val markers: TakeoverMarkerStore,
    private val volumes: DirectUsbVolumeControl,
    private val volumeMemory: DirectUsbVolumeMemory,
) : DirectUsbRoute {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private class Prepared(val device: UsbDevice, val connection: UsbDeviceConnection, val function: UacFunction, val plan: StreamingPlan)

    private val releases = PendingUsbReleases()
    private var recovering = false
    private val recoveryCallbacks = mutableListOf<(DirectUsbRecoveryResult) -> Unit>()
    @Volatile override var lastCleanup: CleanupRecord? = null
        private set
    private var prepared: Prepared? = null
    @Volatile
    private var session: DirectUsbSession? = null

    /** What [readings] reports, set once a takeover is ready and cleared on release. */
    private class StreamingFacts(
        val session: DirectUsbSession,
        val device: UsbDeviceIdentity,
        val plan: StreamingPlan,
        val protocol: String,
        val syncType: String,
        @Volatile var volume: Int,
    )

    /** Main thread: one volume request goes to the device at a time, and only the newest waits. */
    private var volumeInFlight = false
    // At once on the main thread, so a request counts as waiting before the read-back of the one before
    // it settles the control's level (key presses and read-backs both arrive there).
    private val volumeSetter: (Int) -> Unit = { target ->
        if (Looper.myLooper() == main.looper) setVolume(target) else main.post { setVolume(target) }
    }
    private var volumeWaiting: Int? = null

    @Volatile
    private var streamingFacts: StreamingFacts? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    @Volatile
    private var sessionProvider: DirectUsbAudioOutputProvider? = null

    @Volatile
    private var configuringSource: TelemetrySourceFacts? = null

    private var configuringPeriodUid: Any? = null
    @Volatile private var outputGeneration = 0L
    private var configuringGeneration = 0L

    override fun invalidateOutput() { outputGeneration++ }

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
            if (detached.deviceId == prepared?.device?.deviceId) {
                volumeMemory.forgetConnection()
                session?.onDeviceDetached()
            }
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
        volumes.attach(volumeSetter)
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
        configuringGeneration = outputGeneration
        configuringPeriodUid = config.mediaPeriodId?.periodUid
        configuringSource = config.mediaPeriodId?.takeIf { !config.timeline.isEmpty }?.let { period ->
            val index = config.timeline.getIndexOfPeriod(period.periodUid).takeIf { it != C.INDEX_UNSET }
                ?: return@let null
            val window = config.timeline.getWindow(config.timeline.getPeriod(index, Timeline.Period()).windowIndex, Timeline.Window())
            TelemetryMediaItemExtras.read(window.mediaItem.mediaMetadata.extras)
        }
    }

    override val recoveryPending: Boolean get() = recovering || releases.pending || markers.isOccupied()

    override fun recover(onDone: (DirectUsbRecoveryResult) -> Unit) {
        recoveryCallbacks += onDone
        if (recovering) return
        recovering = true
        markers.whenIdle {
            control.execute {
                val result = try {
                    recoverSavedDevice()
                } catch (_: SecurityException) {
                    DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.permission_required")
                } catch (_: IllegalStateException) {
                    DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.usb_transfer_failed")
                }
                main.post {
                    lastCleanup = result.cleanup
                    recovering = false
                    val callbacks = recoveryCallbacks.toList()
                    recoveryCallbacks.clear()
                    callbacks.forEach { it(result) }
                }
            }
        }
    }

    private fun recoverSavedDevice(): DirectUsbRecoveryResult {
        val marker = markers.recoveryRecord() ?: return DirectUsbRecoveryResult()
        val device = usbManager.deviceList.values.firstOrNull {
            it.deviceId == marker.device.deviceId && it.vendorId == marker.device.vendorId && it.productId == marker.device.productId
        } ?: return DirectUsbRecoveryResult()
        if (!usbManager.hasPermission(device)) {
            return DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.permission_required")
        }
        val connection = usbManager.openDevice(device)
            ?: return DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.open_failed")
        val parsed = try {
            connection.rawDescriptors?.let(UacDescriptorParser::parse)
        } catch (_: SecurityException) {
            connection.close()
            return DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.permission_required")
        } catch (_: IllegalStateException) {
            connection.close()
            return DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.usb_transfer_failed")
        }
        if (parsed !is UacParseResult.Parsed) {
            connection.close()
            return DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.descriptors_refused")
        }
        val record = UsbTakeoverRecovery(marker.device, AndroidUsbLink(device, connection), parsed.topology.functions, markers).recover()
        val failure = record.firstFailure ?: return DirectUsbRecoveryResult(cleanup = record)
        val code = if (failure.name == "volume restored") "direct_usb.volume_control_failed" else "direct_usb.usb_transfer_failed"
        return DirectUsbRecoveryResult(UsbOutputFailure.PLATFORM_ERROR, code, record)
    }

    override fun hasUsbAudioDevice(): Boolean = usbManager.deviceList.values.any(UsbDevice::isUsbAudioDevice)

    override fun inspect(source: SourcePcmFormat): DirectUsbInspection {
        val devices = usbManager.deviceList.values.filter(UsbDevice::isUsbAudioDevice)
        val device = when (devices.size) {
            0 -> return DirectUsbInspection.NoDevice
            1 -> devices.single()
            else -> return DirectUsbInspection.SeveralDevices
        }
        if (!usbManager.hasPermission(device)) return DirectUsbInspection.PermissionNeeded(device.displayName())
        val held = prepared
        if (held != null && streaming && held.device.deviceId == device.deviceId && held.plan.carries(source)) {
            return DirectUsbInspection.Ready(device.displayName(), held.plan)
        }
        release()
        val name = device.displayName()
        val connection = usbManager.openDevice(device) ?: return DirectUsbInspection.Refused("open_failed", deviceName = name)
        val raw = connection.rawDescriptors
        val parsed = raw?.let(UacDescriptorParser::parse)
        if (parsed !is UacParseResult.Parsed) {
            connection.close()
            return DirectUsbInspection.Refused("descriptors_refused", deviceName = name)
        }
        val speed = UsbStreamEngine.busSpeed(connection.fileDescriptor)
        val refusals = mutableListOf<StreamingRefusal>()
        // The deepest depth the device offers at this rate: tracks of other depths then share the
        // DAC's setting, 16-bit samples gaining zeros below them (#65).
        for (bits in WIRE_DEPTHS.filter { it >= source.bitDepth }) {
            when (val preflight = StreamPreflight.run(parsed.topology, speed, source.sampleRateHz, bits)) {
                is StreamPreflight.Ready -> {
                    // The settings the own output streams through are stereo, so a known mono or
                    // multichannel source is refused here, before anything is claimed (#295).
                    if (!preflight.plan.carries(source)) {
                        connection.close()
                        return DirectUsbInspection.Refused(
                            "channels_unsupported",
                            formatRefused = true,
                            deviceName = name,
                            details = source.toDirectDetails().withOffer(preflight.function),
                        )
                    }
                    prepared = Prepared(device, connection, preflight.function, preflight.plan)
                    return DirectUsbInspection.Ready(name, preflight.plan)
                }
                StreamPreflight.NoPlaybackFunction ->
                    return DirectUsbInspection.Refused("no_playback_function", deviceName = name).also { connection.close() }
                is StreamPreflight.LibraryUnavailable ->
                    return DirectUsbInspection.Refused("engine_unavailable", deviceName = name).also { connection.close() }
                is StreamPreflight.Refused -> refusals += preflight.reason
            }
        }
        connection.close()
        return directRefusal(parsed.topology, refusals, source).copy(deviceName = name)
    }

    override fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        val candidates = usbManager.deviceList.values.filter(UsbDevice::isUsbAudioDevice)
        val pending = markers.recoveryRecord()?.device
        val device = candidates.firstOrNull { pending != null && it.deviceId == pending.deviceId &&
            it.vendorId == pending.vendorId && it.productId == pending.productId }
            ?: candidates.singleOrNull() ?: return onResult(false)
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
        val identity = UsbDeviceIdentity(target.device.deviceId, target.device.vendorId, target.device.productId)
        lateinit var next: DirectUsbSession
        next = DirectUsbSession(
            device = identity,
            link = AndroidUsbLink(target.device, target.connection) { usbManager.hasPermission(target.device) },
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
        next.takeOver(target.plan, volumeMemory.startVolume(identity)) { result ->
            main.post {
                if (session !== next) return@post
                when (result) {
                    is DirectUsbSession.Takeover.Ready -> {
                        streamingFacts = StreamingFacts(
                            next, identity, target.plan, target.function.protocolToken(), target.function.syncToken(target.plan), result.volumeNow,
                        )
                        next.volumeRange?.let { volumes.publish(DirectUsbVolume(result.volumeNow, it.minimum, it.maximum, it.resolution)) }
                        sessionProvider = DirectUsbAudioOutputProvider(
                            target.plan,
                            openOutput = next::openOutput,
                            source = { configuringSource },
                            onFailure = { failure ->
                                val details = configuringSource?.toDirectDetails()?.withOffer(target.function)
                                if (configuringGeneration == outputGeneration) listener?.onFailed(failure, details, configuringPeriodUid)
                            },
                            listenerFactory = {
                                val uid = configuringPeriodUid
                                val generation = outputGeneration
                                val token = Any()
                                val details = configuringSource?.toDirectDetails()?.withOffer(target.function)
                                if (uid != null) main.post { if (session === next && outputGeneration == generation) listener?.onOutputOpened(uid, token) }
                                object : DirectUsbAudioOutput.PlaybackListener {
                                    override fun onActiveChanged(active: Boolean) {
                                        if (uid != null) main.post {
                                            if (session === next && outputGeneration == generation) listener?.onOutputActive(uid, token, active)
                                        }
                                    }

                                    override fun onFailed(failure: DirectUsbFailure) {
                                        if (session === next && outputGeneration == generation) listener?.onFailed(failure, details, uid, token)
                                    }
                                }
                            },
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

    override fun readings(): DirectUsbReadings? {
        val facts = streamingFacts ?: return null
        val totals = facts.session.totals()
        return DirectUsbReadings(
            sampleRateHz = facts.plan.sampleRateHz,
            bitDepth = facts.plan.bitResolution,
            subslotBytes = facts.plan.subslotBytes,
            channelCount = facts.plan.channels,
            protocol = facts.protocol,
            syncType = facts.syncType,
            // UAC volume is in 1/256 dB.
            hardwareVolumeDb = facts.volume / 256.0,
            deliveredFrames = totals.deliveredFrames,
            underruns = totals.underruns,
            lostFrames = totals.lostFrames,
            failedPackets = totals.failedPackets,
        )
    }

    /** Main thread. The device's read-back, not the request, is what gets published and remembered. */
    private fun setVolume(target: Int) {
        val facts = streamingFacts ?: return
        if (volumeInFlight) {
            volumeWaiting = target
            return
        }
        volumeInFlight = true
        facts.session.setVolume(target) { readBack ->
            main.post {
                volumeInFlight = false
                val waiting = volumeWaiting
                volumeWaiting = null
                if (streamingFacts === facts && readBack != null) {
                    facts.volume = readBack
                    volumes.state.value?.let { volumes.publish(it.copy(current = readBack), settled = waiting == null) }
                    volumeMemory.remember(facts.device, readBack)
                }
                if (waiting != null) setVolume(waiting)
            }
        }
    }

    override fun release(onReleased: ((Boolean) -> Unit)?) {
        streamingFacts = null
        volumeWaiting = null
        volumes.publish(null)
        streaming = false
        sessionProvider = null
        val current = session
        session = null
        val held = prepared
        prepared = null
        if (current != null) {
            releases.begin()
            current.release { cleanup ->
                main.post {
                    lastCleanup = cleanup
                    releases.complete(cleanup.firstFailure == null)
                    listener?.onReleased(cleanup)
                }
            }
        } else {
            held?.connection?.close()
        }
        onReleased?.let(releases::await)
    }

    override fun close() {
        release()
        volumes.detach(volumeSetter)
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
        runCatching { appContext.unregisterReceiver(detachReceiver) }
    }

    private fun UsbDevice.displayName(): String = productName?.takeIf(String::isNotBlank) ?: "USB audio $deviceId"

    private companion object {
        // Cleanup retries must survive PlaybackService destruction. The executor starts on first use.
        val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "usb-control") }
        const val ACTION_PERMISSION = "io.github.sumirenokai.vesqen.playback.USB_PERMISSION"

        val WIRE_DEPTHS = listOf(32, 24, 16)
    }
}

/** Survives the process, so a later start can give back a device a crash left claimed (M7-4). */
internal class PreferencesTakeoverMarkerStore(context: Context) : TakeoverMarkerStore() {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val preferences = context.getSharedPreferences("direct_usb_takeover", Context.MODE_PRIVATE)

    override fun hasPendingRecovery(): Boolean {
        if (!preferences.contains(DEVICE_ID)) return false
        // A physically removed connection cannot be restored. A still-live owner is protected by acquire().
        val id = preferences.getInt(DEVICE_ID, -1)
        return usbManager.deviceList.values.any { it.deviceId == id &&
            it.vendorId == preferences.getInt(VENDOR_ID, -1) && it.productId == preferences.getInt(PRODUCT_ID, -1) }
    }

    override fun read(): TakeoverMarker? {
        if (!preferences.contains(DEVICE_ID)) return null
        return TakeoverMarker(
            UsbDeviceIdentity(preferences.getInt(DEVICE_ID, -1), preferences.getInt(VENDOR_ID, -1), preferences.getInt(PRODUCT_ID, -1)),
            volumeBefore = if (preferences.contains(VOLUME_BEFORE)) preferences.getInt(VOLUME_BEFORE, 0) else null,
            mutedBefore = if (preferences.contains(MUTED_BEFORE)) preferences.getBoolean(MUTED_BEFORE, false) else null,
            controlInterface = if (preferences.contains(CONTROL_INTERFACE)) preferences.getInt(CONTROL_INTERFACE, -1) else null,
            volumeUnitId = if (preferences.contains(VOLUME_UNIT_ID)) preferences.getInt(VOLUME_UNIT_ID, -1) else null,
        )
    }

    override fun write(marker: TakeoverMarker) = preferences.edit().apply {
        putInt(DEVICE_ID, marker.device.deviceId)
        putInt(VENDOR_ID, marker.device.vendorId)
        putInt(PRODUCT_ID, marker.device.productId)
        marker.volumeBefore?.let { putInt(VOLUME_BEFORE, it) } ?: remove(VOLUME_BEFORE)
        marker.mutedBefore?.let { putBoolean(MUTED_BEFORE, it) } ?: remove(MUTED_BEFORE)
        marker.controlInterface?.let { putInt(CONTROL_INTERFACE, it) } ?: remove(CONTROL_INTERFACE)
        marker.volumeUnitId?.let { putInt(VOLUME_UNIT_ID, it) } ?: remove(VOLUME_UNIT_ID)
    }.commit()

    override fun clear() = preferences.edit().clear().commit()

    private companion object {
        const val DEVICE_ID = "device_id"
        const val VENDOR_ID = "vendor_id"
        const val PRODUCT_ID = "product_id"
        const val VOLUME_BEFORE = "volume_before"
        const val MUTED_BEFORE = "muted_before"
        const val CONTROL_INTERFACE = "control_interface"
        const val VOLUME_UNIT_ID = "volume_unit_id"
    }
}

/**
 * The own path's failures in the existing strict vocabulary, which device reports also use. The
 * decision code keeps the exact reason; dedicated failure kinds and their texts come with M7-4's
 * user-facing step, together with the report vocabulary.
 */
internal fun DirectUsbFailure.toStrictFailure(): Pair<UsbOutputFailure, String> = when (reason) {
    DirectUsbFailure.Reason.SOURCE_FORMAT_UNKNOWN -> UsbOutputFailure.SOURCE_FORMAT_UNKNOWN
    DirectUsbFailure.Reason.SOURCE_NOT_LOSSLESS,
    DirectUsbFailure.Reason.SOURCE_BIT_DEPTH_UNSUPPORTED,
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
    DirectUsbFailure.Reason.USB_PERMISSION_REVOKED,
    DirectUsbFailure.Reason.PCM_NOT_EXACT -> UsbOutputFailure.PLATFORM_ERROR
} to "direct_usb.${reason.name.lowercase()}"
