# Vesqen

[![Android CI](https://github.com/Sumire-no-kai/Vesqen/actions/workflows/android.yml/badge.svg?branch=master)](https://github.com/Sumire-no-kai/Vesqen/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/Sumire-no-kai/Vesqen?include_prereleases&color=7A4F00)](https://github.com/Sumire-no-kai/Vesqen/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](app/build.gradle.kts)
[![License](https://img.shields.io/badge/license-Apache--2.0-536B1E)](LICENSE)

**English** · [简体中文](README.zh-CN.md)

Vesqen is an offline music player for Android. It plays the music stored on your phone and needs no account. While a track plays, Vesqen records the path from the file to your headphones, and the Chain page shows where each value came from and how it was measured.

[Website](https://vesqen.sumirenokai.com) · [Privacy policy](https://vesqen.sumirenokai.com/privacy/) · [Support](https://vesqen.sumirenokai.com/support/)

> **Status:** `1.0.0-beta.1`, the first public beta, is out on [GitHub Releases](https://github.com/Sumire-no-kai/Vesqen/releases/tag/v1.0.0-beta.1). Vesqen isn't on Google Play yet. Read the [known limitations](#known-limitations) before relying on strict USB output.

## What Vesqen does

- **Your library.** Finds music through Android's media library and in folders you add. Browse by song, album, artist, folder, genre or playlist, keep favorites, and edit a queue that is still there next time.
- **Formats.** Lossless FLAC, ALAC, WAV and AIFF, plus MP3, AAC, Ogg Vorbis and Opus, as far as your phone's decoders support them.
- **Background playback** with controls in the notification and on the lock screen.
- **The Chain page** shows the source file, the decoder, what AudioTrack and Android's mixer report, and the output route. Each value says whether it was measured, derived or estimated, and when it was observed.
- **Strict USB output** (Android 14 and later) asks Android for its official bit-perfect USB mode when a compatible DAC is connected. If the conditions can't be kept, Vesqen stops playback and tells you why. It doesn't switch to system output on its own.
- **Output verification records** can be imported. `BIT-PERFECT VERIFIED` appears only when a record signed by Vesqen's verification issuer matches the exact app build, phone and ROM, DAC, source format and output format.

## Output states

| State | Meaning |
| --- | --- |
| `SYSTEM MIXED` | Android's normal playback path. Android may mix, resample or apply system effects. |
| `BIT-PERFECT AVAILABLE` | The connected USB DAC advertises a matching bit-perfect mixer profile. Strict output isn't active yet. |
| `BIT-PERFECT REQUESTED` | Vesqen is preparing strict USB output and checking the mixer and the route. |
| `BIT-PERFECT ACTIVE` | Android reports that the AudioTrack, the mixer preference and the route match the file. This is evidence from the Android side, not an external measurement. |
| `BIT-PERFECT VERIFIED` | A signed record from an external measurement matches this exact setup. |
| `STRICT OUTPUT STOPPED` | Strict output couldn't be kept, so playback stopped. |

A state is never promoted to a stronger one. An active path is not called verified, and a lossless file is not called bit-perfect.

## Download

- **GitHub Releases:** each [release](https://github.com/Sumire-no-kai/Vesqen/releases) has a signed APK and its SHA-256. The application signing certificate SHA-256 is
  `74:3E:96:FC:B7:1D:C5:81:88:49:68:19:A0:01:A2:7C:D8:8D:90:91:62:C5:AE:92:9C:87:BF:A2:9A:B8:62:93`.
  You can check it with `apksigner verify --print-certs`. GitHub and Google Play builds use the same signing key, so an update from either source keeps your library.
- **Google Play:** closed testing first. The public listing follows after the test.
- **Requirements:** Android 8.0 or later. Strict USB output needs Android 14 or later and a compatible USB DAC.

## Privacy

Vesqen doesn't request Android's Internet permission, so the app itself can't send or receive data. Your library, playlists and play counts stay in the app's private storage on your phone. The [privacy policy](https://vesqen.sumirenokai.com/privacy/) ([中文](https://vesqen.sumirenokai.com/zh/privacy/)) is also inside the app, under Settings → About.

## Known limitations

- No phone and DAC combination has been verified bit-perfect yet, so no `BIT-PERFECT VERIFIED` record exists.
- Strict USB output hasn't been confirmed with a real DAC yet. Many phones don't offer Android's bit-perfect path, even on Android 14 and later; none of the Android 14+ phones tested so far (vivo and iQOO, on Android 15 and 16) do. On those phones strict mode stops and tells you why. Treat every combination as unverified.
- Android 13 and earlier don't provide the official bit-perfect mixer API, so strict USB output isn't available there.
- If Android stops Vesqen in the background, the queue comes back when you open the app again. Resuming straight from headphone buttons isn't supported yet.
- Long listening sessions, foldables, high refresh rates and complete TalkBack coverage are still being tested.

Each release lists its own limitations in the release notes. Known bugs and the work planned before the stable release are in the [milestone](https://github.com/Sumire-no-kai/Vesqen/milestone/1).

## Feedback and support

- **Questions and problems:** email vesqen@sumirenokai.com, in English or Chinese.
- **Bugs and ideas:** [open an issue](https://github.com/Sumire-no-kai/Vesqen/issues/new/choose). Include the app version, phone model, Android version, DAC or headphones and the file format. For output problems, a screenshot of the Chain page helps. You don't need to turn on any logging.
- **Security issues:** follow [SECURITY.md](SECURITY.md) and please don't report them in public.

## Building from source

You need JDK 25 and Android SDK Platform 36. The Gradle wrapper provides Gradle 9.5.0 and Android Gradle Plugin 9.3.2. `minSdk` is 26, `compileSdk` and `targetSdk` are 36, and the application ID is `io.github.sumirenokai.vesqen`.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows, use `.\gradlew.bat` instead. Debug APKs are written to `app/build/outputs/apk/debug/`.

For previews and scrolling checks, use the Profile build. It isn't debuggable, it uses Release's R8 optimization, and it is signed with the local debug key:

```bash
./gradlew :app:installProfile
```

Keep `app/build/outputs/mapping/<variant>/mapping.txt` with any optimized APK so crash traces can be decoded. Release signing uses the maintainer's keys, which never enter the repository ([release signing](docs/RELEASE_SIGNING.md)). Versions come from [`version.properties`](version.properties) ([versioning](docs/VERSIONING.md)).

## Documentation

- Product: [PRODUCT.md](PRODUCT.md), [requirements](docs/PRD.md), [roadmap](docs/ROADMAP.md)
- Design: [DESIGN.md](DESIGN.md), [visual identity](docs/brand/VISUAL_IDENTITY.md), [redesign direction](docs/redesign/README.md)
- Engineering: [architecture review](docs/ARCHITECTURE_REVIEW.md), [engineering casebook](docs/ENGINEERING_CASEBOOK.md), [development log](docs/DEVELOPMENT_LOG.md)
- Releases: [beta checklist](docs/M4_BETA_RELEASE.md), [launch backlog](docs/LAUNCH_BACKLOG.md), [device acceptance](docs/M4_DEVICE_ACCEPTANCE.md)

## Contributing and license

Read [CONTRIBUTING.md](CONTRIBUTING.md) before proposing changes. Vesqen is developed by Sumire Studio and released under the [Apache License 2.0](LICENSE).
