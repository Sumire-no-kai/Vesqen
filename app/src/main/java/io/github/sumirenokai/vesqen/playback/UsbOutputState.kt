package io.github.sumirenokai.vesqen.playback

import android.os.Bundle
import java.util.concurrent.CopyOnWriteArraySet

enum class UsbOutputMode {
    SYSTEM,
    STRICT_BIT_PERFECT,
}

enum class UsbOutputPhase {
    SYSTEM,
    AVAILABLE,
    APPLYING,
    ACTIVE,
    FAILED,
}

enum class UsbOutputFailure {
    UNSUPPORTED_ANDROID_VERSION,
    USB_HOST_UNAVAILABLE,
    MODIFY_AUDIO_SETTINGS_DENIED,
    NO_USB_AUDIO_DEVICE,
    SOURCE_FORMAT_UNKNOWN,
    SOURCE_FORMAT_UNSUPPORTED,
    MIXER_QUERY_FAILED,
    NO_MATCHING_MIXER_ATTRIBUTE,
    MIXER_REQUEST_REJECTED,
    MIXER_READBACK_MISMATCH,
    AUDIO_TRACK_FORMAT_MISMATCH,
    PREFERRED_DEVICE_REJECTED,
    ROUTE_UNAVAILABLE,
    ROUTE_MISMATCH,
    DEVICE_DISCONNECTED,
    PROCESSING_NOT_NEUTRAL,
    SERVICE_STOPPED,
    PLATFORM_ERROR,
}

data class AudioFormatSummary(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: String,
) {
    init {
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(channelCount > 0) { "Channel count must be positive" }
        require(encoding.isNotBlank()) { "Encoding cannot be blank" }
    }

    val displayName: String
        get() = "${sampleRateHz / 1_000f} kHz · $encoding · $channelCount ch"
}

data class UsbOutputStatus(
    val mode: UsbOutputMode = UsbOutputMode.SYSTEM,
    val phase: UsbOutputPhase = UsbOutputPhase.SYSTEM,
    val failure: UsbOutputFailure? = null,
    val deviceName: String? = null,
    val sourceFormat: AudioFormatSummary? = null,
    val sinkFormat: AudioFormatSummary? = null,
    val decisionCode: String = "system_audio_route",
    val observedAtEpochMs: Long = 0,
    val observedAtElapsedRealtimeMs: Long = 0,
    val generation: Long = 0,
) {
    init {
        require(observedAtEpochMs >= 0) { "Output state timestamp cannot be negative" }
        require(observedAtElapsedRealtimeMs >= 0) { "Output state monotonic timestamp cannot be negative" }
        require(generation >= 0) { "Output state generation cannot be negative" }
        require(deviceName == null || deviceName.isNotBlank()) { "Device name cannot be blank" }
        require(decisionCode.matches(DECISION_CODE_PATTERN)) { "Output decision code must be stable" }
        require((phase == UsbOutputPhase.FAILED) == (failure != null)) {
            "Only failed output states may retain a failure reason"
        }
        require(mode != UsbOutputMode.SYSTEM || phase == UsbOutputPhase.SYSTEM) {
            "System mode cannot retain a strict USB phase"
        }
    }

    val declaration: OutputDeclaration
        get() = when (phase) {
            UsbOutputPhase.SYSTEM -> OutputDeclaration.SYSTEM_MIXED
            UsbOutputPhase.AVAILABLE -> OutputDeclaration.BIT_PERFECT_AVAILABLE
            UsbOutputPhase.APPLYING -> OutputDeclaration.BIT_PERFECT_REQUESTED
            UsbOutputPhase.ACTIVE -> OutputDeclaration.BIT_PERFECT_ACTIVE
            UsbOutputPhase.FAILED -> OutputDeclaration.BIT_PERFECT_FAILED
        }

    companion object {
        private val DECISION_CODE_PATTERN = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*")
    }
}

/** Process-wide read model. PlaybackService is the only writer. */
class UsbOutputStateRepository {
    private val listeners = CopyOnWriteArraySet<(UsbOutputStatus) -> Unit>()
    @Volatile private var current = UsbOutputStatus()

    fun snapshot(): UsbOutputStatus = current

    fun publish(status: UsbOutputStatus) {
        synchronized(this) {
            if (status.generation <= current.generation) return
            current = status
            listeners.forEach { listener -> listener(status) }
        }
    }

    fun addListener(listener: (UsbOutputStatus) -> Unit) {
        synchronized(this) {
            listeners += listener
            listener(current)
        }
    }

    fun removeListener(listener: (UsbOutputStatus) -> Unit) {
        synchronized(this) { listeners -= listener }
    }
}

internal object UsbOutputSessionContract {
    const val SET_MODE_ACTION = "io.github.sumirenokai.vesqen.playback.SET_USB_OUTPUT_MODE"
    const val MODE_ARGUMENT = "usb_output_mode"

    private const val PHASE = "usb_output_phase"
    private const val FAILURE = "usb_output_failure"
    private const val DEVICE_NAME = "usb_output_device_name"
    private const val DECISION_CODE = "usb_output_decision_code"
    private const val OBSERVED_AT = "usb_output_observed_at"
    private const val OBSERVED_AT_ELAPSED = "usb_output_observed_at_elapsed"
    private const val GENERATION = "usb_output_generation"
    private const val SOURCE_RATE = "usb_output_source_rate"
    private const val SOURCE_CHANNELS = "usb_output_source_channels"
    private const val SOURCE_ENCODING = "usb_output_source_encoding"
    private const val SINK_RATE = "usb_output_sink_rate"
    private const val SINK_CHANNELS = "usb_output_sink_channels"
    private const val SINK_ENCODING = "usb_output_sink_encoding"

    fun modeArguments(mode: UsbOutputMode): Bundle = Bundle().apply {
        putString(MODE_ARGUMENT, mode.name)
    }

    fun readMode(arguments: Bundle): UsbOutputMode? = arguments.getString(MODE_ARGUMENT)
        ?.let { stored -> UsbOutputMode.entries.firstOrNull { it.name == stored } }

    fun toBundle(status: UsbOutputStatus): Bundle = Bundle().apply {
        putString(MODE_ARGUMENT, status.mode.name)
        putString(PHASE, status.phase.name)
        status.failure?.let { putString(FAILURE, it.name) }
        status.deviceName?.let { putString(DEVICE_NAME, it) }
        putString(DECISION_CODE, status.decisionCode)
        putLong(OBSERVED_AT, status.observedAtEpochMs)
        putLong(OBSERVED_AT_ELAPSED, status.observedAtElapsedRealtimeMs)
        putLong(GENERATION, status.generation)
        status.sourceFormat?.writeTo(this, SOURCE_RATE, SOURCE_CHANNELS, SOURCE_ENCODING)
        status.sinkFormat?.writeTo(this, SINK_RATE, SINK_CHANNELS, SINK_ENCODING)
    }

    fun fromBundle(bundle: Bundle?): UsbOutputStatus? {
        bundle ?: return null
        val mode = bundle.getString(MODE_ARGUMENT)?.let { stored ->
            UsbOutputMode.entries.firstOrNull { it.name == stored }
        } ?: return null
        val phase = bundle.getString(PHASE)?.let { stored ->
            UsbOutputPhase.entries.firstOrNull { it.name == stored }
        } ?: return null
        val failure = bundle.getString(FAILURE)?.let { stored ->
            UsbOutputFailure.entries.firstOrNull { it.name == stored }
        }
        return runCatching {
            UsbOutputStatus(
                mode = mode,
                phase = phase,
                failure = failure,
                deviceName = bundle.getString(DEVICE_NAME),
                sourceFormat = bundle.readFormat(SOURCE_RATE, SOURCE_CHANNELS, SOURCE_ENCODING),
                sinkFormat = bundle.readFormat(SINK_RATE, SINK_CHANNELS, SINK_ENCODING),
                decisionCode = bundle.getString(DECISION_CODE) ?: "session_state_missing_decision",
                observedAtEpochMs = bundle.getLong(OBSERVED_AT).coerceAtLeast(0),
                observedAtElapsedRealtimeMs = bundle.getLong(OBSERVED_AT_ELAPSED).coerceAtLeast(0),
                generation = bundle.getLong(GENERATION).coerceAtLeast(0),
            )
        }.getOrNull()
    }

    private fun AudioFormatSummary.writeTo(
        bundle: Bundle,
        rateKey: String,
        channelKey: String,
        encodingKey: String,
    ) {
        bundle.putInt(rateKey, sampleRateHz)
        bundle.putInt(channelKey, channelCount)
        bundle.putString(encodingKey, encoding)
    }

    private fun Bundle.readFormat(rateKey: String, channelKey: String, encodingKey: String): AudioFormatSummary? {
        val rate = getInt(rateKey).takeIf { it > 0 } ?: return null
        val channels = getInt(channelKey).takeIf { it > 0 } ?: return null
        val encoding = getString(encodingKey)?.takeIf(String::isNotBlank) ?: return null
        return AudioFormatSummary(rate, channels, encoding)
    }
}
