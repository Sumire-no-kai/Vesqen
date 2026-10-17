# Baseline Profile generator

`BaselineProfileGenerator` records which classes and methods Vesqen runs on its hot paths (#50): startup to the Library, flinging a large library, Now with the chain notes, the Chain tab, and Settings with Playback chain, About and Licenses. ART compiles those ahead of time, so the first flings and page transitions no longer wait for the interpreter and JIT.

The result is checked in as `app/src/main/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt`. Both channels and the `profile` build type use it, and R8 lays out the startup classes first in the release DEX (`dexLayoutOptimization`).

When it takes effect:

- Google Play compiles the profile at install time.
- A sideloaded GitHub APK (including the in-app updater) starts uncompiled (`verify`). `androidx.profileinstaller` writes the profile on first launch, and ART compiles it in its next background optimization, usually while the phone is idle and charging.
- Debuggable builds (`debug`, `deviceTest`, "Vesqen Test") are never compiled ahead of time, so the profile does not change them. Judge performance on the `profile` build.

## Regenerate

Regenerate after large UI changes or before a release whose hot paths changed.

1. Use an Android 13+ device that is not shared (a Google APIs emulator works; root is not needed on API 33+). Gradle installs on every connected device, so set `ANDROID_SERIAL` to that one.
2. Put a large library on it and let MediaStore scan it:

   ```bash
   python3 -I tools/generate_scroll_library.py /tmp/vesqen-scroll
   python3 -I tools/demo_library/flacgen.py /tmp/vesqen-demo tools/demo_library
   adb push /tmp/vesqen-scroll /sdcard/Music/
   adb push /tmp/vesqen-demo /sdcard/Music/
   adb shell content call --uri content://media --method scan_volume --arg external_primary
   ```

3. Generate. The tests grant the media and notification permissions themselves and wait for the first scan:

   ```bash
   ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
   ```

4. Review the diff of the two profile files and commit them.

The generator runs against the `githubNonMinifiedRelease` build, a release build without R8 so the profile keeps source names. R8 rewrites the rules for the shipped release and profile builds.
