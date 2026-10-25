package io.github.sumirenokai.vesqen.telemetry

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/** Estimated-evidence method: the nominal ceiling of the LDAC quality tier Android reports. */
internal const val LDAC_NOMINAL_BITRATE_METHOD = "bluetooth.ldac_quality_nominal_bitrate"

/**
 * LDAC playback quality as Android reports it in `codecSpecific1`. The values are AOSP's
 * (1000–1003); the SDK does not name them. On the iQOO 9 (Android 15, 2026-10-16) choosing 990 kbps
 * was reported as 1003, the adaptive value, so 1003 cannot be shown as adaptive alone (#73).
 * [nominalBitsPerSecond] is the tier's ceiling, an estimate: adaptive LDAC also tops out at 990 kbps,
 * so 1003's ceiling holds either way, and its kbps is labelled Estimated like the others (#136 R5).
 */
enum class LdacQuality(val token: String, val nominalBitsPerSecond: Long) {
    KBPS_990("ldac_990kbps", 990_000),
    KBPS_660("ldac_660kbps", 660_000),
    KBPS_330("ldac_330kbps", 330_000),
    ADAPTIVE_OR_990("ldac_adaptive_or_990kbps", 990_000),
    ;

    companion object {
        fun fromCodecSpecific1(value: Long): LdacQuality? = when (value) {
            1000L -> KBPS_990
            1001L -> KBPS_660
            1002L -> KBPS_330
            1003L -> ADAPTIVE_OR_990
            else -> null
        }

        fun fromToken(token: String): LdacQuality? = entries.firstOrNull { it.token == token }
    }
}

/**
 * The negotiated parameters of one codec report. [token] is the telemetry text, for example
 * `96000hz,32bit,stereo,ldac_990kbps`, so exports stay language-neutral; [parse] reads it back.
 */
data class BluetoothCodecSettings(
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    val channelCount: Int?,
    val ldacQuality: LdacQuality?,
) {
    val isEmpty: Boolean get() = sampleRateHz == null && bitsPerSample == null && channelCount == null && ldacQuality == null

    fun token(): String? = listOfNotNull(
        sampleRateHz?.let { "${it}hz" },
        bitsPerSample?.let { "${it}bit" },
        when (channelCount) {
            1 -> "mono"
            2 -> "stereo"
            else -> null
        },
        ldacQuality?.token,
    ).joinToString(",").ifEmpty { null }

    companion object {
        fun parse(token: String): BluetoothCodecSettings {
            val parts = token.split(',')
            fun number(suffix: String) = parts.firstNotNullOfOrNull { part ->
                if (part.endsWith(suffix)) part.removeSuffix(suffix).toIntOrNull() else null
            }
            return BluetoothCodecSettings(
                sampleRateHz = number("hz"),
                bitsPerSample = number("bit"),
                channelCount = when {
                    "mono" in parts -> 1
                    "stereo" in parts -> 2
                    else -> null
                },
                ldacQuality = parts.firstNotNullOfOrNull(LdacQuality::fromToken),
            )
        }
    }
}

/** The codec Android last reported for an A2DP device. [codecName] is null for an unnamed vendor codec. */
internal data class BluetoothCodecReport(
    val codecName: String?,
    val settings: BluetoothCodecSettings,
    val receivedAt: TelemetryInstant,
)

/**
 * Keeps the last report and drops it once it may no longer describe the active device: that device
 * disconnects, another device becomes active, or Bluetooth turns off. Device keys stay in memory.
 */
internal class BluetoothCodecState {
    private var deviceKey: String? = null
    private var report: BluetoothCodecReport? = null

    val current: BluetoothCodecReport? get() = report

    fun onCodecReported(device: String?, value: BluetoothCodecReport) {
        deviceKey = device
        report = value
    }

    fun onDisconnected(device: String?) {
        if (device == null || device == deviceKey) clear()
    }

    fun onActiveDeviceChanged(device: String?) {
        if (device != deviceKey) clear()
    }

    fun clear() {
        deviceKey = null
        report = null
    }
}

/** Decodes the single-bit fields of the current `BluetoothCodecConfig`; a mask with more or no bits is unknown. */
internal object BluetoothCodecFields {
    private val sampleRates = mapOf(
        0x01 to 44_100, 0x02 to 48_000, 0x04 to 88_200, 0x08 to 96_000, 0x10 to 176_400, 0x20 to 192_000,
        0x40 to 16_000, 0x80 to 24_000, 0x100 to 32_000, 0x200 to 8_000,
    )
    private val bitDepths = mapOf(0x1 to 16, 0x2 to 24, 0x4 to 32)
    private val channels = mapOf(0x1 to 1, 0x2 to 2)

    /**
     * The AOSP codec types no vendor stack is known to reuse. AOSP numbers LC3 5 and Opus 6, but a
     * vendor stack may use those numbers for its own codecs (aptX Adaptive, unconfirmed), and LE
     * Audio sends no A2DP report at all, so 5 and 6 are named only by the system's own name from
     * API 35 (#160).
     */
    private val standardNames = mapOf(0 to "SBC", 1 to "AAC", 2 to "aptX", 3 to "aptX HD", 4 to "LDAC")

    fun standardCodecName(type: Int): String? = standardNames[type]

    fun settings(isLdac: Boolean, sampleRate: Int, bits: Int, channelMode: Int, codecSpecific1: Long) =
        BluetoothCodecSettings(
            sampleRateHz = sampleRates[sampleRate],
            bitsPerSample = bitDepths[bits],
            channelCount = channels[channelMode],
            ldacQuality = if (isLdac) LdacQuality.fromCodecSpecific1(codecSpecific1) else null,
        )
}

/**
 * Receives `CODEC_CONFIG_CHANGED`, which Android sends only to apps holding BLUETOOTH_CONNECT and
 * only when a codec is (re)negotiated, so headphones connected before Vesqen started report nothing
 * until they reconnect. The receiver lives as long as the telemetry runtime: it caches events and
 * does no sampling. The action string is a system API; the extras are public since API 33.
 */
internal class AndroidBluetoothCodecObserver(
    context: Context,
    private val clock: TelemetryClock,
) {
    private val appContext = context.applicationContext
    private val state = BluetoothCodecState()
    private val lock = Any()

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val permissionGranted: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    val current: BluetoothCodecReport? get() = synchronized(lock) { state.current }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) handle(intent)
        }
    }

    /** Registering without the permission is allowed; delivery starts once the user grants it. */
    fun start() {
        if (!supported) return
        val filter = IntentFilter().apply {
            addAction(ACTION_CODEC_CONFIG_CHANGED)
            addAction(ACTION_ACTIVE_DEVICE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun handle(intent: Intent) {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.let(::deviceKey)
        synchronized(lock) {
            when (intent.action) {
                ACTION_CODEC_CONFIG_CHANGED -> {
                    val config = intent.getParcelableExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS, BluetoothCodecStatus::class.java)
                        ?.codecConfig
                    if (config == null) state.onDisconnected(device) else state.onCodecReported(device, report(config))
                }
                ACTION_ACTIVE_DEVICE_CHANGED -> state.onActiveDeviceChanged(device)
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_DISCONNECTED) {
                        state.onDisconnected(device)
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) state.clear()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun report(config: BluetoothCodecConfig): BluetoothCodecReport {
        @Suppress("DEPRECATION") val type = config.codecType
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            config.extendedCodecType?.codecName?.takeIf(String::isNotBlank)
        } else {
            null
        } ?: BluetoothCodecFields.standardCodecName(type)
        return BluetoothCodecReport(
            codecName = name,
            settings = BluetoothCodecFields.settings(
                isLdac = name == "LDAC" || type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                sampleRate = config.sampleRate,
                bits = config.bitsPerSample,
                channelMode = config.channelMode,
                codecSpecific1 = config.codecSpecific1,
            ),
            receivedAt = clock.now(),
        )
    }

    // Only compared for equality within this process; never stored, shown or exported.
    private fun deviceKey(device: BluetoothDevice): String = device.address

    private companion object {
        const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
        const val ACTION_ACTIVE_DEVICE_CHANGED = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
    }
}
