package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.sumirenokai.vesqen.usb.session.UsbDeviceIdentity
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The DAC's hardware volume while Vesqen's own USB output holds it (D4), in the UAC unit of 1/256 dB.
 * [current] is what the device read back, and [resolution] is the device's own step.
 */
data class DirectUsbVolume(val current: Int, val minimum: Int, val maximum: Int, val resolution: Int) {
    init {
        require(minimum <= maximum && resolution > 0)
    }

    val decibels: Double get() = current / 256.0

    /** One volume-key press: about 2 dB, as a whole number of the device's steps. */
    val keyStep: Int get() = max(1, (KEY_STEP + resolution / 2) / resolution) * resolution

    /** Key presses from the minimum to the maximum; the last one may be shorter. */
    val keySteps: Int get() = (maximum - minimum + keyStep - 1) / keyStep

    /** [current] counted in key presses above the minimum, as a media session's remote volume reports it. */
    val keyIndex: Int
        get() = if (current >= maximum) keySteps else ((current - minimum + keyStep / 2) / keyStep).coerceIn(0, keySteps)

    fun atKeyIndex(index: Int): Int = min(maximum, minimum + index.coerceIn(0, keySteps) * keyStep)

    fun afterKeys(presses: Int): Int = (current + presses * keyStep).coerceIn(minimum, maximum)

    private companion object {
        const val KEY_STEP = 2 * 256
    }
}

/**
 * Process-wide: the playback service's route publishes the volume it holds and takes the changes;
 * the UI and the volume keys read and ask through here. Null while the own output holds no device.
 */
class DirectUsbVolumeControl {
    private val mutableState = MutableStateFlow<DirectUsbVolume?>(null)
    val state: StateFlow<DirectUsbVolume?> = mutableState.asStateFlow()

    private val mutableKeyPresses = MutableStateFlow(0L)

    /** Counts the volume-key presses taken, so the UI can show the level when its bar is not on screen. */
    val keyPresses: StateFlow<Long> = mutableKeyPresses.asStateFlow()

    @Volatile
    private var setter: ((Int) -> Unit)? = null

    /**
     * The last level asked for that the device has not answered yet. A key press builds on it, so
     * presses that come faster than the read-backs add up instead of all starting from the same level.
     */
    @Volatile
    private var requested: Int? = null

    /** Main thread: the session's player, which reports the volume to the media session. */
    @Volatile
    internal var onChanged: ((DirectUsbVolume?) -> Unit)? = null

    internal fun attach(setter: (target: Int) -> Unit) {
        this.setter = setter
    }

    /** Only the route that attached [setter] ends it: a recreated service's route may already have taken over. */
    internal fun detach(setter: (target: Int) -> Unit) {
        if (this.setter !== setter) return
        this.setter = null
        publish(null)
    }

    /**
     * Main thread. [settled] is false while the route still has a newer request for the device; once it
     * has none, the read-back is the level the next key press starts from, whatever was asked for.
     */
    internal fun publish(volume: DirectUsbVolume?, settled: Boolean = true) {
        if (settled || volume == null) requested = null
        if (mutableState.value == volume) return
        mutableState.value = volume
        onChanged?.invoke(volume)
    }

    /** Asks for [target] (1/256 dB); the device's answer arrives through [state]. */
    fun set(target: Int) {
        val send = setter ?: return
        requested = target
        send(target)
    }

    /** [presses] volume-key presses, up when positive. False while there is no volume to change. */
    fun pressKeys(presses: Int, shown: Boolean = true): Boolean {
        val volume = mutableState.value ?: return false
        set(volume.copy(current = (requested ?: volume.current).coerceIn(volume.minimum, volume.maximum)).afterKeys(presses))
        if (shown) mutableKeyPresses.value += 1
        return true
    }
}

/** D4: the level a takeover starts at, and the level the listener last chose. */
internal interface DirectUsbVolumeMemory {
    fun startVolume(device: UsbDeviceIdentity): Int

    fun remember(device: UsbDeviceIdentity, volume: Int)

    /** The connection ended: its level no longer applies as it is. */
    fun forgetConnection()
}

/**
 * Within one USB connection the listener's last level comes back. After a replug the start is the
 * lower of the level remembered for the model and about -30 dB: the model is not proof of the same
 * unit or the same headphones. The per-model level stays on this device (private preferences).
 */
internal class PreferencesDirectUsbVolumeMemory(context: Context) : DirectUsbVolumeMemory {
    private val preferences = context.getSharedPreferences("direct_usb_volume", Context.MODE_PRIVATE)

    @Volatile
    private var connection: Pair<UsbDeviceIdentity, Int>? = null

    override fun startVolume(device: UsbDeviceIdentity): Int =
        connection?.takeIf { it.first == device }?.second
            ?: min(preferences.getInt(device.modelKey(), START_VOLUME), START_VOLUME)

    override fun remember(device: UsbDeviceIdentity, volume: Int) {
        connection = device to volume
        preferences.edit().putInt(device.modelKey(), volume).apply()
    }

    override fun forgetConnection() {
        connection = null
    }

    private fun UsbDeviceIdentity.modelKey() = "%04x:%04x".format(vendorId, productId)

    companion object {
        /** D4 without a remembered volume: about -30 dB. */
        const val START_VOLUME = -30 * 256
    }
}

/**
 * What the media session sees of the device volume. While the own output holds a DAC, the session
 * is told its playback is remote, so Android sends the volume keys to it instead of changing a
 * stream volume that this output does not use (D4; Media3 1.11.0 `MediaSessionLegacyStub`).
 * One unit of device volume is one key press ([DirectUsbVolume.keyStep]). At any other time the
 * wrapped player answers.
 */
@UnstableApi
internal class DeviceVolumeSessionPlayer(player: Player, private val volumes: DirectUsbVolumeControl) : ForwardingPlayer(player) {
    private val listeners = CopyOnWriteArraySet<Player.Listener>()
    private var reported: DirectUsbVolume? = volumes.state.value
    private val main = Handler(player.applicationLooper)
    private val changed: (DirectUsbVolume?) -> Unit = { report(it, force = false) }

    private val localChanges = object : Player.Listener {
        // The wrapped player reports its stream volume to the same listeners; the remote one is restored after it.
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = reportAgain()

        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = reportAgain()

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = reportAgain()

        // Posted, so it follows the wrapped player's own callback to the session's listeners.
        private fun reportAgain() {
            main.post { if (reported != null) report(reported, force = true) }
        }
    }

    init {
        player.addListener(localChanges)
        volumes.onChanged = changed
    }

    /** Stops reporting; the wrapped player is released by its owner. */
    fun stopReporting() {
        // A recreated service's player may already report in this one's place.
        if (volumes.onChanged === changed) volumes.onChanged = null
        main.removeCallbacksAndMessages(null)
        wrappedPlayer.removeListener(localChanges)
    }

    private fun report(volume: DirectUsbVolume?, force: Boolean) {
        val before = reported
        reported = volume
        val rangeChanged = force || (before == null) != (volume == null) || before?.keySteps != volume?.keySteps
        if (rangeChanged) {
            // The commands first: the media session reads them when the device info makes it build its volume provider.
            listeners.forEach { it.onAvailableCommandsChanged(availableCommands) }
            listeners.forEach { it.onDeviceInfoChanged(deviceInfo) }
        }
        if (rangeChanged || before?.keyIndex != volume?.keyIndex) {
            listeners.forEach { it.onDeviceVolumeChanged(deviceVolume, isDeviceMuted) }
        }
    }

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    // ExoPlayer offers no device volume commands unless it controls the stream volume itself, which
    // Vesqen leaves off. Without them the media session reports a fixed volume and takes no key.
    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        return if (reported != null) commands.buildUpon().addAll(*DEVICE_VOLUME_COMMANDS).build() else commands
    }

    override fun isCommandAvailable(command: Int): Boolean =
        (reported != null && command in DEVICE_VOLUME_COMMANDS) || super.isCommandAvailable(command)

    override fun getDeviceInfo(): DeviceInfo = reported?.let {
        DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(it.keySteps).build()
    } ?: super.getDeviceInfo()

    override fun getDeviceVolume(): Int = reported?.keyIndex ?: super.getDeviceVolume()

    override fun isDeviceMuted(): Boolean = if (reported != null) false else super.isDeviceMuted()

    @Deprecated("Media3 keeps the variant without flags")
    override fun setDeviceVolume(volume: Int) {
        reported?.let { volumes.set(it.atKeyIndex(volume)) } ?: super.setDeviceVolume(volume)
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        reported?.let { volumes.set(it.atKeyIndex(volume)) } ?: super.setDeviceVolume(volume, flags)
    }

    @Deprecated("Media3 keeps the variant without flags")
    override fun increaseDeviceVolume() {
        if (!volumes.pressKeys(1, shown = false)) super.increaseDeviceVolume()
    }

    override fun increaseDeviceVolume(flags: Int) {
        if (!volumes.pressKeys(1, shown = false)) super.increaseDeviceVolume(flags)
    }

    @Deprecated("Media3 keeps the variant without flags")
    override fun decreaseDeviceVolume() {
        if (!volumes.pressKeys(-1, shown = false)) super.decreaseDeviceVolume()
    }

    override fun decreaseDeviceVolume(flags: Int) {
        if (!volumes.pressKeys(-1, shown = false)) super.decreaseDeviceVolume(flags)
    }

    // The own output has no software volume and does not touch the DAC's mute for a key press.
    @Deprecated("Media3 keeps the variant without flags")
    override fun setDeviceMuted(muted: Boolean) {
        if (reported == null) super.setDeviceMuted(muted)
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        if (reported == null) super.setDeviceMuted(muted, flags)
    }

    private companion object {
        val DEVICE_VOLUME_COMMANDS = intArrayOf(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
    }
}
