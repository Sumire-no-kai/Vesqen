# Vesqen

Vesqen is a lightweight, offline-first Android player for local lossless audio. Its defining goal is to expose an auditable playback chain and use Android's official USB bit-perfect path only when the device, ROM, DAC, and source format genuinely support it.

The repository now contains the M1 local-player implementation candidate: MediaStore and persistently authorised multi-folder SAF discovery, a private incremental catalog, songs/albums/artists/folders/genres browsing, search and listening history, playlists, an editable persistent queue, rich local metadata, Media3 background playback, selected-system-route observation, and the adaptive `Library / Now / Chain` shell. M1 is not accepted until the connected runner, real format fixtures, endurance, route-disconnect and accessibility/device matrix in the [M1 device acceptance gate](docs/M1_DEVICE_ACCEPTANCE.md) pass. USB direct/bit-perfect output and Audio Proof remain later milestones; see the [product requirements](docs/PRD.md), [roadmap](docs/ROADMAP.md), and [development log](docs/DEVELOPMENT_LOG.md) for the evidence boundary.

The formal Vesqen visual baseline is documented in [DESIGN.md](DESIGN.md) and the [visual identity guide](docs/brand/VISUAL_IDENTITY.md). Its Twin Paths mark, adaptive launcher icon, light/dark palette, and component tokens are versioned with the application instead of being maintained as detached mockups.

The installable app version is managed from the repository-level [`version.properties`](version.properties). See the [versioning guide](docs/VERSIONING.md) before preparing a release.

## Development baseline

- Android 8.0+ (`minSdk 26`)
- `compileSdk 36` and `targetSdk 36`
- JDK 21
- Android Gradle Plugin 9.3.2 and Gradle 9.5.0 (via the wrapper)
- Application ID and namespace: `io.github.sumirenokai.vesqen`

Install JDK 21 and Android SDK Platform 36, then create `local.properties` through Android Studio or set `ANDROID_HOME`/`ANDROID_SDK_ROOT`.

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

On macOS or Linux:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Generated debug APKs are written under `app/build/outputs/apk/debug/`.

## Product boundaries

Vesqen distinguishes lossless source files, Android direct-playback support, an active bit-perfect request, and externally verified bit-perfect output. No implementation or UI may promote one evidence level into another. Core playback remains offline and does not require an account or network permission.

See [CONTRIBUTING.md](CONTRIBUTING.md) before proposing changes and [SECURITY.md](SECURITY.md) for private vulnerability reporting guidance.

## License

Apache License 2.0. See [LICENSE](LICENSE).
