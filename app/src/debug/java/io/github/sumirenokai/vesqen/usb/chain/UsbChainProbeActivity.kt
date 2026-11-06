package io.github.sumirenokai.vesqen.usb.chain

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import io.github.sumirenokai.vesqen.playback.DirectUsbAudioOutputProvider
import io.github.sumirenokai.vesqen.playback.GuardedRenderersFactory
import io.github.sumirenokai.vesqen.playback.StrictPcmAudioSink
import io.github.sumirenokai.vesqen.usb.UsbProbeBaseActivity
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * M7-2: a real ExoPlayer on Vesqen's own USB path. It runs a fixed script of playback controls and
 * reports PASS or FAIL for each, at the device's volume of about -30 dB.
 *
 * ```
 * adb shell am start -S -n io.github.sumirenokai.vesqen.devicetest/io.github.sumirenokai.vesqen.usb.chain.UsbChainProbeActivity --ei bits 16
 * adb logcat -s VesqenUsbChain
 * ```
 *
 * `--es mode long --ei minutes 30` plays one stream that long under a foreground service, as the app
 * would, and reports each minute. `--es mode file --es file <path> --ei seconds 30` plays a real file.
 * `--es mode scan --es dir <path>` claims nothing and reports the formats of the files under it.
 * `--ei volume` gives the device's volume in 1/256 dB. -15400 is the lowest on the JBL Flip 7.
 * `--ez silentTrack true` adds [SilentSpeakerTrack] to a long run, and `--ei silentTrackLevel` gives
 * its tone a level, 3277 being -20 dBFS and audible from the phone. `--ez session true` adds a media
 * session, as the app has, and `--ez mediaStyle true` also shows the notification in the media style.
 * `--ef silentTrackGain` sets the track's own volume, `--ei silentTrackHz` the tone's frequency and `--ei silentTrackDevice` the type of output
 * it prefers, from [AudioDeviceInfo]. `--es mode battery` asks the system to stop
 * optimizing this app's battery use, which the person holding the phone answers.
 * `--ez connectedDevice true` adds that foreground service type.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class UsbChainProbeActivity : UsbProbeBaseActivity() {
    override val logTag = "VesqenUsbChain"

    private lateinit var session: ChainUsb
    private lateinit var player: ExoPlayer
    private val main = Handler(Looper.getMainLooper())
    private val volumesIgnored = AtomicInteger()
    private val noisyBroadcasts = AtomicInteger()
    private var passed = 0
    private var failed = 0

    /** Main thread only. */
    private var releasedForFocus = false

    @Volatile
    private var focusLossDelivered = false

    @Volatile
    private var deviceGone = false

    @Volatile
    private var keepsRunningWithoutActivity = false

    @Volatile
    private var playerError: PlaybackException? = null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            noisyBroadcasts.incrementAndGet()
            report("BECOMING_NOISY received, claimed ${session.isClaimed}")
        }
    }

    /** The stream fails by itself at the same moment; this is what stops the player and frees the rest. */
    private val detached = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            deviceGone = true
            report("Device detached: ${session.latestStats()}")
            player.stop()
            session.onDetached()
        }
    }

    private val routes = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any(::isUsbRoute)) report("USB route added, claimed ${session.isClaimed}")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any(::isUsbRoute)) report("USB route removed, claimed ${session.isClaimed}")
        }
    }

    private val playerEvents = object : Player.Listener {
        /** Only this may take the device back: a route coming back does not (design, "Audio focus"). */
        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            if (reason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                focusLossDelivered = true
                giveBackForFocus()
            } else if (reason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && releasedForFocus) {
                takeBackAfterFocus()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            playerError = error
            report("Player error: ${error.errorCodeName}: ${error.cause}")
        }
    }

    private fun giveBackForFocus() {
        releasedForFocus = true
        session.giveBack()
    }

    private fun takeBackAfterFocus() {
        releasedForFocus = false
        player.stop()
        // A session's outputs exist only while it streams, so the player prepares after the takeover.
        if (!session.takeOverAndWait()) report("Taking the device back failed")
        player.prepare()
    }

    override fun onDevice(device: UsbDevice) {
        val bits = intent.getIntExtra(EXTRA_BITS, 16)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SCRIPT
        if (mode == MODE_BATTERY) {
            val power = getSystemService(PowerManager::class.java)
            report("Ignoring battery optimizations: ${power.isIgnoringBatteryOptimizations(packageName)}")
            @SuppressLint("BatteryLife")
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(request)
            return
        }
        if (mode == MODE_SCAN) {
            thread(name = "chain-scan") {
                ChainFileScan.scan(File(checkNotNull(intent.getStringExtra(EXTRA_DIR)) { "No dir" }), ::report)
                finishReport(device, "scan")
            }
            return
        }
        thread(name = "chain-script") {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                report("Could not open the device")
                return@thread
            }
            val planned = planFor(connection, bits)
            if (planned == null) {
                connection.close()
                return@thread
            }
            val (function, plan) = planned
            val volume = intent.getIntExtra(EXTRA_VOLUME, START_VOLUME)
            session = ChainUsb(device, usbManager, connection, function, plan, volume, ::report)
            val tracks = ChainTracks.write(File(filesDir, "usb-chain"), plan.bitResolution)
            try {
                ContextCompat.registerReceiver(
                    this,
                    noisy,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                ContextCompat.registerReceiver(
                    this,
                    detached,
                    IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                audioManager.registerAudioDeviceCallback(routes, main)
                val taken = CountDownLatch(1)
                var claimed = false
                session.takeOver {
                    claimed = it
                    taken.countDown()
                }
                taken.await()
                if (!claimed) return@thread report("Not playing: the takeover failed")
                onMain {
                    val provider = DirectUsbAudioOutputProvider(
                        plan,
                        openOutput = session::openOutput,
                        onFailure = { failure -> report("Output failed: ${failure.reason} (${failure.detail})") },
                        onVolumeIgnored = { volumesIgnored.incrementAndGet() },
                    )
                    player = ExoPlayer.Builder(this, GuardedRenderersFactory(this, StrictPcmAudioSink(provider)))
                        .setAudioAttributes(MUSIC, true)
                        // The design turns this off for a takeover; the broadcast is counted instead.
                        .setHandleAudioBecomingNoisy(false)
                        .build()
                    player.addListener(playerEvents)
                }
                when (mode) {
                    MODE_LONG -> longRun(tracks.drop(1), intent.getIntExtra(EXTRA_MINUTES, 30))
                    MODE_FILE -> playFile(File(checkNotNull(intent.getStringExtra(EXTRA_FILE)) { "No file" }), intent.getIntExtra(EXTRA_SECONDS, 30))
                    else -> script(tracks)
                }
            } catch (exception: InterruptedException) {
                report("Stopped: $exception")
                failed++
            } catch (exception: IllegalStateException) {
                report("Stopped: $exception")
                failed++
            } finally {
                if (::player.isInitialized) onMain { player.release() }
                audioManager.unregisterAudioDeviceCallback(routes)
                unregisterReceiver(noisy)
                unregisterReceiver(detached)
                stopService(Intent(this, UsbChainKeepAliveService::class.java))
                session.shutdown()
                report("Focus loss delivered by the system: $focusLossDelivered")
                report("Chain result: $passed passed, $failed failed, ${session.totals()}")
                finishReport(device, "chain-$mode-$bits")
            }
        }
    }

    /** One stream and one output throughout: both tracks arrive as float, so the output carries over between them. */
    private fun longRun(tracks: List<ChainTrack>, minutes: Int) {
        val silentTrack = if (intent.getBooleanExtra(EXTRA_SILENT_TRACK, false)) SilentSpeakerTrack(audioManager, intent.getIntExtra(EXTRA_SILENT_TRACK_LEVEL, 0), intent.getIntExtra(EXTRA_SILENT_TRACK_HZ, 1000), intent.getIntExtra(EXTRA_SILENT_TRACK_DEVICE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), intent.getFloatExtra(EXTRA_SILENT_TRACK_GAIN, 1f)) else null
        val mediaSession = if (intent.getBooleanExtra(EXTRA_SESSION, false)) onMain { MediaSession.Builder(this, player).build() } else null
        UsbChainKeepAliveService.mediaStyleSession = mediaSession.takeIf { intent.getBooleanExtra(EXTRA_MEDIA_STYLE, false) }
        UsbChainKeepAliveService.connectedDevice = intent.getBooleanExtra(EXTRA_CONNECTED_DEVICE, false)
        ContextCompat.startForegroundService(this, Intent(this, UsbChainKeepAliveService::class.java))
        onMain {
            player.setWakeMode(C.WAKE_MODE_LOCAL)
            player.setMediaItems(tracks.map { MediaItem.fromUri(Uri.fromFile(it.file)) })
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.prepare()
            player.play()
        }
        check("start", waitFor(START_TIMEOUT_MS) { position() > 0 }, "position ${position()}")
        val started = now()
        val startedCpu = Process.getElapsedCpuTime()
        var minute = 0
        // The screen going off may take the activity with it, which must not end the run.
        keepsRunningWithoutActivity = true
        while (minute < minutes && !deviceGone && playerError == null) {
            SystemClock.sleep(WATCH_MS)
            if (now() - started >= (minute + 1) * MINUTE_MS) {
                minute++
                val cpu = (Process.getElapsedCpuTime() - startedCpu) * 100.0 / (now() - started)
                val stats = session.latestStats()
                report(
                    "Minute $minute: ${session.totals()}, frames ${stats?.mediaFramesProcessed}, silence ${stats?.silenceFramesInserted}, " +
                        "failure ${stats?.failure}, CPU %.1f%%, stream thread ${streamThreadSwitches()}".format(cpu) +
                        (silentTrack?.let { ", silent track ${it.describe()}" }.orEmpty()),
                )
            }
        }
        silentTrack?.close()
        mediaSession?.let { session -> onMain { session.release() } }
        val elapsed = now() - started
        val totals = session.totals()
        val deliveredMs = totals.deliveredFrames / FRAMES_PER_MS
        report("Ran ${elapsed / 1000} s, delivered $deliveredMs ms")
        check("ran the whole time", minute >= minutes, "$minute of $minutes minutes")
        check("one stream", session.streamsCreated == 1, "${session.streamsCreated} streams")
        check("the frames kept up with the clock", abs(deliveredMs - elapsed) <= LONG_TOLERANCE_MS, "$deliveredMs ms in $elapsed ms")
        check("nothing lost", totals.lostFrames == 0L && totals.failedPackets == 0L, "$totals")
        check("no underruns", totals.underruns == 0L, "$totals")
        check("no player error", playerError == null, "$playerError")
    }

    private fun playFile(file: File, seconds: Int) {
        onMain {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
        }
        check("start", waitFor(START_TIMEOUT_MS) { position() > 0 }, "position ${position()}, error $playerError")
        val format = onMain { player.audioFormat }
        report("Format ${format?.sampleMimeType}, ${format?.sampleRate} Hz, ${format?.channelCount} channels, delay ${format?.encoderDelay}, padding ${format?.encoderPadding}")
        checkProgress("plays in real time", seconds * 1000L)
        val totals = session.totals()
        report("Stats ${session.latestStats()}")
        check("nothing lost", totals.lostFrames == 0L && totals.failedPackets == 0L, "$totals")
        check("no underruns", totals.underruns == 0L, "$totals")
        check("no player error", playerError == null, "$playerError")
    }

    /** Waits of the stream thread, one way to see how often it wakes. */
    private fun streamThreadSwitches(): String {
        val task = File("/proc/self/task").listFiles().orEmpty().firstOrNull { File(it, "comm").readText().trim() == "usb-stream" }
            ?: return "not running"
        return File(task, "status").readLines().filter { "ctxt_switches" in it }.joinToString { it.replace("\t", " ") }
    }

    private fun script(tracks: List<ChainTrack>) {
        val (first, second, third) = tracks

        // Start.
        onMain {
            player.setMediaItems(tracks.map { MediaItem.fromUri(Uri.fromFile(it.file)) })
            player.prepare()
            player.play()
        }
        val started = now()
        check("start", waitFor(START_TIMEOUT_MS) { position() > 0 }, "progress after ${now() - started} ms")
        checkProgress("plays in real time", PROGRESS_MS)
        check("position follows the frames sent", abs(position() - processedMs()) <= TOLERANCE_MS, "position ${position()}, frames ${processedMs()} ms")

        // Pause and resume.
        onMain { player.pause() }
        SystemClock.sleep(SETTLE_MS)
        val pausedAt = position()
        val before = latest()
        SystemClock.sleep(PAUSE_MS)
        val after = latest()
        check("pause holds the position", abs(position() - pausedAt) <= FROZEN_TOLERANCE_MS, "$pausedAt then ${position()}")
        check("pause sends no media", after.mediaFramesProcessed == before.mediaFramesProcessed, "${before.mediaFramesProcessed} then ${after.mediaFramesProcessed}")
        check("pause sends silence", after.silenceFramesInserted - before.silenceFramesInserted >= PAUSE_MS * FRAMES_PER_MS / 2, "${after.silenceFramesInserted - before.silenceFramesInserted} frames")
        onMain { player.play() }
        checkProgress("resume plays in real time", PROGRESS_MS)

        // Seek: the new output has counted nothing from the old position, and the stream carries on.
        checkSeek("seek forward", 15_000)
        checkSeek("seek back", 5_000)

        // A format change between tracks: WAV arrives as integers and FLAC as float. The DAC's
        // configuration stays the same, so the stream does too and only the output changes (D14).
        val outputsBeforeChange = session.outputsOpened
        onMain { player.seekTo(first.durationMs - NEAR_END_MS) }
        check("format change moves to the next track", waitFor(NEAR_END_MS + TRANSITION_TIMEOUT_MS) { index() == 1 }, "index ${index()}")
        SystemClock.sleep(SETTLE_MS)
        check("format change starts one output per track", session.outputsOpened == outputsBeforeChange + 2, "${session.outputsOpened - outputsBeforeChange} outputs")
        check("format change keeps the stream", session.streamsCreated == 1, "${session.streamsCreated} streams")
        checkProgress("the next track plays in real time", PROGRESS_MS)

        // Changing tracks by hand.
        onMain { player.seekToNextMediaItem() }
        check("next track", waitFor(TRANSITION_TIMEOUT_MS) { index() == 2 }, "index ${index()}")
        checkProgress("it plays in real time", PROGRESS_MS)
        onMain { player.seekToPreviousMediaItem() }
        SystemClock.sleep(SETTLE_MS)

        // The same format continues with no gap and no repeated frames.
        val outputsBeforeGapless = session.outputsOpened
        onMain { player.seekTo(1, second.durationMs - NEAR_END_MS) }
        check("same format moves to the next track", waitFor(NEAR_END_MS + TRANSITION_TIMEOUT_MS) { index() == 2 }, "index ${index()}")
        SystemClock.sleep(PROGRESS_MS)
        val gaplessPosition = position()
        val expected = NEAR_END_MS + gaplessPosition
        check("same format keeps one output", session.outputsOpened == outputsBeforeGapless + 1, "${session.outputsOpened - outputsBeforeGapless} outputs")
        check("same format has no gap", abs(processedMs() - expected) <= TOLERANCE_MS, "frames ${processedMs()} ms, expected $expected ms")

        // End of stream.
        val endStarted = now()
        onMain { player.seekTo(2, third.durationMs - NEAR_END_MS) }
        check("the stream ends", waitFor(NEAR_END_MS + TRANSITION_TIMEOUT_MS) { state() == Player.STATE_ENDED }, "after ${now() - endStarted} ms")
        check("it ends on time", abs(now() - endStarted - NEAR_END_MS) <= END_TOLERANCE_MS, "${now() - endStarted} ms")
        check("position is at the end", abs(position() - third.durationMs) <= TOLERANCE_MS, "${position()} of ${third.durationMs}")

        // Losing audio focus gives the device back, and regaining it takes the device again.
        onMain {
            player.seekTo(0, 5_000)
            player.play()
        }
        waitFor(START_TIMEOUT_MS) { state() == Player.STATE_READY }
        SystemClock.sleep(PROGRESS_MS)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setOnAudioFocusChangeListener {}.build()
        audioManager.requestAudioFocus(focus)
        // iQOO 9: the system tells no holder of its loss, so the same steps then run without the event.
        if (!waitFor(TRANSITION_TIMEOUT_MS) { focusLossDelivered }) {
            report("NOTE the system did not deliver the focus loss, so it is simulated from here")
            audioManager.abandonAudioFocusRequest(focus)
            onMain {
                player.pause()
                giveBackForFocus()
            }
        }
        check("focus loss gives the device back", waitFor(TRANSITION_TIMEOUT_MS) { !session.isClaimed }, "claimed ${session.isClaimed}")
        SystemClock.sleep(SETTLE_MS)
        val focusLostAt = position()
        report("Device free from ${now()}: sample the card now")
        SystemClock.sleep(FOCUS_HOLD_MS)
        check("the device stays free while focus is away", !session.isClaimed, "claimed ${session.isClaimed}")
        check("the position holds while focus is away", abs(position() - focusLostAt) <= FROZEN_TOLERANCE_MS, "$focusLostAt then ${position()}")
        if (focusLossDelivered) {
            audioManager.abandonAudioFocusRequest(focus)
        } else {
            onMain {
                takeBackAfterFocus()
                player.play()
            }
        }
        check("regaining focus takes the device", waitFor(START_TIMEOUT_MS) { session.isClaimed && isPlaying() }, "claimed ${session.isClaimed}, playing ${isPlaying()}")
        check("playback continues where it stopped", abs(position() - focusLostAt) <= RESUME_TOLERANCE_MS, "$focusLostAt then ${position()}")
        checkProgress("it plays in real time", PROGRESS_MS)

        // Duck: the factor is ignored, and playback goes on.
        val duck = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setOnAudioFocusChangeListener {}.build()
        audioManager.requestAudioFocus(duck)
        SystemClock.sleep(SETTLE_MS)
        check("duck does not pause", isPlaying() && session.isClaimed, "playing ${isPlaying()}, claimed ${session.isClaimed}")
        if (focusLossDelivered) {
            check("duck reaches the output and is ignored", volumesIgnored.get() > 0, "${volumesIgnored.get()} volume calls")
        } else {
            report("NOTE duck could not reach the output: ${volumesIgnored.get()} volume calls")
        }
        checkProgress("it plays in real time while ducked", PROGRESS_MS)
        audioManager.abandonAudioFocusRequest(duck)

        val totals = session.totals()
        check("no BECOMING_NOISY", noisyBroadcasts.get() == 0, "${noisyBroadcasts.get()}")
        check("no player error", playerError == null, "$playerError")
        check("nothing lost", totals.lostFrames == 0L && totals.failedPackets == 0L, "$totals")
        check("no underruns", totals.underruns == 0L, "$totals")
    }

    private fun checkSeek(name: String, targetMs: Long) {
        val outputs = session.outputsOpened
        val streams = session.streamsCreated
        onMain { player.seekTo(targetMs) }
        SystemClock.sleep(PROGRESS_MS)
        val played = position() - targetMs
        check("$name reaches the target", played in 0..PROGRESS_MS + TOLERANCE_MS, "$played ms past $targetMs")
        check("$name starts a new output", session.outputsOpened == outputs + 1, "${session.outputsOpened - outputs} outputs")
        check("$name keeps the stream", session.streamsCreated == streams, "${session.streamsCreated - streams} new streams")
        check("$name leaves no old audio", abs(processedMs() - played) <= TOLERANCE_MS, "frames ${processedMs()} ms, played $played ms")
    }

    private fun checkProgress(name: String, durationMs: Long) {
        val startPosition = position()
        val startTime = now()
        SystemClock.sleep(durationMs)
        val advanced = position() - startPosition
        val elapsed = now() - startTime
        check(name, abs(advanced - elapsed) <= TOLERANCE_MS, "$advanced ms in $elapsed ms")
    }

    private fun check(name: String, ok: Boolean, detail: String) {
        if (ok) passed++ else failed++
        report("${if (ok) "PASS" else "FAIL"} $name: $detail")
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            check(!cancelled || keepsRunningWithoutActivity) { "The activity went away" }
            if (condition()) return true
            SystemClock.sleep(POLL_MS)
        }
        return condition()
    }

    private fun latest(): UsbStreamStats = checkNotNull(session.latestStats()) { "No output has counters yet" }

    private fun processedMs() = latest().mediaFramesProcessed / FRAMES_PER_MS

    private fun now() = SystemClock.elapsedRealtime()

    private fun position(): Long = onMain { player.currentPosition }

    private fun index(): Int = onMain { player.currentMediaItemIndex }

    private fun state(): Int = onMain { player.playbackState }

    private fun isPlaying(): Boolean = onMain { player.isPlaying }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        main.post(task)
        return task.get()
    }

    private companion object {
        const val EXTRA_BITS = "bits"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_FILE = "file"
        const val EXTRA_DIR = "dir"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_SILENT_TRACK = "silentTrack"
        const val EXTRA_SILENT_TRACK_LEVEL = "silentTrackLevel"
        const val EXTRA_SILENT_TRACK_HZ = "silentTrackHz"
        const val EXTRA_SILENT_TRACK_DEVICE = "silentTrackDevice"
        const val EXTRA_SILENT_TRACK_GAIN = "silentTrackGain"
        const val EXTRA_CONNECTED_DEVICE = "connectedDevice"
        const val EXTRA_MEDIA_STYLE = "mediaStyle"
        const val EXTRA_SESSION = "session"
        const val MODE_SCRIPT = "script"
        const val MODE_LONG = "long"
        const val MODE_FILE = "file"
        const val MODE_SCAN = "scan"
        const val MODE_BATTERY = "battery"

        /** -30 dB in the spec's 1/256 dB (D4). */
        const val START_VOLUME = -30 * 256
        const val FRAMES_PER_MS = 48L
        const val START_TIMEOUT_MS = 5000L
        const val TRANSITION_TIMEOUT_MS = 4000L
        const val PROGRESS_MS = 3000L
        const val PAUSE_MS = 2000L
        const val SETTLE_MS = 300L
        const val NEAR_END_MS = 3000L
        const val FOCUS_HOLD_MS = 6000L
        const val POLL_MS = 20L
        const val WATCH_MS = 1000L
        const val MINUTE_MS = 60_000L
        const val LONG_TOLERANCE_MS = 1000L
        const val TOLERANCE_MS = 200L
        const val FROZEN_TOLERANCE_MS = 40L
        const val RESUME_TOLERANCE_MS = 500L
        const val END_TOLERANCE_MS = 500L
        val MUSIC: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
}
