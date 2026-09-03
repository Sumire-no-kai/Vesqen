package io.github.sumirenokai.vesqen.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecList
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Application-scope production Adapter for [PlaybackTelemetry].
 *
 * Media3 callbacks retain only bounded primitive facts. Periodic Android probes run on a shared
 * background sampler whose lifetime is the union of active observations (including an explicit
 * diagnostic recording). No system probing is performed by a Composable or on the UI thread.
 */
@OptIn(UnstableApi::class)
class AndroidPlaybackTelemetry internal constructor(
    context: Context,
    private val clock: TelemetryClock = AndroidTelemetryClock,
) : PlaybackTelemetry, AnalyticsListener, TransferListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private val requestLock = Any()
    private val sourceBytesRead = AtomicLong()
    private val eventSequence = AtomicLong()
    private val observationSequence = AtomicLong()
    private val observations = linkedMapOf<Long, TelemetryObservation>()
    private val rawSamples = MutableStateFlow<RawTelemetrySample?>(null)
    private val systemProbe = AndroidSystemTelemetryProbe(appContext)
    private var samplerJob: Job? = null
    private var samplerIntervalMs: Long? = null
    private var player: ExoPlayer? = null
    private var sessionId: String? = null
    private var sourceFacts: TelemetrySourceFacts? = null
    private var sourceObservedAt: TelemetryInstant? = null
    private var decoderName: String? = null
    private var decoderInitializationDurationMs: Long? = null
    private var decoderSoftwareOnly: Boolean? = null
    private var decoderHardwareAccelerated: Boolean? = null
    private var decoderVendor: Boolean? = null
    private var decoderObservedAt: TelemetryInstant? = null
    private var inputFormat: Format? = null
    private var inputFormatObservedAt: TelemetryInstant? = null
    private var decoderCounters: DecoderCounters? = null
    private var lastDecoderCounters: DecoderCounterSnapshot? = null
    private var audioTrackConfig: AudioSink.AudioTrackConfig? = null
    private var audioTrackObservedAt: TelemetryInstant? = null
    private var playerFacts = PlayerTelemetryFacts()
    private var playerObservedAt: TelemetryInstant? = null
    private var underrunCount = 0L
    private var lastUnderrunFeedGapMs: Long? = null
    private var underrunObservedAt: TelemetryInstant? = null
    private val recentEvents = ArrayDeque<TelemetryEvent>()

    internal val transferListener: TransferListener
        get() = this

    internal fun attachPlayer(player: ExoPlayer) {
        synchronized(stateLock) {
            check(this.player == null || this.player === player) { "A different player is already attached" }
            if (this.player === player) return
            this.player = player
        }
        player.addAnalyticsListener(this)
    }

    internal fun detachPlayer(player: ExoPlayer) {
        player.removeAnalyticsListener(this)
        synchronized(stateLock) {
            if (this.player === player) {
                this.player = null
                sessionId = null
                sourceFacts = null
                decoderName = null
                inputFormat = null
                decoderCounters = null
                audioTrackConfig = null
                playerFacts = PlayerTelemetryFacts()
            }
        }
    }

    override fun observe(observation: TelemetryObservation): Flow<TelemetrySnapshot> = callbackFlow {
        val observationId = observationSequence.incrementAndGet()
        val startedAtElapsedMs = clock.elapsedRealtimeMs()
        synchronized(requestLock) {
            observations[observationId] = observation
            reconcileSamplerLocked()
        }
        val rateTracker = TelemetryRateTracker(observation.derivedWindowMs)
        val forwarding = scope.launch {
            var lastEmissionElapsedMs: Long? = null
            rawSamples.filterNotNull().collect { raw ->
                if (raw.instant.elapsedRealtimeMs < startedAtElapsedMs) return@collect
                val due = lastEmissionElapsedMs?.let { previous ->
                    raw.instant.elapsedRealtimeMs - previous >= observation.refreshInterval.milliseconds
                } ?: true
                if (due) {
                    trySend(buildSnapshot(raw, observation, rateTracker))
                    lastEmissionElapsedMs = raw.instant.elapsedRealtimeMs
                }
            }
        }
        awaitClose {
            forwarding.cancel()
            synchronized(requestLock) {
                observations.remove(observationId)
                reconcileSamplerLocked()
            }
        }
    }

    internal fun debugSamplingState(): TelemetrySamplingState = synchronized(requestLock) {
        TelemetrySamplingState(
            activeObservationCount = observations.size,
            activeIntervalMs = observations.values.minOfOrNull(TelemetryObservation::effectiveIntervalMs),
        )
    }

    private fun reconcileSamplerLocked() {
        val requestedIntervalMs = observations.values.minOfOrNull(TelemetryObservation::effectiveIntervalMs)
        if (requestedIntervalMs == null) {
            samplerJob?.cancel()
            samplerJob = null
            samplerIntervalMs = null
            return
        }
        if (samplerJob?.isActive == true && samplerIntervalMs == requestedIntervalMs) return
        samplerJob?.cancel()
        samplerIntervalMs = requestedIntervalMs
        samplerJob = scope.launch {
            while (isActive) {
                rawSamples.value = captureRaw()
                delay(requestedIntervalMs)
            }
        }
    }

    private fun captureRaw(): RawTelemetrySample {
        val (instant, media) = synchronized(stateLock) {
            // Take the clock sample while holding the same lock as the event copy. Otherwise a
            // callback can append a newer event after the timestamp but before recentEvents is
            // copied, producing a snapshot that appears to contain an event from the future.
            val capturedAt = clock.now()
            val liveCounters = decoderCounters?.also(DecoderCounters::ensureUpdated)?.let {
                DecoderCounterSnapshot(
                    queuedInputBuffers = it.queuedInputBufferCount.toLong(),
                    renderedOutputBuffers = it.renderedOutputBufferCount.toLong(),
                    skippedOutputBuffers = it.skippedOutputBufferCount.toLong(),
                )
            } ?: lastDecoderCounters
            capturedAt to MediaTelemetrySnapshot(
                sessionId = sessionId,
                sourceFacts = sourceFacts,
                sourceObservedAt = sourceObservedAt,
                decoderName = decoderName,
                decoderInitializationDurationMs = decoderInitializationDurationMs,
                decoderSoftwareOnly = decoderSoftwareOnly,
                decoderHardwareAccelerated = decoderHardwareAccelerated,
                decoderVendor = decoderVendor,
                decoderObservedAt = decoderObservedAt,
                inputFormat = inputFormat,
                inputFormatObservedAt = inputFormatObservedAt,
                decoderCounters = liveCounters,
                audioTrackConfig = audioTrackConfig,
                audioTrackObservedAt = audioTrackObservedAt,
                playerFacts = playerFacts,
                playerObservedAt = playerObservedAt,
                sourceBytesRead = sourceBytesRead.get().coerceAtLeast(0),
                underrunCount = underrunCount,
                lastUnderrunFeedGapMs = lastUnderrunFeedGapMs,
                underrunObservedAt = underrunObservedAt,
                recentEvents = recentEvents.toList(),
            )
        }
        return RawTelemetrySample(
            instant = instant,
            media = media,
            system = systemProbe.capture(instant),
        )
    }

    private fun buildSnapshot(
        raw: RawTelemetrySample,
        observation: TelemetryObservation,
        rateTracker: TelemetryRateTracker,
    ): TelemetrySnapshot {
        val metrics = linkedMapOf<TelemetryMetricId, TelemetryMetric>()
        val media = raw.media
        val activeReason = if (media.sessionId == null) {
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
        } else {
            TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT
        }
        val sourceInstant = media.sourceObservedAt ?: raw.instant
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.SOURCE_CONTAINER,
            media.sourceFacts?.container,
            SOURCE_METADATA,
            sourceInstant,
            activeReason,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.SOURCE_CODEC,
            media.sourceFacts?.codec,
            SOURCE_METADATA,
            sourceInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
            media.sourceFacts?.sampleRateHz?.toLong(),
            TelemetryUnit.HERTZ,
            SOURCE_METADATA,
            sourceInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.SOURCE_BIT_DEPTH,
            media.sourceFacts?.bitDepth?.toLong(),
            TelemetryUnit.BITS,
            SOURCE_METADATA,
            sourceInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.SOURCE_CHANNEL_COUNT,
            media.sourceFacts?.channelCount?.toLong(),
            TelemetryUnit.COUNT,
            SOURCE_METADATA,
            sourceInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.SOURCE_AVERAGE_BITRATE,
            media.sourceFacts?.averageBitrate?.toLong(),
            TelemetryUnit.BITS_PER_SECOND,
            SOURCE_METADATA,
            sourceInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.SOURCE_FILE_SIZE,
            media.sourceFacts?.fileSizeBytes,
            TelemetryUnit.BYTES,
            SOURCE_METADATA,
            sourceInstant,
            activeReason,
        )

        val decoderInstant = media.decoderObservedAt ?: raw.instant
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.DECODER_NAME,
            media.decoderName,
            MEDIA3_ANALYTICS,
            decoderInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_INITIALIZATION_DURATION,
            media.decoderInitializationDurationMs,
            TelemetryUnit.MILLISECONDS,
            MEDIA3_ANALYTICS,
            decoderInstant,
            activeReason,
        )
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.DECODER_SOFTWARE_ONLY,
            media.decoderSoftwareOnly,
            ANDROID_CODEC,
            decoderInstant,
            activeReason,
        )
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.DECODER_HARDWARE_ACCELERATED,
            media.decoderHardwareAccelerated,
            ANDROID_CODEC,
            decoderInstant,
            activeReason,
        )
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.DECODER_VENDOR,
            media.decoderVendor,
            ANDROID_CODEC,
            decoderInstant,
            activeReason,
        )
        val formatInstant = media.inputFormatObservedAt ?: raw.instant
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.DECODER_INPUT_MIME,
            media.inputFormat?.sampleMimeType,
            MEDIA3_ANALYTICS,
            formatInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_INPUT_SAMPLE_RATE,
            media.inputFormat?.sampleRate?.takeIf { it != Format.NO_VALUE }?.toLong(),
            TelemetryUnit.HERTZ,
            MEDIA3_ANALYTICS,
            formatInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_INPUT_CHANNEL_COUNT,
            media.inputFormat?.channelCount?.takeIf { it != Format.NO_VALUE }?.toLong(),
            TelemetryUnit.COUNT,
            MEDIA3_ANALYTICS,
            formatInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_QUEUED_INPUT_BUFFERS,
            media.decoderCounters?.queuedInputBuffers,
            TelemetryUnit.COUNT,
            MEDIA3_COUNTERS,
            raw.instant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_RENDERED_OUTPUT_BUFFERS,
            media.decoderCounters?.renderedOutputBuffers,
            TelemetryUnit.COUNT,
            MEDIA3_COUNTERS,
            raw.instant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_SKIPPED_OUTPUT_BUFFERS,
            media.decoderCounters?.skippedOutputBuffers,
            TelemetryUnit.COUNT,
            MEDIA3_COUNTERS,
            raw.instant,
            activeReason,
        )

        val playerInstant = media.playerObservedAt ?: raw.instant
        metrics.measuredDecimal(
            TelemetryMetricCatalog.PROCESSING_SPEED,
            media.playerFacts.speed * 100.0,
            TelemetryUnit.PERCENT,
            MEDIA3_PLAYER,
            playerInstant,
        )
        metrics.measuredDecimal(
            TelemetryMetricCatalog.PROCESSING_PITCH,
            media.playerFacts.pitch * 100.0,
            TelemetryUnit.PERCENT,
            MEDIA3_PLAYER,
            playerInstant,
        )
        metrics.measuredDecimal(
            TelemetryMetricCatalog.PROCESSING_PLAYER_VOLUME,
            media.playerFacts.volume * 100.0,
            TelemetryUnit.PERCENT,
            MEDIA3_PLAYER,
            playerInstant,
        )
        metrics.measuredFlag(
            TelemetryMetricCatalog.PROCESSING_SKIP_SILENCE,
            media.playerFacts.skipSilence,
            MEDIA3_PLAYER,
            playerInstant,
        )
        metrics.measuredFlag(
            TelemetryMetricCatalog.PROCESSING_APP_DSP_ACTIVE,
            false,
            APP_CONFIGURATION,
            raw.instant,
        )
        metrics.measuredText(
            TelemetryMetricCatalog.PLAYBACK_STATE,
            media.playerFacts.state,
            MEDIA3_PLAYER,
            playerInstant,
        )
        metrics.measuredFlag(
            TelemetryMetricCatalog.PLAYBACK_IS_PLAYING,
            media.playerFacts.isPlaying,
            MEDIA3_PLAYER,
            playerInstant,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_POSITION,
            media.playerFacts.positionMs.takeIf { media.sessionId != null },
            TelemetryUnit.MILLISECONDS,
            MEDIA3_PLAYER,
            playerInstant,
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK,
        )
        metrics.estimatedIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_BUFFERED_DURATION,
            media.playerFacts.bufferedDurationMs.takeIf { media.sessionId != null },
            TelemetryUnit.MILLISECONDS,
            MEDIA3_PLAYER,
            playerInstant,
            "media3.total_buffered_duration",
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK,
        )
        metrics.measuredInteger(
            TelemetryMetricCatalog.PLAYBACK_SOURCE_BYTES_READ,
            media.sourceBytesRead,
            TelemetryUnit.BYTES,
            MEDIA3_DATA_SOURCE,
            raw.instant,
        )
        rateTracker.add(raw.instant, media.sessionId, media.sourceBytesRead, raw.system.processCpuTimeMs)
        rateTracker.sourceReadBitrate()?.let { rate ->
            metrics[TelemetryMetricCatalog.PLAYBACK_SOURCE_READ_BITRATE] = TelemetryMetric(
                id = TelemetryMetricCatalog.PLAYBACK_SOURCE_READ_BITRATE,
                section = TelemetrySection.PLAYBACK,
                evidence = TelemetryEvidence.Derived(
                    reading = TelemetryReading.Decimal(rate.value, TelemetryUnit.BITS_PER_SECOND),
                    source = MEDIA3_DATA_SOURCE,
                    observedAtEpochMs = raw.instant.epochMs,
                    observedAtElapsedRealtimeMs = raw.instant.elapsedRealtimeMs,
                    window = rate.window,
                    calculationId = "rate.bytes_per_window",
                    inputMetricIds = setOf(TelemetryMetricCatalog.PLAYBACK_SOURCE_BYTES_READ),
                    operands = mapOf(
                        "bytes.delta" to rate.delta.toDouble(),
                        "window.seconds" to rate.window.durationMs / 1_000.0,
                    ),
                ),
            )
        } ?: metrics.unavailable(
            TelemetryMetricCatalog.PLAYBACK_SOURCE_READ_BITRATE,
            TelemetryUnavailableReason.WARMING_UP,
            raw.instant,
            MEDIA3_DATA_SOURCE,
        )
        metrics.measuredInteger(
            TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT,
            media.underrunCount,
            TelemetryUnit.COUNT,
            MEDIA3_ANALYTICS,
            raw.instant,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_LAST_UNDERRUN_FEED_GAP,
            media.lastUnderrunFeedGapMs,
            TelemetryUnit.MILLISECONDS,
            MEDIA3_ANALYTICS,
            media.underrunObservedAt ?: raw.instant,
            TelemetryUnavailableReason.NOT_APPLICABLE,
        )
        val sink = media.audioTrackConfig
        val sinkInstant = media.audioTrackObservedAt ?: raw.instant
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE,
            sink?.sampleRate?.toLong(),
            TelemetryUnit.HERTZ,
            MEDIA3_AUDIO_TRACK,
            sinkInstant,
            activeReason,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_ENCODING,
            sink?.encoding?.let(::audioEncodingName),
            MEDIA3_AUDIO_TRACK,
            sinkInstant,
            activeReason,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_CHANNEL_MASK,
            sink?.channelConfig?.let { "0x${it.toUInt().toString(16)}" },
            MEDIA3_AUDIO_TRACK,
            sinkInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_BUFFER_SIZE,
            sink?.bufferSize?.toLong(),
            TelemetryUnit.BYTES,
            MEDIA3_AUDIO_TRACK,
            sinkInstant,
            activeReason,
        )
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_OFFLOAD,
            sink?.offload,
            MEDIA3_AUDIO_TRACK,
            sinkInstant,
            activeReason,
        )
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_TUNNELING,
            sink?.tunneling,
            MEDIA3_AUDIO_TRACK,
            sinkInstant,
            activeReason,
        )

        addSystemMetrics(metrics, raw, rateTracker)
        addPendingOutputMetrics(metrics, raw.instant, media.sessionId)

        val selected = when (val selection = observation.selection) {
            TelemetryMetricSelection.Default -> TelemetryMetricCatalog.defaultIds
            is TelemetryMetricSelection.Explicit -> selection.metricIds
        }
        val retained = selected.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            retained.toList().forEach { id ->
                val inputs = when (val evidence = metrics[id]?.evidence) {
                    is TelemetryEvidence.Derived -> evidence.inputMetricIds
                    is TelemetryEvidence.Estimated -> evidence.inputMetricIds
                    else -> emptySet()
                }
                if (retained.addAll(inputs)) changed = true
            }
        } while (changed)
        return TelemetrySnapshot(
            capturedAtEpochMs = raw.instant.epochMs,
            capturedAtElapsedRealtimeMs = raw.instant.elapsedRealtimeMs,
            playbackSessionId = media.sessionId,
            metrics = metrics.values.filter { it.id in retained },
            recentEvents = media.recentEvents,
        )
    }

    private fun addSystemMetrics(
        metrics: MutableMap<TelemetryMetricId, TelemetryMetric>,
        raw: RawTelemetrySample,
        rateTracker: TelemetryRateTracker,
    ) {
        val system = raw.system
        metrics.measuredInteger(
            TelemetryMetricCatalog.PROCESS_CPU_TIME,
            system.processCpuTimeMs,
            TelemetryUnit.MILLISECONDS,
            ANDROID_PROCESS,
            raw.instant,
        )
        rateTracker.processCpuPercent()?.let { cpu ->
            metrics[TelemetryMetricCatalog.PROCESS_CPU_PERCENT] = TelemetryMetric(
                id = TelemetryMetricCatalog.PROCESS_CPU_PERCENT,
                section = TelemetrySection.PROCESS,
                evidence = TelemetryEvidence.Derived(
                    reading = TelemetryReading.Decimal(cpu.value, TelemetryUnit.PERCENT),
                    source = ANDROID_PROCESS,
                    observedAtEpochMs = raw.instant.epochMs,
                    observedAtElapsedRealtimeMs = raw.instant.elapsedRealtimeMs,
                    window = cpu.window,
                    calculationId = "rate.process_cpu_one_core",
                    inputMetricIds = setOf(TelemetryMetricCatalog.PROCESS_CPU_TIME),
                    operands = mapOf(
                        "cpu.delta_ms" to cpu.delta.toDouble(),
                        "window.elapsed_ms" to cpu.window.durationMs.toDouble(),
                    ),
                ),
            )
        } ?: metrics.unavailable(
            TelemetryMetricCatalog.PROCESS_CPU_PERCENT,
            TelemetryUnavailableReason.WARMING_UP,
            raw.instant,
            ANDROID_PROCESS,
        )
        metrics.measuredInteger(
            TelemetryMetricCatalog.PROCESS_JAVA_HEAP,
            system.javaHeapBytes,
            TelemetryUnit.BYTES,
            ANDROID_PROCESS,
            raw.instant,
        )
        metrics.measuredInteger(
            TelemetryMetricCatalog.PROCESS_NATIVE_HEAP,
            system.nativeHeapBytes,
            TelemetryUnit.BYTES,
            ANDROID_PROCESS,
            raw.instant,
        )
        metrics.measuredInteger(
            TelemetryMetricCatalog.PROCESS_PSS,
            system.pssBytes,
            TelemetryUnit.BYTES,
            ANDROID_PROCESS,
            system.expensiveObservedAt,
        )
        metrics.measuredInteger(
            TelemetryMetricCatalog.PROCESSOR_COUNT,
            system.processorCount.toLong(),
            TelemetryUnit.COUNT,
            ANDROID_PROCESS,
            raw.instant,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.PROCESS_THERMAL_STATUS,
            system.thermalStatus,
            ANDROID_POWER,
            system.expensiveObservedAt,
            system.thermalUnavailableReason ?: TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.PROCESS_SOC_MODEL,
            system.socModel,
            ANDROID_BUILD,
            raw.instant,
            TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION,
        )
        metrics.measuredDecimalOrUnavailable(
            TelemetryMetricCatalog.POWER_BATTERY_CURRENT,
            system.batteryCurrentMa,
            TelemetryUnit.MILLIAMPERES,
            ANDROID_BATTERY,
            system.expensiveObservedAt,
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
        )
        metrics.measuredDecimalOrUnavailable(
            TelemetryMetricCatalog.POWER_BATTERY_VOLTAGE,
            system.batteryVoltageV,
            TelemetryUnit.VOLTS,
            ANDROID_BATTERY,
            system.expensiveObservedAt,
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
        )
        metrics.measuredDecimalOrUnavailable(
            TelemetryMetricCatalog.POWER_BATTERY_TEMPERATURE,
            system.batteryTemperatureC,
            TelemetryUnit.CELSIUS,
            ANDROID_BATTERY,
            system.expensiveObservedAt,
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
        )
        val devicePowerMw = system.batteryCurrentMa?.let { current ->
            system.batteryVoltageV?.let { voltage -> kotlin.math.abs(current * voltage) }
        }
        if (devicePowerMw != null) {
            metrics[TelemetryMetricCatalog.POWER_DEVICE_ESTIMATE] = TelemetryMetric(
                id = TelemetryMetricCatalog.POWER_DEVICE_ESTIMATE,
                section = TelemetrySection.POWER,
                evidence = TelemetryEvidence.Estimated(
                    reading = TelemetryReading.Decimal(devicePowerMw, TelemetryUnit.MILLIWATTS),
                    source = ANDROID_BATTERY,
                    observedAtEpochMs = system.expensiveObservedAt.epochMs,
                    observedAtElapsedRealtimeMs = system.expensiveObservedAt.elapsedRealtimeMs,
                    methodId = "power.whole_device_current_times_voltage",
                    inputMetricIds = setOf(
                        TelemetryMetricCatalog.POWER_BATTERY_CURRENT,
                        TelemetryMetricCatalog.POWER_BATTERY_VOLTAGE,
                    ),
                ),
            )
        } else {
            metrics.unavailable(
                TelemetryMetricCatalog.POWER_DEVICE_ESTIMATE,
                TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
                system.expensiveObservedAt,
                ANDROID_BATTERY,
            )
        }
    }

    private fun addPendingOutputMetrics(
        metrics: MutableMap<TelemetryMetricId, TelemetryMetric>,
        instant: TelemetryInstant,
        activeSessionId: String?,
    ) {
        val reason = if (activeSessionId == null) {
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
        } else {
            TelemetryUnavailableReason.NOT_SAMPLED
        }
        listOf(
            TelemetryMetricCatalog.ROUTE_SELECTED_NAME,
            TelemetryMetricCatalog.ROUTE_SELECTED_TYPE,
            TelemetryMetricCatalog.ROUTE_CONNECTED_TYPES,
            TelemetryMetricCatalog.ROUTE_DIRECT_SUPPORTED,
            TelemetryMetricCatalog.ROUTE_DIRECT_MODES,
            TelemetryMetricCatalog.ROUTE_SUPPORTED_MIXER_PROFILE_COUNT,
            TelemetryMetricCatalog.ROUTE_PREFERRED_MIXER_PROFILE,
            TelemetryMetricCatalog.USB_HOST_SUPPORTED,
            TelemetryMetricCatalog.USB_AUDIO_DEVICE_COUNT,
            TelemetryMetricCatalog.USB_DEVICE_NAMES,
            TelemetryMetricCatalog.USB_VENDOR_PRODUCT_IDS,
            TelemetryMetricCatalog.USB_PERMISSION_GRANTED,
            TelemetryMetricCatalog.USB_SAMPLE_RATES,
            TelemetryMetricCatalog.USB_CHANNEL_COUNTS,
            TelemetryMetricCatalog.USB_ENCODINGS,
        ).forEach { id -> metrics.unavailable(id, reason, instant) }
    }

    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        synchronized(stateLock) {
            sessionId = mediaItem?.let { UUID.randomUUID().toString() }
            sourceFacts = mediaItem?.mediaMetadata?.extras.let(TelemetryMediaItemExtras::read)
                .takeIf { mediaItem != null }
            sourceObservedAt = instant.takeIf { mediaItem != null }
            sourceBytesRead.set(0)
            decoderName = null
            decoderInitializationDurationMs = null
            decoderSoftwareOnly = null
            decoderHardwareAccelerated = null
            decoderVendor = null
            inputFormat = null
            decoderCounters = null
            lastDecoderCounters = null
            audioTrackConfig = null
            underrunCount = 0
            lastUnderrunFeedGapMs = null
            recordEventLocked(
                kind = TelemetryEventKind.FORMAT_CHANGED,
                severity = TelemetryEventSeverity.INFO,
                instant = instant,
                code = "playback.media_item_changed",
            )
        }
    }

    override fun onEvents(player: Player, events: AnalyticsListener.Events) {
        val instant = clock.now()
        synchronized(stateLock) {
            playerFacts = PlayerTelemetryFacts(
                state = player.playbackState.toTelemetryState(),
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                bufferedDurationMs = player.totalBufferedDuration.coerceAtLeast(0),
                speed = player.playbackParameters.speed.toDouble(),
                pitch = player.playbackParameters.pitch.toDouble(),
                volume = player.volume.toDouble(),
                skipSilence = (player as? ExoPlayer)?.skipSilenceEnabled ?: false,
            )
            playerObservedAt = instant
        }
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        synchronized(stateLock) {
            recordEventLocked(
                kind = TelemetryEventKind.PLAYBACK_STATE_CHANGED,
                severity = TelemetryEventSeverity.INFO,
                instant = instant,
                code = "playback.state_${state.toTelemetryState()}",
                relatedMetricIds = setOf(TelemetryMetricCatalog.PLAYBACK_STATE),
            )
        }
    }

    override fun onPlaybackParametersChanged(
        eventTime: AnalyticsListener.EventTime,
        playbackParameters: PlaybackParameters,
    ) = Unit

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        val codecFlags = decoderFlags(decoderName)
        synchronized(stateLock) {
            this.decoderName = decoderName
            decoderInitializationDurationMs = initializationDurationMs.coerceAtLeast(0)
            decoderSoftwareOnly = codecFlags?.softwareOnly
            decoderHardwareAccelerated = codecFlags?.hardwareAccelerated
            decoderVendor = codecFlags?.vendor
            decoderObservedAt = instant
            recordEventLocked(
                kind = TelemetryEventKind.DECODER_INITIALIZED,
                severity = TelemetryEventSeverity.INFO,
                instant = instant,
                code = "decoder.initialized",
                relatedMetricIds = setOf(TelemetryMetricCatalog.DECODER_NAME),
            )
        }
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        synchronized(stateLock) {
            inputFormat = format
            inputFormatObservedAt = instant
            recordEventLocked(
                kind = TelemetryEventKind.FORMAT_CHANGED,
                severity = TelemetryEventSeverity.INFO,
                instant = instant,
                code = "decoder.input_format_changed",
                relatedMetricIds = setOf(TelemetryMetricCatalog.DECODER_INPUT_MIME),
            )
        }
    }

    override fun onAudioEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
        synchronized(stateLock) {
            this.decoderCounters = decoderCounters
            lastDecoderCounters = null
        }
    }

    override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
        decoderCounters.ensureUpdated()
        synchronized(stateLock) {
            lastDecoderCounters = DecoderCounterSnapshot(
                queuedInputBuffers = decoderCounters.queuedInputBufferCount.toLong(),
                renderedOutputBuffers = decoderCounters.renderedOutputBufferCount.toLong(),
                skippedOutputBuffers = decoderCounters.skippedOutputBufferCount.toLong(),
            )
            if (this.decoderCounters === decoderCounters) this.decoderCounters = null
        }
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        synchronized(stateLock) {
            this.audioTrackConfig = audioTrackConfig
            audioTrackObservedAt = instant
            recordEventLocked(
                kind = TelemetryEventKind.OUTPUT_INITIALIZED,
                severity = TelemetryEventSeverity.INFO,
                instant = instant,
                code = "output.audio_track_initialized",
                relatedMetricIds = setOf(TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE),
            )
        }
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        synchronized(stateLock) {
            if (this.audioTrackConfig == audioTrackConfig) this.audioTrackConfig = null
        }
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        synchronized(stateLock) {
            underrunCount += 1
            lastUnderrunFeedGapMs = elapsedSinceLastFeedMs.coerceAtLeast(0)
            underrunObservedAt = instant
            recordEventLocked(
                kind = TelemetryEventKind.UNDERRUN,
                severity = TelemetryEventSeverity.WARNING,
                instant = instant,
                code = "playback.audio_underrun",
                relatedMetricIds = setOf(TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT),
            )
        }
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        synchronized(stateLock) {
            recordEventLocked(
                kind = TelemetryEventKind.SEEK_COMPLETED,
                severity = TelemetryEventSeverity.INFO,
                instant = instant,
                code = "playback.seek_completed",
                relatedMetricIds = setOf(TelemetryMetricCatalog.PLAYBACK_POSITION),
            )
        }
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        recordError(eventTime, "error.player")
    }

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
        recordError(eventTime, "error.audio_codec")
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        recordError(eventTime, "error.audio_sink")
    }

    private fun recordError(eventTime: AnalyticsListener.EventTime, code: String) {
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        synchronized(stateLock) {
            recordEventLocked(
                kind = TelemetryEventKind.ERROR,
                severity = TelemetryEventSeverity.ERROR,
                instant = instant,
                code = code,
            )
        }
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (bytesTransferred > 0) sourceBytesRead.addAndGet(bytesTransferred.toLong())
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    private fun recordEventLocked(
        kind: TelemetryEventKind,
        severity: TelemetryEventSeverity,
        instant: TelemetryInstant,
        code: String,
        relatedMetricIds: Set<TelemetryMetricId> = emptySet(),
    ) {
        recentEvents.addLast(
            TelemetryEvent(
                sequence = eventSequence.incrementAndGet(),
                kind = kind,
                severity = severity,
                occurredAtEpochMs = instant.epochMs,
                occurredAtElapsedRealtimeMs = instant.elapsedRealtimeMs,
                code = code,
                playbackSessionId = sessionId,
                relatedMetricIds = relatedMetricIds,
            ),
        )
        while (recentEvents.size > MAX_RECENT_EVENTS) recentEvents.removeFirst()
    }

    private fun decoderFlags(name: String): DecoderFlags? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == name }?.let {
                DecoderFlags(
                    softwareOnly = it.isSoftwareOnly,
                    hardwareAccelerated = it.isHardwareAccelerated,
                    vendor = it.isVendor,
                )
            }
        }.getOrNull()
    } else {
        null
    }

    private companion object {
        const val MAX_RECENT_EVENTS = 128
        val SOURCE_METADATA = TelemetryDataSource(TelemetrySourceId("library.metadata"))
        val MEDIA3_ANALYTICS = TelemetryDataSource(TelemetrySourceId("media3.analytics"))
        val MEDIA3_COUNTERS = TelemetryDataSource(TelemetrySourceId("media3.decoder_counters"))
        val MEDIA3_PLAYER = TelemetryDataSource(TelemetrySourceId("media3.player"))
        val MEDIA3_DATA_SOURCE = TelemetryDataSource(TelemetrySourceId("media3.data_source"))
        val MEDIA3_AUDIO_TRACK = TelemetryDataSource(TelemetrySourceId("media3.audio_track"))
        val ANDROID_CODEC = TelemetryDataSource(TelemetrySourceId("android.media_codec"))
        val ANDROID_PROCESS = TelemetryDataSource(TelemetrySourceId("android.process"))
        val ANDROID_POWER = TelemetryDataSource(TelemetrySourceId("android.power"))
        val ANDROID_BUILD = TelemetryDataSource(TelemetrySourceId("android.build"))
        val ANDROID_BATTERY = TelemetryDataSource(TelemetrySourceId("android.battery"))
        val APP_CONFIGURATION = TelemetryDataSource(TelemetrySourceId("vesqen.configuration"))
    }
}

private fun TelemetryObservation.effectiveIntervalMs(): Long = when (powerMode) {
    TelemetryPowerMode.STANDARD -> refreshInterval.milliseconds
    TelemetryPowerMode.LOW_POWER -> maxOf(refreshInterval.milliseconds, 2_000L)
}

internal data class TelemetrySamplingState(
    val activeObservationCount: Int,
    val activeIntervalMs: Long?,
)

internal data class TelemetryInstant(
    val epochMs: Long,
    val elapsedRealtimeMs: Long,
)

internal interface TelemetryClock {
    fun epochMs(): Long
    fun elapsedRealtimeMs(): Long
    fun processCpuTimeMs(): Long

    fun now(): TelemetryInstant = TelemetryInstant(epochMs(), elapsedRealtimeMs())

    fun fromElapsedRealtime(elapsedRealtimeMs: Long): TelemetryInstant {
        val current = now()
        return TelemetryInstant(
            epochMs = (current.epochMs - (current.elapsedRealtimeMs - elapsedRealtimeMs)).coerceAtLeast(0),
            elapsedRealtimeMs = elapsedRealtimeMs.coerceAtLeast(0),
        )
    }
}

private object AndroidTelemetryClock : TelemetryClock {
    override fun epochMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun processCpuTimeMs(): Long = Process.getElapsedCpuTime()
}

private data class RawTelemetrySample(
    val instant: TelemetryInstant,
    val media: MediaTelemetrySnapshot,
    val system: SystemTelemetrySnapshot,
)

private data class MediaTelemetrySnapshot(
    val sessionId: String?,
    val sourceFacts: TelemetrySourceFacts?,
    val sourceObservedAt: TelemetryInstant?,
    val decoderName: String?,
    val decoderInitializationDurationMs: Long?,
    val decoderSoftwareOnly: Boolean?,
    val decoderHardwareAccelerated: Boolean?,
    val decoderVendor: Boolean?,
    val decoderObservedAt: TelemetryInstant?,
    val inputFormat: Format?,
    val inputFormatObservedAt: TelemetryInstant?,
    val decoderCounters: DecoderCounterSnapshot?,
    val audioTrackConfig: AudioSink.AudioTrackConfig?,
    val audioTrackObservedAt: TelemetryInstant?,
    val playerFacts: PlayerTelemetryFacts,
    val playerObservedAt: TelemetryInstant?,
    val sourceBytesRead: Long,
    val underrunCount: Long,
    val lastUnderrunFeedGapMs: Long?,
    val underrunObservedAt: TelemetryInstant?,
    val recentEvents: List<TelemetryEvent>,
)

private data class PlayerTelemetryFacts(
    val state: String = "idle",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val bufferedDurationMs: Long = 0,
    val speed: Double = 1.0,
    val pitch: Double = 1.0,
    val volume: Double = 1.0,
    val skipSilence: Boolean = false,
)

private data class DecoderCounterSnapshot(
    val queuedInputBuffers: Long,
    val renderedOutputBuffers: Long,
    val skippedOutputBuffers: Long,
)

private data class DecoderFlags(
    val softwareOnly: Boolean,
    val hardwareAccelerated: Boolean,
    val vendor: Boolean,
)

private class AndroidSystemTelemetryProbe(
    private val context: Context,
    private val clock: TelemetryClock = AndroidTelemetryClock,
) {
    private val runtime = Runtime.getRuntime()
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var cachedExpensive: ExpensiveSystemSample? = null

    fun capture(instant: TelemetryInstant): SystemTelemetrySnapshot {
        val expensive = cachedExpensive?.takeIf {
            instant.elapsedRealtimeMs - it.observedAt.elapsedRealtimeMs < EXPENSIVE_SAMPLE_INTERVAL_MS
        } ?: captureExpensive(instant).also { cachedExpensive = it }
        return SystemTelemetrySnapshot(
            processCpuTimeMs = clock.processCpuTimeMs().coerceAtLeast(0),
            javaHeapBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize().coerceAtLeast(0),
            processorCount = runtime.availableProcessors().coerceAtLeast(1),
            pssBytes = expensive.pssBytes,
            thermalStatus = expensive.thermalStatus,
            thermalUnavailableReason = expensive.thermalUnavailableReason,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.takeUnless { it.isBlank() || it.equals("unknown", ignoreCase = true) }
            } else {
                null
            },
            batteryCurrentMa = expensive.batteryCurrentMa,
            batteryVoltageV = expensive.batteryVoltageV,
            batteryTemperatureC = expensive.batteryTemperatureC,
            expensiveObservedAt = expensive.observedAt,
        )
    }

    private fun captureExpensive(instant: TelemetryInstant): ExpensiveSystemSample {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val currentMicroAmps = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeUnless { it == Long.MIN_VALUE }
        val voltageMv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val temperatureTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager.currentThermalStatus.toThermalStatusName() }
        } else {
            Result.failure(UnsupportedOperationException("Thermal status requires API 29"))
        }
        return ExpensiveSystemSample(
            observedAt = instant,
            pssBytes = memoryInfo.totalPss.toLong().coerceAtLeast(0) * 1_024,
            thermalStatus = thermal.getOrNull(),
            thermalUnavailableReason = if (thermal.isSuccess) null else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION
            } else {
                TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
            },
            batteryCurrentMa = currentMicroAmps?.div(1_000.0),
            batteryVoltageV = voltageMv?.div(1_000.0),
            batteryTemperatureC = temperatureTenths?.div(10.0),
        )
    }

    private companion object {
        const val EXPENSIVE_SAMPLE_INTERVAL_MS = 2_000L
    }
}

private data class ExpensiveSystemSample(
    val observedAt: TelemetryInstant,
    val pssBytes: Long,
    val thermalStatus: String?,
    val thermalUnavailableReason: TelemetryUnavailableReason?,
    val batteryCurrentMa: Double?,
    val batteryVoltageV: Double?,
    val batteryTemperatureC: Double?,
)

private data class SystemTelemetrySnapshot(
    val processCpuTimeMs: Long,
    val javaHeapBytes: Long,
    val nativeHeapBytes: Long,
    val processorCount: Int,
    val pssBytes: Long,
    val thermalStatus: String?,
    val thermalUnavailableReason: TelemetryUnavailableReason?,
    val socModel: String?,
    val batteryCurrentMa: Double?,
    val batteryVoltageV: Double?,
    val batteryTemperatureC: Double?,
    val expensiveObservedAt: TelemetryInstant,
)

internal data class RateResult(
    val value: Double,
    val delta: Long,
    val window: TelemetryWindow,
)

internal class TelemetryRateTracker(private val windowMs: Long) {
    private val samples = ArrayDeque<RateSample>()
    private var sessionId: String? = null

    fun add(instant: TelemetryInstant, sessionId: String?, sourceBytes: Long, processCpuTimeMs: Long) {
        if (this.sessionId != sessionId) {
            samples.clear()
            this.sessionId = sessionId
        }
        samples.addLast(RateSample(instant, sourceBytes, processCpuTimeMs))
        while (samples.size > 2 && instant.elapsedRealtimeMs - samples.elementAt(1).instant.elapsedRealtimeMs >= windowMs) {
            samples.removeFirst()
        }
    }

    fun sourceReadBitrate(): RateResult? = rate { it.sourceBytes }?.let { result ->
        result.copy(value = result.value * 8.0)
    }

    fun processCpuPercent(): RateResult? = rate { it.processCpuTimeMs }?.let { result ->
        // rate() reports milliseconds of CPU time per wall-clock second. Dividing by ten converts
        // that to one-core utilization percent: cpuDeltaMs / elapsedDeltaMs * 100.
        result.copy(value = result.value / 10.0)
    }

    private fun rate(value: (RateSample) -> Long): RateResult? {
        val first = samples.firstOrNull() ?: return null
        val last = samples.lastOrNull() ?: return null
        val elapsedMs = last.instant.elapsedRealtimeMs - first.instant.elapsedRealtimeMs
        val delta = value(last) - value(first)
        if (elapsedMs <= 0 || delta < 0) return null
        return RateResult(
            value = delta.toDouble() / (elapsedMs / 1_000.0),
            delta = delta,
            window = TelemetryWindow(
                startedAtEpochMs = first.instant.epochMs,
                endedAtEpochMs = last.instant.epochMs,
                startedAtElapsedRealtimeMs = first.instant.elapsedRealtimeMs,
                endedAtElapsedRealtimeMs = last.instant.elapsedRealtimeMs,
            ),
        )
    }

    private data class RateSample(
        val instant: TelemetryInstant,
        val sourceBytes: Long,
        val processCpuTimeMs: Long,
    )
}

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.measuredText(
    id: TelemetryMetricId,
    value: String,
    source: TelemetryDataSource,
    instant: TelemetryInstant,
) = putMetric(id, TelemetryEvidence.Measured(TelemetryReading.Text(value), source, instant.epochMs, instant.elapsedRealtimeMs))

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.measuredTextOrUnavailable(
    id: TelemetryMetricId,
    value: String?,
    source: TelemetryDataSource,
    instant: TelemetryInstant,
    reason: TelemetryUnavailableReason,
) = value?.takeIf(String::isNotBlank)?.let { measuredText(id, it, source, instant) }
    ?: unavailable(id, reason, instant, source)

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.measuredFlag(
    id: TelemetryMetricId,
    value: Boolean,
    source: TelemetryDataSource,
    instant: TelemetryInstant,
) = putMetric(id, TelemetryEvidence.Measured(TelemetryReading.Flag(value), source, instant.epochMs, instant.elapsedRealtimeMs))

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.measuredFlagOrUnavailable(
    id: TelemetryMetricId,
    value: Boolean?,
    source: TelemetryDataSource,
    instant: TelemetryInstant,
    reason: TelemetryUnavailableReason,
) = value?.let { measuredFlag(id, it, source, instant) } ?: unavailable(id, reason, instant, source)

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.measuredInteger(
    id: TelemetryMetricId,
    value: Long,
    unit: TelemetryUnit,
    source: TelemetryDataSource,
    instant: TelemetryInstant,
) = putMetric(id, TelemetryEvidence.Measured(TelemetryReading.Integer(value, unit), source, instant.epochMs, instant.elapsedRealtimeMs))

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.measuredIntegerOrUnavailable(
    id: TelemetryMetricId,
    value: Long?,
    unit: TelemetryUnit,
    source: TelemetryDataSource,
    instant: TelemetryInstant,
    reason: TelemetryUnavailableReason,
) = value?.let { measuredInteger(id, it, unit, source, instant) } ?: unavailable(id, reason, instant, source)

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.measuredDecimal(
    id: TelemetryMetricId,
    value: Double,
    unit: TelemetryUnit,
    source: TelemetryDataSource,
    instant: TelemetryInstant,
) = putMetric(id, TelemetryEvidence.Measured(TelemetryReading.Decimal(value, unit), source, instant.epochMs, instant.elapsedRealtimeMs))

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.measuredDecimalOrUnavailable(
    id: TelemetryMetricId,
    value: Double?,
    unit: TelemetryUnit,
    source: TelemetryDataSource,
    instant: TelemetryInstant,
    reason: TelemetryUnavailableReason,
) = value?.let { measuredDecimal(id, it, unit, source, instant) } ?: unavailable(id, reason, instant, source)

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.estimatedIntegerOrUnavailable(
    id: TelemetryMetricId,
    value: Long?,
    unit: TelemetryUnit,
    source: TelemetryDataSource,
    instant: TelemetryInstant,
    methodId: String,
    reason: TelemetryUnavailableReason,
) = value?.let {
    putMetric(
        id,
        TelemetryEvidence.Estimated(
            reading = TelemetryReading.Integer(it, unit),
            source = source,
            observedAtEpochMs = instant.epochMs,
            observedAtElapsedRealtimeMs = instant.elapsedRealtimeMs,
            methodId = methodId,
        ),
    )
} ?: unavailable(id, reason, instant, source)

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.unavailable(
    id: TelemetryMetricId,
    reason: TelemetryUnavailableReason,
    instant: TelemetryInstant,
    source: TelemetryDataSource? = null,
) = putMetric(
    id,
    TelemetryEvidence.Unavailable(
        reason = reason,
        observedAtEpochMs = instant.epochMs,
        observedAtElapsedRealtimeMs = instant.elapsedRealtimeMs,
        source = source,
    ),
)

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.putMetric(
    id: TelemetryMetricId,
    evidence: TelemetryEvidence,
) {
    val descriptor = TelemetryMetricCatalog.descriptor(id)
    this[id] = TelemetryMetric(id = id, section = descriptor.section, evidence = evidence)
}

private fun Int.toTelemetryState(): String = when (this) {
    Player.STATE_BUFFERING -> "buffering"
    Player.STATE_READY -> "ready"
    Player.STATE_ENDED -> "ended"
    else -> "idle"
}

private fun Int.toThermalStatusName(): String = when (this) {
    PowerManager.THERMAL_STATUS_NONE -> "none"
    PowerManager.THERMAL_STATUS_LIGHT -> "light"
    PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
    PowerManager.THERMAL_STATUS_SEVERE -> "severe"
    PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
    else -> "unknown"
}

private fun audioEncodingName(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_8BIT -> "pcm-8"
    C.ENCODING_PCM_16BIT -> "pcm-16"
    C.ENCODING_PCM_24BIT -> "pcm-24"
    C.ENCODING_PCM_32BIT -> "pcm-32"
    C.ENCODING_PCM_FLOAT -> "pcm-float"
    C.ENCODING_AC3 -> "ac3"
    C.ENCODING_E_AC3 -> "e-ac3"
    C.ENCODING_DTS -> "dts"
    C.ENCODING_DTS_HD -> "dts-hd"
    else -> "encoding-$encoding"
}
