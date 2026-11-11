package io.github.sumirenokai.vesqen.usb.descriptor

/**
 * What the raw descriptors say about a USB audio device, before any interface is claimed.
 * Anything that takes a control request to learn stays [SampleRates.RuntimeUnknown] or absent here;
 * a device's claims are not proof of what it does.
 */
data class UsbAudioTopology(
    val usbVersionBcd: Int,
    val vendorId: Int,
    val productId: Int,
    val configurations: List<UacConfiguration>,
) {
    val functions: List<UacFunction> get() = configurations.flatMap(UacConfiguration::functions)
}

data class UacConfiguration(
    val value: Int,
    val functions: List<UacFunction>,
)

enum class UacVersion { UAC1, UAC2, OTHER }

data class UacFunction(
    val version: UacVersion,
    val interfaceProtocol: Int,
    val controlInterface: Int,
    val inputTerminals: List<UacInputTerminal>,
    val outputTerminals: List<UacOutputTerminal>,
    val featureUnits: List<UacFeatureUnit>,
    /** Mixers, selectors and processing units: only how they connect matters at this stage. */
    val otherUnits: List<UacOtherUnit>,
    val clockEntities: List<UacClockEntity>,
    val streamingInterfaces: List<UacStreamingInterface>,
) {
    /** The feature units audio passes between this USB stream and any output terminal. */
    fun featureUnitsAfter(terminalLink: Int): List<UacFeatureUnit> =
        playbackEntitiesAfter(terminalLink).let { path -> featureUnits.filter { it.id in path } }

    /** Entities on a route from this USB input to a physical output; empty for an unresolved path. */
    internal fun playbackEntitiesAfter(terminalLink: Int): Set<Int> {
        val sources = buildMap<Int, List<Int>> {
            outputTerminals.forEach { put(it.id, listOf(it.sourceId)) }
            featureUnits.forEach { put(it.id, listOf(it.sourceId)) }
            otherUnits.forEach { put(it.id, it.sourceIds) }
        }
        val reaches = mutableMapOf<Int, Boolean>()
        fun reachesLink(id: Int): Boolean {
            if (id == terminalLink) return true
            reaches[id]?.let { return it }
            // Marked first: a device can describe a loop.
            reaches[id] = false
            return sources[id].orEmpty().map(::reachesLink).any { it }.also { reaches[id] = it }
        }
        outputTerminals.filterNot(UacOutputTerminal::isUsbStreaming).forEach { reachesLink(it.id) }
        return reaches.filterValues { it }.keys
    }

    /** UAC2: the clock sources this USB stream can run from. */
    fun clockSourcesFor(terminalLink: Int): List<UacClockEntity.Source> {
        val first = inputTerminals.firstOrNull { it.id == terminalLink }?.clockId
            ?: outputTerminals.firstOrNull { it.id == terminalLink }?.clockId
            ?: return emptyList()
        val found = linkedSetOf<UacClockEntity.Source>()
        val seen = mutableSetOf<Int>()
        fun visit(id: Int) {
            if (!seen.add(id)) return
            when (val entity = clockEntities.firstOrNull { it.id == id }) {
                is UacClockEntity.Source -> found += entity
                is UacClockEntity.Selector -> entity.inputIds.forEach(::visit)
                is UacClockEntity.Multiplier -> visit(entity.sourceId)
                null -> Unit
            }
        }
        visit(first)
        return found.toList()
    }
}

data class UacInputTerminal(
    val id: Int,
    val type: Int,
    /** UAC2 only. */
    val clockId: Int?,
) {
    val isUsbStreaming: Boolean get() = type == USB_STREAMING_TERMINAL
}

data class UacOutputTerminal(
    val id: Int,
    val type: Int,
    val sourceId: Int,
    /** UAC2 only. */
    val clockId: Int?,
) {
    val isUsbStreaming: Boolean get() = type == USB_STREAMING_TERMINAL
}

data class UacOtherUnit(
    val id: Int,
    val sourceIds: List<Int>,
)

/**
 * UAC2 states whether a control can be written. UAC1 only states that it exists, so whether it can
 * be written stays unknown until a request is made.
 */
enum class UacControlAccess { ABSENT, PRESENT_ACCESS_UNKNOWN, READ_ONLY, WRITABLE }

data class UacFeatureUnit(
    val id: Int,
    val sourceId: Int,
    val masterMute: UacControlAccess,
    val masterVolume: UacControlAccess,
    /** Logical channels 1 and up, in order. */
    val channelVolumes: List<UacControlAccess>,
)

sealed interface UacClockEntity {
    val id: Int

    enum class SourceType { EXTERNAL, INTERNAL_FIXED, INTERNAL_VARIABLE, INTERNAL_PROGRAMMABLE }

    data class Source(
        override val id: Int,
        val type: SourceType,
        val frequency: UacControlAccess,
        val validity: UacControlAccess,
    ) : UacClockEntity

    data class Selector(
        override val id: Int,
        val inputIds: List<Int>,
        val selection: UacControlAccess,
    ) : UacClockEntity

    data class Multiplier(
        override val id: Int,
        val sourceId: Int,
    ) : UacClockEntity
}

data class UacStreamingInterface(
    val interfaceNumber: Int,
    val alternateSettings: List<UacAltSetting>,
)

enum class UacPcmKind { LINEAR_PCM, FLOAT, OTHER }

sealed interface SampleRates {
    data class Discrete(val ratesHz: List<Int>) : SampleRates

    data class Continuous(val minHz: Int, val maxHz: Int) : SampleRates

    /** UAC2 lists rates only in answer to a control request on the clock. */
    data object RuntimeUnknown : SampleRates
}

data class UacFormat(
    val kind: UacPcmKind,
    /** False for Type II and Type III. Only Type I carries plain frames of samples. */
    val isTypeI: Boolean,
    val channels: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    val sampleRates: SampleRates,
)

enum class UacSyncType { NONE, ASYNCHRONOUS, ADAPTIVE, SYNCHRONOUS }

enum class UacEndpointUsage { DATA, FEEDBACK, IMPLICIT_FEEDBACK_DATA, RESERVED }

data class UacEndpoint(
    val address: Int,
    val isIsochronous: Boolean,
    val syncType: UacSyncType,
    val usage: UacEndpointUsage,
    /** Low 11 bits of wMaxPacketSize. */
    val maxPacketBytes: Int,
    /** Bits 11 and 12 of wMaxPacketSize: extra high-speed transactions per interval. */
    val additionalTransactions: Int,
    val interval: Int,
    /** UAC1's bRefresh as declared. UAC2 has no such field: [interval] alone paces its feedback. */
    val refresh: Int?,
    /** UAC1's standard endpoint names its feedback endpoint here; 0 means none. */
    val synchAddress: Int,
) {
    val isOutToDevice: Boolean get() = address and 0x80 == 0

    /** How often a UAC1 feedback endpoint has a new value: 2^bRefresh ms, with 1 to 9 allowed. */
    val refreshPeriodMs: Int? get() = refresh?.takeIf { it in 1..9 }?.let { 1 shl it }

    /** Matches by endpoint number: like Linux, this accepts a [synchAddress] without its direction bit. */
    fun names(other: UacEndpoint): Boolean =
        synchAddress != 0 && other.isOutToDevice != isOutToDevice && (synchAddress xor other.address) and 0x7F == 0
}

data class UacAltSetting(
    val version: UacVersion,
    val alternateSetting: Int,
    val terminalLink: Int?,
    val format: UacFormat?,
    val dataEndpoint: UacEndpoint?,
    val feedbackEndpoint: UacEndpoint?,
    /** UAC1 only: the data endpoint accepts a sampling frequency request. */
    val endpointSetsSampleRate: Boolean,
) {
    val isPlayback: Boolean get() = dataEndpoint?.isOutToDevice == true
    val isCapture: Boolean get() = dataEndpoint?.isOutToDevice == false

    /** Why M7's first version cannot stream through this setting (D3, architecture "StreamingPlan"). */
    val streamingBlockers: Set<UacStreamingBlocker>
        get() = buildSet {
            val format = format
            if (format == null) {
                add(UacStreamingBlocker.NO_FORMAT)
            } else {
                if (!format.isTypeI || format.kind != UacPcmKind.LINEAR_PCM) add(UacStreamingBlocker.NOT_LINEAR_PCM)
                if (format.channels != 2) add(UacStreamingBlocker.NOT_STEREO)
                if (format.subslotBytes !in 2..4 || format.bitResolution !in 1..format.subslotBytes * 8) {
                    add(UacStreamingBlocker.BAD_SAMPLE_LAYOUT)
                }
            }
            val endpoint = dataEndpoint
            if (endpoint == null) {
                add(UacStreamingBlocker.NO_DATA_ENDPOINT)
            } else {
                if (!endpoint.isIsochronous) add(UacStreamingBlocker.NOT_ISOCHRONOUS)
                if (endpoint.additionalTransactions != 0) add(UacStreamingBlocker.MULTIPLE_TRANSACTIONS)
                if (endpoint.interval !in 1..16) add(UacStreamingBlocker.BAD_INTERVAL)
                if (endpoint.maxPacketBytes == 0) add(UacStreamingBlocker.EMPTY_PACKETS)
                if (endpoint.usage == UacEndpointUsage.IMPLICIT_FEEDBACK_DATA) {
                    add(UacStreamingBlocker.IMPLICIT_FEEDBACK)
                }
                if (endpoint.syncType == UacSyncType.ASYNCHRONOUS) {
                    val feedback = feedbackEndpoint
                    if (feedback == null) {
                        add(UacStreamingBlocker.ASYNCHRONOUS_WITHOUT_FEEDBACK)
                    } else {
                        addAll(feedbackBlockers(endpoint, feedback))
                    }
                }
            }
        }

    /**
     * What the descriptors alone can hold against a feedback endpoint. Whatever depends on the bus
     * speed waits for StreamingPlan: four feedback bytes at high speed, and how long an interval lasts.
     */
    private fun feedbackBlockers(data: UacEndpoint, feedback: UacEndpoint) = buildSet {
        if (feedback.isOutToDevice == data.isOutToDevice) add(UacStreamingBlocker.FEEDBACK_WRONG_DIRECTION)
        if (!feedback.isIsochronous) add(UacStreamingBlocker.FEEDBACK_NOT_ISOCHRONOUS)
        // UAC1 reserves the usage bits, so a feedback endpoint named by address may leave them at DATA.
        if (feedback.usage != UacEndpointUsage.DATA && feedback.usage != UacEndpointUsage.FEEDBACK) {
            add(UacStreamingBlocker.FEEDBACK_NOT_EXPLICIT)
        }
        if (feedback.maxPacketBytes < MIN_FEEDBACK_BYTES) add(UacStreamingBlocker.FEEDBACK_TOO_SMALL)
        if (feedback.additionalTransactions != 0) add(UacStreamingBlocker.FEEDBACK_MULTIPLE_TRANSACTIONS)
        if (feedback.interval !in 1..16) add(UacStreamingBlocker.FEEDBACK_BAD_INTERVAL)
        if (version == UacVersion.UAC1 && feedback.refreshPeriodMs == null) {
            add(UacStreamingBlocker.FEEDBACK_BAD_REFRESH)
        }
        if (feedback.synchAddress != 0 || (data.synchAddress != 0 && !data.names(feedback))) {
            add(UacStreamingBlocker.FEEDBACK_BAD_REFERENCE)
        }
    }
}

enum class UacStreamingBlocker {
    NO_FORMAT,
    NOT_LINEAR_PCM,
    NOT_STEREO,
    BAD_SAMPLE_LAYOUT,
    NO_DATA_ENDPOINT,
    NOT_ISOCHRONOUS,
    MULTIPLE_TRANSACTIONS,
    BAD_INTERVAL,
    EMPTY_PACKETS,
    IMPLICIT_FEEDBACK,
    ASYNCHRONOUS_WITHOUT_FEEDBACK,
    FEEDBACK_WRONG_DIRECTION,
    FEEDBACK_NOT_ISOCHRONOUS,
    FEEDBACK_NOT_EXPLICIT,
    FEEDBACK_TOO_SMALL,
    FEEDBACK_MULTIPLE_TRANSACTIONS,
    FEEDBACK_BAD_INTERVAL,
    FEEDBACK_BAD_REFRESH,
    FEEDBACK_BAD_REFERENCE,
}

internal const val USB_STREAMING_TERMINAL = 0x0101

/** Full speed sends feedback in 3 bytes, high speed in 4. */
private const val MIN_FEEDBACK_BYTES = 3
