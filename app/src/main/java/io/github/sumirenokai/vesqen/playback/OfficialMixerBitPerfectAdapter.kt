package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

internal interface MixerBitPerfectAdapter {
    fun profiles(device: AudioDeviceInfo): Result<List<MixerProfile>>

    fun setPreferred(device: AudioDeviceInfo, format: PlatformPcmFormat): Result<Boolean>

    fun isPreferred(device: AudioDeviceInfo, format: PlatformPcmFormat): Result<Boolean>

    fun clearPreferred(device: AudioDeviceInfo): Result<Boolean>
}

internal object MixerBitPerfectAdapterFactory {
    fun create(context: Context): MixerBitPerfectAdapter =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            OfficialMixerBitPerfectAdapter(context)
        } else {
            UnsupportedMixerBitPerfectAdapter
        }
}

private object UnsupportedMixerBitPerfectAdapter : MixerBitPerfectAdapter {
    private fun <T> unsupported(): Result<T> = Result.failure(
        UnsupportedOperationException("Mixer attributes require Android 14 or newer"),
    )

    override fun profiles(device: AudioDeviceInfo): Result<List<MixerProfile>> = unsupported()

    override fun setPreferred(device: AudioDeviceInfo, format: PlatformPcmFormat): Result<Boolean> = unsupported()

    override fun isPreferred(device: AudioDeviceInfo, format: PlatformPcmFormat): Result<Boolean> = unsupported()

    override fun clearPreferred(device: AudioDeviceInfo): Result<Boolean> = unsupported()
}

/** The only class that links against API 34 AudioMixerAttributes. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class OfficialMixerBitPerfectAdapter(context: Context) : MixerBitPerfectAdapter {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun profiles(device: AudioDeviceInfo): Result<List<MixerProfile>> = runCatching {
        audioManager.getSupportedMixerAttributes(device).map { attributes ->
            MixerProfile(
                format = attributes.format.toPlatformFormat(),
                bitPerfect = attributes.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
            )
        }
    }

    override fun setPreferred(device: AudioDeviceInfo, format: PlatformPcmFormat): Result<Boolean> = runCatching {
        val attributes = findBitPerfectAttributes(device, format) ?: return@runCatching false
        audioManager.setPreferredMixerAttributes(MEDIA_ATTRIBUTES, device, attributes)
    }

    override fun isPreferred(device: AudioDeviceInfo, format: PlatformPcmFormat): Result<Boolean> = runCatching {
        val preferred = audioManager.getPreferredMixerAttributes(MEDIA_ATTRIBUTES, device)
        preferred?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            preferred.format.toPlatformFormat() == format
    }

    override fun clearPreferred(device: AudioDeviceInfo): Result<Boolean> = runCatching {
        val preferred = audioManager.getPreferredMixerAttributes(MEDIA_ATTRIBUTES, device)
        preferred == null || audioManager.clearPreferredMixerAttributes(MEDIA_ATTRIBUTES, device)
    }

    private fun findBitPerfectAttributes(
        device: AudioDeviceInfo,
        format: PlatformPcmFormat,
    ): AudioMixerAttributes? = audioManager.getSupportedMixerAttributes(device).firstOrNull { attributes ->
        attributes.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            attributes.format.toPlatformFormat() == format
    }

    private fun AudioFormat.toPlatformFormat() = PlatformPcmFormat(
        sampleRateHz = sampleRate,
        encoding = encoding,
        channelMask = channelMask,
        channelCount = channelCount,
    )

    private companion object {
        val MEDIA_ATTRIBUTES = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
