package io.github.sumirenokai.vesqen.usb.chain

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import io.github.sumirenokai.vesqen.playback.DirectUsbAudioOutput
import io.github.sumirenokai.vesqen.playback.DirectUsbAudioOutputProvider
import io.github.sumirenokai.vesqen.playback.GuardedRenderersFactory
import io.github.sumirenokai.vesqen.playback.StrictPcmAudioSink
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.pcm.ExactPcmConverter
import io.github.sumirenokai.vesqen.usb.pcm.UsbPcmEncoding
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import io.github.sumirenokai.vesqen.usb.session.UsbOutputHandle
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.concurrent.thread

/**
 * #271: what the conversion and the own output's work cost on this phone's CPU, with no USB device.
 * Started over adb; results go to logcat under VesqenUsbCost:
 * `adb shell am start -n io.github.sumirenokai.vesqen.devicetest/io.github.sumirenokai.vesqen.usb.chain.UsbCostProbeActivity`
 *
 * `--es mode player --es output system|direct` plays generated 48 kHz, 16-bit FLAC through the same
 * player, renderer and strict sink as the chain probe, and only the output differs: the system's
 * AudioTrack at volume 0, or the own output on a handle that a DAC-like clock drains. It then reports
 * the CPU of every busy thread, so the playback thread of the two paths can be compared silently.
 *
 * A debuggable build runs several times slower and says nothing about release costs: measure with
 * `isDebuggable = false` set on the deviceTest build type for the run only, as the 2026-11-06 record
 * in docs/M7_DEVICE_ACCEPTANCE.md did.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class UsbCostProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // vivo freezes a backgrounded app once the screen goes off, which would stop the player mid-run.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        report("Build debuggable: ${applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0}")
        if (intent.getStringExtra(EXTRA_MODE) == MODE_PLAYER) {
            playerRun(intent.getStringExtra(EXTRA_OUTPUT) ?: OUTPUT_DIRECT, intent.getIntExtra(EXTRA_SECONDS, 60))
            return
        }
        thread(name = "usb-cost", priority = Thread.MAX_PRIORITY) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            converter("float to a 16-bit wire, 16-bit source", UsbPcmEncoding.FLOAT, floats(16), 16, 16, 2)
            converter("float to a 24-bit wire, 16-bit source", UsbPcmEncoding.FLOAT, floats(16), 16, 24, 3)
            converter("float to a 24-bit wire, 24-bit source", UsbPcmEncoding.FLOAT, floats(24), 24, 24, 3)
            converter("int16 to a 16-bit wire", UsbPcmEncoding.INT16, ints16(), 16, 16, 2)
            output("output with float, 16-bit source and wire", PLAN_16)
            allocation()
            report("Finished")
            runOnUiThread { finish() }
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun playerRun(output: String, seconds: Int) {
        val tracks = ChainTracks.write(File(filesDir, "usb-cost"), 16).filter { it.file.extension == "flac" }
        val provider = when (output) {
            OUTPUT_SYSTEM -> AudioTrackAudioOutputProvider.Builder(this).build()
            else -> DirectUsbAudioOutputProvider(
                PLAN_16,
                openOutput = { PacedHandle(PLAN_16) },
                source = { TelemetrySourceFacts(codecMime = "audio/flac", sampleRateHz = FRAMES, bitDepth = 16, channelCount = CHANNELS) },
                onFailure = { report("Output failed: ${it.reason} (${it.detail})") },
            )
        }
        val timed = object : ForwardingAudioOutputProvider(provider) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                Timing.encoding = config.encoding
                return TimedOutput(super.getAudioOutput(config))
            }
        }
        val player = ExoPlayer.Builder(this, GuardedRenderersFactory(this, StrictPcmAudioSink(timed)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build()
        this.player = player
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = report("Player error: $error, cause ${error.cause}")
            },
        )
        // Silent on the system path; the own path has no device to play on.
        player.volume = 0f
        player.setMediaItems(tracks.map { MediaItem.fromUri(Uri.fromFile(it.file)) })
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.prepare()
        player.play()
        report("Playing ${tracks.size} tracks through the $output output; warming up for $PLAYER_WARMUP_SECONDS s")
        main.postDelayed({
            val before = threadTicks()
            val switchesBefore = playbackSwitches()
            val timingBefore = Timing.snapshot()
            val cpuBefore = Process.getElapsedCpuTime()
            val started = SystemClock.elapsedRealtime()
            val positionBefore = player.currentPosition
            report("Measuring for $seconds s")
            main.postDelayed({
                val elapsed = SystemClock.elapsedRealtime() - started
                val after = threadTicks()
                val timing = Timing.snapshot().zip(timingBefore) { now, then -> (now - then) * 1000 / elapsed }
                val switches = playbackSwitches().zip(switchesBefore) { now, then -> (now - then) * 1000 / elapsed }
                report(
                    "CALLS $output, per second: ${timing[0]} writes (${timing[1]} not finished) taking ${timing[2] / 1000} us, " +
                        "${timing[3]} positions taking ${timing[4] / 1000} us; playback thread switches voluntary ${switches[0]}, involuntary ${switches[1]}; output encoding ${Timing.encoding}",
                )
                val tickMs = 1000.0 / Os.sysconf(OsConstants._SC_CLK_TCK)
                report("PLAYER $output: process CPU ${"%.1f".format((Process.getElapsedCpuTime() - cpuBefore) * 100.0 / elapsed)} % of one core over $elapsed ms, audio format ${player.audioFormat?.pcmEncoding}, played ${player.currentPosition - positionBefore} ms")
                after.map { (key, ticks) -> key to ticks - (before[key] ?: 0L) }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }
                    .take(TOP_THREADS)
                    .forEach { (key, ticks) -> report("THREAD $output ${key.second}: ${"%.2f".format(ticks * tickMs * 100 / elapsed)} %") }
                player.release()
                this.player = null
                report("Finished")
                finish()
            }, seconds * 1000L)
        }, PLAYER_WARMUP_SECONDS * 1000L)
    }

    private fun playbackSwitches(): List<Long> {
        val task = File("/proc/self/task").listFiles().orEmpty().firstOrNull { File(it, "comm").readText().trim().startsWith("ExoPlayer:Playb") }
            ?: return listOf(0, 0)
        val lines = File(task, "status").readLines()
        return listOf("voluntary_ctxt_switches", "nonvoluntary_ctxt_switches").map { key ->
            lines.first { it.startsWith(key) }.substringAfter(':').trim().toLong()
        }
    }

    /** Counts and times the calls the playback thread makes into the output. */
    private class TimedOutput(output: AudioOutput) : ForwardingAudioOutput(output) {
        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            val started = System.nanoTime()
            val done = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
            Timing.writeNanos += System.nanoTime() - started
            Timing.writes++
            if (!done) Timing.unfinished++
            return done
        }

        override fun getPositionUs(): Long {
            val started = System.nanoTime()
            val position = super.getPositionUs()
            Timing.positionNanos += System.nanoTime() - started
            Timing.positions++
            return position
        }
    }

    /** Written by the playback thread only; the main thread reads a slightly stale copy, which is fine here. */
    private object Timing {
        @Volatile var writes = 0L
        @Volatile var unfinished = 0L
        @Volatile var writeNanos = 0L
        @Volatile var positions = 0L
        @Volatile var positionNanos = 0L
        @Volatile var encoding = 0

        fun snapshot() = listOf(writes, unfinished, writeNanos, positions, positionNanos)
    }

    /** User and system time of each thread of this process, in clock ticks, keyed by thread id and name. */
    private fun threadTicks(): Map<Pair<Int, String>, Long> = File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
        val stat = runCatching { File(task, "stat").readText() }.getOrNull() ?: return@mapNotNull null
        val name = stat.substringAfter('(').substringBeforeLast(')')
        // The fields after the name start with the state, field 3; utime and stime are fields 14 and 15.
        val fields = stat.substringAfterLast(") ").split(' ')
        (task.name.toInt() to name) to fields[11].toLong() + fields[12].toLong()
    }.toMap()

    /** One second of stereo on the source's lattice, as a decoder hands it over. */
    private fun floats(sourceBits: Int): ByteBuffer {
        val random = Random(1)
        val range = 1 shl sourceBits
        return ByteBuffer.allocateDirect(FRAMES * CHANNELS * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(FRAMES * CHANNELS) { putFloat((random.nextInt(range) - range / 2) / (range / 2).toFloat()) }
            flip()
        }
    }

    private fun ints16(): ByteBuffer {
        val random = Random(1)
        return ByteBuffer.allocateDirect(FRAMES * CHANNELS * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(FRAMES * CHANNELS) { putShort((random.nextInt(65536) - 32768).toShort()) }
            flip()
        }
    }

    private fun converter(name: String, encoding: UsbPcmEncoding, second: ByteBuffer, sourceBits: Int, wireBits: Int, subslot: Int) {
        val converter = ExactPcmConverter(encoding, CHANNELS, sourceBits, wireBits, subslot)
        val target = ByteBuffer.allocateDirect(SCRATCH_FRAMES * CHANNELS * subslot)
        timed(name) {
            val input = second.duplicate()
            while (input.hasRemaining()) {
                target.clear()
                check(converter.convert(input, target))
            }
        }
    }

    /** The output as Media3 drives it: a write per decoded buffer and a position read after each. */
    private fun output(name: String, plan: StreamingPlan) {
        val output = DirectUsbAudioOutput(plan, UsbPcmEncoding.FLOAT, sourceBits = 16, handle = AcceptAll(), nanoTime = System::nanoTime)
        output.play()
        val second = floats(16)
        val buffer = ByteBuffer.allocateDirect(DECODED_FRAMES * CHANNELS * 4).order(ByteOrder.LITTLE_ENDIAN)
        timed(name) {
            val input = second.duplicate()
            while (input.hasRemaining()) {
                buffer.clear()
                val end = minOf(input.position() + buffer.capacity(), input.limit())
                val slice = input.duplicate().apply { limit(end) }
                buffer.put(slice)
                input.position(end)
                buffer.flip()
                while (!output.write(buffer, 1, 0)) Unit
                output.positionUs
            }
        }
    }

    /** What reading the stream's stats allocates, once per write and once per position, without the JNI call. */
    private fun allocation() = timed("stats objects, ${CALLS_PER_SECOND} per second of audio") {
        repeat(CALLS_PER_SECOND) { sink = newStats(it.toLong()) }
    }

    private fun timed(name: String, secondOfAudio: () -> Unit) {
        repeat(WARMUP_SECONDS) { secondOfAudio() }
        val wall = System.nanoTime()
        val cpu = Process.getElapsedCpuTime()
        repeat(MEASURED_SECONDS) { secondOfAudio() }
        val wallUs = (System.nanoTime() - wall) / 1000 / MEASURED_SECONDS
        val cpuMs = (Process.getElapsedCpuTime() - cpu).toDouble() / MEASURED_SECONDS
        report("COST $name: $wallUs us of wall time per second of audio (${"%.2f".format(wallUs / 10_000.0)} % of one core); process CPU ${"%.1f".format(cpuMs)} ms")
    }

    private fun report(message: String) {
        Log.i(TAG, message)
    }

    private class AcceptAll : UsbOutputHandle {
        private var processed = 0L
        override val epoch = 0L
        override val failure: DirectUsbFailure? = null

        override fun write(buffer: ByteBuffer, offset: Int, size: Int): Int {
            processed += size / 4
            return size
        }

        override fun setRunning(running: Boolean) = Unit

        override fun markEndOfStream() = Unit

        override fun readStats(): UsbStreamStats = newStats(processed)

        override fun release() = Unit
    }

    /**
     * A handle drained at the plan's rate once running, like the real stream: its ring holds the plan's
     * frames, and its processed count moves a URB at a time. It skips the JNI call of the real one.
     */
    private class PacedHandle(private val plan: StreamingPlan) : UsbOutputHandle {
        private var written = 0L
        private var drainedBeforeRun = 0L
        private var runningSince = -1L
        override val epoch = 0L
        override val failure: DirectUsbFailure? = null

        private fun drained(): Long {
            if (runningSince < 0) return drainedBeforeRun
            val elapsed = (System.nanoTime() - runningSince) * plan.sampleRateHz / 1_000_000_000L
            return minOf(written, drainedBeforeRun + elapsed)
        }

        override fun write(buffer: ByteBuffer, offset: Int, size: Int): Int {
            val free = plan.ringFrames - (written - drained())
            val frames = minOf(size / plan.frameBytes.toLong(), free).coerceAtLeast(0)
            written += frames
            return (frames * plan.frameBytes).toInt()
        }

        override fun setRunning(running: Boolean) {
            if (running == runningSince >= 0) return
            drainedBeforeRun = drained()
            runningSince = if (running) System.nanoTime() else -1
        }

        override fun markEndOfStream() = Unit

        override fun readStats(): UsbStreamStats {
            val urbFrames = plan.packetsPerUrb.toLong() * plan.sampleRateHz / 1000
            val processed = drained() / urbFrames * urbFrames
            return newStats(processed).copy(mediaFramesWritten = written, mediaFramesSubmitted = minOf(written, processed + plan.urbCount * urbFrames))
        }

        override fun release() = Unit
    }

    private companion object {
        const val TAG = "VesqenUsbCost"
        const val EXTRA_MODE = "mode"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_SECONDS = "seconds"
        const val MODE_PLAYER = "player"
        const val OUTPUT_SYSTEM = "system"
        const val OUTPUT_DIRECT = "direct"
        const val TOP_THREADS = 8
        const val FRAMES = 48_000
        const val CHANNELS = 2
        const val SCRATCH_FRAMES = 2048
        const val DECODED_FRAMES = 4096
        const val WARMUP_SECONDS = 10
        const val MEASURED_SECONDS = 30
        const val PLAYER_WARMUP_SECONDS = 20
        /** About two stats reads per decoded buffer of 4096 frames: one in the write, one for the position. */
        const val CALLS_PER_SECOND = 2 * FRAMES / DECODED_FRAMES

        @Volatile
        var sink: Any? = null

        val PLAN_16 = StreamingPlan(
            interfaceNumber = 2, alternateSetting = 1, endpointAddress = 3, endpointSetsSampleRate = true, sampleRateHz = FRAMES,
            channels = CHANNELS, subslotBytes = 2, bitResolution = 16, serviceIntervalUs = 1000, maxPacketBytes = 192,
            packetsPerUrb = 32, urbCount = 3, ringFrames = 24_000,
        )

        fun newStats(frames: Long) = UsbStreamStats(
            mediaFramesWritten = frames, mediaFramesSubmitted = frames, mediaFramesProcessed = frames, mediaFramesDelivered = frames,
            mediaFramesLost = 0, silenceFramesInserted = 0, usbFramesDelivered = frames, underruns = 0, urbsCompleted = 0,
            packetsFailed = 0, failure = UsbStreamStats.Failure.NONE, failureErrno = 0, threadNice = 0, threadSchedulerPolicy = 0,
            epoch = 0, requestedEpoch = 0,
        )
    }
}
