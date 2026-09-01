# Version management

`version.properties` is the single source of truth for Vesqen application versions.

- `versionName` follows semantic versioning (`MAJOR.MINOR.PATCH`, with an optional pre-release suffix).
- `versionCode` is a positive integer and must increase for every build published to an Android distribution channel.
- Product milestones and visual-system document versions are independent from the installable application version.

For an ordinary release, update both values in the same pull request as the release notes. Use patch releases for compatible fixes, minor releases for compatible features, and reserve `1.0.0` for the first stable product release.
