# Vesqen release signing

This document records the public identities and custody boundaries used to
release Vesqen. It deliberately contains no password, private key, decrypted
temporary material, or recovery secret.

## Identity model

Vesqen uses three independent signing identities:

| Identity | Purpose | Rotation model |
| --- | --- | --- |
| Application signing `v1` | Signs GitHub APKs and, after one-time import into Play App Signing, Play-delivered APKs | Long-lived Android update identity; do not rotate as an ordinary release operation |
| Play upload `v1` | Signs AABs submitted to Google Play | Replaceable through the Play upload-key reset process |
| Output verification issuer `vesqen.output_verification.2026_01` | Signs offline bit-perfect verification registries | Versioned `keyId`; old public keys remain while their records must be readable |

The Play upload certificate is not an application update identity. Google Play
accepts an AAB signed by the registered upload key, then signs installable APKs
with its managed copy of the application-signing key. GitHub APKs are signed
locally with that same application-signing identity so both channels can remain
update-compatible.

## Current public identities

### Application signing v1

- Alias: `vesqen-app-signing-v1`
- Subject: `CN=Vesqen App Signing v1`
- Algorithm: RSA 4096 / SHA-256 with RSA
- Validity: 2026-09-20 through 2126-08-27
- Certificate SHA-1: `F0:F4:2D:09:D1:10:41:B6:73:EA:9A:32:C9:58:FE:68:BB:32:BE:D2`
- Certificate SHA-256: `74:3E:96:FC:B7:1D:C5:81:88:49:68:19:A0:01:A2:7C:D8:8D:90:91:62:C5:AE:92:9C:87:BF:A2:9A:B8:62:93`
- SPKI SHA-256: `64e735ac71c0a20ae0f4317d4f99bbfc05a02087cae39d50dc20d71d3fde0fd1`
- Public certificate: [`release-keys/vesqen-app-signing-v1-cert.pem`](release-keys/vesqen-app-signing-v1-cert.pem)

### Play upload v1

- Alias: `vesqen-play-upload-v1`
- Subject: `CN=Vesqen Play Upload v1`
- Algorithm: RSA 4096 / SHA-256 with RSA
- Validity: 2026-09-20 through 2126-08-27
- Certificate SHA-1: `36:84:2D:1B:3E:E4:92:B5:E2:9C:E5:40:C1:5D:6D:65:27:A2:D2:56`
- Certificate SHA-256: `6A:78:82:9F:9C:74:FA:CE:FB:CE:C4:62:57:CF:9B:E6:01:55:1F:3B:86:49:E6:1E:14:6B:1A:98:20:06:31:5B`
- SPKI SHA-256: `4363564b8ed182dd073ca8047a91dd4c48f04f16f0302215dc9acd32071cb19a`
- Public certificate: [`release-keys/vesqen-play-upload-v1-cert.pem`](release-keys/vesqen-play-upload-v1-cert.pem)

The two public keys and certificates are distinct. Neither matches the output
verification issuer, whose SPKI SHA-256 is
`35619e5cc562b23282aa5bce0aa4e6ba6221e40b97522d96d91ea5c07daf0db4`.

## Local custody

The original private keystores are outside the repository on the release Mac:

- `~/Library/Application Support/Vesqen/keys/vesqen-app-signing-v1.p12`
- `~/Library/Application Support/Vesqen/keys/vesqen-play-upload-v1.p12`

Both files use mode `0600`; their parent key directory uses mode `0700`. Each
PKCS12 store uses a separate randomly generated password. The store and private
key inside a given PKCS12 use the same password for Java PKCS12 compatibility.
Passwords are stored as macOS Keychain generic-password entries:

| Service | Account |
| --- | --- |
| `Vesqen App Signing` | `vesqen-app-signing-v1` |
| `Vesqen Play Upload` | `vesqen-play-upload-v1` |

Routine commands must retrieve these values without printing them. Passwords
must not be passed as literal command arguments, pasted into documentation,
stored in Gradle properties, or exported into captured CI environments.

The application key must remain offline except for local GitHub APK signing,
controlled recovery verification, and the one-time protected Play App Signing
import. The upload key may be used for normal Play AAB submissions but is not
permitted to sign GitHub release APKs.

## Creation and validation record

The current key pair was generated locally with Oracle JDK 25 `keytool` on
2026-09-20. Before its public identity was recorded, validation completed all
of the following:

- exact password round-trip from macOS Keychain;
- PKCS12 open and expected alias lookup;
- private-key use through a signed certificate request for each key;
- RSA key size, certificate signature algorithm, subject, and validity review;
- public-certificate and SPKI fingerprint comparison;
- file permission check (`0600`);
- disposable Release APK signing with application signing `v1`, verified under
  APK Signature Schemes v2 and v3 with the recorded certificate;
- disposable Release AAB JAR signing with Play upload `v1`, verified with the
  recorded upload certificate. Both disposable signed artifacts were deleted
  after the smoke test and are not release candidates.

The first attempted creation on the same date failed the Keychain round-trip
because an interactive password confirmation received only one input line. It
was never used or published. After explicit approval, both unusable PKCS12
files and both incorrect Keychain entries were deleted before the identities
documented above were generated.

## Backup, migration, and Play status

As of 2026-09-21:

- local keystores and Keychain passwords: **created and verified**;
- public certificates and fingerprints: **recorded in Git**;
- encrypted offline application-key backups: **copy 1 created and
  recovery-verified; second independent copy pending**;
- encrypted offline upload-key backups: **copy 1 created and recovery-verified;
  second independent copy pending**;
- off-device custody of the passwords needed after loss of the release Mac:
  **pending**;
- Play App Signing import and certificate reconciliation: **not yet executed**;
- signed APK/AAB candidate and same-signer upgrade acceptance: **not yet executed**.

Backup copy 1 was created on 2026-09-21 on controlled removable media as an
AES-256 encrypted, compressed, read-only UDZO disk image without reformatting
or modifying unrelated files on the medium. The image is 150,528 bytes with
SHA-256
`7a77998a727549c16aff70e4cded8d83700a1734ecd539048e5b64d623a23af8`.
It contains the two password-protected PKCS12 files and a non-secret manifest;
it does not contain any password. The removable volume name, device identifier,
and physical storage location are intentionally excluded from the repository.

Recovery verification detached the newly written image, unmounted and
remounted the entire removable volume, mounted the image again read-only using
the separately stored image password, reproduced both PKCS12 file hashes,
opened the expected aliases, used each recovered private key to produce a valid
CSR, and reproduced both recorded certificate SHA-256 values. The outer image
hash remained stable across the remount.

An earlier read/write raw image passed its same-session checks but changed hash
and became unmountable after the first physical-volume eject/reinsert cycle. It
was quarantined under an explicit invalid filename and is not a backup. The
read-only image documented above was rebuilt from the original local PKCS12
files and passed the stronger post-remount recovery check. This is one verified
copy, not completion of the backup gate: a second independent encrypted copy
and separate off-device password custody remain required.

Do not publish a signed release until at least two controlled encrypted backup
copies of the application keystore have been created and independently restored
far enough to reproduce the recorded public fingerprint. Copy the PKCS12 and
its password through separate controlled channels when migrating release Macs.
Never rely on an ordinary cloud-synced folder as the only backup.

During initial Play enrollment, choose the flow that supplies the existing
application-signing key rather than allowing Play to generate an unrelated
identity. After import, the Play app-signing certificate must exactly match the
application certificate recorded above, while the registered upload
certificate must exactly match the separate upload certificate.

## Per-release record

Every public/test-channel release must record, without secrets:

- versionName, versionCode, commit, and annotated tag;
- application certificate SHA-256;
- upload certificate SHA-256 for the submitted AAB;
- base APK and AAB SHA-256;
- Play app-signing certificate reconciliation result;
- install and same-signer update/data-retention evidence;
- CI URL, device evidence, release URL, publication time, and rollback point.

Published tags and artifacts are immutable. A correction uses a higher version
and versionCode signed with the same application identity.
