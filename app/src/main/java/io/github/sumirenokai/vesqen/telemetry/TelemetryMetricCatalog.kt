package io.github.sumirenokai.vesqen.telemetry

enum class TelemetryValueKind {
    TEXT,
    FLAG,
    INTEGER,
    DECIMAL,
    USB_INVENTORY,
}

enum class TelemetryExportPolicy {
    INCLUDE,
    REDACT_TEXT,
    REDACT_DEVICE_IDENTIFIERS,
}

data class TelemetryMetricDescriptor(
    val id: TelemetryMetricId,
    val section: TelemetrySection,
    val valueKind: TelemetryValueKind,
    val unit: TelemetryUnit? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val defaultVisible: Boolean = false,
    val chartable: Boolean = false,
    val exportPolicy: TelemetryExportPolicy = TelemetryExportPolicy.REDACT_TEXT,
)

/**
 * The fail-closed schema for every metric accepted by Audio Proof.
 *
 * Keeping identity, section, value type, unit, range and privacy together prevents adapters from
 * inventing subtly incompatible meanings for the same stable id.
 */
object TelemetryMetricCatalog {
    val SOURCE_CONTAINER = id("source.container")
    val SOURCE_CODEC_MIME = id("source.codec_mime")
    val SOURCE_CODEC_LABEL = id("source.codec_label")
    val SOURCE_SAMPLE_RATE = id("source.sample_rate")
    val SOURCE_BIT_DEPTH = id("source.bit_depth")
    val SOURCE_CHANNEL_COUNT = id("source.channel_count")
    val SOURCE_AVERAGE_BITRATE = id("source.average_bitrate")
    val SOURCE_FILE_SIZE = id("source.file_size")

    val DECODER_NAME = id("decoder.name")
    val DECODER_INPUT_MIME = id("decoder.input_mime")
    val DECODER_INPUT_SAMPLE_RATE = id("decoder.input_sample_rate")
    val DECODER_INPUT_CHANNEL_COUNT = id("decoder.input_channel_count")
    val DECODER_INITIALIZATION_DURATION = id("decoder.initialization_duration")
    val DECODER_SOFTWARE_ONLY = id("decoder.software_only")
    val DECODER_HARDWARE_ACCELERATED = id("decoder.hardware_accelerated")
    val DECODER_VENDOR = id("decoder.is_vendor")
    val DECODER_PATH = id("decoder.path")
    val DECODER_OUTPUT_SAMPLE_RATE = id("decoder.output_sample_rate")
    val DECODER_OUTPUT_ENCODING = id("decoder.output_encoding")
    val DECODER_OUTPUT_CHANNEL_CONFIG = id("decoder.output_channel_config")
    val DECODER_COMPRESSED_FRAME_SIZE = id("decoder.compressed_frame_size")
    val DECODER_INPUT_BUFFER_RATE = id("decoder.input_buffer_rate")
    val DECODER_OUTPUT_BUFFER_RATE = id("decoder.output_buffer_rate")
    val DECODER_FRAME_DECODE_TIME = id("decoder.frame_decode_time")
    val DECODER_FORMAT_CHANGE_COUNT = id("decoder.format_change_count")
    val DECODER_RENDERER_QUEUED_INPUT_BUFFERS_TOTAL = id("decoder.renderer_queued_input_buffers_total")
    val DECODER_RENDERER_RENDERED_OUTPUT_BUFFERS_TOTAL = id("decoder.renderer_rendered_output_buffers_total")
    val DECODER_RENDERER_SKIPPED_OUTPUT_BUFFERS_TOTAL = id("decoder.renderer_skipped_output_buffers_total")

    val PROCESSING_SPEED = id("processing.speed")
    val PROCESSING_PITCH = id("processing.pitch")
    val PROCESSING_PLAYER_VOLUME = id("processing.player_volume")
    val PROCESSING_SKIP_SILENCE = id("processing.skip_silence")
    val PROCESSING_REPLAY_GAIN_ACTIVE = id("processing.replay_gain_active")
    val PROCESSING_EQUALIZER_ACTIVE = id("processing.equalizer_active")
    val PROCESSING_CROSSFADE_ACTIVE = id("processing.crossfade_active")
    val PROCESSING_LOUDNESS_ACTIVE = id("processing.loudness_active")
    val PROCESSING_SAMPLE_RATE_CONVERSION = id("processing.sample_rate_conversion_detected")
    val PROCESSING_APP_DSP_ACTIVE = id("processing.app_dsp_active")

    val PLAYBACK_STATE = id("playback.state")
    val PLAYBACK_IS_PLAYING = id("playback.is_playing")
    val PLAYBACK_LAST_EVENT_POSITION = id("playback.last_event_position")
    val PLAYBACK_POSITION = id("playback.position")
    val PLAYBACK_DURATION = id("playback.duration")
    val PLAYBACK_ESTIMATED_TOTAL_BUFFERED_DURATION = id("playback.estimated_total_buffered_duration")
    val PLAYBACK_CURRENT_MEDIA_BYTES_READ = id("playback.current_media_bytes_read")
    val PLAYBACK_CURRENT_MEDIA_READ_BITRATE = id("playback.current_media_read_bitrate")
    val PROCESS_DATA_SOURCE_BYTES_TRANSFERRED = id("process.data_source_bytes_transferred_since_start")
    val PROCESS_DATA_SOURCE_READ_THROUGHPUT = id("process.data_source_read_throughput")
    val PLAYBACK_UNDERRUN_COUNT = id("playback.underrun_count")
    val PLAYBACK_LAST_UNDERRUN_FEED_GAP = id("playback.last_underrun_feed_gap")
    val PLAYBACK_AUDIO_TRACK_SAMPLE_RATE = id("playback.audio_track_sample_rate")
    val PLAYBACK_AUDIO_TRACK_ENCODING = id("playback.audio_track_encoding")
    val PLAYBACK_AUDIO_TRACK_CHANNEL_MASK = id("playback.audio_track_channel_mask")
    val PLAYBACK_AUDIO_TRACK_BUFFER_SIZE = id("playback.audio_track_buffer_size")
    val PLAYBACK_PCM_DATA_RATE = id("playback.pcm_data_rate")
    val PLAYBACK_OFFLOAD = id("playback.offload")
    val PLAYBACK_TUNNELING = id("playback.tunneling")
    val PLAYBACK_TARGET_BUFFER_DURATION = id("playback.target_buffer_duration")
    val PLAYBACK_PREBUFFER_TARGET_DURATION = id("playback.prebuffer_target_duration")
    val PLAYBACK_LAST_SEEK_LATENCY = id("playback.last_seek_latency")
    val PLAYBACK_LAST_GAPLESS_TRANSITION_GAP = id("playback.last_gapless_transition_gap")
    val PLAYBACK_AUDIO_TRACK_TIMESTAMP = id("playback.audio_track_timestamp")
    val PLAYBACK_CLOCK_DEVIATION = id("playback.clock_deviation")
    val PLAYBACK_LAST_ERROR_CODE = id("playback.last_error_code")

    val PROCESS_CPU_TIME = id("process.cpu_time")
    val PROCESS_CPU_PERCENT = id("process.cpu_percent")
    val PROCESS_JAVA_HEAP = id("process.java_heap")
    val PROCESS_NATIVE_HEAP = id("process.native_heap")
    val PROCESS_PSS = id("process.pss")
    val PROCESSOR_COUNT = id("process.processor_count")
    val PROCESS_PLAYBACK_THREAD_CPU_TIME = id("process.playback_thread_cpu_time")
    val PROCESS_GC_COUNT = id("process.gc_count")
    val PROCESS_GC_TIME = id("process.gc_time")
    val PROCESS_CPU_CORE_STATE = id("process.cpu_core_state")
    val PROCESS_THERMAL_STATUS = id("process.thermal_status")
    val PROCESS_SOC_MODEL = id("process.soc_model")
    val POWER_BATTERY_CURRENT = id("power.battery_current")
    val POWER_BATTERY_VOLTAGE = id("power.battery_voltage")
    val POWER_BATTERY_TEMPERATURE = id("power.battery_temperature")
    val POWER_DEVICE_ESTIMATE = id("power.device_estimate")

    val ROUTE_SELECTED_SYSTEM_NAME = id("route.selected_system_name")
    val ROUTE_SELECTED_SYSTEM_TYPE = id("route.selected_system_type")
    val ROUTE_ANTICIPATED_NAME = id("route.anticipated_name")
    val ROUTE_ANTICIPATED_TYPE = id("route.anticipated_type")
    val ROUTE_CONNECTED_TYPES = id("route.connected_types")
    val ROUTE_REQUEST_FORMAT_DIRECT_SUPPORTED = id("route.request_format_direct_supported")
    val ROUTE_REQUEST_FORMAT_DIRECT_MODES = id("route.request_format_direct_modes")
    val ROUTE_ANTICIPATED_MIXER_PROFILE_COUNT = id("route.anticipated_mixer_profile_count")
    val ROUTE_ANTICIPATED_PREFERRED_MIXER_PROFILE = id("route.anticipated_preferred_mixer_profile")
    val ROUTE_OUTPUT_DECLARATION = id("route.output_declaration")
    val ROUTE_AUDIO_TRACK_REQUEST_FORMAT = id("route.audio_track_request_format")
    val ROUTE_OBSERVED_OUTPUT_FORMAT = id("route.observed_output_format")
    val ROUTE_BLUETOOTH_CODEC = id("route.bluetooth_codec")
    val ROUTE_BLUETOOTH_CONNECTED_NAMES = id("route.bluetooth_connected_names")
    val ROUTE_BLUETOOTH_CONNECTED_TYPES = id("route.bluetooth_connected_types")
    val ROUTE_BLUETOOTH_CAPABILITIES = id("route.bluetooth_capabilities")
    val ROUTE_BLUETOOTH_CONFIGURATION = id("route.bluetooth_configuration")
    val ROUTE_BLUETOOTH_CONFIGURED_BITRATE = id("route.bluetooth_configured_bitrate")
    val ROUTE_BLUETOOTH_TRANSPORT_BITRATE = id("route.bluetooth_transport_bitrate")
    val ROUTE_SYSTEM_MUSIC_VOLUME = id("route.system_music_volume")
    val ROUTE_SYSTEM_MUSIC_MUTED = id("route.system_music_muted")
    val ROUTE_SYSTEM_DSP_STATE = id("route.system_dsp_state")
    val ROUTE_LAST_STRATEGY_DECISION = id("route.last_strategy_decision")

    val USB_HOST_SUPPORTED = id("usb.host_supported")
    val USB_AUDIO_DEVICE_COUNT = id("usb.audio_device_count")
    val USB_DEVICE_INVENTORY = id("usb.device_inventory")

    val descriptors: List<TelemetryMetricDescriptor> = listOf(
        safeText(SOURCE_CONTAINER, TelemetrySection.SOURCE, defaultVisible = true),
        safeText(SOURCE_CODEC_MIME, TelemetrySection.SOURCE),
        safeText(SOURCE_CODEC_LABEL, TelemetrySection.SOURCE, defaultVisible = true),
        integer(SOURCE_SAMPLE_RATE, TelemetrySection.SOURCE, TelemetryUnit.HERTZ, 1.0, defaultVisible = true),
        integer(SOURCE_BIT_DEPTH, TelemetrySection.SOURCE, TelemetryUnit.BITS, 1.0, defaultVisible = true),
        integer(SOURCE_CHANNEL_COUNT, TelemetrySection.SOURCE, TelemetryUnit.COUNT, 1.0),
        integer(SOURCE_AVERAGE_BITRATE, TelemetrySection.SOURCE, TelemetryUnit.BITS_PER_SECOND, 0.0),
        integer(SOURCE_FILE_SIZE, TelemetrySection.SOURCE, TelemetryUnit.BYTES, 0.0),
        safeText(DECODER_NAME, TelemetrySection.DECODER, defaultVisible = true),
        safeText(DECODER_INPUT_MIME, TelemetrySection.DECODER),
        integer(DECODER_INPUT_SAMPLE_RATE, TelemetrySection.DECODER, TelemetryUnit.HERTZ, 1.0),
        integer(DECODER_INPUT_CHANNEL_COUNT, TelemetrySection.DECODER, TelemetryUnit.COUNT, 1.0),
        integer(DECODER_INITIALIZATION_DURATION, TelemetrySection.DECODER, TelemetryUnit.MILLISECONDS, 0.0),
        flag(DECODER_SOFTWARE_ONLY, TelemetrySection.DECODER),
        flag(DECODER_HARDWARE_ACCELERATED, TelemetrySection.DECODER),
        flag(DECODER_VENDOR, TelemetrySection.DECODER),
        safeText(DECODER_PATH, TelemetrySection.DECODER, defaultVisible = true),
        integer(DECODER_OUTPUT_SAMPLE_RATE, TelemetrySection.DECODER, TelemetryUnit.HERTZ, 1.0),
        safeText(DECODER_OUTPUT_ENCODING, TelemetrySection.DECODER),
        safeText(DECODER_OUTPUT_CHANNEL_CONFIG, TelemetrySection.DECODER),
        integer(DECODER_COMPRESSED_FRAME_SIZE, TelemetrySection.DECODER, TelemetryUnit.BYTES, 0.0),
        decimal(DECODER_INPUT_BUFFER_RATE, TelemetrySection.DECODER, TelemetryUnit.COUNT_PER_SECOND, 0.0, chartable = true),
        decimal(DECODER_OUTPUT_BUFFER_RATE, TelemetrySection.DECODER, TelemetryUnit.COUNT_PER_SECOND, 0.0, chartable = true),
        decimal(DECODER_FRAME_DECODE_TIME, TelemetrySection.DECODER, TelemetryUnit.MILLISECONDS, 0.0, chartable = true),
        integer(DECODER_FORMAT_CHANGE_COUNT, TelemetrySection.DECODER, TelemetryUnit.COUNT, 0.0),
        integer(DECODER_RENDERER_QUEUED_INPUT_BUFFERS_TOTAL, TelemetrySection.DECODER, TelemetryUnit.COUNT, 0.0, chartable = true),
        integer(DECODER_RENDERER_RENDERED_OUTPUT_BUFFERS_TOTAL, TelemetrySection.DECODER, TelemetryUnit.COUNT, 0.0, chartable = true),
        integer(DECODER_RENDERER_SKIPPED_OUTPUT_BUFFERS_TOTAL, TelemetrySection.DECODER, TelemetryUnit.COUNT, 0.0, chartable = true),
        decimal(PROCESSING_SPEED, TelemetrySection.PROCESSING, TelemetryUnit.PERCENT, 0.0),
        decimal(PROCESSING_PITCH, TelemetrySection.PROCESSING, TelemetryUnit.PERCENT, 0.0),
        decimal(PROCESSING_PLAYER_VOLUME, TelemetrySection.PROCESSING, TelemetryUnit.PERCENT, 0.0, 100.0),
        flag(PROCESSING_SKIP_SILENCE, TelemetrySection.PROCESSING),
        flag(PROCESSING_REPLAY_GAIN_ACTIVE, TelemetrySection.PROCESSING),
        flag(PROCESSING_EQUALIZER_ACTIVE, TelemetrySection.PROCESSING),
        flag(PROCESSING_CROSSFADE_ACTIVE, TelemetrySection.PROCESSING),
        flag(PROCESSING_LOUDNESS_ACTIVE, TelemetrySection.PROCESSING),
        flag(PROCESSING_SAMPLE_RATE_CONVERSION, TelemetrySection.PROCESSING),
        flag(PROCESSING_APP_DSP_ACTIVE, TelemetrySection.PROCESSING, defaultVisible = true),
        safeText(PLAYBACK_STATE, TelemetrySection.PLAYBACK),
        flag(PLAYBACK_IS_PLAYING, TelemetrySection.PLAYBACK),
        integer(PLAYBACK_LAST_EVENT_POSITION, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0),
        integer(PLAYBACK_POSITION, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0, chartable = true),
        integer(PLAYBACK_DURATION, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0),
        integer(PLAYBACK_ESTIMATED_TOTAL_BUFFERED_DURATION, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0, chartable = true),
        integer(PLAYBACK_CURRENT_MEDIA_BYTES_READ, TelemetrySection.PLAYBACK, TelemetryUnit.BYTES, 0.0),
        decimal(PLAYBACK_CURRENT_MEDIA_READ_BITRATE, TelemetrySection.PLAYBACK, TelemetryUnit.BITS_PER_SECOND, 0.0, chartable = true),
        integer(PROCESS_DATA_SOURCE_BYTES_TRANSFERRED, TelemetrySection.PROCESS, TelemetryUnit.BYTES, 0.0),
        decimal(PROCESS_DATA_SOURCE_READ_THROUGHPUT, TelemetrySection.PROCESS, TelemetryUnit.BITS_PER_SECOND, 0.0, chartable = true),
        integer(PLAYBACK_UNDERRUN_COUNT, TelemetrySection.PLAYBACK, TelemetryUnit.COUNT, 0.0, defaultVisible = true, chartable = true),
        integer(PLAYBACK_LAST_UNDERRUN_FEED_GAP, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0),
        integer(PLAYBACK_AUDIO_TRACK_SAMPLE_RATE, TelemetrySection.PLAYBACK, TelemetryUnit.HERTZ, 1.0, defaultVisible = true),
        safeText(PLAYBACK_AUDIO_TRACK_ENCODING, TelemetrySection.PLAYBACK),
        safeText(PLAYBACK_AUDIO_TRACK_CHANNEL_MASK, TelemetrySection.PLAYBACK),
        integer(PLAYBACK_AUDIO_TRACK_BUFFER_SIZE, TelemetrySection.PLAYBACK, TelemetryUnit.BYTES, 0.0),
        decimal(PLAYBACK_PCM_DATA_RATE, TelemetrySection.PLAYBACK, TelemetryUnit.BITS_PER_SECOND, 0.0, chartable = true),
        flag(PLAYBACK_OFFLOAD, TelemetrySection.PLAYBACK),
        flag(PLAYBACK_TUNNELING, TelemetrySection.PLAYBACK),
        integer(PLAYBACK_TARGET_BUFFER_DURATION, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0),
        integer(PLAYBACK_PREBUFFER_TARGET_DURATION, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0),
        integer(PLAYBACK_LAST_SEEK_LATENCY, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0),
        integer(PLAYBACK_LAST_GAPLESS_TRANSITION_GAP, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0),
        integer(PLAYBACK_AUDIO_TRACK_TIMESTAMP, TelemetrySection.PLAYBACK, TelemetryUnit.NANOSECONDS, 0.0),
        decimal(PLAYBACK_CLOCK_DEVIATION, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, chartable = true),
        safeText(PLAYBACK_LAST_ERROR_CODE, TelemetrySection.PLAYBACK),
        integer(PROCESS_CPU_TIME, TelemetrySection.PROCESS, TelemetryUnit.MILLISECONDS, 0.0),
        decimal(PROCESS_CPU_PERCENT, TelemetrySection.PROCESS, TelemetryUnit.PERCENT, 0.0, chartable = true),
        integer(PROCESS_JAVA_HEAP, TelemetrySection.PROCESS, TelemetryUnit.BYTES, 0.0, chartable = true),
        integer(PROCESS_NATIVE_HEAP, TelemetrySection.PROCESS, TelemetryUnit.BYTES, 0.0, chartable = true),
        integer(PROCESS_PSS, TelemetrySection.PROCESS, TelemetryUnit.BYTES, 0.0, chartable = true),
        integer(PROCESSOR_COUNT, TelemetrySection.PROCESS, TelemetryUnit.COUNT, 1.0),
        integer(PROCESS_PLAYBACK_THREAD_CPU_TIME, TelemetrySection.PROCESS, TelemetryUnit.MILLISECONDS, 0.0),
        integer(PROCESS_GC_COUNT, TelemetrySection.PROCESS, TelemetryUnit.COUNT, 0.0, chartable = true),
        integer(PROCESS_GC_TIME, TelemetrySection.PROCESS, TelemetryUnit.MILLISECONDS, 0.0, chartable = true),
        text(PROCESS_CPU_CORE_STATE, TelemetrySection.PROCESS),
        safeText(PROCESS_THERMAL_STATUS, TelemetrySection.PROCESS, chartable = false),
        text(PROCESS_SOC_MODEL, TelemetrySection.PROCESS),
        decimal(POWER_BATTERY_CURRENT, TelemetrySection.POWER, TelemetryUnit.MILLIAMPERES),
        decimal(POWER_BATTERY_VOLTAGE, TelemetrySection.POWER, TelemetryUnit.VOLTS, 0.0),
        decimal(POWER_BATTERY_TEMPERATURE, TelemetrySection.POWER, TelemetryUnit.CELSIUS),
        decimal(POWER_DEVICE_ESTIMATE, TelemetrySection.POWER, TelemetryUnit.MILLIWATTS, 0.0, chartable = true),
        text(ROUTE_SELECTED_SYSTEM_NAME, TelemetrySection.ROUTE),
        safeText(ROUTE_SELECTED_SYSTEM_TYPE, TelemetrySection.ROUTE, defaultVisible = true),
        text(ROUTE_ANTICIPATED_NAME, TelemetrySection.ROUTE),
        safeText(ROUTE_ANTICIPATED_TYPE, TelemetrySection.ROUTE),
        safeText(ROUTE_CONNECTED_TYPES, TelemetrySection.ROUTE),
        flag(ROUTE_REQUEST_FORMAT_DIRECT_SUPPORTED, TelemetrySection.ROUTE, defaultVisible = true),
        safeText(ROUTE_REQUEST_FORMAT_DIRECT_MODES, TelemetrySection.ROUTE),
        integer(ROUTE_ANTICIPATED_MIXER_PROFILE_COUNT, TelemetrySection.ROUTE, TelemetryUnit.COUNT, 0.0),
        safeText(ROUTE_ANTICIPATED_PREFERRED_MIXER_PROFILE, TelemetrySection.ROUTE),
        safeText(ROUTE_OUTPUT_DECLARATION, TelemetrySection.ROUTE, defaultVisible = true),
        safeText(ROUTE_AUDIO_TRACK_REQUEST_FORMAT, TelemetrySection.ROUTE),
        safeText(ROUTE_OBSERVED_OUTPUT_FORMAT, TelemetrySection.ROUTE),
        safeText(ROUTE_BLUETOOTH_CODEC, TelemetrySection.ROUTE, defaultVisible = true),
        text(ROUTE_BLUETOOTH_CONNECTED_NAMES, TelemetrySection.ROUTE, defaultVisible = true),
        safeText(ROUTE_BLUETOOTH_CONNECTED_TYPES, TelemetrySection.ROUTE),
        safeText(ROUTE_BLUETOOTH_CAPABILITIES, TelemetrySection.ROUTE),
        safeText(ROUTE_BLUETOOTH_CONFIGURATION, TelemetrySection.ROUTE),
        integer(ROUTE_BLUETOOTH_CONFIGURED_BITRATE, TelemetrySection.ROUTE, TelemetryUnit.BITS_PER_SECOND, 0.0),
        decimal(ROUTE_BLUETOOTH_TRANSPORT_BITRATE, TelemetrySection.ROUTE, TelemetryUnit.BITS_PER_SECOND, 0.0, defaultVisible = true),
        decimal(ROUTE_SYSTEM_MUSIC_VOLUME, TelemetrySection.ROUTE, TelemetryUnit.PERCENT, 0.0, 100.0),
        flag(ROUTE_SYSTEM_MUSIC_MUTED, TelemetrySection.ROUTE),
        text(ROUTE_SYSTEM_DSP_STATE, TelemetrySection.ROUTE),
        safeText(ROUTE_LAST_STRATEGY_DECISION, TelemetrySection.ROUTE),
        flag(USB_HOST_SUPPORTED, TelemetrySection.USB),
        integer(USB_AUDIO_DEVICE_COUNT, TelemetrySection.USB, TelemetryUnit.COUNT, 0.0, defaultVisible = true),
        usbInventory(USB_DEVICE_INVENTORY, TelemetrySection.USB),
    )

    private val byId = descriptors.associateBy(TelemetryMetricDescriptor::id).also { indexed ->
        require(indexed.size == descriptors.size) { "Telemetry metric descriptors must have unique ids" }
    }

    val allIds: Set<TelemetryMetricId> = byId.keys
    val defaultIds: Set<TelemetryMetricId> = descriptors.filterTo(linkedSetOf()) {
        it.defaultVisible
    }.mapTo(linkedSetOf(), TelemetryMetricDescriptor::id)

    /** Metrics that require Android process, runtime, thermal, or battery polling. */
    val systemProbeIds: Set<TelemetryMetricId> = setOf(
        PROCESS_CPU_TIME,
        PROCESS_CPU_PERCENT,
        PROCESS_JAVA_HEAP,
        PROCESS_NATIVE_HEAP,
        PROCESS_PSS,
        PROCESSOR_COUNT,
        PROCESS_PLAYBACK_THREAD_CPU_TIME,
        PROCESS_GC_COUNT,
        PROCESS_GC_TIME,
        PROCESS_CPU_CORE_STATE,
        PROCESS_THERMAL_STATUS,
        PROCESS_SOC_MODEL,
        POWER_BATTERY_CURRENT,
        POWER_BATTERY_VOLTAGE,
        POWER_BATTERY_TEMPERATURE,
        POWER_DEVICE_ESTIMATE,
    )

    /** Metrics that require output-route, mixer, volume, or USB system queries. */
    val outputProbeIds: Set<TelemetryMetricId> = setOf(
        ROUTE_SELECTED_SYSTEM_NAME,
        ROUTE_SELECTED_SYSTEM_TYPE,
        ROUTE_ANTICIPATED_NAME,
        ROUTE_ANTICIPATED_TYPE,
        ROUTE_CONNECTED_TYPES,
        ROUTE_REQUEST_FORMAT_DIRECT_SUPPORTED,
        ROUTE_REQUEST_FORMAT_DIRECT_MODES,
        ROUTE_ANTICIPATED_MIXER_PROFILE_COUNT,
        ROUTE_ANTICIPATED_PREFERRED_MIXER_PROFILE,
        ROUTE_OBSERVED_OUTPUT_FORMAT,
        ROUTE_BLUETOOTH_CODEC,
        ROUTE_BLUETOOTH_CONNECTED_NAMES,
        ROUTE_BLUETOOTH_CONNECTED_TYPES,
        ROUTE_BLUETOOTH_CAPABILITIES,
        ROUTE_BLUETOOTH_CONFIGURATION,
        ROUTE_BLUETOOTH_CONFIGURED_BITRATE,
        ROUTE_BLUETOOTH_TRANSPORT_BITRATE,
        ROUTE_SYSTEM_MUSIC_VOLUME,
        ROUTE_SYSTEM_MUSIC_MUTED,
        ROUTE_SYSTEM_DSP_STATE,
        USB_HOST_SUPPORTED,
        USB_AUDIO_DEVICE_COUNT,
        USB_DEVICE_INVENTORY,
    )

    fun descriptor(id: TelemetryMetricId): TelemetryMetricDescriptor = requireNotNull(byId[id]) {
        "Unknown telemetry metric id: $id"
    }

    fun requireKnown(id: TelemetryMetricId) {
        descriptor(id)
    }

    fun requireValid(metric: TelemetryMetric) {
        val descriptor = descriptor(metric.id)
        require(metric.section == descriptor.section) {
            "Telemetry metric ${metric.id} belongs to ${descriptor.section}, not ${metric.section}"
        }
        val reading = metric.evidence.reading ?: return
        val actualKind = when (reading) {
            is TelemetryReading.Text -> TelemetryValueKind.TEXT
            is TelemetryReading.Flag -> TelemetryValueKind.FLAG
            is TelemetryReading.Integer -> TelemetryValueKind.INTEGER
            is TelemetryReading.Decimal -> TelemetryValueKind.DECIMAL
            is TelemetryReading.UsbInventory -> TelemetryValueKind.USB_INVENTORY
        }
        require(actualKind == descriptor.valueKind) {
            "Telemetry metric ${metric.id} requires ${descriptor.valueKind}, not $actualKind"
        }
        val actualUnit = when (reading) {
            is TelemetryReading.Integer -> reading.unit
            is TelemetryReading.Decimal -> reading.unit
            is TelemetryReading.UsbInventory -> null
            else -> null
        }
        require(actualUnit == descriptor.unit) {
            "Telemetry metric ${metric.id} requires unit ${descriptor.unit}, not $actualUnit"
        }
        val numeric = when (reading) {
            is TelemetryReading.Integer -> reading.value.toDouble()
            is TelemetryReading.Decimal -> reading.value
            is TelemetryReading.UsbInventory -> null
            else -> null
        }
        if (numeric != null) {
            descriptor.minimum?.let { minimum ->
                require(numeric >= minimum) { "Telemetry metric ${metric.id} is below its minimum" }
            }
            descriptor.maximum?.let { maximum ->
                require(numeric <= maximum) { "Telemetry metric ${metric.id} is above its maximum" }
            }
        }
    }

    private fun id(value: String) = TelemetryMetricId(value)

    private fun text(
        id: TelemetryMetricId,
        section: TelemetrySection,
        defaultVisible: Boolean = false,
        chartable: Boolean = false,
    ) = TelemetryMetricDescriptor(
        id = id,
        section = section,
        valueKind = TelemetryValueKind.TEXT,
        defaultVisible = defaultVisible,
        chartable = chartable,
    )

    private fun safeText(
        id: TelemetryMetricId,
        section: TelemetrySection,
        defaultVisible: Boolean = false,
        chartable: Boolean = false,
    ) = text(id, section, defaultVisible, chartable).copy(
        exportPolicy = TelemetryExportPolicy.INCLUDE,
    )

    private fun flag(
        id: TelemetryMetricId,
        section: TelemetrySection,
        defaultVisible: Boolean = false,
    ) = TelemetryMetricDescriptor(
        id = id,
        section = section,
        valueKind = TelemetryValueKind.FLAG,
        defaultVisible = defaultVisible,
        exportPolicy = TelemetryExportPolicy.INCLUDE,
    )

    private fun integer(
        id: TelemetryMetricId,
        section: TelemetrySection,
        unit: TelemetryUnit,
        minimum: Double? = null,
        maximum: Double? = null,
        defaultVisible: Boolean = false,
        chartable: Boolean = false,
    ) = TelemetryMetricDescriptor(
        id = id,
        section = section,
        valueKind = TelemetryValueKind.INTEGER,
        unit = unit,
        minimum = minimum,
        maximum = maximum,
        defaultVisible = defaultVisible,
        chartable = chartable,
        exportPolicy = TelemetryExportPolicy.INCLUDE,
    )

    private fun decimal(
        id: TelemetryMetricId,
        section: TelemetrySection,
        unit: TelemetryUnit,
        minimum: Double? = null,
        maximum: Double? = null,
        defaultVisible: Boolean = false,
        chartable: Boolean = false,
    ) = TelemetryMetricDescriptor(
        id = id,
        section = section,
        valueKind = TelemetryValueKind.DECIMAL,
        unit = unit,
        minimum = minimum,
        maximum = maximum,
        defaultVisible = defaultVisible,
        chartable = chartable,
        exportPolicy = TelemetryExportPolicy.INCLUDE,
    )

    private fun usbInventory(
        id: TelemetryMetricId,
        section: TelemetrySection,
    ) = TelemetryMetricDescriptor(
        id = id,
        section = section,
        valueKind = TelemetryValueKind.USB_INVENTORY,
        exportPolicy = TelemetryExportPolicy.REDACT_DEVICE_IDENTIFIERS,
    )
}
