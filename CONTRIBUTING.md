# Contributing to Vesqen

Thank you for helping improve Vesqen.

## Before coding

1. Read `docs/PRD.md` and keep the stated first-release scope and non-goals intact.
2. Open an issue before large architectural work, new codecs, DSP, native code, network features, or changes to bit-perfect claims.
3. Keep experimental engines and AI features optional and outside the core playback path.

## Local checks

Use JDK 21 and Android SDK Platform 36. Before submitting a change, run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Add unit tests for core logic and instrumentation tests for Android integration when practical. Device-dependent audio claims must name the app version, phone, ROM/build, DAC, source format, and verification method.

## Changes and pull requests

- Keep commits focused and describe observable behavior.
- Do not commit `local.properties`, IDE state, SDKs, build outputs, music files, device identifiers, or private filesystem paths.
- Treat `SYSTEM MIXED`, `DIRECT SUPPORTED`, `BIT-PERFECT AVAILABLE`, `BIT-PERFECT ACTIVE`, and `BIT-PERFECT VERIFIED` as separate evidence levels.
- Explain test coverage and any unverified device-dependent behavior in the pull request.

By contributing, you agree that your contribution is licensed under Apache-2.0.
