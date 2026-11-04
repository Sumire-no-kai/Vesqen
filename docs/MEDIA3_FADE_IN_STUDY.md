# Media3's start-up fade-in and strict output claims: a capture test

English · [中文](MEDIA3_FADE_IN_STUDY.zh-CN.md)

2026-11-02. Tested version: Media3 1.11.0, the version Vesqen 1.1.1 uses. The test code is [`Media3PcmCaptureDeviceTest`](../app/src/androidTest/java/io/github/sumirenokai/vesqen/playback/Media3PcmCaptureDeviceTest.kt), merged in [PR #241](https://github.com/Sumire-no-kai/Vesqen/pull/241).

## Abstract

Vesqen's strict USB output aims to deliver the same samples to the DAC as the source file holds. This study uses a capture test to check whether the PCM that Media3 hands to the audio output matches the source. `DefaultAudioSink` applies a linear fade-in to the first 20 ms after every start, seek, re-prepare and format change. All other samples match byte for byte. The fade-in cannot be turned off and cannot be undone at the output. This study compares two ways around it. Both keep the samples intact, and Vesqen chose a strict PCM sink that bypasses `DefaultAudioSink`. These findings stop at Media3's output interface and do not prove what a DAC receives.

## 1. Background

Vesqen separates claims of different strength: a lossless file, system support for direct output, a requested bit-perfect path, and external verification. One condition for strict USB output is that the app's own processing leaves the samples unchanged. The Chain page also reports "Audio unchanged" for Vesqen's part of the path.

That judgment used to rest on configuration: volume at 100%, speed at 1.0, no EQ, and format conversions that keep precision. None of these looks at the samples. An architecture review on 2026-11-02 read the Media3 source and pointed out that `DefaultAudioSink` changes the first samples of its output unconditionally. Nobody had noticed this before, so it needed measuring.

## 2. Questions

1. How does the PCM that Media3 hands to the audio output differ from the source file?
2. Which approach keeps the samples unchanged in strict mode?
3. Can a future Media3 upgrade that changes this behavior be detected?

## 3. Method

**Harness.** A fake audio output replaces the real one. It keeps everything `AudioOutput.write()` receives, and the test compares that with the source PCM byte for byte. Playback uses a real `ExoPlayer` and real decoders. No DAC is needed and nothing is audible. The output's clock is simulated and consumes data at 4x speed.

**Sources.** Fixtures are generated at run time, so the test depends on no external files. They contain full-scale noise from a fixed seed, which allows unambiguous alignment by content. Formats are 16-bit and 24-bit WAV and FLAC at 44.1, 48 and 96 kHz, usually 2 seconds long, plus a 10 ms short track. The test writes FLAC itself using verbatim subframes, with no encoder involved.

**Comparison.** The test locates a window beyond the fade in the source by content, then compares every captured frame. Float samples are scaled back to integers at the source depth. A result that is not an integer is counted separately as off the integer lattice.

**Three modes.**

- Baseline: the `DefaultAudioSink` the app uses today.
- Option A: wrap `DefaultAudioSink` and feed it 20 ms of digital silence before each stretch of output, so that the fade only affects silence.
- Option B: a strict PCM sink that writes PCM to the output with no processing.

**Cases.** Whole file, seek, re-prepare, same-format tracks, tracks of mixed source depth, consecutive format changes, tracks shorter than 20 ms, partial writes, a stalled output, progress after a start and a seek, and sources with an encoder delay.

**Environment.**

| Device | System | FLAC decoder |
| --- | --- | --- |
| Emulator | Android 16 (API 36) | `c2.android.flac.decoder` |
| Honor STF-AL00 | Android 9 (API 28) | `OMX.google.flac.decoder` |

## 4. Results

### 4.1 Baseline: the first 20 ms change

The table shows whole-file playback on the emulator. Seek, re-prepare and format changes give the same result: the first 20 ms of each new stretch of output change.

| Source | Format at the output | Frames changed | Samples off the integer lattice |
| --- | --- | --- | --- |
| WAV, 44.1 kHz, 16-bit | int16 | 882 | 0 / 1764 |
| FLAC, 44.1 kHz, 16-bit | float | 882 | 1739 / 1764 |
| WAV or FLAC, 48 kHz, 24-bit | float | 960 | 1561 / 1920 |
| WAV or FLAC, 96 kHz, 24-bit | float | 1920 | 3104 / 3840 |

The number of changed frames is exactly 20 ms. The first frame becomes zero, and everything after 20 ms matches. This agrees with [the source](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1915-L1931): `maybeRampUpVolume()` only checks for PCM and the number of frames written, and that count restarts with each new output. The `DefaultAudioSink` builder has no option to turn it off. Media3 1.11.1 and the current main branch behave the same way, so upgrading does not help.

Same-format tracks reuse the output, with no fade at the boundary and no lost frames. The same holds for tracks of different source depth that decode to float at one sample rate.

### 4.2 An integer check cannot catch this

The faded samples of a 16-bit WAV are still valid integers, and all 1764 pass an integer check. Requiring an integer result is therefore necessary but not sufficient. Vesqen's earlier architecture relied on that check and assumed that replacing `AudioOutput` alone would be enough. Neither holds.

### 4.3 Comparing the options

Both options passed every byte comparison. In strict mode, every case matched the source from the first frame, no frames were lost at the end of the stream, and the reported progress followed the source. They differ in the following ways:

| Aspect | Option A: silence pre-roll | Option B: strict PCM sink |
| --- | --- | --- |
| What reaches the output | An extra 20 ms of silence at each start, seek and format change | Source data only |
| Stalled output | The output is rebuilt and real samples are faded (960 frames at 48 kHz) | Does not use this path |
| Encoder delay | Trimming removes silence, leaving the source's delay frames in the output | Does not trim |
| Clock and end of stream | Media3 handles them; a wrapper must map progress | Implemented by the sink |
| Depends on | Media3 internals, whose changes the compiler cannot flag | The public `AudioSink` interface |
| Prototype size | 65 lines | 184 lines, plus an outer sink that selects by mode |

Option A cannot keep encoder-delay trimming correct, so both options now reject such sources. WAV and FLAC are unaffected. ALAC has not yet been checked with real files.

### 4.4 Android 9

On Android 9, 24-bit FLAC reaches the output with only 16 bits. To avoid the crash in [#107](https://github.com/Sumire-no-kai/Vesqen/issues/107), Vesqen does not ask OMX decoders for float output, and they deliver 16-bit PCM. The low bits are gone before the audio reaches the output, so strict output rejects this case. On the Honor these cases are logged and skip the byte comparison, so the 24-bit FLAC comparison comes from the emulator.

### 4.5 Runs

| Version | Device | Cases | Runs | Result |
| --- | --- | --- | --- | --- |
| Comparison (baseline, A, B) | Emulator | 36 | 5 | All passed, 1 skipped by design |
| Comparison (baseline, A, B) | Honor | 36 | 2 | All passed, 1 skipped by design |
| Merged (baseline, B) | Emulator | 26 | 2 | All passed, 2 skipped by design |
| Merged (baseline, B) | Honor | 26 | 1 | All passed, 2 skipped by design |
| After tightening the assertions (baseline, B) | Emulator | 36 | 3 | All passed, 6 skipped by design |

The skipped cases apply to strict mode only, or need no playback and run once. After tightening the assertions, the suite was not rerun on the Honor.

## 5. Discussion

**What this means for released versions.** The Chain page in Vesqen 1.1.1 says "Audio unchanged". That judgment waits for a settling period, so the fade has usually passed by the time it appears, but the wording does not say so. `BIT-PERFECT ACTIVE` on the official strict USB path is affected too. None of the test phones opens the official path, so there is no device evidence for that part. The plan is to correct the wording in 1.2.0 ([#242](https://github.com/Sumire-no-kai/Vesqen/issues/242)) and fix strict mode in 2.0. Normal output keeps `DefaultAudioSink` and its fade, and its wording has to say so.

**Why option B.** Option A is shorter, but its correctness depends on how Media3 flushes and trims and on how long the fade lasts, and the compiler reports none of those changes. Option B has more code and has to handle the clock and end of stream itself. In return, Media3's processing never touches the samples in strict mode and nothing extra reaches the output.

**About Media3.** This study does not consider the fade a defect in Media3, which is a general-purpose library that makes no promise of sample identity. It also does not assess how the fade sounds in normal playback.

**A flaw in the test itself.** The comparison aligns by content, so a capture that lost its first frames could originally pass. Automated code review found this. Whole-source captures must now start at the first frame, with a regression test. A later independent review found two loose assertions: the end-of-stream check had only an upper bound on the position, and the baseline only limited the fade to at most 20 ms, so a 15 ms fade would also pass. Both are now tightened, with regression tests. Separately, a progress case was sensitive to timing under the 4x simulated clock and failed occasionally; it now freezes the clock at its sample point.

## 6. Limitations

- The test stops at `AudioOutput.write()` and does not show what a DAC receives. Real sample-by-sample proof needs external digital capture ([#49](https://github.com/Sumire-no-kai/Vesqen/issues/49)), and no combination has reached `BIT-PERFECT VERIFIED` yet.
- The output's clock is simulated. Timing, underruns and feedback on real USB are for M7-2.
- Only two devices ran the test, and the emulator stands for AOSP decoders. The iQOO, other vendors' decoders and ALAC were not tested.
- Option B is a prototype in debug code. Only the capture test and the M7-2 validation use it, and release builds do not contain it.
- The test needs a device. CI compiles it but does not run it.

## 7. Conclusions

1. `DefaultAudioSink` in Media3 1.11.0 changes the first 20 ms after a start, seek, re-prepare or format change. All other samples stay the same.
2. This cannot be turned off or undone at the output, so strict mode has to deal with it at the sink. A strict PCM sink keeps the samples intact from the first frame, and Vesqen chose this approach.
3. Checking configuration and the integer lattice is not enough to support a claim such as "Audio unchanged". The samples have to be compared directly.

The test stays in the repository. Run it by hand on a device when upgrading Media3. If the fade changes, the baseline cases fail.

## 8. Reproducing

You need a connected device or emulator and JDK 25.

```bash
./gradlew :app:assembleGithubDeviceTest :app:assembleGithubDeviceTestAndroidTest
```

```bash
adb install -r -t app/build/outputs/apk/github/deviceTest/app-github-deviceTest.apk
```

```bash
adb install -r -t app/build/outputs/apk/androidTest/github/deviceTest/app-github-deviceTest-androidTest.apk
```

```bash
adb shell am instrument -w -r -e class io.github.sumirenokai.vesqen.playback.Media3PcmCaptureDeviceTest io.github.sumirenokai.vesqen.devicetest.test/androidx.test.runner.AndroidJUnitRunner
```

Detailed numbers for each case are in logcat under the tag `Media3PcmCapture`. On ROMs that freeze processes with no UI, use `tools/run_device_tests.py --foreground-host --host-launch each-case`.

## References

- [`DefaultAudioSink.maybeRampUpVolume()`, Media3 1.11.0](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1915-L1931)
- [M7 architecture](M7_USB_ARCHITECTURE.md), [review](M7_USB_ARCHITECTURE_REVIEW.md), [second review](M7_USB_ARCHITECTURE_REVIEW_2.md)
- [#71 Vesqen's own USB output](https://github.com/Sumire-no-kai/Vesqen/issues/71), [#49 external verification](https://github.com/Sumire-no-kai/Vesqen/issues/49), [#242 Chain wording](https://github.com/Sumire-no-kai/Vesqen/issues/242)
