# GitHub app updates — beta.2 backend (#68)

The UI contract and Debug fake are reviewed separately in PR #77. This implementation
uses that contract; it does not add screens, strings, notifications or playback dialogs.
`VesqenApplication.appUpdater` provides the real `AppUpdater`. UI renders the enums and
plain-text notes, and calls the explicit check, skip, download, install and preference commands.
`ReadyToInstall.requiresInstallPermission` describes the system permission step; granting
permission does not automatically start installation. Call `installUpdate()` again after the
user returns and chooses to continue. A cancelled system confirmation reports `INSTALL_CANCELLED`.

## Distribution boundary

The repository currently produces GitHub artifacts only. Implementation and installation
permission live in `app/src/github`; Gradle temporarily adds that Kotlin directory to the
current variants and uses its manifest as a build-type overlay. There are no new dependencies.
When #47 adds flavors, remove those two source-set mappings, use the normal `github` flavor,
and give Play its own application composition without `GitHubUpdateRuntime` or the installation
permission. `AppUpdater` in `main` remains the shared contract. The runtime also refuses download
and installation when Android reports Google Play as the installer.

On API 30+, installation ownership uses `getInstallSourceInfo`; API 26–29 use the older
installer-package API. Direct/shell/system-package-installer installs default to automatic
checks on. Other stores/updaters and unknown ownership default off and expose
`ManagedExternally` with the installer package, allowing the UI to explain ownership.
Self-upgrades retain their source classification and saved preferences.

## Checks, transport and files

An application activity entering the foreground triggers a check when automatic checks are
on and more than 24 hours have passed since both the last successful check and the last automatic
attempt. Persisting failed automatic attempts also prevents repeated offline requests throughout
the day. A clock that moves backwards does not trigger a burst. Manual checks bypass the timer.
There is no background worker or notification. Checks, downloads and installation preparation
run on an IO scope independent of playback; concurrent commands cannot start duplicate operations.

The fixed endpoints are `https://vesqen.sumirenokai.com/updates/stable.json` and `beta.json`.
Prerelease builds select beta; release builds select stable. The beta file may advertise a newer
stable version. Version comparison happens locally by `versionCode`. Only Debug accepts
`-Pvesqen.updateManifestBaseUrl=https://.../`; Release and Profile retain the fixed origin.
No app version, device identity or analytics payload is added to a standalone request.

For #70, set `GitHubUpdateRuntime.usageRequestExpected` before the first activity starts when
its daily statistics request will carry the channel manifest. Feed the response to
`acceptUsageResponse()`; it passes through the same parser and local comparison without a
separate GET. A failed statistics request must not silently enable an extra updater request.
That statistics subsystem is outside this PR.

Transport requires HTTPS, bounded redirects with no HTTP downgrade, a 128 KiB manifest limit,
and a 512 MiB APK limit. APK URLs are tried in their listed order. Each successful transfer is
checked for SHA-256, package name, exact advertised and strictly higher versionCode, and the
same nonempty set of current signing certificates. Every mirror gets these checks. APKs are
checked again immediately before installation. Storage failures stop retries, and incomplete
or rejected files are removed. Interrupted downloads are discarded after process restart;
retrying a download is an explicit command.

Android 9's `getPackageArchiveInfo` only collects certificates with `GET_SIGNATURES`, even
when `GET_SIGNING_CERTIFICATES` was requested. The archive path supplies both flags on API 28
and still reads current signers from `SigningInfo`. It never accepts a missing certificate or
substitutes an installed certificate for an archive certificate. See the
[Android 9 implementation](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/content/pm/PackageManager.java).

Installation uses `PackageInstaller` with a persisted session/token and explicit non-exported
callback activity. The activity only forwards the system confirmation and typed result; it has
no app dialog or copy. Android 12+ is explicitly told to require user action. The package installer
also independently enforces Android's signature and update rules. Session metadata allows a
fresh process to recover installation outcome; local staging files are not a trust decision.

Notes are plain text. The UI must not interpret HTML or automatically activate links.
`isAllowedUpdateNotesLink()` permits HTTPS links to the exact Vesqen website or GitHub host,
including anchors, and rejects embedded credentials and other hosts.

## Static version-file schema and publication

```json
{
  "schemaVersion": 1,
  "channel": "beta",
  "release": {
    "versionName": "1.0.0-beta.2",
    "versionCode": 11,
    "minimumAndroidApi": 26,
    "apkUrls": ["https://github.com/Sumire-no-kai/Vesqen/releases/download/v1.0.0-beta.2/Vesqen-1.0.0-beta.2.apk"],
    "sha256": "<64 lowercase hexadecimal characters from the signed APK>",
    "releaseNotes": {"en": "English plain text", "zh-CN": "中文纯文本"}
  }
}
```

This is a schema example, not a release declaration or version change. A channel without a
published release has `"release": null`. Numbers must be JSON integers. Both note languages
are required, with at most 32,768 characters each; a release has one to five HTTPS APK URLs.

`github-publish.yml` generates both channel files as part of the accepted-draft publication
job, before the irreversible publication step. It verifies the actual APK against its release
ledger, derives minimum SDK from that APK, and splits the existing bilingual public release notes.
Stable selects the highest stable versionCode; beta selects the highest of all published versions
and the accepted candidate. After successful publication, both files are saved in the
`update-manifests-<version>` workflow artifact for 90 days. No website deployment is performed.
The GitHub APK permission guard uses an explicit allowlist including INTERNET and
REQUEST_INSTALL_PACKAGES; Billing and any unknown permission still fail.

**Pending owner/release work:** choose how these artifacts reach the website and confirm the
endpoint paths; choose any mirrors and their ownership; coordinate #70's shared daily response;
update the beta.1 privacy policy (which still says INTERNET is absent) with the final beta.2
network behavior before public distribution. UI controls and wording are handled by the UI work.
The policy and website are not published by this change. Beta.1 users must install beta.2 manually.

## Validation and device procedure

Run focused `*updates.*` JVM tests, then the repository Debug unit/lint/build checks, Release APK
and instrumentation compilation, plus `python3 -m unittest discover -s tools/tests -v`.
`UpdateManifestDeviceTest` checks the actual Android JSON implementation. `UpdateUpgradeDeviceTest`
is opt-in (`updateQa=true`) and rejects any target except `.devicetest`.

For the upgrade lab, create an isolated source copy, give only its Debug build the `.devicetest`
applicationId suffix, and build two temporary versionCodes (1000 and 1001 for this run). The owner
explicitly authorized these test-only overrides; repository `version.properties` stays unchanged.
Install the lower host and its matching tests. Put the higher `next.apk` and an ephemeral
`localhost.p12` (alias `localhost`, password `local-test`, SAN `localhost` and `127.0.0.1`) in the
host's `files/update-qa` via `adb push` followed by `run-as ... cp`; verify hashes after transfer.
The certificate stays in the isolated test fixture; no device CA installation or production TLS
change is needed. The test starts its own localhost HTTPS server and trusts that exact certificate.

The test checks a static file, observes first-mirror HTTP failure, downloads the second mirror,
verifies it, and requests system installation. Use `cancelInstall=true` to assert cancellation.
For successful self-upgrade the instrumentation process is expected to terminate: verify the
new installed version and retained `files/update-qa/state.txt` from a fresh process, rather than
counting process termination as a passing JUnit result. Delete the temporary TLS material after
acceptance. Platform dialogs, including vendor risk checkboxes, must be handled normally.
