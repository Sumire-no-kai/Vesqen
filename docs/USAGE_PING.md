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
immediate request. Show the explanation whenever `introductionRequired` is true,
not only on first launch: a region change can reset it, and until it is completed
an enabled toggle sends nothing. Debug has `FakeUsageStatistics` for UI development.

The centralized pure region policy covers EU27, the EU outermost regions and Åland
that carry their own ISO codes (RE, GP, MQ, GF, YT, MF, AX), Iceland, Liechtenstein,
Norway, the UK, mainland China and South Korea (owner decision 2026-10-07 after the
#70 review; Hong Kong, Macao and Taiwan keep their own codes). Its inputs are the system locales, the default subscription's SIM
and network country, and from Android 11 every slot's network country
(`getNetworkCountryIso(slot)`). A match in any of them requires consent; when none
is readable, the user is asked as well. A dual-SIM phone is treated like any other:
owner decision 2026-10-04, since most phones in China and South-East Asia are
dual-SIM. Android 8–10 cannot read the other slot, so there a second SIM is covered
by locale and network only. SIM identifiers, subscription lists, phone permissions,
IP geolocation and hidden APIs are not used.
The #70 review kept Switzerland, Brazil and California default-on: Switzerland
allows it with notice and a way to refuse, Brazil under legitimate interest, and the
CCPA does not apply at this size. State-level California detection cannot be derived
from country codes.
A default-on user entering a consent region is returned to the consent gate;
an explicit accepted choice survives restarts. Region inputs remain local.

Scheduling reuses #78's foreground callback, awaiting preferences off the UI thread
before deciding whether statistics or the updater owns the request, with one durable attempt reservation
per UTC day (the server's day; a rolling 24 hours would skip people who open the app
a little earlier each day). A stamp left a day or more ahead by a wrong clock does
not block. No background wakeup or connectivity retry is scheduled.
An unconfigured endpoint, disabled/pending consent, unavailable preferences or
unvalidated network causes no usage request. Network failures, and the
SecurityException some OEM per-app firewalls raise from DNS, are silent and not
retried that day. The reservation is made before HTTP, so process death or a lost
ack cannot produce a duplicate; failures may undercount weekly/monthly activity.
Withdrawal persists without waiting for a network response; it cannot retract
bytes already sent. No device/install ID or retry/idempotency token is generated.

`firstToday`, `firstThisWeek` (ISO Monday week) and `firstThisMonth` are calculated
locally in UTC from the prior attempt. The only transmitted fields are the schema
version, app version, distribution channel, Android version, manufacturer, model,
ROM build, nullable bit-perfect mixer capability, recent USB-audio observation and
these three booleans. Android version, manufacturer, model and ROM build are reduced
to the server's alphabet (letters, digits, space and `._()+-`, at most 160) and a
self-built ROM's `eng.`/`userdebug.` builder login is dropped. No timestamps,
region, SIM country, installer package or identifiers are sent. Device/model/ROM dimensions are required by #70 and must not
be described as an independent guarantee against fingerprinting.

Mixer capability is a one-shot public API query of connected USB outputs when a ping
is due; with no USB output, or unavailable platform/device data, it is null, not a
fabricated false (the built-in speaker never offers it) and never an active/verified
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
updater checks available. A successful usage response without update data (for
example while the server's relay is not configured) calls
`GitHubUpdateRuntime.checkWithoutUsageResponse`, so the updater checks on its own.
A failed usage request was that day's only request and is not replaced by another
one (#78). Manual updates remain independent.

See the PR description for proposed public-policy/README/site/release-note edits.
Those documents are not changed by this implementation.
