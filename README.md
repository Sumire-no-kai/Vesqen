# Vesqen

Vesqen is a lightweight, offline-first Android player for local lossless audio. Its defining goal is to expose an auditable playback chain and use Android's official USB bit-perfect path only when the device, ROM, DAC, and source format genuinely support it.

The repository contains implementation candidates for the local player, Audio Proof, Android 14+ official strict USB output, and the M4 signed verification registry. MediaStore and persistently authorised multi-folder SAF discovery, a private incremental catalog, local browsing and metadata, playlists, an editable persistent queue, Media3 background playback, selected-system-route observation, and the adaptive `Library / Now / Settings` shell feed one Chain evidence surface. Strict USB fails closed, and `BIT-PERFECT VERIFIED` is available only when a maintainer-signed record exactly matches the installed APK, phone/ROM, DAC, source, and sink while strict output is active. These implementation candidates do not complete M1–M4: real format fixtures, endurance, accessibility, performance, Android 14+ phone/DAC matrices, and at least one external digital verification remain acceptance gates. See the [product requirements](docs/PRD.md), [roadmap](docs/ROADMAP.md), [M4 device acceptance gate](docs/M4_DEVICE_ACCEPTANCE.md), and [development log](docs/DEVELOPMENT_LOG.md) for the evidence boundary.

The formal Vesqen visual baseline is documented in [DESIGN.md](DESIGN.md) and the [visual identity guide](docs/brand/VISUAL_IDENTITY.md). Its Twin Paths mark, adaptive launcher icon, light/dark palette, and component tokens are versioned with the application instead of being maintained as detached mockups.

The installable app version is managed from the repository-level [`version.properties`](version.properties). See the [versioning guide](docs/VERSIONING.md) before preparing a release.

The [architecture review](docs/ARCHITECTURE_REVIEW.md) records current ownership boundaries, local fixes, and the service-side output contract needed before M3.

## Development baseline

The M4 software candidate adds offline signed verification records, deterministic PCM vectors and comparison tooling, explicit evidence linkage, a repeatable device baseline collector, and a `0.4.0-beta.1` limited-Beta version candidate. Local validation covers 199 JVM tests, 13 Python tool tests, Debug lint, and Debug/Profile/Release/test APK assembly. Honor Android 9 has current ordinary-output and legacy-system evidence; iQOO Android 15 has no-DAC fail-closed, UI/lifecycle, Profile-baseline, and data-retention evidence. Real DAC strict USB, external digital sample comparison, deferred endurance runs, final accessibility coverage, and the M3 Library root-cause gate remain open. Passing software checks or an Android-side `ACTIVE` state does not imply `VERIFIED` or milestone acceptance. See the [M4 acceptance record](docs/M4_DEVICE_ACCEPTANCE.md) and [limited-Beta release checklist](docs/M4_BETA_RELEASE.md).

- Android 8.0+ (`minSdk 26`)
- `compileSdk 36` and `targetSdk 36`
- JDK 21
- Android Gradle Plugin 9.3.2 and Gradle 9.5.0 (via the wrapper)
- Application ID and namespace: `io.github.sumirenokai.vesqen`

Install JDK 21 and Android SDK Platform 36, then create `local.properties` through Android Studio or set `ANDROID_HOME`/`ANDROID_SDK_ROOT`.

On Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleProfile :app:assembleRelease :app:assembleDebugAndroidTest
```

On macOS or Linux:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleProfile :app:assembleRelease :app:assembleDebugAndroidTest
```

Generated debug APKs are written under `app/build/outputs/apk/debug/`.

## Product boundaries

Vesqen distinguishes lossless source files, Android direct-playback support, an active bit-perfect request, and externally verified bit-perfect output. No implementation or UI may promote one evidence level into another. Core playback remains offline and does not require an account or network permission.

See [CONTRIBUTING.md](CONTRIBUTING.md) before proposing changes and [SECURITY.md](SECURITY.md) for private vulnerability reporting guidance.

## License

Apache License 2.0. See [LICENSE](LICENSE).
