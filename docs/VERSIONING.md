# Version management

`version.properties` is the single source of truth for Vesqen application versions.

- `versionName` follows semantic versioning (`MAJOR.MINOR.PATCH`, with an optional pre-release suffix).
- `versionCode` is a positive integer and must increase for every build published to an Android distribution channel.
- Product milestones and visual-system document versions are independent from the installable application version.

The first public release line starts at `1.0.0-beta.1`; it is a pre-release of, not a substitute for, the first stable `1.0.0` release. Subsequent Beta builds on this line increment the suffix (`beta.2`, `beta.3`, and so on). After stability, compatible fixes increment `PATCH`, compatible features increment `MINOR`, and incompatible product changes increment `MAJOR`.

For every build distributed through GitHub Releases, Google Play, or another public/test channel:

- update `versionName` and `versionCode` together with the release notes;
- use a monotonically increasing `versionCode`, independent of semantic-version ordering;
- create the matching annotated Git tag `v<versionName>` only for the reviewed release commit;
- record the artifact hash and signing identity against that tag.

The long-lived application certificate, replaceable Play upload certificate,
local custody boundary, and per-release signing ledger are defined in
[`RELEASE_SIGNING.md`](RELEASE_SIGNING.md). Signing passwords and private keys
must never be added to version properties or repository files.

A published version, tag, APK, or AAB is immutable. A blocker discovered after publication is released under a new version and a larger `versionCode`; for example, `1.0.0-beta.1` / `10` is followed by `1.0.0-beta.2` / `11`, never by a replacement `beta.1` binary. Local builds that are not distributed do not consume a `versionCode`, but they must not be presented as a published release.
