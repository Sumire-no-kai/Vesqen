# Direct USB output

This guide describes the M7 development builds. Direct USB output remains disabled by default in Release builds until the 2.0 rollout. Device results are recorded in [M7 device acceptance](M7_DEVICE_ACCEPTANCE.md).

## Starting playback

Choose Strict USB output in Settings. On a phone without Android's official bit-perfect channel, an enabled build can use Vesqen's own USB output. Before its first playback, Vesqen explains exclusive access, volume control and USB permission. Cancelling keeps playback stopped. The explanation remains available under Settings → Playback output → Direct USB output.

While Vesqen holds the DAC, other apps and notification sounds may be inaudible. Ringtone and alarm routing depends on the phone and still needs device testing. Pausing eventually releases the DAC according to the pause policy; choosing System output requests its release immediately. A failed release keeps playback stopped and requires recovery before another takeover.

Android asks for access to the connected USB device. A DAC with microphone input may require permission after every replug. Disabling microphone access in Android can prevent USB permission. Vesqen does not request microphone permission. Android's default-application choice, when available, can also open Vesqen when a DAC is connected while Strict USB output is selected.

## Volume

Start with the DAC or amplifier volume low. When the selected path has writable master volume, Vesqen sets and reads back its starting volume before sending audio. The Now slider follows the DAC's range and steps. A replug uses the lower of the remembered setting and approximately −30 dB, bounded by the device's range; this is not a guarantee of a safe listening level for every amplifier or headphone.

A path without writable volume requires a separate full-scale digital-output confirmation for that DAC model. Until confirmed, no audio interface is claimed and no stream starts. Volume must then be adjusted on the DAC or amplifier. Vesqen hides the slider and reports fixed volume to Android; it does not substitute software volume or unmute the DAC. A device that advertises writable master volume but fails its read, write or read-back checks is stopped rather than treated as fixed volume. Per-channel-only volume controls remain unsupported.

On the tested iQOO 9, foreground and background volume keys work with writable hardware volume while the screen is on. Screen-off keys did not work in the recorded test. Other phones and DACs require their own checks. The optional screen-off keep-alive track and the manufacturer's background-power setting are described in Settings; the keep-alive option is off by default and may produce noise from the phone speaker.

## Reading the chain and failures

Now and Chain show the format sent to the DAC. The continuity card shows underruns, failed packets and lost frames. Unavailable counters remain unavailable. USB delivery counters describe delivery to the USB controller, not the time a DAC presents an analog sample.

An already configured output waiting for delivery or paused is described as waiting, rather than as a new takeover. A failure names the observed stage, including permission, interface/format selection, volume, sample-rate setup, clock feedback or transfer. An unknown stage is explicitly identified as unknown. Playback stops without automatically changing to System output; retry or select System output yourself.

`BIT-PERFECT ACTIVE` describes the configured path and delivery evidence. It does not prove an uninterrupted sample-perfect stream. `BIT-PERFECT VERIFIED` requires an independently captured, signed external comparison.

## Local records

Vesqen keeps the first-use confirmation, per-model hardware volume and fixed-volume confirmation locally. A recovery marker records the temporary connection ID and the original volume/mute settings. The connection ID is not a serial number. These records, wire readings and USB transfer counters are excluded from reports and diagnostic exports. See the [privacy policy](PRIVACY_POLICY.md).
