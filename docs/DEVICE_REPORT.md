# Device Report data contract

Part of #69. The settings/preview UI is intentionally a separate integration. No
network transport is enabled; #70 can implement `DeviceReportUploader` later.

## UI integration

Obtain `VesqenApplication.deviceReporter`, collect its `snapshot`, and update
`DeviceReportOptions`. Basic information is mandatory. Every optional group and
`includeFileNames` defaults to false. Filenames apply only to failing-track formats.

`generate()` captures the selected inputs once and produces `Preview(report)`.
Display the **complete** `report.previewText`. Only after the user confirms, call
`send(SHARE)`, `send(EMAIL)` or, when implemented, `send(UPLOAD)`. The uploader and
share file receive the same immutable artifact; sending never captures again.
Changing options or discarding invalidates the preview and cancels pending work.
A delivery failure retains the artifact for retry; errors are reason codes for UI
localization. Success moves to `Sent(report, delivery)`, from which the same report
can be sent again. Successful sharing means Android accepted the chooser request,
not that an email or upload was delivered. When no app can take the report, the
reason is `NO_SHARE_APPLICATION`.

`FakeDeviceReporter` and `FakeDeviceReportUploader` in `src/debug` allow controlled
loading, preview, sending and error states without sampling, storage or network.

## Local error history

All builds record only player error codes, strict-output failure/origin codes and
Android historical **failure** exits. No exceptions, stack traces, exit description,
process name, media ID, title, library list or listening history is stored.
An error may retain reviewed source-format fields and a basename for an explicit
filename opt-in during export. URI display-name lookup runs on IO and its URI is
never serialized. This is separate from Debug diagnostic recording; its existing
Release guard remains unchanged.

The journal lives under the app's private `noBackupFilesDir`. It retains at most
100 events, seven days, and 256 KiB. Retention runs on startup, reads and writes;
there is no periodic background job while the app is inactive. Writes use atomic
replacement, synced to disk before the rename. A bounded queue avoids disk IO in
playback callbacks. Queue overflow and storage failures appear as availability
codes in reports; storage availability recovers once writes work again. A journal
that cannot be decoded (truncated, or written by another app version) is discarded
so history keeps working, and the report says `JOURNAL_RESET` for that run.
Historical exits are deduplicated by timestamp and reason; Android versions before
30 report unsupported history. Normal user stops (including a strict session ending
with `SERVICE_STOPPED`), successful/self exits, and low-memory or signal kills of a
background process are excluded. Android may omit exits.

Strict failures use the failure snapshot's own source format. A reliable filename,
container or codec is not available there; these remain absent rather than being
attributed to a later current track. Format fields describe library metadata or
the strict request, **not** independently verified decoded/output samples. Library
formats are exported as `ESTIMATED` (cached metadata, as in Audio Proof); a strict
request's format is `MEASURED` because Vesqen itself asked Android for it.

## Privacy and evidence

`DeviceReportPrivacy` is the persistence/export boundary. It allowlists format,
route and encoding values from `DeviceReportVocabulary`, which takes them from the
producers where code defines them (format matrix, route and declaration enums),
strips directory components from opted-in basenames, and rejects MAC addresses,
encoded paths and control characters. An opted-in basename is also withheld when it
contains a connected Bluetooth device's name. Raw telemetry
text, source details, unavailability details, device names/keys and recent event
history are never copied into the report. Unsupported text is explicitly redacted.
Basic Build strings are checked separately; filtered values are null. They are not
cross-checked against route names: the built-in speaker route is named after the
phone model. USB inventory
keeps numeric capabilities and per-report ordinals, without device identifiers or
names. Filenames are never emitted when their option is off.

Telemetry confidence, timestamps, known provenance IDs, dependency IDs, numeric
operands, derivation windows and unavailable reasons survive export. New unknown
provenance/calculation labels are marked redacted until reviewed. The report makes
no new output declaration and does not promote support/request evidence to active
or externally verified bit-perfect output.

Only an explicitly requested capability/chain report opens a telemetry observer;
it closes at the first snapshot whose rates have warmed up, or after five seconds
with the latest snapshot. Timeout is reported
as `TEMPORARILY_UNAVAILABLE`. Error-only/basic reports do not start sampling.

Sharing uses a read-only FileProvider URI restricted to `cache/device-reports/`.
It cannot expose the private error journal. Email pre-fills
`vesqen@sumirenokai.com`. Cache files are bounded to three; old files are pruned
on the next share after 24 hours. The receiving app controls delivery. Android
share/email compatibility and historical exit behavior require owner-scheduled
device acceptance; compiling the integration tests does not establish that.

## Server contract

`contracts/device-report/vocabulary.json` (enums, allowed text values, the metric
catalog and limits) and `sample-report.json` (one of each group and evidence kind)
are generated from the app. `DeviceReportContractTest` fails when either differs
from the code and writes the current output under `app/build/contracts/` for review.
The report server validates against these files instead of its own copies.

## Before release

The published privacy policy says public builds record and export no diagnostics.
This journal records error events in every build from the moment it ships, so the
policy (in the app and on the website) must describe it, the report's contents,
upload retention and the recipient before a build with it is released.

