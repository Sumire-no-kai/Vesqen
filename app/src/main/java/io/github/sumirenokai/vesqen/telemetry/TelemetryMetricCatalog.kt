package io.github.sumirenokai.vesqen.telemetry

enum class TelemetryValueKind {
    TEXT,
    FLAG,
    INTEGER,
    DECIMAL,
}

enum class TelemetryExportPolicy {
    INCLUDE,
    REDACT_TEXT,
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
    val exportPolicy: TelemetryExportPolicy = TelemetryExportPolicy.INCLUDE,
)

/**
 * The fail-closed schema for every metric accepted by Audio Proof.
 *
 * Keeping identity, section, value type, unit, range and privacy together prevents adapters from
 * inventing subtly incompatible meanings for the same stable id.
 */
object TelemetryMetricCatalog {
    val SOURCE_CONTAINER = id("source.container")
    val SOURCE_CODEC = id("source.codec")
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
    val DECODER_VENDOR = id("decoder.vendor")
    val DECODER_QUEUED_INPUT_BUFFERS = id("decoder.queued_input_buffers")
    val DECODER_RENDERED_OUTPUT_BUFFERS = id("decoder.rendered_output_buffers")
    val DECODER_SKIPPED_OUTPUT_BUFFERS = id("decoder.skipped_output_buffers")

    val PROCESSING_SPEED = id("processing.speed")
    val PROCESSING_PITCH = id("processing.pitch")
    val PROCESSING_PLAYER_VOLUME = id("processing.player_volume")
    val PROCESSING_SKIP_SILENCE = id("processing.skip_silence")
    val PROCESSING_APP_DSP_ACTIVE = id("processing.app_dsp_active")

    val PLAYBACK_STATE = id("playback.state")
    val PLAYBACK_IS_PLAYING = id("playback.is_playing")
    val PLAYBACK_POSITION = id("playback.position")
    val PLAYBACK_BUFFERED_DURATION = id("playback.buffered_duration")
    val PLAYBACK_SOURCE_BYTES_READ = id("playback.source_bytes_read")
    val PLAYBACK_SOURCE_READ_BITRATE = id("playback.source_read_bitrate")
    val PLAYBACK_UNDERRUN_COUNT = id("playback.underrun_count")
    val PLAYBACK_LAST_UNDERRUN_FEED_GAP = id("playback.last_underrun_feed_gap")
    val PLAYBACK_AUDIO_TRACK_SAMPLE_RATE = id("playback.audio_track_sample_rate")
    val PLAYBACK_AUDIO_TRACK_ENCODING = id("playback.audio_track_encoding")
    val PLAYBACK_AUDIO_TRACK_CHANNEL_MASK = id("playback.audio_track_channel_mask")
    val PLAYBACK_AUDIO_TRACK_BUFFER_SIZE = id("playback.audio_track_buffer_size")
    val PLAYBACK_OFFLOAD = id("playback.offload")
    val PLAYBACK_TUNNELING = id("playback.tunneling")

    val PROCESS_CPU_TIME = id("process.cpu_time")
    val PROCESS_CPU_PERCENT = id("process.cpu_percent")
    val PROCESS_JAVA_HEAP = id("process.java_heap")
    val PROCESS_NATIVE_HEAP = id("process.native_heap")
    val PROCESS_PSS = id("process.pss")
    val PROCESSOR_COUNT = id("process.processor_count")
    val PROCESS_THERMAL_STATUS = id("process.thermal_status")
    val PROCESS_SOC_MODEL = id("process.soc_model")
    val POWER_BATTERY_CURRENT = id("power.battery_current")
    val POWER_BATTERY_VOLTAGE = id("power.battery_voltage")
    val POWER_BATTERY_TEMPERATURE = id("power.battery_temperature")
    val POWER_DEVICE_ESTIMATE = id("power.device_estimate")

    val ROUTE_SELECTED_NAME = id("route.selected_name")
    val ROUTE_SELECTED_TYPE = id("route.selected_type")
    val ROUTE_CONNECTED_TYPES = id("route.connected_types")
    val ROUTE_DIRECT_SUPPORTED = id("route.direct_supported")
    val ROUTE_DIRECT_MODES = id("route.direct_modes")
    val ROUTE_SUPPORTED_MIXER_PROFILE_COUNT = id("route.supported_mixer_profile_count")
    val ROUTE_PREFERRED_MIXER_PROFILE = id("route.preferred_mixer_profile")

    val USB_HOST_SUPPORTED = id("usb.host_supported")
    val USB_AUDIO_DEVICE_COUNT = id("usb.audio_device_count")
    val USB_DEVICE_NAMES = id("usb.device_names")
    val USB_VENDOR_PRODUCT_IDS = id("usb.vendor_product_ids")
    val USB_PERMISSION_GRANTED = id("usb.permission_granted")
    val USB_SAMPLE_RATES = id("usb.sample_rates")
    val USB_CHANNEL_COUNTS = id("usb.channel_counts")
    val USB_ENCODINGS = id("usb.encodings")

    val descriptors: List<TelemetryMetricDescriptor> = listOf(
        text(SOURCE_CONTAINER, TelemetrySection.SOURCE),
        text(SOURCE_CODEC, TelemetrySection.SOURCE, defaultVisible = true),
        integer(SOURCE_SAMPLE_RATE, TelemetrySection.SOURCE, TelemetryUnit.HERTZ, 1.0, defaultVisible = true),
        integer(SOURCE_BIT_DEPTH, TelemetrySection.SOURCE, TelemetryUnit.BITS, 1.0, defaultVisible = true),
        integer(SOURCE_CHANNEL_COUNT, TelemetrySection.SOURCE, TelemetryUnit.COUNT, 1.0),
        integer(SOURCE_AVERAGE_BITRATE, TelemetrySection.SOURCE, TelemetryUnit.BITS_PER_SECOND, 0.0),
        integer(SOURCE_FILE_SIZE, TelemetrySection.SOURCE, TelemetryUnit.BYTES, 0.0),
        text(DECODER_NAME, TelemetrySection.DECODER, defaultVisible = true),
        text(DECODER_INPUT_MIME, TelemetrySection.DECODER),
        integer(DECODER_INPUT_SAMPLE_RATE, TelemetrySection.DECODER, TelemetryUnit.HERTZ, 1.0),
        integer(DECODER_INPUT_CHANNEL_COUNT, TelemetrySection.DECODER, TelemetryUnit.COUNT, 1.0),
        integer(DECODER_INITIALIZATION_DURATION, TelemetrySection.DECODER, TelemetryUnit.MILLISECONDS, 0.0),
        flag(DECODER_SOFTWARE_ONLY, TelemetrySection.DECODER),
        flag(DECODER_HARDWARE_ACCELERATED, TelemetrySection.DECODER),
        flag(DECODER_VENDOR, TelemetrySection.DECODER),
        integer(DECODER_QUEUED_INPUT_BUFFERS, TelemetrySection.DECODER, TelemetryUnit.COUNT, 0.0, chartable = true),
        integer(DECODER_RENDERED_OUTPUT_BUFFERS, TelemetrySection.DECODER, TelemetryUnit.COUNT, 0.0, chartable = true),
        integer(DECODER_SKIPPED_OUTPUT_BUFFERS, TelemetrySection.DECODER, TelemetryUnit.COUNT, 0.0, chartable = true),
        decimal(PROCESSING_SPEED, TelemetrySection.PROCESSING, TelemetryUnit.PERCENT, 0.0),
        decimal(PROCESSING_PITCH, TelemetrySection.PROCESSING, TelemetryUnit.PERCENT, 0.0),
        decimal(PROCESSING_PLAYER_VOLUME, TelemetrySection.PROCESSING, TelemetryUnit.PERCENT, 0.0, 100.0),
        flag(PROCESSING_SKIP_SILENCE, TelemetrySection.PROCESSING),
        flag(PROCESSING_APP_DSP_ACTIVE, TelemetrySection.PROCESSING, defaultVisible = true),
        text(PLAYBACK_STATE, TelemetrySection.PLAYBACK),
        flag(PLAYBACK_IS_PLAYING, TelemetrySection.PLAYBACK),
        integer(PLAYBACK_POSITION, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0, chartable = true),
        integer(PLAYBACK_BUFFERED_DURATION, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0, chartable = true),
        integer(PLAYBACK_SOURCE_BYTES_READ, TelemetrySection.PLAYBACK, TelemetryUnit.BYTES, 0.0),
        decimal(PLAYBACK_SOURCE_READ_BITRATE, TelemetrySection.PLAYBACK, TelemetryUnit.BITS_PER_SECOND, 0.0, defaultVisible = true, chartable = true),
        integer(PLAYBACK_UNDERRUN_COUNT, TelemetrySection.PLAYBACK, TelemetryUnit.COUNT, 0.0, defaultVisible = true, chartable = true),
        integer(PLAYBACK_LAST_UNDERRUN_FEED_GAP, TelemetrySection.PLAYBACK, TelemetryUnit.MILLISECONDS, 0.0),
        integer(PLAYBACK_AUDIO_TRACK_SAMPLE_RATE, TelemetrySection.PLAYBACK, TelemetryUnit.HERTZ, 1.0, defaultVisible = true),
        text(PLAYBACK_AUDIO_TRACK_ENCODING, TelemetrySection.PLAYBACK),
        text(PLAYBACK_AUDIO_TRACK_CHANNEL_MASK, TelemetrySection.PLAYBACK),
        integer(PLAYBACK_AUDIO_TRACK_BUFFER_SIZE, TelemetrySection.PLAYBACK, TelemetryUnit.BYTES, 0.0),
        flag(PLAYBACK_OFFLOAD, TelemetrySection.PLAYBACK),
        flag(PLAYBACK_TUNNELING, TelemetrySection.PLAYBACK),
        integer(PROCESS_CPU_TIME, TelemetrySection.PROCESS, TelemetryUnit.MILLISECONDS, 0.0),
        decimal(PROCESS_CPU_PERCENT, TelemetrySection.PROCESS, TelemetryUnit.PERCENT, 0.0, chartable = true),
        integer(PROCESS_JAVA_HEAP, TelemetrySection.PROCESS, TelemetryUnit.BYTES, 0.0, chartable = true),
        integer(PROCESS_NATIVE_HEAP, TelemetrySection.PROCESS, TelemetryUnit.BYTES, 0.0, chartable = true),
        integer(PROCESS_PSS, TelemetrySection.PROCESS, TelemetryUnit.BYTES, 0.0, chartable = true),
        integer(PROCESSOR_COUNT, TelemetrySection.PROCESS, TelemetryUnit.COUNT, 1.0),
        text(PROCESS_THERMAL_STATUS, TelemetrySection.PROCESS, chartable = false),
        text(PROCESS_SOC_MODEL, TelemetrySection.PROCESS),
        decimal(POWER_BATTERY_CURRENT, TelemetrySection.POWER, TelemetryUnit.MILLIAMPERES),
        decimal(POWER_BATTERY_VOLTAGE, TelemetrySection.POWER, TelemetryUnit.VOLTS, 0.0),
        decimal(POWER_BATTERY_TEMPERATURE, TelemetrySection.POWER, TelemetryUnit.CELSIUS),
        decimal(POWER_DEVICE_ESTIMATE, TelemetrySection.POWER, TelemetryUnit.MILLIWATTS, 0.0, chartable = true),
        text(ROUTE_SELECTED_NAME, TelemetrySection.ROUTE, exportPolicy = TelemetryExportPolicy.REDACT_TEXT),
        text(ROUTE_SELECTED_TYPE, TelemetrySection.ROUTE, defaultVisible = true),
        text(ROUTE_CONNECTED_TYPES, TelemetrySection.ROUTE),
        flag(ROUTE_DIRECT_SUPPORTED, TelemetrySection.ROUTE, defaultVisible = true),
        text(ROUTE_DIRECT_MODES, TelemetrySection.ROUTE),
        integer(ROUTE_SUPPORTED_MIXER_PROFILE_COUNT, TelemetrySection.ROUTE, TelemetryUnit.COUNT, 0.0),
        text(ROUTE_PREFERRED_MIXER_PROFILE, TelemetrySection.ROUTE),
        flag(USB_HOST_SUPPORTED, TelemetrySection.USB),
        integer(USB_AUDIO_DEVICE_COUNT, TelemetrySection.USB, TelemetryUnit.COUNT, 0.0, defaultVisible = true),
        text(USB_DEVICE_NAMES, TelemetrySection.USB, exportPolicy = TelemetryExportPolicy.REDACT_TEXT),
        text(USB_VENDOR_PRODUCT_IDS, TelemetrySection.USB, exportPolicy = TelemetryExportPolicy.REDACT_TEXT),
        flag(USB_PERMISSION_GRANTED, TelemetrySection.USB),
        text(USB_SAMPLE_RATES, TelemetrySection.USB),
        text(USB_CHANNEL_COUNTS, TelemetrySection.USB),
        text(USB_ENCODINGS, TelemetrySection.USB),
    )

    private val byId = descriptors.associateBy(TelemetryMetricDescriptor::id).also { indexed ->
        require(indexed.size == descriptors.size) { "Telemetry metric descriptors must have unique ids" }
    }

    val allIds: Set<TelemetryMetricId> = byId.keys
    val defaultIds: Set<TelemetryMetricId> = descriptors.filterTo(linkedSetOf()) {
        it.defaultVisible
    }.mapTo(linkedSetOf(), TelemetryMetricDescriptor::id)

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
        }
        require(actualKind == descriptor.valueKind) {
            "Telemetry metric ${metric.id} requires ${descriptor.valueKind}, not $actualKind"
        }
        val actualUnit = when (reading) {
            is TelemetryReading.Integer -> reading.unit
            is TelemetryReading.Decimal -> reading.unit
            else -> null
        }
        require(actualUnit == descriptor.unit) {
            "Telemetry metric ${metric.id} requires unit ${descriptor.unit}, not $actualUnit"
        }
        val numeric = when (reading) {
            is TelemetryReading.Integer -> reading.value.toDouble()
            is TelemetryReading.Decimal -> reading.value
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
        exportPolicy: TelemetryExportPolicy = TelemetryExportPolicy.INCLUDE,
    ) = TelemetryMetricDescriptor(
        id = id,
        section = section,
        valueKind = TelemetryValueKind.TEXT,
        defaultVisible = defaultVisible,
        chartable = chartable,
        exportPolicy = exportPolicy,
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
    )
}
