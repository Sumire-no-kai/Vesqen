## What changes

<!-- The observable behavior this pull request changes, and the issue it addresses. -->

## How it was tested

- [ ] `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- [ ] Instrumentation tests compiled or run (if Android integration changed)

<!-- Device-dependent audio claims must name the app version, phone, ROM or build, DAC, source format and verification method. List anything you couldn't verify. -->

## Checklist

- [ ] No weaker evidence level is presented as a stronger one (`SYSTEM MIXED` … `BIT-PERFECT VERIFIED`).
- [ ] No `INTERNET` permission, analytics, accounts or network features added to the core app.
- [ ] No `local.properties`, signing keys, music files, device identifiers or private paths committed.
