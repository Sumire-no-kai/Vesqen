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
import androidx.media3.exoplayer.source.MediaSource
import io.github.sumirenokai.vesqen.audio.AudioRouteSource
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStateRepository
import java.util.ArrayDeque
import java.util.IdentityHashMap
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val usbOutputStateRepository: UsbOutputStateRepository = UsbOutputStateRepository(),
    private val clock: TelemetryClock = AndroidTelemetryClock,
    audioRouteSource: AudioRouteSource = AudioRouteSource(context),
) : PlaybackTelemetry, AnalyticsListener, TransferListener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private val requestLock = Any()
    /** Player-runtime aggregate. It intentionally is not reset at media transitions: DataSource
     * callbacks do not expose a MediaPeriod and can include seek/re-read/next-item prefetch. */
    private val dataSourceBytesTransferredTotal = AtomicLong()
    private val currentMediaTransfers = CurrentMediaTransferAttribution<DataSource>()
    private val eventSequence = AtomicLong()
    private val observationSequence = AtomicLong()
    private val observations = linkedMapOf<Long, TelemetryObservation>()
    private val rawSamples = MutableStateFlow<RawTelemetrySample?>(null)
    private val captureMutex = Mutex()
    private val systemProbe = AndroidSystemTelemetryProbe(appContext, clock)
    private val outputProbe = AndroidOutputTelemetryProbe(
        appContext,
        clock,
        audioRouteSource,
        ::onOutputSignal,
    )
    private var samplerJob: Job? = null
    private var samplerIntervalMs: Long? = null
    private var samplerGeneration = 0L
    @Volatile private var samplingDemand = TelemetrySamplingDemand.NONE
    private var player: ExoPlayer? = null
    private var sessionId: String? = null
    private var activeMediaPeriodId: MediaSource.MediaPeriodId? = null
    private val sessionByPeriod = linkedMapOf<MediaSource.MediaPeriodId, String>()
    private val pendingInputFormats = linkedMapOf<MediaSource.MediaPeriodId, ObservedFormat>()
    private val pendingDecoders = linkedMapOf<MediaSource.MediaPeriodId, ObservedDecoder>()
    private val decoderGenerations = DecoderGenerationRegistry<MediaSource.MediaPeriodId>()
    private val pendingDecoderCounters = linkedMapOf<MediaSource.MediaPeriodId, DecoderCounters>()
    private val pendingAudioTracks = linkedMapOf<MediaSource.MediaPeriodId, ObservedAudioTrack>()
    private val codecFlagResolver = CodecFlagResolver()
    private var sourceFacts: TelemetrySourceFacts? = null
    private var sourceObservedAt: TelemetryInstant? = null
    private var decoderName: String? = null
    private val decoderGenerationSequence = AtomicLong()
    private var decoderInitializationDurationMs: Long? = null
    private var decoderSoftwareOnly: Boolean? = null
    private var decoderHardwareAccelerated: Boolean? = null
    private var decoderVendor: Boolean? = null
    private var decoderObservedAt: TelemetryInstant? = null
    private var decoderFlagsObservedAt: TelemetryInstant? = null
    private var inputFormat: Format? = null
    private var inputFormatObservedAt: TelemetryInstant? = null
    private var decoderCounters: DecoderCounters? = null
    private var lastDecoderCounters: DecoderCounterSnapshot? = null
    private var audioTrackConfig: AudioTrackFacts? = null
    private var audioTrackIdentity: Any? = null
    private var audioTrackObservedAt: TelemetryInstant? = null
    private var playerFacts: PlayerTelemetryFacts? = null
    private var playerObservedAt: TelemetryInstant? = null
    private var underrunCount = 0L
    private var lastUnderrunFeedGapMs: Long? = null
    private var underrunObservedAt: TelemetryInstant? = null
    private var formatChangeCount = 0L
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
                resetAllFactsLocked()
            }
        }
    }

    override fun observe(observation: TelemetryObservation): Flow<TelemetrySnapshot> = callbackFlow {
        val observationId = observationSequence.incrementAndGet()
        synchronized(requestLock) {
            observations[observationId] = observation
            reconcileSamplerLocked()
        }
        val rateTracker = TelemetryRateTracker(observation.derivedWindowMs)
        // A UI collector must not inherit the snapshot/rate calculation work onto Main.
        val forwarding = launch(Dispatchers.Default) {
            var lastEmissionElapsedMs: Long? = null
            rawSamples.filterNotNull().collect { raw ->
                val due = lastEmissionElapsedMs?.let { previous ->
                    raw.instant.elapsedRealtimeMs - previous >= observation.effectiveIntervalMs()
                } ?: true
                if (due) {
                    send(buildSnapshot(raw, observation, rateTracker))
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
        val requestedDemand = TelemetrySamplingDemand.from(observations.values)
        if (requestedIntervalMs == null) {
            samplerGeneration += 1
            samplerJob?.cancel()
            samplerJob = null
            samplerIntervalMs = null
            samplingDemand = TelemetrySamplingDemand.NONE
            rawSamples.value = null
            runCatching(outputProbe::stop)
            return
        }
        if (
            samplerJob?.isActive == true &&
            samplerIntervalMs == requestedIntervalMs &&
            samplingDemand == requestedDemand
        ) return
        samplerGeneration += 1
        val generation = samplerGeneration
        samplerJob?.cancel()
        samplerIntervalMs = requestedIntervalMs
        samplingDemand = requestedDemand
        if (requestedDemand.includeOutput) {
            runCatching(outputProbe::start)
        } else {
            runCatching(outputProbe::stop)
        }
        samplerJob = scope.launch {
            while (isActive) {
                val captured = try {
                    captureMutex.withLock { captureRaw() }
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    synchronized(stateLock) {
                        recordEventLocked(
                            kind = TelemetryEventKind.ERROR,
                            severity = TelemetryEventSeverity.ERROR,
                            instant = clock.now(),
                            code = "error.telemetry_capture",
                            playbackSessionId = null,
                        )
                    }
                    null
                }
                if (captured != null) {
                    synchronized(requestLock) {
                        if (samplerGeneration == generation && observations.isNotEmpty()) {
                            rawSamples.value = captured
                        }
                    }
                }
                delay(requestedIntervalMs)
            }
        }
    }

    private fun captureRaw(): RawTelemetrySample {
        val (_, media) = synchronized(stateLock) {
            // Take the clock sample while holding the same lock as the event copy. Otherwise a
            // callback can append a newer event after the timestamp but before recentEvents is
            // copied, producing a snapshot that appears to contain an event from the future.
            val capturedAt = clock.now()
            val liveCounters = decoderCounters?.also(DecoderCounters::ensureUpdated)?.let {
                DecoderCounterSnapshot(
                    queuedInputBuffers = it.queuedInputBufferCount.toLong().coerceAtLeast(0),
                    renderedOutputBuffers = it.renderedOutputBufferCount.toLong().coerceAtLeast(0),
                    skippedOutputBuffers = it.skippedOutputBufferCount.toLong().coerceAtLeast(0),
                    observedAt = capturedAt,
                )
            } ?: lastDecoderCounters
            capturedAt to MediaTelemetrySnapshot(
                capturedAt = capturedAt,
                sessionId = sessionId,
                sourceFacts = sourceFacts,
                sourceObservedAt = sourceObservedAt,
                decoderName = decoderName,
                decoderGeneration = decoderGenerations.activeGeneration,
                decoderInitializationDurationMs = decoderInitializationDurationMs,
                decoderSoftwareOnly = decoderSoftwareOnly,
                decoderHardwareAccelerated = decoderHardwareAccelerated,
                decoderVendor = decoderVendor,
                decoderObservedAt = decoderObservedAt,
                decoderFlagsObservedAt = decoderFlagsObservedAt,
                inputFormat = inputFormat,
                inputFormatObservedAt = inputFormatObservedAt,
                decoderCounters = liveCounters,
                audioTrackConfig = audioTrackConfig,
                audioTrackObservedAt = audioTrackObservedAt,
                playerFacts = playerFacts,
                playerObservedAt = playerObservedAt,
                dataSourceBytesTransferredTotal = dataSourceBytesTransferredTotal.get().coerceAtLeast(0),
                currentMediaRead = currentMediaTransfers.snapshot(),
                underrunCount = underrunCount,
                lastUnderrunFeedGapMs = lastUnderrunFeedGapMs,
                underrunObservedAt = underrunObservedAt,
                formatChangeCount = formatChangeCount,
                recentEvents = recentEvents.toList(),
            )
        }
        val demand = samplingDemand
        val system = if (demand.includeSystem) {
            runCatching { systemProbe.capture() }.getOrNull()
        } else {
            null
        }
        val output = if (demand.includeOutput) {
            runCatching {
                outputProbe.capture(
                    audioTrackRequest = media.audioTrackConfig
                        .takeIf { media.sessionId != null }
                        ?.let { config ->
                            AudioTrackRequestFacts(
                                sampleRate = config.sampleRate,
                                encoding = config.encoding,
                                channelConfig = config.channelConfig,
                            )
                        },
                    hasActivePlayback = media.sessionId != null,
                )
            }.getOrNull()
        } else {
            null
        }
        val capturedAt = clock.now()
        return RawTelemetrySample(
            instant = capturedAt,
            media = media,
            system = system,
            systemRequested = demand.includeSystem,
            output = output,
            outputRequested = demand.includeOutput,
        )
    }

    private fun buildSnapshot(
        raw: RawTelemetrySample,
        observation: TelemetryObservation,
        rateTracker: TelemetryRateTracker,
    ): TelemetrySnapshot {
        val metrics = linkedMapOf<TelemetryMetricId, TelemetryMetric>()
        val media = raw.media
        val hasActivePlayback = media.sessionId != null
        val activeDecoderName = media.decoderName.takeIf { hasActivePlayback }
        val activeDecoderGeneration = media.decoderGeneration.takeIf { hasActivePlayback }
        val activeDecoderSoftwareOnly = media.decoderSoftwareOnly.takeIf { hasActivePlayback }
        val activeDecoderHardwareAccelerated = media.decoderHardwareAccelerated.takeIf { hasActivePlayback }
        val activeDecoderVendor = media.decoderVendor.takeIf { hasActivePlayback }
        val activeInputFormat = media.inputFormat.takeIf { hasActivePlayback }
        val activeDecoderCounters = media.decoderCounters.takeIf { hasActivePlayback }
        val activeSink = media.audioTrackConfig.takeIf { hasActivePlayback }
        val activeReason = if (media.sessionId == null) {
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
        } else {
            TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT
        }
        val sourceInstant = media.sourceObservedAt ?: raw.instant
        metrics.estimatedTextOrUnavailable(
            TelemetryMetricCatalog.SOURCE_CONTAINER,
            media.sourceFacts?.container,
            SOURCE_METADATA,
            sourceInstant,
            "library.filename_extension",
            activeReason,
        )
        metrics.estimatedTextOrUnavailable(
            TelemetryMetricCatalog.SOURCE_CODEC_MIME,
            media.sourceFacts?.codecMime,
            SOURCE_METADATA,
            sourceInstant,
            "library.cached_metadata",
            activeReason,
        )
        metrics.estimatedTextOrUnavailable(
            TelemetryMetricCatalog.SOURCE_CODEC_LABEL,
            media.sourceFacts?.codecLabel,
            SOURCE_METADATA,
            sourceInstant,
            "library.cached_metadata",
            activeReason,
        )
        metrics.estimatedIntegerOrUnavailable(
            TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
            media.sourceFacts?.sampleRateHz?.toLong(),
            TelemetryUnit.HERTZ,
            SOURCE_METADATA,
            sourceInstant,
            "library.cached_metadata",
            activeReason,
        )
        metrics.estimatedIntegerOrUnavailable(
            TelemetryMetricCatalog.SOURCE_BIT_DEPTH,
            media.sourceFacts?.bitDepth?.toLong(),
            TelemetryUnit.BITS,
            SOURCE_METADATA,
            sourceInstant,
            "library.cached_metadata",
            activeReason,
        )
        metrics.estimatedIntegerOrUnavailable(
            TelemetryMetricCatalog.SOURCE_CHANNEL_COUNT,
            media.sourceFacts?.channelCount?.toLong(),
            TelemetryUnit.COUNT,
            SOURCE_METADATA,
            sourceInstant,
            "library.cached_metadata",
            activeReason,
        )
        metrics.estimatedIntegerOrUnavailable(
            TelemetryMetricCatalog.SOURCE_AVERAGE_BITRATE,
            media.sourceFacts?.averageBitrate?.toLong(),
            TelemetryUnit.BITS_PER_SECOND,
            SOURCE_METADATA,
            sourceInstant,
            "library.cached_metadata",
            activeReason,
        )
        metrics.estimatedIntegerOrUnavailable(
            TelemetryMetricCatalog.SOURCE_FILE_SIZE,
            media.sourceFacts?.fileSizeBytes,
            TelemetryUnit.BYTES,
            SOURCE_METADATA,
            sourceInstant,
            "library.cached_metadata",
            activeReason,
        )

        val decoderInstant = media.decoderObservedAt.takeIf { hasActivePlayback } ?: raw.instant
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.DECODER_NAME,
            activeDecoderName,
            MEDIA3_ANALYTICS,
            decoderInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_INITIALIZATION_DURATION,
            media.decoderInitializationDurationMs.takeIf { hasActivePlayback },
            TelemetryUnit.MILLISECONDS,
            MEDIA3_ANALYTICS,
            decoderInstant,
            activeReason,
        )
        val codecFlagReason = when {
            media.sessionId == null -> TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION
            activeDecoderName == null -> TelemetryUnavailableReason.WARMING_UP
            else -> TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
        }
        val codecFlagInstant = decoderFlagEvidenceInstant(
            hasActivePlayback = hasActivePlayback,
            flagsObservedAt = media.decoderFlagsObservedAt,
            snapshotInstant = raw.instant,
        )
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.DECODER_SOFTWARE_ONLY,
            activeDecoderSoftwareOnly,
            ANDROID_CODEC,
            codecFlagInstant,
            codecFlagReason,
        )
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.DECODER_HARDWARE_ACCELERATED,
            activeDecoderHardwareAccelerated,
            ANDROID_CODEC,
            codecFlagInstant,
            codecFlagReason,
        )
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.DECODER_VENDOR,
            activeDecoderVendor,
            ANDROID_CODEC,
            codecFlagInstant,
            codecFlagReason,
        )
        val decoderPath = when {
            activeSink?.offload == true -> "offload"
            activeDecoderSoftwareOnly == true -> "software"
            activeDecoderHardwareAccelerated == true -> "platform_hardware"
            activeDecoderName != null -> "platform_unspecified"
            else -> null
        }
        if (decoderPath != null) {
            metrics.putMetric(
                TelemetryMetricCatalog.DECODER_PATH,
                TelemetryEvidence.Estimated(
                    reading = TelemetryReading.Text(decoderPath),
                    source = ANDROID_CODEC,
                    observedAtEpochMs = raw.instant.epochMs,
                    observedAtElapsedRealtimeMs = raw.instant.elapsedRealtimeMs,
                    methodId = "decoder.path_from_public_runtime_facts",
                    inputMetricIds = setOf(
                        TelemetryMetricCatalog.DECODER_NAME,
                        TelemetryMetricCatalog.DECODER_SOFTWARE_ONLY,
                        TelemetryMetricCatalog.DECODER_HARDWARE_ACCELERATED,
                        TelemetryMetricCatalog.PLAYBACK_OFFLOAD,
                    ),
                ),
            )
        } else {
            metrics.unavailable(TelemetryMetricCatalog.DECODER_PATH, codecFlagReason, raw.instant, ANDROID_CODEC)
        }
        val formatInstant = media.inputFormatObservedAt.takeIf { hasActivePlayback } ?: raw.instant
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.DECODER_INPUT_MIME,
            activeInputFormat?.sampleMimeType,
            MEDIA3_ANALYTICS,
            formatInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_INPUT_SAMPLE_RATE,
            activeInputFormat?.sampleRate?.takeIf { it != Format.NO_VALUE }?.toLong(),
            TelemetryUnit.HERTZ,
            MEDIA3_ANALYTICS,
            formatInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_INPUT_CHANNEL_COUNT,
            activeInputFormat?.channelCount?.takeIf { it != Format.NO_VALUE }?.toLong(),
            TelemetryUnit.COUNT,
            MEDIA3_ANALYTICS,
            formatInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_RENDERER_QUEUED_INPUT_BUFFERS_TOTAL,
            activeDecoderCounters?.queuedInputBuffers,
            TelemetryUnit.COUNT,
            MEDIA3_COUNTERS,
            activeDecoderCounters?.observedAt ?: raw.instant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_RENDERER_RENDERED_OUTPUT_BUFFERS_TOTAL,
            activeDecoderCounters?.renderedOutputBuffers,
            TelemetryUnit.COUNT,
            MEDIA3_COUNTERS,
            activeDecoderCounters?.observedAt ?: raw.instant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_RENDERER_SKIPPED_OUTPUT_BUFFERS_TOTAL,
            activeDecoderCounters?.skippedOutputBuffers,
            TelemetryUnit.COUNT,
            MEDIA3_COUNTERS,
            activeDecoderCounters?.observedAt ?: raw.instant,
            activeReason,
        )
        val decoderOutputReason = if (media.sessionId == null) {
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
        } else {
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM
        }
        metrics.unavailable(
            TelemetryMetricCatalog.DECODER_OUTPUT_SAMPLE_RATE,
            decoderOutputReason,
            raw.instant,
            MEDIA3_ANALYTICS,
            "Media3 does not expose the decoder PCM output format separately from the audio sink",
        )
        metrics.unavailable(
            TelemetryMetricCatalog.DECODER_OUTPUT_ENCODING,
            decoderOutputReason,
            raw.instant,
            MEDIA3_ANALYTICS,
            "Media3 does not expose the decoder PCM output format separately from the audio sink",
        )
        metrics.unavailable(
            TelemetryMetricCatalog.DECODER_OUTPUT_CHANNEL_CONFIG,
            decoderOutputReason,
            raw.instant,
            MEDIA3_ANALYTICS,
            "Media3 does not expose the decoder PCM output format separately from the audio sink",
        )
        metrics.unavailable(
            TelemetryMetricCatalog.DECODER_COMPRESSED_FRAME_SIZE,
            decoderOutputReason,
            raw.instant,
            MEDIA3_ANALYTICS,
            "Public callbacks do not associate transferred bytes with individual compressed frames",
        )
        metrics.unavailable(
            TelemetryMetricCatalog.DECODER_FRAME_DECODE_TIME,
            decoderOutputReason,
            raw.instant,
            MEDIA3_ANALYTICS,
            "Public callbacks expose decoder initialization time, not per-frame decode duration",
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.DECODER_FORMAT_CHANGE_COUNT,
            media.formatChangeCount.takeIf { media.sessionId != null },
            TelemetryUnit.COUNT,
            MEDIA3_ANALYTICS,
            raw.instant,
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK,
        )

        val playerInstant = media.playerObservedAt ?: raw.instant
        val playerFacts = media.playerFacts.takeIf { media.sessionId != null }
        val playerReason = if (media.sessionId == null) {
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
        } else {
            TelemetryUnavailableReason.WARMING_UP
        }
        metrics.measuredDecimalOrUnavailable(
            TelemetryMetricCatalog.PROCESSING_SPEED,
            playerFacts?.speed?.times(100.0),
            TelemetryUnit.PERCENT,
            MEDIA3_PLAYER,
            playerInstant,
            playerReason,
        )
        metrics.measuredDecimalOrUnavailable(
            TelemetryMetricCatalog.PROCESSING_PITCH,
            playerFacts?.pitch?.times(100.0),
            TelemetryUnit.PERCENT,
            MEDIA3_PLAYER,
            playerInstant,
            playerReason,
        )
        metrics.measuredDecimalOrUnavailable(
            TelemetryMetricCatalog.PROCESSING_PLAYER_VOLUME,
            playerFacts?.volume?.times(100.0),
            TelemetryUnit.PERCENT,
            MEDIA3_PLAYER,
            playerInstant,
            playerReason,
        )
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.PROCESSING_SKIP_SILENCE,
            playerFacts?.skipSilence,
            MEDIA3_PLAYER,
            playerInstant,
            playerReason,
        )
        metrics.measuredFlag(
            TelemetryMetricCatalog.PROCESSING_REPLAY_GAIN_ACTIVE,
            false,
            APP_CONFIGURATION,
            raw.instant,
        )
        metrics.measuredFlag(
            TelemetryMetricCatalog.PROCESSING_EQUALIZER_ACTIVE,
            false,
            APP_CONFIGURATION,
            raw.instant,
        )
        metrics.measuredFlag(
            TelemetryMetricCatalog.PROCESSING_CROSSFADE_ACTIVE,
            false,
            APP_CONFIGURATION,
            raw.instant,
        )
        metrics.measuredFlag(
            TelemetryMetricCatalog.PROCESSING_LOUDNESS_ACTIVE,
            false,
            APP_CONFIGURATION,
            raw.instant,
        )
        metrics.measuredFlag(
            TelemetryMetricCatalog.PROCESSING_APP_DSP_ACTIVE,
            false,
            APP_CONFIGURATION,
            raw.instant,
        )
        val decoderSampleRate = activeInputFormat?.sampleRate?.takeIf { it != Format.NO_VALUE && it > 0 }
        val audioTrackSampleRate = activeSink?.sampleRate?.takeIf { it > 0 }
        if (decoderSampleRate != null && audioTrackSampleRate != null) {
            metrics.putMetric(
                TelemetryMetricCatalog.PROCESSING_SAMPLE_RATE_CONVERSION,
                TelemetryEvidence.Estimated(
                    reading = TelemetryReading.Flag(decoderSampleRate != audioTrackSampleRate),
                    source = MEDIA3_AUDIO_TRACK,
                    observedAtEpochMs = raw.instant.epochMs,
                    observedAtElapsedRealtimeMs = raw.instant.elapsedRealtimeMs,
                    methodId = "processing.compare_decoder_input_and_audio_track_rates",
                    inputMetricIds = setOf(
                        TelemetryMetricCatalog.DECODER_INPUT_SAMPLE_RATE,
                        TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE,
                    ),
                ),
            )
        } else {
            metrics.unavailable(
                TelemetryMetricCatalog.PROCESSING_SAMPLE_RATE_CONVERSION,
                activeReason,
                raw.instant,
                MEDIA3_AUDIO_TRACK,
            )
        }
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_STATE,
            playerFacts?.state,
            MEDIA3_PLAYER,
            playerInstant,
            playerReason,
        )
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_IS_PLAYING,
            playerFacts?.isPlaying,
            MEDIA3_PLAYER,
            playerInstant,
            playerReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_LAST_EVENT_POSITION,
            playerFacts?.positionMs,
            TelemetryUnit.MILLISECONDS,
            MEDIA3_PLAYER,
            playerInstant,
            playerReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_DURATION,
            playerFacts?.durationMs,
            TelemetryUnit.MILLISECONDS,
            MEDIA3_PLAYER,
            playerInstant,
            playerReason,
        )
        val extrapolatedPositionMs = playerFacts?.positionMs?.let { lastPositionMs ->
            val facts = checkNotNull(playerFacts)
            val elapsedSinceEventMs = (raw.instant.elapsedRealtimeMs - playerInstant.elapsedRealtimeMs)
                .coerceAtLeast(0)
            (lastPositionMs + if (facts.isPlaying) elapsedSinceEventMs * facts.speed else 0.0)
                .coerceAtLeast(0.0)
                .let { value -> facts.durationMs?.let { duration -> value.coerceAtMost(duration.toDouble()) } ?: value }
                .toLong()
        }
        if (extrapolatedPositionMs != null) {
            metrics[TelemetryMetricCatalog.PLAYBACK_POSITION] = TelemetryMetric(
                id = TelemetryMetricCatalog.PLAYBACK_POSITION,
                section = TelemetrySection.PLAYBACK,
                evidence = TelemetryEvidence.Estimated(
                    reading = TelemetryReading.Integer(extrapolatedPositionMs, TelemetryUnit.MILLISECONDS),
                    source = MEDIA3_PLAYER,
                    observedAtEpochMs = raw.instant.epochMs,
                    observedAtElapsedRealtimeMs = raw.instant.elapsedRealtimeMs,
                    methodId = "media3.position_from_last_event",
                    inputMetricIds = setOf(
                        TelemetryMetricCatalog.PLAYBACK_LAST_EVENT_POSITION,
                        TelemetryMetricCatalog.PLAYBACK_IS_PLAYING,
                        TelemetryMetricCatalog.PROCESSING_SPEED,
                        TelemetryMetricCatalog.PLAYBACK_DURATION,
                    ),
                ),
            )
        } else {
            metrics.unavailable(TelemetryMetricCatalog.PLAYBACK_POSITION, playerReason, raw.instant, MEDIA3_PLAYER)
        }
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_ESTIMATED_TOTAL_BUFFERED_DURATION,
            playerFacts?.bufferedDurationMs,
            TelemetryUnit.MILLISECONDS,
            MEDIA3_PLAYER,
            playerInstant,
            playerReason,
        )
        metrics.measuredInteger(
            TelemetryMetricCatalog.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED,
            media.dataSourceBytesTransferredTotal,
            TelemetryUnit.BYTES,
            MEDIA3_DATA_SOURCE,
            media.capturedAt,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_CURRENT_MEDIA_BYTES_READ,
            media.currentMediaRead.bytesRead,
            TelemetryUnit.BYTES,
            MEDIA3_CURRENT_MEDIA_DATA_SOURCE,
            media.capturedAt,
            media.currentMediaRead.unavailableReason
                ?: TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
        )
        rateTracker.add(
            instant = media.capturedAt,
            dataSourceBytesTransferredTotal = media.dataSourceBytesTransferredTotal,
            processCpuTimeMs = raw.system?.processCpuTimeMs,
            processCpuInstant = raw.system?.capturedAt,
            currentMediaSessionId = media.currentMediaRead.playbackSessionId,
            currentMediaBytesRead = media.currentMediaRead.bytesRead,
            decoderSessionId = media.sessionId,
            decoderGeneration = activeDecoderGeneration,
            decoderInstant = activeDecoderCounters?.observedAt,
            decoderQueuedInputBuffersTotal = activeDecoderCounters?.queuedInputBuffers,
            decoderRenderedOutputBuffersTotal = activeDecoderCounters?.renderedOutputBuffers,
        )
        rateTracker.dataSourceReadThroughput()?.let { rate ->
            metrics[TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT] = TelemetryMetric(
                id = TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT,
                section = TelemetrySection.PROCESS,
                evidence = TelemetryEvidence.Derived(
                    reading = TelemetryReading.Decimal(rate.value, TelemetryUnit.BITS_PER_SECOND),
                    source = MEDIA3_DATA_SOURCE,
                    observedAtEpochMs = raw.instant.epochMs,
                    observedAtElapsedRealtimeMs = raw.instant.elapsedRealtimeMs,
                    window = rate.window,
                    calculationId = "rate.data_source_bytes_per_window",
                    inputMetricIds = setOf(TelemetryMetricCatalog.PROCESS_DATA_SOURCE_BYTES_TRANSFERRED),
                    operands = mapOf(
                        "bytes.delta" to rate.delta.toDouble(),
                        "window.seconds" to rate.window.durationMs / 1_000.0,
                    ),
                ),
            )
        } ?: metrics.unavailable(
            TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT,
            TelemetryUnavailableReason.WARMING_UP,
            raw.instant,
            MEDIA3_DATA_SOURCE,
        )
        metrics.putRateOrUnavailable(
            id = TelemetryMetricCatalog.PLAYBACK_CURRENT_MEDIA_READ_BITRATE,
            rate = rateTracker.currentMediaReadBitrate(),
            unit = TelemetryUnit.BITS_PER_SECOND,
            source = MEDIA3_CURRENT_MEDIA_DATA_SOURCE,
            instant = raw.instant,
            calculationId = "rate.current_media_bytes_per_window",
            inputMetricId = TelemetryMetricCatalog.PLAYBACK_CURRENT_MEDIA_BYTES_READ,
            unavailableReason = media.currentMediaRead.unavailableReason
                ?: TelemetryUnavailableReason.WARMING_UP,
        )
        metrics.putRateOrUnavailable(
            id = TelemetryMetricCatalog.DECODER_INPUT_BUFFER_RATE,
            rate = rateTracker.decoderInputBufferRate(),
            unit = TelemetryUnit.COUNT_PER_SECOND,
            source = MEDIA3_COUNTERS,
            instant = raw.instant,
            calculationId = "rate.decoder_input_buffers_per_window",
            inputMetricId = TelemetryMetricCatalog.DECODER_RENDERER_QUEUED_INPUT_BUFFERS_TOTAL,
            unavailableReason = if (media.sessionId == null) {
                TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
            } else {
                TelemetryUnavailableReason.WARMING_UP
            },
        )
        metrics.putRateOrUnavailable(
            id = TelemetryMetricCatalog.DECODER_OUTPUT_BUFFER_RATE,
            rate = rateTracker.decoderOutputBufferRate(),
            unit = TelemetryUnit.COUNT_PER_SECOND,
            source = MEDIA3_COUNTERS,
            instant = raw.instant,
            calculationId = "rate.decoder_output_buffers_per_window",
            inputMetricId = TelemetryMetricCatalog.DECODER_RENDERER_RENDERED_OUTPUT_BUFFERS_TOTAL,
            unavailableReason = if (media.sessionId == null) {
                TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
            } else {
                TelemetryUnavailableReason.WARMING_UP
            },
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT,
            media.underrunCount.takeIf { media.sessionId != null },
            TelemetryUnit.COUNT,
            MEDIA3_ANALYTICS,
            raw.instant,
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_LAST_UNDERRUN_FEED_GAP,
            media.lastUnderrunFeedGapMs,
            TelemetryUnit.MILLISECONDS,
            MEDIA3_ANALYTICS,
            media.underrunObservedAt ?: raw.instant,
            if (media.sessionId == null) TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK else TelemetryUnavailableReason.NOT_APPLICABLE,
        )
        val sink = activeSink
        val sinkInstant = media.audioTrackObservedAt.takeIf { hasActivePlayback } ?: raw.instant
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE,
            sink?.sampleRate?.takeIf { it > 0 }?.toLong(),
            TelemetryUnit.HERTZ,
            MEDIA3_AUDIO_TRACK,
            sinkInstant,
            activeReason,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_ENCODING,
            sink?.encoding?.takeIf { it != C.ENCODING_INVALID }?.let(::audioEncodingName),
            MEDIA3_AUDIO_TRACK,
            sinkInstant,
            activeReason,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_CHANNEL_MASK,
            sink?.channelConfig?.takeIf { it != 0 }?.let { "0x${it.toUInt().toString(16)}" },
            MEDIA3_AUDIO_TRACK,
            sinkInstant,
            activeReason,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_BUFFER_SIZE,
            sink?.bufferSize?.takeIf { it > 0 }?.toLong(),
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
        val pcmBitDepth = sink?.encoding?.let(::pcmBitDepth)
        val pcmChannelCount = sink?.channelConfig
            ?.takeIf { it != 0 }
            ?.let(Integer::bitCount)
            ?.takeIf { it > 0 }
        val pcmDataRate = sink?.sampleRate
            ?.takeIf { it > 0 }
            ?.let { sampleRate ->
                pcmBitDepth?.let { bitDepth ->
                    pcmChannelCount?.let { channelCount ->
                        sampleRate.toDouble() * bitDepth * channelCount
                    }
                }
            }
        if (pcmDataRate != null) {
            metrics.putMetric(
                TelemetryMetricCatalog.PLAYBACK_PCM_DATA_RATE,
                TelemetryEvidence.Estimated(
                    reading = TelemetryReading.Decimal(pcmDataRate, TelemetryUnit.BITS_PER_SECOND),
                    source = MEDIA3_AUDIO_TRACK,
                    observedAtEpochMs = sinkInstant.epochMs,
                    observedAtElapsedRealtimeMs = sinkInstant.elapsedRealtimeMs,
                    methodId = "audio_track.request_format_pcm_data_rate",
                    inputMetricIds = setOf(
                        TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE,
                        TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_ENCODING,
                        TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_CHANNEL_MASK,
                    ),
                ),
            )
        } else {
            val unavailableReason = pcmDataRateUnavailableReason(
                hasActivePlayback = media.sessionId != null,
                hasAudioTrack = sink != null,
                isPcmEncoding = pcmBitDepth != null,
            )
            metrics.unavailable(
                TelemetryMetricCatalog.PLAYBACK_PCM_DATA_RATE,
                unavailableReason,
                raw.instant,
                MEDIA3_AUDIO_TRACK,
                "Only a theoretical rate for a public PCM AudioTrack request can be calculated",
            )
        }
        val playbackPlatformReason = if (media.sessionId == null) {
            TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
        } else {
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM
        }
        listOf(
            TelemetryMetricCatalog.PLAYBACK_TARGET_BUFFER_DURATION,
            TelemetryMetricCatalog.PLAYBACK_PREBUFFER_TARGET_DURATION,
            TelemetryMetricCatalog.PLAYBACK_LAST_SEEK_LATENCY,
            TelemetryMetricCatalog.PLAYBACK_LAST_GAPLESS_TRANSITION_GAP,
            TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_TIMESTAMP,
            TelemetryMetricCatalog.PLAYBACK_CLOCK_DEVIATION,
        ).forEach { id ->
            metrics.unavailable(
                id,
                playbackPlatformReason,
                raw.instant,
                MEDIA3_ANALYTICS,
                "The public Media3 callback surface does not expose this value reliably",
            )
        }
        val lastErrorEvent = media.sessionId?.let { activeSessionId ->
            media.recentEvents.lastOrNull { event ->
                event.kind == TelemetryEventKind.ERROR && event.playbackSessionId == activeSessionId
            }
        }
        if (lastErrorEvent != null) {
            metrics.measuredText(
                TelemetryMetricCatalog.PLAYBACK_LAST_ERROR_CODE,
                lastErrorEvent.code,
                MEDIA3_ANALYTICS,
                TelemetryInstant(
                    epochMs = lastErrorEvent.occurredAtEpochMs,
                    elapsedRealtimeMs = lastErrorEvent.occurredAtElapsedRealtimeMs,
                ),
            )
        } else {
            metrics.unavailable(
                TelemetryMetricCatalog.PLAYBACK_LAST_ERROR_CODE,
                if (media.sessionId == null) {
                    TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
                } else {
                    TelemetryUnavailableReason.NOT_APPLICABLE
                },
                raw.instant,
                MEDIA3_ANALYTICS,
            )
        }

        val usbOutputStatus = usbOutputStateRepository.snapshot()
        val usbOutputInstant = if (usbOutputStatus.generation > 0) {
            TelemetryInstant(
                epochMs = usbOutputStatus.observedAtEpochMs,
                elapsedRealtimeMs = usbOutputStatus.observedAtElapsedRealtimeMs,
            )
        } else {
            raw.instant
        }
        val usbOutputSource = if (usbOutputStatus.phase == UsbOutputPhase.SYSTEM) {
            APP_CONFIGURATION
        } else {
            VESQEN_OUTPUT_COORDINATOR
        }
        metrics.measuredText(
            TelemetryMetricCatalog.ROUTE_OUTPUT_DECLARATION,
            usbOutputStatus.declaration.name.replace('_', ' '),
            usbOutputSource,
            usbOutputInstant,
        )
        metrics.measuredText(
            TelemetryMetricCatalog.ROUTE_LAST_STRATEGY_DECISION,
            usbOutputStatus.decisionCode,
            usbOutputSource,
            usbOutputInstant,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.ROUTE_AUDIO_TRACK_REQUEST_FORMAT,
            sink?.let(::audioTrackRequestFormat),
            MEDIA3_AUDIO_TRACK,
            sinkInstant,
            activeReason,
        )

        addSystemMetrics(metrics, raw, rateTracker)
        addOutputMetrics(metrics, raw)

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
        if (system == null) {
            val reason = if (raw.systemRequested) {
                TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
            } else {
                TelemetryUnavailableReason.NOT_SAMPLED
            }
            TelemetryMetricCatalog.systemProbeIds.forEach { id -> metrics.unavailable(id, reason, raw.instant) }
            return
        }
        metrics.measuredInteger(
            TelemetryMetricCatalog.PROCESS_CPU_TIME,
            system.processCpuTimeMs,
            TelemetryUnit.MILLISECONDS,
            ANDROID_PROCESS,
            system.capturedAt,
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
            system.capturedAt,
            ANDROID_PROCESS,
        )
        metrics.measuredInteger(
            TelemetryMetricCatalog.PROCESS_JAVA_HEAP,
            system.javaHeapBytes,
            TelemetryUnit.BYTES,
            ANDROID_PROCESS,
            system.capturedAt,
        )
        metrics.measuredInteger(
            TelemetryMetricCatalog.PROCESS_NATIVE_HEAP,
            system.nativeHeapBytes,
            TelemetryUnit.BYTES,
            ANDROID_PROCESS,
            system.capturedAt,
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
            system.capturedAt,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PROCESS_GC_COUNT,
            system.gcCount,
            TelemetryUnit.COUNT,
            ANDROID_RUNTIME,
            system.expensiveObservedAt,
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.PROCESS_GC_TIME,
            system.gcTimeMs,
            TelemetryUnit.MILLISECONDS,
            ANDROID_RUNTIME,
            system.expensiveObservedAt,
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
        )
        metrics.unavailable(
            TelemetryMetricCatalog.PROCESS_PLAYBACK_THREAD_CPU_TIME,
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
            system.capturedAt,
            ANDROID_PROCESS,
            "Android exposes process CPU time, not Media3 playback-thread CPU time",
        )
        metrics.unavailable(
            TelemetryMetricCatalog.PROCESS_CPU_CORE_STATE,
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
            system.capturedAt,
            ANDROID_PROCESS,
            "Per-core frequency and residency are not a stable public application API",
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
            socModelUnavailableReason(Build.VERSION.SDK_INT),
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

    private fun addOutputMetrics(
        metrics: MutableMap<TelemetryMetricId, TelemetryMetric>,
        raw: RawTelemetrySample,
    ) {
        val output = raw.output
        if (output == null) {
            val reason = if (raw.outputRequested) {
                TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
            } else {
                TelemetryUnavailableReason.NOT_SAMPLED
            }
            listOf(
            TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME,
            TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_TYPE,
            TelemetryMetricCatalog.ROUTE_ANTICIPATED_NAME,
            TelemetryMetricCatalog.ROUTE_ANTICIPATED_TYPE,
            TelemetryMetricCatalog.ROUTE_CONNECTED_TYPES,
            TelemetryMetricCatalog.ROUTE_REQUEST_FORMAT_DIRECT_SUPPORTED,
            TelemetryMetricCatalog.ROUTE_REQUEST_FORMAT_DIRECT_MODES,
            TelemetryMetricCatalog.ROUTE_ANTICIPATED_MIXER_PROFILE_COUNT,
            TelemetryMetricCatalog.ROUTE_ANTICIPATED_PREFERRED_MIXER_PROFILE,
            TelemetryMetricCatalog.ROUTE_OBSERVED_OUTPUT_FORMAT,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CODEC,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONNECTED_NAMES,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONNECTED_TYPES,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CAPABILITIES,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONFIGURATION,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONFIGURED_BITRATE,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_TRANSPORT_BITRATE,
            TelemetryMetricCatalog.ROUTE_SYSTEM_MUSIC_VOLUME,
            TelemetryMetricCatalog.ROUTE_SYSTEM_MUSIC_MUTED,
            TelemetryMetricCatalog.ROUTE_SYSTEM_DSP_STATE,
            TelemetryMetricCatalog.USB_HOST_SUPPORTED,
            TelemetryMetricCatalog.USB_AUDIO_DEVICE_COUNT,
            TelemetryMetricCatalog.USB_DEVICE_INVENTORY,
            ).forEach { id -> metrics.unavailable(id, reason, raw.instant) }
            return
        }
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME,
            output.selectedSystemRouteName,
            ANDROID_SYSTEM_ROUTE,
            output.observedAt,
            TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_TYPE,
            output.selectedSystemRouteType,
            ANDROID_SYSTEM_ROUTE,
            output.observedAt,
            output.selectedSystemRouteTypeUnavailableReason ?: TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
        )
        val routeReason = output.routeUnavailableReason ?: TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.ROUTE_ANTICIPATED_NAME,
            output.anticipatedRouteName,
            ANDROID_AUDIO_ROUTE,
            output.observedAt,
            routeReason,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.ROUTE_ANTICIPATED_TYPE,
            output.anticipatedRouteType,
            ANDROID_AUDIO_ROUTE,
            output.observedAt,
            routeReason,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.ROUTE_CONNECTED_TYPES,
            output.connectedRouteTypes,
            ANDROID_AUDIO_ROUTE,
            output.observedAt,
            output.connectedRouteTypesUnavailableReason
                ?: TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
        )
        val directReason = output.directUnavailableReason ?: TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.ROUTE_REQUEST_FORMAT_DIRECT_SUPPORTED,
            output.directSupported,
            ANDROID_DIRECT_SUPPORT,
            output.observedAt,
            directReason,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.ROUTE_REQUEST_FORMAT_DIRECT_MODES,
            output.directModes,
            ANDROID_DIRECT_SUPPORT,
            output.observedAt,
            directReason,
        )
        val mixerReason = output.mixerUnavailableReason ?: TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.ROUTE_ANTICIPATED_MIXER_PROFILE_COUNT,
            output.mixerProfileCount,
            TelemetryUnit.COUNT,
            ANDROID_MIXER,
            output.observedAt,
            mixerReason,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.ROUTE_ANTICIPATED_PREFERRED_MIXER_PROFILE,
            output.preferredMixerProfile,
            ANDROID_MIXER,
            output.observedAt,
            mixerReason,
        )
        metrics.unavailable(
            TelemetryMetricCatalog.ROUTE_OBSERVED_OUTPUT_FORMAT,
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
            output.observedAt,
            ANDROID_AUDIO_ROUTE,
            "The final post-mixer hardware format is not exposed to ordinary applications",
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONNECTED_NAMES,
            output.bluetoothConnectedNames,
            ANDROID_AUDIO_ROUTE,
            output.observedAt,
            output.bluetoothUnavailableReason ?: TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT,
        )
        metrics.measuredTextOrUnavailable(
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONNECTED_TYPES,
            output.bluetoothConnectedTypes,
            ANDROID_AUDIO_ROUTE,
            output.observedAt,
            output.bluetoothUnavailableReason ?: TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
        )
        val bluetoothReason = bluetoothTransportUnavailableReason(
            output.selectedSystemRouteType, output.anticipatedRouteType,
        )
        // Public AudioManager endpoints describe PCM-facing capabilities, not negotiated radio
        // codecs or over-the-air counters. Never relabel them as Bluetooth transport evidence.
        listOf(
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CODEC,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONFIGURATION,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CONFIGURED_BITRATE,
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_TRANSPORT_BITRATE,
        ).forEach { id ->
            metrics.unavailable(id, bluetoothReason, output.observedAt, ANDROID_AUDIO_ROUTE)
        }
        metrics.unavailable(
            TelemetryMetricCatalog.ROUTE_BLUETOOTH_CAPABILITIES,
            when (output.bluetoothConnectedTypes) {
                null -> output.bluetoothUnavailableReason ?: TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
                "none" -> TelemetryUnavailableReason.NOT_APPLICABLE
                else -> TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM
            },
            output.observedAt,
            ANDROID_AUDIO_ROUTE,
        )
        metrics.measuredDecimalOrUnavailable(
            TelemetryMetricCatalog.ROUTE_SYSTEM_MUSIC_VOLUME,
            output.systemMusicVolumePercent,
            TelemetryUnit.PERCENT,
            ANDROID_AUDIO_ROUTE,
            output.observedAt,
            TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
        )
        metrics.measuredFlagOrUnavailable(
            TelemetryMetricCatalog.ROUTE_SYSTEM_MUSIC_MUTED,
            output.systemMusicMuted,
            ANDROID_AUDIO_ROUTE,
            output.observedAt,
            TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
        )
        metrics.unavailable(
            TelemetryMetricCatalog.ROUTE_SYSTEM_DSP_STATE,
            TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
            output.observedAt,
            ANDROID_AUDIO_ROUTE,
            "Android does not expose the complete vendor and system DSP chain",
        )
        metrics.measuredFlag(
            TelemetryMetricCatalog.USB_HOST_SUPPORTED,
            output.usbHostSupported,
            ANDROID_USB,
            output.observedAt,
        )
        metrics.measuredIntegerOrUnavailable(
            TelemetryMetricCatalog.USB_AUDIO_DEVICE_COUNT,
            output.usbAudioDeviceCount,
            TelemetryUnit.COUNT,
            ANDROID_USB,
            output.observedAt,
            output.usbAudioDeviceCountUnavailableReason
                ?: TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
        )
        output.usbInventory?.let { inventory ->
            metrics.putMetric(
                TelemetryMetricCatalog.USB_DEVICE_INVENTORY,
                TelemetryEvidence.Measured(
                    reading = TelemetryReading.UsbInventory(inventory),
                    source = ANDROID_USB,
                    observedAtEpochMs = output.observedAt.epochMs,
                    observedAtElapsedRealtimeMs = output.observedAt.elapsedRealtimeMs,
                ),
            )
        } ?: metrics.unavailable(
            TelemetryMetricCatalog.USB_DEVICE_INVENTORY,
            output.usbInventoryUnavailableReason ?: TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE,
            output.observedAt,
            ANDROID_USB,
        )
    }

    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        synchronized(stateLock) {
            resetSessionScopedFactsLocked()
            sessionId = mediaItem?.let { UUID.randomUUID().toString() }
            val mediaUri = mediaItem?.localConfiguration?.uri?.toString()
            currentMediaTransfers.onMediaSessionChanged(
                playbackSessionId = sessionId,
                mediaUri = mediaUri,
                mediaUriIsUnique = isUniquePlaylistUriLocked(mediaUri),
            )
            bindDeferredCurrentPeriodLocked(eventTime)
            sourceFacts = mediaItem?.mediaMetadata?.extras.let(TelemetryMediaItemExtras::read)
                .takeIf { mediaItem != null }
            sourceObservedAt = instant.takeIf { mediaItem != null }
            recordEventLocked(
                kind = TelemetryEventKind.MEDIA_ITEM_CHANGED,
                severity = TelemetryEventSeverity.INFO,
                instant = instant,
                code = "playback.media_item_changed",
            )
        }
    }

    override fun onTimelineChanged(
        eventTime: AnalyticsListener.EventTime,
        reason: Int,
    ) {
        synchronized(stateLock) {
            bindDeferredCurrentPeriodLocked(eventTime)
            currentMediaTransfers.onTimelineChanged(::isUniquePlaylistUriLocked)
        }
    }

    override fun onEvents(player: Player, events: AnalyticsListener.Events) {
        val instant = clock.now()
        synchronized(stateLock) {
            playerFacts = PlayerTelemetryFacts(
                state = player.playbackState.toTelemetryState(),
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.takeIf { it >= 0 && it != C.TIME_UNSET },
                bufferedDurationMs = player.totalBufferedDuration.takeIf { it >= 0 && it != C.TIME_UNSET },
                durationMs = player.duration.takeIf { it >= 0 && it != C.TIME_UNSET },
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
        val mediaPeriodId = eventTime.mediaPeriodId ?: eventTime.currentMediaPeriodId
        val observed = ObservedDecoder(
            generation = decoderGenerationSequence.incrementAndGet(),
            name = decoderName,
            initializationDurationMs = initializationDurationMs
                .takeIf { it >= 0 && it != C.TIME_UNSET },
            instant = instant,
        )
        synchronized(stateLock) {
            bindDeferredCurrentPeriodLocked(eventTime)
            val belongsToActivePeriod = mediaPeriodId == null || belongsToActivePeriodLocked(mediaPeriodId)
            decoderGenerations.onDecoderInitialized(
                periodId = mediaPeriodId,
                generation = observed.generation,
                decoderName = observed.name,
                belongsToActivePeriod = belongsToActivePeriod,
            )
            if (belongsToActivePeriod) {
                applyDecoderFactsLocked(observed)
            } else {
                pendingDecoders[mediaPeriodId] = observed
                trimPeriodMapsLocked()
            }
            recordEventLocked(
                kind = TelemetryEventKind.DECODER_INITIALIZED,
                severity = TelemetryEventSeverity.INFO,
                instant = instant,
                code = "decoder.initialized",
                playbackSessionId = sessionForPeriodLocked(mediaPeriodId),
                relatedMetricIds = setOf(TelemetryMetricCatalog.DECODER_NAME),
            )
        }
        scope.launch {
            val codecFlags = codecFlagResolver.resolve(decoderName)
            val flagsObservedAt = clock.now()
            synchronized(stateLock) {
                if (decoderGenerations.activeGeneration == observed.generation) {
                    decoderSoftwareOnly = codecFlags?.softwareOnly
                    decoderHardwareAccelerated = codecFlags?.hardwareAccelerated
                    decoderVendor = codecFlags?.vendor
                    decoderFlagsObservedAt = flagsObservedAt
                }
                mediaPeriodId?.let { periodId ->
                    pendingDecoders[periodId]?.takeIf { it.generation == observed.generation }?.let { pending ->
                        pendingDecoders[periodId] = pending.copy(
                            flags = codecFlags,
                            flagsObservedAt = flagsObservedAt,
                        )
                    }
                }
            }
        }
    }

    override fun onAudioDecoderReleased(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
    ) {
        val mediaPeriodId = eventTime.mediaPeriodId ?: eventTime.currentMediaPeriodId
        synchronized(stateLock) {
            val release = decoderGenerations.onDecoderReleased(
                periodId = mediaPeriodId,
                decoderName = decoderName,
                allowActiveFallback = belongsToActivePeriodLocked(mediaPeriodId) && this.decoderName == decoderName,
            )
            pendingDecoders.entries.removeAll { (_, pending) -> pending.generation == release.releasedGeneration }
            if (release.clearsActiveDecoder) {
                this.decoderName = null
                decoderInitializationDurationMs = null
                decoderSoftwareOnly = null
                decoderHardwareAccelerated = null
                decoderVendor = null
                decoderObservedAt = null
                decoderFlagsObservedAt = null
                inputFormat = null
                inputFormatObservedAt = null
            }
        }
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        val mediaPeriodId = eventTime.mediaPeriodId ?: eventTime.currentMediaPeriodId
        synchronized(stateLock) {
            bindDeferredCurrentPeriodLocked(eventTime)
            if (mediaPeriodId == null || belongsToActivePeriodLocked(mediaPeriodId)) {
                inputFormat = format
                inputFormatObservedAt = instant
                formatChangeCount += 1
            } else {
                pendingInputFormats[mediaPeriodId] = ObservedFormat(format, instant)
                trimPeriodMapsLocked()
            }
            recordEventLocked(
                kind = TelemetryEventKind.FORMAT_CHANGED,
                severity = TelemetryEventSeverity.INFO,
                instant = instant,
                code = "decoder.input_format_changed",
                playbackSessionId = sessionForPeriodLocked(mediaPeriodId),
                relatedMetricIds = setOf(TelemetryMetricCatalog.DECODER_INPUT_MIME),
            )
        }
    }

    override fun onAudioEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
        val mediaPeriodId = eventTime.mediaPeriodId ?: eventTime.currentMediaPeriodId
        synchronized(stateLock) {
            bindDeferredCurrentPeriodLocked(eventTime)
            if (mediaPeriodId == null || belongsToActivePeriodLocked(mediaPeriodId)) {
                this.decoderCounters = decoderCounters
                lastDecoderCounters = null
            } else {
                pendingDecoderCounters[mediaPeriodId] = decoderCounters
                trimPeriodMapsLocked()
            }
        }
    }

    override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
        decoderCounters.ensureUpdated()
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        synchronized(stateLock) {
            if (this.decoderCounters === decoderCounters) {
                lastDecoderCounters = DecoderCounterSnapshot(
                    queuedInputBuffers = decoderCounters.queuedInputBufferCount.toLong().coerceAtLeast(0),
                    renderedOutputBuffers = decoderCounters.renderedOutputBufferCount.toLong().coerceAtLeast(0),
                    skippedOutputBuffers = decoderCounters.skippedOutputBufferCount.toLong().coerceAtLeast(0),
                    observedAt = instant,
                )
                this.decoderCounters = null
            }
            pendingDecoderCounters.entries.removeAll { it.value === decoderCounters }
        }
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        val mediaPeriodId = eventTime.mediaPeriodId ?: eventTime.currentMediaPeriodId
        val observed = ObservedAudioTrack(
            identity = audioTrackConfig,
            config = audioTrackConfig.toAudioTrackFacts(),
            instant = instant,
        )
        synchronized(stateLock) {
            bindDeferredCurrentPeriodLocked(eventTime)
            if (mediaPeriodId == null || belongsToActivePeriodLocked(mediaPeriodId)) {
                this.audioTrackConfig = observed.config
                audioTrackIdentity = observed.identity
                audioTrackObservedAt = instant
            } else {
                pendingAudioTracks[mediaPeriodId] = observed
                trimPeriodMapsLocked()
            }
            recordEventLocked(
                kind = TelemetryEventKind.OUTPUT_INITIALIZED,
                severity = TelemetryEventSeverity.INFO,
                instant = instant,
                code = "output.audio_track_initialized",
                playbackSessionId = sessionForPeriodLocked(mediaPeriodId),
                relatedMetricIds = setOf(TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE),
            )
        }
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        synchronized(stateLock) {
            if (audioTrackIdentity === audioTrackConfig) {
                this.audioTrackConfig = null
                audioTrackIdentity = null
                audioTrackObservedAt = null
            }
            pendingAudioTracks.entries.removeAll { it.value.identity === audioTrackConfig }
        }
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        val instant = clock.fromElapsedRealtime(eventTime.realtimeMs)
        val mediaPeriodId = eventTime.mediaPeriodId ?: eventTime.currentMediaPeriodId
        synchronized(stateLock) {
            bindDeferredCurrentPeriodLocked(eventTime)
            if (!belongsToActivePeriodLocked(mediaPeriodId)) return
            underrunCount += 1
            lastUnderrunFeedGapMs = elapsedSinceLastFeedMs
                .takeIf { it >= 0 && it != C.TIME_UNSET }
            underrunObservedAt = instant
            recordEventLocked(
                kind = TelemetryEventKind.UNDERRUN,
                severity = TelemetryEventSeverity.WARNING,
                instant = instant,
                code = "playback.audio_underrun",
                playbackSessionId = sessionForPeriodLocked(mediaPeriodId),
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
        val mediaPeriodId = eventTime.mediaPeriodId ?: eventTime.currentMediaPeriodId
        synchronized(stateLock) {
            if (!belongsToActivePeriodLocked(mediaPeriodId)) return
            recordEventLocked(
                kind = TelemetryEventKind.SEEK_COMPLETED,
                severity = TelemetryEventSeverity.INFO,
                instant = instant,
                code = "playback.seek_completed",
                playbackSessionId = sessionForPeriodLocked(mediaPeriodId),
                relatedMetricIds = setOf(TelemetryMetricCatalog.PLAYBACK_POSITION),
            )
        }
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        recordError(eventTime, "error.player.code_${error.errorCode}")
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
            val mediaPeriodId = eventTime.mediaPeriodId ?: eventTime.currentMediaPeriodId
            recordEventLocked(
                kind = TelemetryEventKind.ERROR,
                severity = TelemetryEventSeverity.ERROR,
                instant = instant,
                code = code,
                playbackSessionId = sessionForPeriodLocked(mediaPeriodId),
            )
        }
    }

    private fun onOutputSignal(signal: OutputTelemetrySignal) {
        synchronized(stateLock) {
            val event = outputSignalEventSpec(signal, sessionId)
            recordEventLocked(
                kind = event.kind,
                severity = TelemetryEventSeverity.INFO,
                instant = clock.now(),
                code = event.code,
                playbackSessionId = event.playbackSessionId,
                relatedMetricIds = event.relatedMetricIds,
            )
        }
    }

    /** A playlist transition can precede resolution of its playing period. Bind it when Media3
     * first reports an explicit current period, never from a renderer's possibly preloaded one.
     * Reuse the same promotion path for facts that arrived before that resolution.
     */
    private fun bindDeferredCurrentPeriodLocked(eventTime: AnalyticsListener.EventTime) {
        if (activeMediaPeriodId != null || sessionId == null) return
        val periodId = eventTime.currentMediaPeriodId ?: return
        activeMediaPeriodId = periodId
        sessionByPeriod[periodId] = checkNotNull(sessionId)
        trimPeriodMapsLocked()
        pendingInputFormats.remove(periodId)?.let { observed ->
            inputFormat = observed.format
            inputFormatObservedAt = observed.instant
            formatChangeCount = 1
        }
        val promotedDecoder = pendingDecoders.remove(periodId)
        decoderGenerations.onMediaItemTransition(periodId, promotedDecoder?.generation)
        promotedDecoder?.let(::applyDecoderFactsLocked)
        pendingDecoderCounters.remove(periodId)?.let { counters ->
            decoderCounters = counters
            lastDecoderCounters = null
        }
        pendingAudioTracks.remove(periodId)?.let { observed ->
            audioTrackConfig = observed.config
            audioTrackIdentity = observed.identity
            audioTrackObservedAt = observed.instant
        }
    }

    /** Null/non-current period callbacks are deliberately ignored instead of guessed into the
     * current playback session. */
    private fun belongsToActivePeriodLocked(mediaPeriodId: MediaSource.MediaPeriodId?): Boolean =
        mediaPeriodId != null && mediaPeriodId == activeMediaPeriodId

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        synchronized(stateLock) {
            currentMediaTransfers.onTransferStart(source, dataSpec.uri.toString())
        }
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (bytesTransferred <= 0) return
        dataSourceBytesTransferredTotal.addAndGet(bytesTransferred.toLong())
        synchronized(stateLock) {
            currentMediaTransfers.onBytesTransferred(
                source = source,
                dataSpecUri = dataSpec.uri.toString(),
                bytesTransferred = bytesTransferred,
            )
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        synchronized(stateLock) {
            currentMediaTransfers.onTransferEnd(source)
        }
    }

    private fun recordEventLocked(
        kind: TelemetryEventKind,
        severity: TelemetryEventSeverity,
        instant: TelemetryInstant,
        code: String,
        playbackSessionId: String? = sessionId,
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
                playbackSessionId = playbackSessionId,
                relatedMetricIds = relatedMetricIds,
            ),
        )
        while (recentEvents.size > MAX_RECENT_EVENTS) recentEvents.removeFirst()
    }

    private fun sessionForPeriodLocked(mediaPeriodId: MediaSource.MediaPeriodId?): String? = when {
        mediaPeriodId == null -> null
        mediaPeriodId == activeMediaPeriodId -> sessionId
        else -> sessionByPeriod[mediaPeriodId]
    }

    private fun applyDecoderFactsLocked(observed: ObservedDecoder) {
        decoderName = observed.name
        decoderInitializationDurationMs = observed.initializationDurationMs
        decoderSoftwareOnly = observed.flags?.softwareOnly
        decoderHardwareAccelerated = observed.flags?.hardwareAccelerated
        decoderVendor = observed.flags?.vendor
        decoderObservedAt = observed.instant
        decoderFlagsObservedAt = observed.flagsObservedAt
    }

    private fun isUniquePlaylistUriLocked(mediaUri: String?): Boolean {
        if (mediaUri == null) return false
        val attachedPlayer = player ?: return false
        var matches = 0
        repeat(attachedPlayer.mediaItemCount) { index ->
            if (attachedPlayer.getMediaItemAt(index).localConfiguration?.uri?.toString() == mediaUri) {
                matches++
            }
        }
        return matches == 1
    }

    private fun trimPeriodMapsLocked() {
        sessionByPeriod.trimToSize(MAX_PERIOD_FACTS)
        pendingInputFormats.trimToSize(MAX_PERIOD_FACTS)
        pendingDecoders.trimToSize(MAX_PERIOD_FACTS)
        decoderGenerations.trimToSize(MAX_PERIOD_FACTS)
        pendingDecoderCounters.trimToSize(MAX_PERIOD_FACTS)
        pendingAudioTracks.trimToSize(MAX_PERIOD_FACTS)
    }

    private fun resetSessionScopedFactsLocked() {
        sessionId = null
        activeMediaPeriodId = null
        sourceFacts = null
        sourceObservedAt = null
        playerFacts = null
        playerObservedAt = null
        underrunCount = 0
        lastUnderrunFeedGapMs = null
        underrunObservedAt = null
        formatChangeCount = 0
    }

    private fun resetAllFactsLocked() {
        resetSessionScopedFactsLocked()
        decoderName = null
        decoderGenerations.clear()
        decoderInitializationDurationMs = null
        decoderSoftwareOnly = null
        decoderHardwareAccelerated = null
        decoderVendor = null
        decoderObservedAt = null
        decoderFlagsObservedAt = null
        inputFormat = null
        inputFormatObservedAt = null
        decoderCounters = null
        lastDecoderCounters = null
        audioTrackConfig = null
        audioTrackIdentity = null
        audioTrackObservedAt = null
        sessionByPeriod.clear()
        pendingInputFormats.clear()
        pendingDecoders.clear()
        pendingDecoderCounters.clear()
        pendingAudioTracks.clear()
        currentMediaTransfers.reset()
    }

    private companion object {
        const val MAX_RECENT_EVENTS = 128
        const val MAX_PERIOD_FACTS = 8
        val SOURCE_METADATA = TelemetryDataSource(TelemetrySourceId("library.metadata"))
        val MEDIA3_ANALYTICS = TelemetryDataSource(TelemetrySourceId("media3.analytics"))
        val MEDIA3_COUNTERS = TelemetryDataSource(TelemetrySourceId("media3.decoder_counters"))
        val MEDIA3_PLAYER = TelemetryDataSource(TelemetrySourceId("media3.player"))
        val MEDIA3_DATA_SOURCE = TelemetryDataSource(
            id = TelemetrySourceId("media3.data_source"),
            detail = "Application-process aggregate; includes seek, re-read, and next-item prefetch",
        )
        val MEDIA3_CURRENT_MEDIA_DATA_SOURCE = TelemetryDataSource(
            id = TelemetrySourceId("media3.current_media_data_source"),
            detail = "Exact unique MediaItem URI and DataSource lifecycle attribution",
        )
        val MEDIA3_AUDIO_TRACK = TelemetryDataSource(TelemetrySourceId("media3.audio_track"))
        val ANDROID_CODEC = TelemetryDataSource(TelemetrySourceId("android.media_codec"))
        val ANDROID_PROCESS = TelemetryDataSource(TelemetrySourceId("android.process"))
        val ANDROID_RUNTIME = TelemetryDataSource(TelemetrySourceId("android.runtime"))
        val ANDROID_POWER = TelemetryDataSource(TelemetrySourceId("android.power"))
        val ANDROID_BUILD = TelemetryDataSource(TelemetrySourceId("android.build"))
        val ANDROID_BATTERY = TelemetryDataSource(TelemetrySourceId("android.battery"))
        val ANDROID_AUDIO_ROUTE = TelemetryDataSource(TelemetrySourceId("android.audio_route"))
        val ANDROID_SYSTEM_ROUTE = TelemetryDataSource(
            id = TelemetrySourceId("android.system_media_route"),
            detail = "System-selected media route; not proof of the final hardware signal path",
        )
        val ANDROID_DIRECT_SUPPORT = TelemetryDataSource(
            id = TelemetrySourceId("android.direct_playback_support"),
            detail = "Capability query for the current AudioTrack request format; not proof of active direct playback",
        )
        val ANDROID_MIXER = TelemetryDataSource(
            id = TelemetrySourceId("android.mixer_attributes"),
            detail = "Read-only mixer capability/preference query; not proof that a profile is active",
        )
        val ANDROID_USB = TelemetryDataSource(TelemetrySourceId("android.usb_public_api"))
        val APP_CONFIGURATION = TelemetryDataSource(TelemetrySourceId("vesqen.configuration"))
        val VESQEN_OUTPUT_COORDINATOR = TelemetryDataSource(
            id = TelemetrySourceId("vesqen.output_coordinator"),
            detail = "Service decision combining Media3 output format, Android mixer readback, and AudioTrack route; not external signal verification",
        )
    }
}

internal fun TelemetryObservation.effectiveIntervalMs(): Long = when (powerMode) {
    TelemetryPowerMode.STANDARD -> refreshInterval.milliseconds
    TelemetryPowerMode.LOW_POWER -> maxOf(refreshInterval.milliseconds, 2_000L)
}

internal data class TelemetrySamplingState(
    val activeObservationCount: Int,
    val activeIntervalMs: Long?,
)

private data class TelemetrySamplingDemand(
    val includeSystem: Boolean,
    val includeOutput: Boolean,
) {
    companion object {
        val NONE = TelemetrySamplingDemand(includeSystem = false, includeOutput = false)

        fun from(observations: Collection<TelemetryObservation>): TelemetrySamplingDemand {
            val selectedIds = observations.flatMapTo(mutableSetOf()) { observation ->
                when (val selection = observation.selection) {
                    TelemetryMetricSelection.Default -> TelemetryMetricCatalog.defaultIds
                    is TelemetryMetricSelection.Explicit -> selection.metricIds
                }
            }
            return TelemetrySamplingDemand(
                includeSystem = selectedIds.any(TelemetryMetricCatalog.systemProbeIds::contains),
                includeOutput = selectedIds.any(TelemetryMetricCatalog.outputProbeIds::contains),
            )
        }
    }
}

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
    val system: SystemTelemetrySnapshot?,
    val systemRequested: Boolean,
    val output: OutputTelemetrySnapshot?,
    val outputRequested: Boolean,
)

private data class MediaTelemetrySnapshot(
    val capturedAt: TelemetryInstant,
    val sessionId: String?,
    val sourceFacts: TelemetrySourceFacts?,
    val sourceObservedAt: TelemetryInstant?,
    val decoderName: String?,
    val decoderGeneration: Long?,
    val decoderInitializationDurationMs: Long?,
    val decoderSoftwareOnly: Boolean?,
    val decoderHardwareAccelerated: Boolean?,
    val decoderVendor: Boolean?,
    val decoderObservedAt: TelemetryInstant?,
    val decoderFlagsObservedAt: TelemetryInstant?,
    val inputFormat: Format?,
    val inputFormatObservedAt: TelemetryInstant?,
    val decoderCounters: DecoderCounterSnapshot?,
    val audioTrackConfig: AudioTrackFacts?,
    val audioTrackObservedAt: TelemetryInstant?,
    val playerFacts: PlayerTelemetryFacts?,
    val playerObservedAt: TelemetryInstant?,
    val dataSourceBytesTransferredTotal: Long,
    val currentMediaRead: CurrentMediaReadSnapshot,
    val underrunCount: Long,
    val lastUnderrunFeedGapMs: Long?,
    val underrunObservedAt: TelemetryInstant?,
    val formatChangeCount: Long,
    val recentEvents: List<TelemetryEvent>,
)

private data class PlayerTelemetryFacts(
    val state: String = "idle",
    val isPlaying: Boolean = false,
    val positionMs: Long?,
    val bufferedDurationMs: Long?,
    val durationMs: Long?,
    val speed: Double = 1.0,
    val pitch: Double = 1.0,
    val volume: Double = 1.0,
    val skipSilence: Boolean = false,
)

private data class DecoderCounterSnapshot(
    val queuedInputBuffers: Long,
    val renderedOutputBuffers: Long,
    val skippedOutputBuffers: Long,
    val observedAt: TelemetryInstant,
)

private data class DecoderFlags(
    val softwareOnly: Boolean,
    val hardwareAccelerated: Boolean,
    val vendor: Boolean,
)

private data class ObservedFormat(
    val format: Format,
    val instant: TelemetryInstant,
)

private data class ObservedDecoder(
    val generation: Long,
    val name: String,
    val initializationDurationMs: Long?,
    val instant: TelemetryInstant,
    val flags: DecoderFlags? = null,
    val flagsObservedAt: TelemetryInstant? = null,
)

internal data class DecoderGenerationRelease(
    val releasedGeneration: Long?,
    val clearsActiveDecoder: Boolean,
)

/**
 * Keeps renderer decoder identity consistent across Media3's preload, transition, and release
 * callbacks. A pending decoder may be initialized before its media item becomes current, while a
 * reused active decoder may acquire another period only at the transition. Release callbacks are
 * associated with Media3's current reading period rather than a stable decoder instance, so the
 * per-name initialization queue disambiguates an old decoder released after a gapless promotion.
 */
internal class DecoderGenerationRegistry<PeriodId> {
    private val generationByPeriod = linkedMapOf<PeriodId, Long>()
    private val decoderNameByGeneration = linkedMapOf<Long, String>()
    private val generationsByDecoderName = linkedMapOf<String, ArrayDeque<Long>>()

    var activeGeneration: Long? = null
        private set

    fun onDecoderInitialized(
        periodId: PeriodId?,
        generation: Long,
        decoderName: String,
        belongsToActivePeriod: Boolean,
    ) {
        require(generation > 0) { "Decoder generations must be positive" }
        require(decoderName.isNotBlank()) { "Decoder names cannot be blank" }
        require(generation !in decoderNameByGeneration) { "Decoder generations must be unique" }
        decoderNameByGeneration[generation] = decoderName
        generationsByDecoderName.getOrPut(decoderName, ::ArrayDeque).addLast(generation)
        periodId?.let { generationByPeriod[it] = generation }
        if (belongsToActivePeriod) activeGeneration = generation
    }

    fun onMediaItemTransition(periodId: PeriodId, promotedGeneration: Long?) {
        promotedGeneration?.let { generation ->
            require(generation > 0) { "Decoder generations must be positive" }
            activeGeneration = generation
        }
        activeGeneration?.let { generation -> generationByPeriod[periodId] = generation }
    }

    fun onDecoderReleased(
        periodId: PeriodId?,
        decoderName: String,
        allowActiveFallback: Boolean,
    ): DecoderGenerationRelease {
        require(decoderName.isNotBlank()) { "Decoder names cannot be blank" }
        val periodGeneration = periodId?.let(generationByPeriod::get)
        val nameGenerations = generationsByDecoderName[decoderName]
        val releasedGeneration = when {
            nameGenerations.isNullOrEmpty() -> periodGeneration ?: activeGeneration.takeIf { allowActiveFallback }
            nameGenerations.size == 1 -> nameGenerations.first()
            periodGeneration != null &&
                periodGeneration != activeGeneration &&
                nameGenerations.contains(periodGeneration) -> periodGeneration
            else -> nameGenerations.first()
        }
        val clearsActive = releasedGeneration != null && releasedGeneration == activeGeneration
        if (releasedGeneration != null) {
            generationByPeriod.entries.removeAll { (_, generation) ->
                generation == releasedGeneration
            }
            removeDecoderTracking(releasedGeneration)
        } else {
            periodId?.let(generationByPeriod::remove)
        }
        if (clearsActive) activeGeneration = null
        return DecoderGenerationRelease(releasedGeneration, clearsActive)
    }

    internal fun generationFor(periodId: PeriodId): Long? = generationByPeriod[periodId]

    fun trimToSize(maximumSize: Int) {
        require(maximumSize > 0) { "Decoder period retention must be positive" }
        while (generationByPeriod.size > maximumSize) {
            generationByPeriod.remove(generationByPeriod.keys.first())
        }
        val maximumTrackedGenerations = maximumSize * 2
        while (decoderNameByGeneration.size > maximumTrackedGenerations) {
            val mappedGenerations = generationByPeriod.values.toSet()
            val generation = decoderNameByGeneration.keys.firstOrNull { candidate ->
                candidate != activeGeneration && candidate !in mappedGenerations
            } ?: decoderNameByGeneration.keys.firstOrNull { it != activeGeneration }
                ?: break
            removeDecoderTracking(generation)
        }
    }

    fun clear() {
        activeGeneration = null
        generationByPeriod.clear()
        decoderNameByGeneration.clear()
        generationsByDecoderName.clear()
    }

    private fun removeDecoderTracking(generation: Long) {
        val name = decoderNameByGeneration.remove(generation) ?: return
        generationsByDecoderName[name]?.let { generations ->
            generations.remove(generation)
            if (generations.isEmpty()) generationsByDecoderName.remove(name)
        }
    }
}

private data class ObservedAudioTrack(
    val identity: Any,
    val config: AudioTrackFacts,
    val instant: TelemetryInstant,
)

private data class AudioTrackFacts(
    val sampleRate: Int,
    val encoding: Int,
    val channelConfig: Int,
    val bufferSize: Int,
    val offload: Boolean,
    val tunneling: Boolean,
)

private class CodecFlagResolver {
    @Volatile private var cached: Map<String, DecoderFlags>? = null

    fun resolve(name: String): DecoderFlags? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val available = cached ?: synchronized(this) {
            cached ?: runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.associate { codec ->
                    codec.name to DecoderFlags(
                        softwareOnly = codec.isSoftwareOnly,
                        hardwareAccelerated = codec.isHardwareAccelerated,
                        vendor = codec.isVendor,
                    )
                }
            }.getOrDefault(emptyMap()).also { cached = it }
        }
        return available[name]
    }
}

private class AndroidSystemTelemetryProbe(
    private val context: Context,
    private val clock: TelemetryClock = AndroidTelemetryClock,
) {
    private val runtime = Runtime.getRuntime()
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var cachedExpensive: ExpensiveSystemSample? = null

    fun capture(): SystemTelemetrySnapshot {
        val startedAt = clock.now()
        val expensive = cachedExpensive?.takeIf {
            startedAt.elapsedRealtimeMs - it.observedAt.elapsedRealtimeMs < EXPENSIVE_SAMPLE_INTERVAL_MS
        } ?: captureExpensive().also { cachedExpensive = it }
        val capturedAt = clock.now()
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
            gcCount = expensive.gcCount,
            gcTimeMs = expensive.gcTimeMs,
            expensiveObservedAt = expensive.observedAt,
            capturedAt = capturedAt,
        )
    }

    private fun captureExpensive(): ExpensiveSystemSample {
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
        val gcCount = runCatching { Debug.getRuntimeStat("art.gc.gc-count").toLong() }.getOrNull()
        val gcTimeMs = runCatching { Debug.getRuntimeStat("art.gc.gc-time").toLong() }.getOrNull()
        return ExpensiveSystemSample(
            observedAt = clock.now(),
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
            gcCount = gcCount,
            gcTimeMs = gcTimeMs,
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
    val gcCount: Long?,
    val gcTimeMs: Long?,
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
    val gcCount: Long?,
    val gcTimeMs: Long?,
    val expensiveObservedAt: TelemetryInstant,
    val capturedAt: TelemetryInstant,
)

internal data class CurrentMediaReadSnapshot(
    val playbackSessionId: String?,
    val bytesRead: Long?,
    val unavailableReason: TelemetryUnavailableReason?,
) {
    init {
        require((bytesRead == null) != (unavailableReason == null)) {
            "Current-media reads must be either attributed or explicitly unavailable"
        }
        require(bytesRead == null || playbackSessionId != null) {
            "Attributed current-media reads require a playback session"
        }
    }
}

/**
 * Attributes TransferListener bytes only when an exact, playlist-unique MediaItem URI and the
 * DataSource lifecycle both identify the active playback session. Callers serialize access.
 */
internal class CurrentMediaTransferAttribution<T : Any>(
    private val maximumActiveTransfers: Int = 16,
) {
    private data class ActiveTransfer(
        val uri: String,
        var playbackSessionId: String?,
    )

    private val activeTransfers = IdentityHashMap<T, ActiveTransfer>()
    private var activeSessionId: String? = null
    private var activeMediaUri: String? = null
    private var activeMediaUriIsUnique = false
    private var lastSessionMediaUri: String? = null
    private var hasSeenSession = false
    private var bytesRead = 0L
    private var hasAttributedTransfer = false
    private var attributionInvalid = false

    init {
        require(maximumActiveTransfers > 0)
    }

    fun onMediaSessionChanged(
        playbackSessionId: String?,
        mediaUri: String?,
        mediaUriIsUnique: Boolean,
    ) {
        val canPromoteExistingTransfer = !hasSeenSession || lastSessionMediaUri != mediaUri
        activeSessionId = playbackSessionId
        activeMediaUri = mediaUri
        activeMediaUriIsUnique = mediaUriIsUnique
        bytesRead = 0
        hasAttributedTransfer = false
        attributionInvalid = false
        if (playbackSessionId != null) {
            hasSeenSession = true
            lastSessionMediaUri = mediaUri
        }
        if (
            playbackSessionId != null &&
            mediaUri != null &&
            mediaUriIsUnique &&
            canPromoteExistingTransfer
        ) {
            activeTransfers.values.forEach { transfer ->
                if (transfer.uri == mediaUri) {
                    transfer.playbackSessionId = playbackSessionId
                    hasAttributedTransfer = true
                }
            }
        }
    }

    fun onTimelineChanged(isUniqueUri: (String?) -> Boolean) {
        if (activeSessionId == null) return
        activeMediaUriIsUnique = isUniqueUri(activeMediaUri)
        if (!activeMediaUriIsUnique) {
            // Losing uniqueness breaks provenance for the entire occurrence. Removing the
            // duplicate later cannot retroactively identify loads started while it was queued.
            attributionInvalid = true
            activeTransfers.values.forEach { it.playbackSessionId = null }
        }
    }

    fun onTransferStart(source: T, dataSpecUri: String) {
        if (!activeTransfers.containsKey(source) && activeTransfers.size >= maximumActiveTransfers) {
            activeTransfers.keys.firstOrNull()?.let { evictedSource ->
                val removed = activeTransfers.remove(evictedSource)
                if (removed?.playbackSessionId == activeSessionId) attributionInvalid = true
            }
        }
        val boundSessionId = activeSessionId.takeIf {
            !attributionInvalid && activeMediaUriIsUnique && dataSpecUri == activeMediaUri
        }
        activeTransfers[source] = ActiveTransfer(dataSpecUri, boundSessionId)
        if (boundSessionId != null) hasAttributedTransfer = true
    }

    fun onBytesTransferred(source: T, dataSpecUri: String, bytesTransferred: Int) {
        if (bytesTransferred <= 0 || activeSessionId == null || attributionInvalid || !activeMediaUriIsUnique) return
        val transfer = activeTransfers[source]
        if (transfer == null) {
            if (activeMediaUriIsUnique && dataSpecUri == activeMediaUri) attributionInvalid = true
            return
        }
        if (transfer.playbackSessionId != activeSessionId) return
        if (transfer.uri != dataSpecUri || dataSpecUri != activeMediaUri) {
            attributionInvalid = true
            return
        }
        val added = bytesTransferred.toLong()
        if (Long.MAX_VALUE - bytesRead < added) {
            attributionInvalid = true
            return
        }
        bytesRead += added
    }

    fun onTransferEnd(source: T) {
        activeTransfers.remove(source)
    }

    fun snapshot(): CurrentMediaReadSnapshot {
        val reason = when {
            activeSessionId == null -> TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
            activeMediaUri == null -> TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT
            !activeMediaUriIsUnique -> TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
            attributionInvalid -> TelemetryUnavailableReason.TEMPORARILY_UNAVAILABLE
            !hasAttributedTransfer -> TelemetryUnavailableReason.WARMING_UP
            else -> null
        }
        return CurrentMediaReadSnapshot(
            playbackSessionId = activeSessionId,
            bytesRead = bytesRead.takeIf { reason == null },
            unavailableReason = reason,
        )
    }

    fun reset() {
        activeTransfers.clear()
        activeSessionId = null
        activeMediaUri = null
        activeMediaUriIsUnique = false
        lastSessionMediaUri = null
        hasSeenSession = false
        bytesRead = 0
        hasAttributedTransfer = false
        attributionInvalid = false
    }
}

internal data class RateResult(
    val value: Double,
    val delta: Long,
    val window: TelemetryWindow,
)

internal class TelemetryRateTracker(private val windowMs: Long) {
    private val dataSourceSamples = ArrayDeque<CounterRateSample>()
    private val currentMediaReadSamples = ArrayDeque<CounterRateSample>()
    private val processCpuSamples = ArrayDeque<CounterRateSample>()
    private val decoderSamples = ArrayDeque<DecoderRateSample>()
    private var currentMediaReadSessionId: String? = null
    private var decoderStreamKey: DecoderStreamKey? = null

    internal val retainedDecoderSampleCount: Int get() = decoderSamples.size

    fun add(
        instant: TelemetryInstant,
        dataSourceBytesTransferredTotal: Long,
        processCpuTimeMs: Long?,
        processCpuInstant: TelemetryInstant? = instant.takeIf { processCpuTimeMs != null },
        currentMediaSessionId: String? = null,
        currentMediaBytesRead: Long? = null,
        decoderSessionId: String? = null,
        decoderGeneration: Long? = null,
        decoderInstant: TelemetryInstant? = instant,
        decoderQueuedInputBuffersTotal: Long? = null,
        decoderRenderedOutputBuffersTotal: Long? = null,
    ) {
        appendSample(dataSourceSamples, CounterRateSample(instant, dataSourceBytesTransferredTotal))
        trimSamples(dataSourceSamples, instant)
        val newCurrentMediaSessionId = currentMediaSessionId.takeIf { currentMediaBytesRead != null }
        if (newCurrentMediaSessionId != currentMediaReadSessionId) {
            currentMediaReadSamples.clear()
            currentMediaReadSessionId = newCurrentMediaSessionId
        }
        if (newCurrentMediaSessionId != null && currentMediaBytesRead != null) {
            appendSample(currentMediaReadSamples, CounterRateSample(instant, currentMediaBytesRead))
            trimSamples(currentMediaReadSamples, instant)
        }
        if (processCpuTimeMs != null && processCpuInstant != null) {
            appendSample(processCpuSamples, CounterRateSample(processCpuInstant, processCpuTimeMs))
        }
        trimSamples(processCpuSamples, instant)

        val newDecoderStreamKey = decoderSessionId?.let { sessionId ->
            DecoderStreamKey(sessionId, decoderGeneration)
        }
        if (newDecoderStreamKey != decoderStreamKey) {
            decoderSamples.clear()
            decoderStreamKey = newDecoderStreamKey
        }
        if (
            newDecoderStreamKey != null &&
            decoderInstant != null &&
            (decoderQueuedInputBuffersTotal != null || decoderRenderedOutputBuffersTotal != null)
        ) {
            appendSample(decoderSamples,
                DecoderRateSample(
                    instant = decoderInstant,
                    queuedInputBuffersTotal = decoderQueuedInputBuffersTotal,
                    renderedOutputBuffersTotal = decoderRenderedOutputBuffersTotal,
                ),
            )
        }
        // Retention follows capture time, while rate denominators keep the source observation time.
        trimSamples(decoderSamples, instant)
    }

    fun dataSourceReadThroughput(): RateResult? = rate(dataSourceSamples) { it.value }?.let { result ->
        result.copy(value = result.value * 8.0)
    }

    fun currentMediaReadBitrate(): RateResult? = rate(currentMediaReadSamples) { it.value }?.let { result ->
        result.copy(value = result.value * 8.0)
    }

    fun processCpuPercent(): RateResult? = rate(processCpuSamples) { it.value }?.let { result ->
        // rate() reports milliseconds of CPU time per wall-clock second. Dividing by ten converts
        // that to one-core utilization percent: cpuDeltaMs / elapsedDeltaMs * 100.
        result.copy(value = result.value / 10.0)
    }

    fun decoderInputBufferRate(): RateResult? = rate(decoderSamples) { it.queuedInputBuffersTotal }

    fun decoderOutputBufferRate(): RateResult? = rate(decoderSamples) { it.renderedOutputBuffersTotal }

    private fun <T : TimedRateSample> rate(
        samples: ArrayDeque<T>,
        value: (T) -> Long?,
    ): RateResult? {
        val first = samples.firstOrNull { value(it) != null } ?: return null
        val last = samples.lastOrNull { value(it) != null } ?: return null
        if (first === last) return null
        val elapsedMs = last.instant.elapsedRealtimeMs - first.instant.elapsedRealtimeMs
        val firstValue = checkNotNull(value(first))
        val lastValue = checkNotNull(value(last))
        val delta = lastValue - firstValue
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

    private fun <T : TimedRateSample> appendSample(samples: ArrayDeque<T>, sample: T) {
        val previous = samples.lastOrNull()
        if (previous != null) {
            val elapsed = sample.instant.elapsedRealtimeMs - previous.instant.elapsedRealtimeMs
            if (elapsed < 0) return
            if (elapsed == 0L) samples.removeLast()
        }
        samples.addLast(sample)
        // A second bound covers high-frequency observers and stopped or anomalous clocks.
        while (samples.size > 4_096) samples.removeFirst()
    }

    private fun <T : TimedRateSample> trimSamples(samples: ArrayDeque<T>, instant: TelemetryInstant) {
        if (samples.lastOrNull()?.let { instant.elapsedRealtimeMs - it.instant.elapsedRealtimeMs > windowMs } == true) {
            samples.clear()
            return
        }
        while (
            samples.size > 2 &&
            instant.elapsedRealtimeMs - samples.elementAt(1).instant.elapsedRealtimeMs >= windowMs
        ) {
            samples.removeFirst()
        }
    }

    private interface TimedRateSample {
        val instant: TelemetryInstant
    }

    private data class CounterRateSample(
        override val instant: TelemetryInstant,
        val value: Long,
    ) : TimedRateSample

    private data class DecoderRateSample(
        override val instant: TelemetryInstant,
        val queuedInputBuffersTotal: Long?,
        val renderedOutputBuffersTotal: Long?,
    ) : TimedRateSample

    private data class DecoderStreamKey(
        val playbackSessionId: String,
        val decoderGeneration: Long?,
    )
}

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.putRateOrUnavailable(
    id: TelemetryMetricId,
    rate: RateResult?,
    unit: TelemetryUnit,
    source: TelemetryDataSource,
    instant: TelemetryInstant,
    calculationId: String,
    inputMetricId: TelemetryMetricId,
    unavailableReason: TelemetryUnavailableReason,
) {
    if (rate == null) {
        unavailable(id, unavailableReason, instant, source)
        return
    }
    putMetric(
        id,
        TelemetryEvidence.Derived(
            reading = TelemetryReading.Decimal(rate.value, unit),
            source = source,
            observedAtEpochMs = instant.epochMs,
            observedAtElapsedRealtimeMs = instant.elapsedRealtimeMs,
            window = rate.window,
            calculationId = calculationId,
            inputMetricIds = setOf(inputMetricId),
            operands = mapOf(
                "counter.delta" to rate.delta.toDouble(),
                "window.seconds" to rate.window.durationMs / 1_000.0,
            ),
        ),
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

private fun MutableMap<TelemetryMetricId, TelemetryMetric>.estimatedTextOrUnavailable(
    id: TelemetryMetricId,
    value: String?,
    source: TelemetryDataSource,
    instant: TelemetryInstant,
    methodId: String,
    reason: TelemetryUnavailableReason,
) = value?.takeIf(String::isNotBlank)?.let {
    putMetric(
        id,
        TelemetryEvidence.Estimated(
            reading = TelemetryReading.Text(it),
            source = source,
            observedAtEpochMs = instant.epochMs,
            observedAtElapsedRealtimeMs = instant.elapsedRealtimeMs,
            methodId = methodId,
        ),
    )
} ?: unavailable(id, reason, instant, source)

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
    detail: String? = null,
) = putMetric(
    id,
    TelemetryEvidence.Unavailable(
        reason = reason,
        observedAtEpochMs = instant.epochMs,
        observedAtElapsedRealtimeMs = instant.elapsedRealtimeMs,
        source = source,
        detail = detail,
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

private fun <K, V> MutableMap<K, V>.trimToSize(maxSize: Int) {
    while (size > maxSize) {
        val oldestKey = entries.firstOrNull()?.key ?: return
        remove(oldestKey)
    }
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

internal fun socModelUnavailableReason(sdkInt: Int): TelemetryUnavailableReason =
    if (sdkInt < Build.VERSION_CODES.S) {
        TelemetryUnavailableReason.UNSUPPORTED_ANDROID_VERSION
    } else {
        TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT
    }

internal fun decoderFlagEvidenceInstant(
    hasActivePlayback: Boolean,
    flagsObservedAt: TelemetryInstant?,
    snapshotInstant: TelemetryInstant,
): TelemetryInstant = flagsObservedAt.takeIf { hasActivePlayback } ?: snapshotInstant

internal data class OutputSignalEventSpec(
    val kind: TelemetryEventKind,
    val code: String,
    val playbackSessionId: String?,
    val relatedMetricIds: Set<TelemetryMetricId>,
)

internal fun outputSignalEventSpec(
    signal: OutputTelemetrySignal,
    activePlaybackSessionId: String?,
): OutputSignalEventSpec = when (signal) {
    OutputTelemetrySignal.ROUTE_CHANGED -> OutputSignalEventSpec(
        kind = TelemetryEventKind.ROUTE_CHANGED,
        code = "route.devices_changed",
        playbackSessionId = activePlaybackSessionId,
        relatedMetricIds = setOf(TelemetryMetricCatalog.ROUTE_CONNECTED_TYPES),
    )
    OutputTelemetrySignal.USB_ATTACHED -> OutputSignalEventSpec(
        kind = TelemetryEventKind.USB_ATTACHED,
        code = "usb.device_attached",
        playbackSessionId = activePlaybackSessionId,
        relatedMetricIds = setOf(TelemetryMetricCatalog.USB_DEVICE_INVENTORY),
    )
    OutputTelemetrySignal.USB_DETACHED -> OutputSignalEventSpec(
        kind = TelemetryEventKind.USB_DETACHED,
        code = "usb.device_detached",
        playbackSessionId = activePlaybackSessionId,
        relatedMetricIds = setOf(TelemetryMetricCatalog.USB_DEVICE_INVENTORY),
    )
}

internal fun pcmDataRateUnavailableReason(
    hasActivePlayback: Boolean,
    hasAudioTrack: Boolean,
    isPcmEncoding: Boolean,
): TelemetryUnavailableReason = when {
    !hasActivePlayback -> TelemetryUnavailableReason.NO_ACTIVE_PLAYBACK
    !hasAudioTrack -> TelemetryUnavailableReason.WARMING_UP
    !isPcmEncoding -> TelemetryUnavailableReason.NOT_APPLICABLE
    else -> TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT
}

private fun pcmBitDepth(encoding: Int): Int? = when (encoding) {
    C.ENCODING_PCM_8BIT -> 8
    C.ENCODING_PCM_16BIT -> 16
    C.ENCODING_PCM_24BIT -> 24
    C.ENCODING_PCM_32BIT,
    C.ENCODING_PCM_FLOAT -> 32
    else -> null
}

@OptIn(UnstableApi::class)
private fun AudioSink.AudioTrackConfig.toAudioTrackFacts() = AudioTrackFacts(
    sampleRate = sampleRate,
    encoding = encoding,
    channelConfig = channelConfig,
    bufferSize = bufferSize,
    offload = offload,
    tunneling = tunneling,
)

private fun audioTrackRequestFormat(config: AudioTrackFacts): String = listOf(
    "${config.sampleRate}hz",
    audioEncodingName(config.encoding),
    "mask-0x${config.channelConfig.toUInt().toString(16)}",
    "buffer-${config.bufferSize}b",
).joinToString(",")
