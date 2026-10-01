# Vesqen Audio Proof

Audio Proof is Vesqen's vocabulary for describing what is known about a playback chain without promoting capability or source metadata into stronger output claims.

## Language

**Source Fact**:
A property read from the selected audio source, such as its container, codec, sample rate, bit depth, channel count, or file size. It says nothing by itself about decoder output or the final device output.
_Avoid_: Output format, playback quality

**Decoder Fact**:
An observation about the decoder identity, execution path, or decoded PCM produced for the current playback session.
_Avoid_: Source format, final output

**Processing Fact**:
The known state of any operation that can alter decoded PCM, including gain, resampling, mixing, volume scaling, or DSP.
_Avoid_: Effect label without state

**Observable Output**:
What Vesqen can observe about the current Android route, requested format, output device, or mixer state. It is not proof of the samples received by the DAC.
_Avoid_: Actual output, bit-perfect output

**Output Declaration**:
A listener-facing claim whose strength is limited by the available evidence, from `SYSTEM MIXED` through device-specific `BIT-PERFECT VERIFIED`.
_Avoid_: Quality badge, Hi-Res badge

**Evidence Confidence**:
The provenance class attached to one fact: `MEASURED`, `DERIVED`, `ESTIMATED`, or `UNAVAILABLE`. Confidence describes how the value is known, not whether the value is desirable.
_Avoid_: Accuracy score, quality level

**Telemetry Snapshot**:
A time-aligned view of the facts and recent events known for one playback session. Every included fact retains its confidence, source, and observation time.
_Avoid_: Player state, device specification

**Diagnostic Recording**:
A developer-initiated, bounded and private history of Audio Proof snapshots and events prepared for local inspection or privacy-cleaned export. It exists only in Debug/Internal builds; public Release builds provide no recording control. From beta.2 they keep only the bounded error history behind a Device Report.
_Avoid_: Analytics, background telemetry

**Chain**:
The listener-facing Audio Proof view that progressively discloses source, decoder, processing, route, USB, and stability evidence.
_Avoid_: Link, output selector

**Device Report**:
A user-initiated, previewed export of device and audio facts, built from the sections the user selects: basic app and device info (always included), audio output capability, the current Chain evidence, a bounded history of recent errors and Android exit reasons, and the format of failing tracks. File names are excluded unless the user opts in. It is uploaded or shared only after the user confirms. It never contains the library or listening history.
_Avoid_: Crash log, bug dump

**Usage Ping**:
An anonymous request sent at most once a day while usage statistics are on. It carries the app version and channel, the device and ROM, bit-perfect support, recent USB audio use, and whether this is the first ping of the day, week or month. It has no device or install identifier. The server keeps only daily aggregates and never stores the IP address.
_Avoid_: Tracking, analytics ID

