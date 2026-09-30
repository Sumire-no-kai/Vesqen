# Contributing to Vesqen

Thank you for helping improve Vesqen. Issues and pull requests can be written in English or Chinese.

## Reporting a problem

Use the [issue forms](https://github.com/Sumire-no-kai/Vesqen/issues/new/choose). A useful report names:

- the app version (Settings → About) and where you installed it (GitHub, Google Play or your own build);
- the phone model and Android version;
- the output: phone speaker, wired headphones, Bluetooth, or a USB DAC and its model;
- the file format, for example FLAC 24-bit / 96 kHz;
- what happened, what you expected, and the steps to reproduce it. For output problems, add a screenshot of the Chain page.

You don't need to enable any logging. Don't attach music files, and remove personal information, file paths and device serial numbers from screenshots. Report security problems privately as described in [SECURITY.md](SECURITY.md).

## Before coding

1. Read [`docs/PRD.md`](docs/PRD.md) and keep the stated first-release scope and non-goals intact.
2. Open an issue before large architectural work, new codecs, DSP, native code, network features, or changes to bit-perfect claims.
3. Keep experimental engines and AI features optional and outside the core playback path.
4. The core app stays offline: don't add the `INTERNET` permission, analytics, crash reporting or accounts.

## Local checks

Use JDK 25 and Android SDK Platform 36. Before submitting a change, run:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows, use `.\gradlew.bat`. Add unit tests for core logic and instrumentation tests for Android integration when practical. Device-dependent audio claims must name the app version, phone, ROM or build, DAC, source format and verification method.

## Changes and pull requests

- Branch from `master`, or from the current `release/*` branch for a fix that must ship in that release. Name the branch by its purpose with a prefix such as `feature/`, `fix/`, `docs/`, `refactor/` or `test/`.
- Keep commits focused and describe observable behavior.
- Don't commit `local.properties`, IDE state, SDKs, build outputs, music files, signing keys, device identifiers or private filesystem paths.
- Keep the evidence levels separate: `SYSTEM MIXED`, `DIRECT SUPPORTED`, `BIT-PERFECT AVAILABLE`, `BIT-PERFECT REQUESTED`, `BIT-PERFECT ACTIVE` and `BIT-PERFECT VERIFIED`. No change may present a weaker level as a stronger one.
- In the pull request, explain the test coverage and any device-dependent behavior you couldn't verify.

Releases, tags and signing are handled by the maintainer ([beta checklist](docs/M4_BETA_RELEASE.md), [release signing](docs/RELEASE_SIGNING.md)).

By contributing, you agree that your contribution is licensed under Apache-2.0.
