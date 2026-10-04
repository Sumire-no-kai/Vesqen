# Usage Ping client contract

Part of #70. UI and server deployment remain separate. The production endpoint is
empty by default: configure `-Pvesqen.usageEndpoint=https://HOST/v1/usage` when the
owner has deployed the service and approved the accompanying public documents.
There is no real service hostname, credential, identifier or new Android dependency.

Collect `VesqenApplication.usageStatistics.snapshot`. The UI must show the initial
explanation and call `completeIntroduction(enabled)` with the user's selection.
The default switch is on outside the consent regions, but no ping is eligible until
this explanation is completed. The settings toggle uses `setEnabled`; an explicit
true selection records consent. Both APIs persist the choice without sending an
immediate request. Debug has `FakeUsageStatistics` for UI development.

The centralized pure region policy covers EU27, Iceland, Liechtenstein, Norway and
the UK. A match in any system locale or the publicly accessible default SIM country
requires consent. SIM identifiers, subscription lists, phone permissions, IP
geolocation and hidden APIs are not used. Unknown countries or multiple active
SIMs whose countries cannot all be observed conservatively require consent.
Switzerland, South Korea, Brazil, California and mainland China are intentionally
not assigned new legal policy; the owner's official-source review in #70 remains
outstanding. State-level California detection cannot be derived from country codes.
A default-on user entering a consent region is returned to the consent gate;
an explicit accepted choice survives restarts. Region inputs remain local.

Scheduling reuses #78's foreground callback, with one durable attempt reservation
per rolling 24 hours. No background wakeup or connectivity retry is scheduled.
An unconfigured endpoint, disabled/pending consent, unavailable preferences or
unvalidated network causes no usage request. Network failures are silent and not
retried that day. The reservation is made before HTTP, so process death or a lost
ack cannot produce a duplicate; failures may undercount weekly/monthly activity.
Withdrawal persists without waiting for a network response; it cannot retract
bytes already sent. No device/install ID or retry/idempotency token is generated.

`firstToday`, `firstThisWeek` (ISO Monday week) and `firstThisMonth` are calculated
locally in UTC from the prior attempt. The only transmitted fields are the schema
version, app version, distribution channel, Android version, manufacturer, model,
ROM build, nullable bit-perfect mixer capability, recent USB-audio observation and
these three booleans. No timestamps, region, SIM country, installer package or
identifiers are sent. Device/model/ROM dimensions are required by #70 and must not
be described as an independent guarantee against fingerprinting.

Mixer capability is a one-shot public API query when a ping is due; unavailable
platform/device data is null, not a fabricated false and never an active/verified
output claim. USB additions are event callbacks, not polling. `recentUsbAudio`
means an audio USB endpoint observed during the app lifetime within seven days;
connections made entirely while the app was stopped are not observable.

All currently defined build types (debug, deviceTest, release, profile) consume
`src/github`, so all contain its single INTERNET declaration. The existing
ACCESS_NETWORK_STATE permission (also contributed by Media3) is explicitly listed
there because usage checks validated connectivity. No phone/location permission is
added. #47 must retain these distribution boundaries when introducing flavors.

The server response may contain `updateManifests.stable` and `.beta`, each the
unchanged #78 manifest schema. The client passes its existing channel's manifest
to `GitHubUpdateRuntime.acceptUsageResponse`. A due usage request suppresses that
foreground's separate updater GET; disabled/not-due statistics leave ordinary
updater checks available. Manual updates remain independent. Failed usage requests
are not retried or silently replaced with another usage request.

See the PR description for proposed public-policy/README/site/release-note edits.
Those documents are not changed by this implementation.
