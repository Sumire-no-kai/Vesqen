# Device Report data contract

Part of #69. The settings/preview UI is intentionally a separate integration. No
automatic network transport is enabled. GitHub builds provide the explicit HTTPS
uploader described below.

## UI integration

Obtain `VesqenApplication.deviceReporter`, collect its `snapshot`, and update
`DeviceReportOptions`. Basic information is mandatory. Every optional group and
`includeFileNames` defaults to false. Filenames apply only to failing-track formats.

`generate()` captures the selected inputs once and produces `Preview(report)`.
Display the **complete** `report.previewText`. Only after the user confirms, call
`send(SHARE)`, `send(EMAIL)` or `send(UPLOAD)`. The uploader and
share file receive the same immutable artifact; sending never captures again.
Changing options or discarding invalidates the preview and cancels pending work.
A delivery failure retains the artifact for retry; errors are reason codes for UI
localization. Success moves to `Sent(report, delivery, reportId)`, from which the same report
can be sent again. `reportId` is the server receipt for an upload and null for
share/email. `DeviceReportUploader.upload` now returns `DeviceReportUploadResult`
(`Uploaded(reportId)` or `Failed(reason)`); custom implementations must adopt that
result. The Debug uploader exposes a configurable receipt and failure.
Successful sharing means Android accepted the chooser request,
not that an email or upload was delivered. When no app can take the report, the
reason is `NO_SHARE_APPLICATION`.

`FakeDeviceReporter` and `FakeDeviceReportUploader` in `src/debug` allow controlled
loading, preview, sending and error states without sampling, storage or network.

## GitHub upload

`HttpsDeviceReportUploader` lives in `src/github`. It uses the existing
`vesqen.usageEndpoint` Gradle property (`BuildConfig.USAGE_ENDPOINT`), replacing
`/v1/usage` with `/v1/reports` on the same HTTPS host and port. There is no default
production host. Empty configuration returns `UPLOAD_NOT_CONFIGURED` without
opening a connection. The configuration declaration is identical to #84, so both
features share one property when integrated. All current build types include the
GitHub source set; #47 will split it into a flavor. The existing GitHub INTERNET
permission is reused, without another manifest declaration.

Only `send(UPLOAD)` after a preview calls this uploader. It does not depend on the
usage statistics switch, run on startup, schedule background work, or retry on its
own. It writes exactly `DeviceReportArtifact.copyBytes()` with fixed-length POST,
Content-Type `application/json`, a maximum of 256 KiB, redirects disabled and
5-second connection/read timeouts. Network work runs on Dispatchers.IO; TLS uses
the platform defaults. Those timeouts are socket timeouts, not a total operation
deadline. A cancelled preview cannot receive a late result, but cancellation
cannot retract bytes already sent to the server.

The #70 Worker in `server/usage` accepts `POST /v1/reports` and responds with
HTTP 201 and `{"reportId":"<UUID>"}`. Receipts are bounded to 4 KiB and accepted
only in this exact single-field JSON shape (with optional JSON whitespace), with
a canonical UUID string. Other statuses, redirects, oversized requests/responses,
malformed receipts, IO failures and SecurityException map to `UPLOAD_FAILED`;
no server response text or exception message reaches the UI. These failures keep
the same artifact for an explicit retry. A timeout after server acceptance can
therefore create a second report if the user retries; the service has no upload
idempotency contract. The receipt identifies a report, not a device or install.

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

## App segment assessment (#72)

Selecting **Chain evidence** also includes the optional top-level `appSegment`.
Capability-only reports omit it. The generator calls the pure `chain.assessAppSegment`
on the exact captured snapshot, with `now` equal to its captured monotonic time
(and maximum snapshot age zero). It records the verdict at capture, not at preview
or send time, and does not open another observer. Latched metric timestamps keep
their original meaning; they are not refreshed or treated as snapshot age.

`appSegment` contains `status`, `sourceCompression`, `route`, all `checks`
(`condition`, `status`, nullable `reason`, `metricIds`, and `issues` with `metricId`
and `reason`), `routeMetricIds`, `routeIssues`, and nullable `bluetooth` with its
second-segment `status`. `sourceCompressionMetricId` identifies `decoder.input_mime`.
Metric references point into `chainEvidence`, where values, confidence, provenance,
observation times and unavailable reasons remain intact. Missing metrics are named
by `MISSING` issues; no placeholder values are invented. A missing compression
metric still has its ID and an `UNKNOWN` compression result.

If capture produces no snapshot, the report still includes `appSegment`: every
condition is `UNKNOWN` with `MISSING`, compression and route are `UNKNOWN`, and the
route issue names its missing metric. `chainEvidence.unavailableReason` preserves
the capture failure. Recent transition events become a `TRANSITION_IN_PROGRESS`
reason only; event details are not exported. All new values are enum codes or metric
IDs, never free text, names or paths. The assessment does not change
`route.output_declaration`, infer bit-perfect output, or describe the entire chain
as lossless. Bluetooth retains `LOSSY_OR_UNKNOWN_DEPENDING_ON_CODEC`.

## Server contract

`contracts/device-report/vocabulary.json` (enums, allowed text values, the metric
catalog and limits) and `sample-report.json` (one of each group and evidence kind)
are generated from the app. App-segment enum lists come directly from the production
assessment enums via `DeviceReportVocabulary`; the sample includes Bluetooth and a
reasoned `UNKNOWN` transition condition. `DeviceReportContractTest` fails when either differs
from the code and writes the current output under `app/build/contracts/` for review.
The report server validates against these files instead of its own copies.

## Before release

The published privacy policy says public builds record and export no diagnostics.
This journal records error events in every build from the moment it ships, so the
policy (in the app and on the website) must describe it, the report's contents,
upload retention and the recipient before a build with it is released.

