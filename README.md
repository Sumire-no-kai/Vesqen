# Vesqen

Vesqen is a lightweight, offline-first Android player for local lossless audio. Its defining goal is to expose an auditable playback chain and use Android's official USB bit-perfect path only when the device, ROM, DAC, and source format genuinely support it.

The repository currently contains the M0 Android/Compose scaffold. Playback features are not implemented yet; see the [product requirements](docs/PRD.md) and [roadmap](docs/ROADMAP.md) for the verified scope.

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
