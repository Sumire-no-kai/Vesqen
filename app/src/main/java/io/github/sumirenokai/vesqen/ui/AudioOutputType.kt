package io.github.sumirenokai.vesqen.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

enum class AudioOutputType {
    PHONE_SPEAKER,
    WIRED_OR_USB,
    BLUETOOTH,
    OTHER,
}

class ConnectedAudioOutputs(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun read(): Set<AudioOutputType> = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .mapTo(linkedSetOf()) { device -> device.type.toOutputType() }

    private fun Int.toOutputType(): AudioOutputType = when (this) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        -> AudioOutputType.PHONE_SPEAKER

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> AudioOutputType.WIRED_OR_USB

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        -> AudioOutputType.BLUETOOTH

        else -> AudioOutputType.OTHER
    }
}
