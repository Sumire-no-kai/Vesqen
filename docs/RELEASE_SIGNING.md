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
by the approved release workflow with that same application-signing identity so both channels can remain
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
stored in Gradle properties, or included in CI logs, caches, or artifacts.

On 2026-10-01 the owner authorized protected GitHub Actions signing. This
replaces the previous local-only application-key rule: the original and offline
backups remain under local custody, and an encrypted GitHub Environment Secret
copy may be used by the `github-apk-signing` environment. Its password is a
separate Environment Secret. Required owner approval and a `master` deployment
branch restriction must be configured before either secret is uploaded.

Only the isolated signing step receives the application keystore and password.
Builds and pull requests receive neither; signing runs on a fresh GitHub-hosted
runner without executing Gradle or application source. The temporary PKCS12 is
restricted to mode `0600`, cleaned on normal success/failure, and otherwise
discarded with the ephemeral runner. Actions are pinned to full commit hashes;
artifact uploads name only the public APK, hashes, manifest, and release notes.
See [GitHub release CI](GITHUB_RELEASE_CI.md) for the exact workflow and approval.

This expands custody to GitHub and its runners. Approval limits unintended use;
it does not make an administrator-account or signing-runner compromise harmless.
A stolen application key can sign impersonated updates accepted as this app's
identity; it does not itself deliver or silently install those updates. Android
supports signing-key rotation on some supported versions, but rotation is not a
universal recovery mechanism for all existing installations, especially API 26/27.
Treat this as a long-lived credential, not a routinely replaceable upload key.

Controlled local signing remains available for recovery, with its use and final
artifact recorded in the release ledger. It follows the same GitHub attachment
contract: every public recovery release must include `release-manifest.json`
and `SHA256SUMS` alongside the APK. Use the existing `prepare` and `sign` helper
commands as described in [local recovery](GITHUB_RELEASE_CI.md#local-recovery-release)
to generate them from the actual artifact. Local signing does not exempt a
release from the version ledger; CI deliberately rejects a published release
without it rather than guessing a versionCode or skipping rollback protection.
The Play upload key and verification issuer remain outside GitHub CI. The upload
key must never sign GitHub APKs.

### CI provisioning record — 2026-10-01

`github-apk-signing` was configured with the repository owner as required
reviewer and `master` as its only allowed deployment branch. The original
application PKCS12 was opened using its Keychain password and its exported
certificate SHA-256 matched the public identity above. The keystore and password
were then sent privately through stdin to the two environment secrets named in
the CI guide. GitHub confirmed both writes; names/timestamps and environment
rules were read back without reading or displaying secret values. No private
material was written to the repository or a plaintext staging file.

This verifies provisioning, not a hosted signing run. The first real candidate
still requires the merged workflow and the owner's signing approval. Local
originals, Keychain entries and offline backups were unchanged.

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

As of 2026-10-01:

- local keystores and Keychain passwords: **created and verified**;
- public certificates and fingerprints: **recorded in Git**;
- encrypted offline application-key and upload-key backups: **three copies on
  three separate media**. Copy 1 was created and recovery-verified on
  2026-09-21; its SHA-256 was re-checked unchanged on 2026-10-01. Copies 2 and 3
  are byte-identical copies of the same encrypted image on a separate NTFS drive
  and on a Windows computer, made and SHA-256-verified by the owner with
  `Get-FileHash` against the recorded image hash. Identical bytes restore to
  the same PKCS12 files, so they reproduce the fingerprints verified for copy 1;
- off-device custody of the passwords needed after loss of the release Mac:
  **done on 2026-10-01**. The image password and the three PKCS12 passwords
  (application signing, Play upload, output verification issuer) are kept in an
  end-to-end encrypted password manager, separate from the backup media. Each
  stored value was compared byte for byte with its Keychain entry without
  being displayed;
- output verification issuer keystore: **in backup image v2 on the removable
  medium since 2026-10-01**; copies 2 and 3 still hold copy 1 until the owner
  copies v2 to them (see below). The issuer key does not affect app signing;
  losing it means rotating to a new issuer key through an app update before new
  verification records can be signed;
- Play App Signing import and certificate reconciliation: **done on
  2026-10-13**, before the first Play upload (see the
  [enrollment record](#play-enrollment-record--2026-10-13));
- signed APK candidate and same-signer update acceptance: **done for
  `1.0.0-beta.1`** (CI-signed APK, published 2026-10-01; see
  [M4 device acceptance](M4_DEVICE_ACCEPTANCE.md) and the release receipt).
  The first Play bundle is `1.0.0-beta.2`, signed locally with the upload key.

Backup copy 1 was created on 2026-09-21 on controlled removable media as an
AES-256 encrypted, compressed, read-only UDZO disk image without reformatting
or modifying unrelated files on the medium. The image is 150,528 bytes with
SHA-256
`7a77998a727549c16aff70e4cded8d83700a1734ecd539048e5b64d623a23af8`.
It contains the two password-protected PKCS12 files and a non-secret manifest;
it does not contain any password. The removable volume name, device identifier,
and physical storage location are intentionally excluded from the repository.

Backup image v2 was created on 2026-10-01 on the same removable medium, next to
copy 1, with the same format and image password. It holds all three
password-protected PKCS12 files (application signing, Play upload and output
verification issuer) and a non-secret manifest. The image is 152,064 bytes with
SHA-256
`25f774c9f17d47c528b150b2a5ee0322d0d5d10d3572e3c7ef8e3bbacd700580`.
Verification ran twice on the copy written to the medium, the second time
after unmounting and remounting the whole volume. Each run confirmed the
following:

- an empty password is rejected;
- the three files match the local originals byte for byte;
- each keystore opens with its Keychain password;
- both certificate SHA-256 values and the issuer SPKI SHA-256 match the
  recorded values;
- each private key produces a CSR that verifies;
- the image hash is stable.

The medium's Windows README now points to v2. Copy 1 remains valid for the
first two keys. Copies 2 and 3 move to v2 when the owner copies it and checks
the hash with `Get-FileHash`.

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

## Google Play enrollment and upload

Play App Signing must receive the existing application signing `v1` key before the first release is uploaded, so Play-delivered APKs keep the same signer as GitHub APKs. The owner runs every command that opens a private key. Commands read the password from its Keychain entry (macOS asks the owner to allow each access); no password is printed, typed into a chat, passed as a literal argument or written to a file.

1. **Replace Play's generated key.** Play Console generates an app signing key when the app is created. Before anything is uploaded, open *Protected with Play → Play Store protection → Manage Play app signing → Change key*, accept the warning (it only matters once a build was uploaded or installed), and choose *Export and upload a key from Java keystore*. Download the encryption public key and the PEPK tool into a new folder.
2. **Export the key with PEPK.** PEPK encrypts with its bundled BouncyCastle provider, which Oracle JDK refuses to load from an unsigned jar (`Cannot find any provider supporting RSA/NONE/OAEPWithSHA1AndMGF1Padding`), so run it with an OpenJDK runtime such as a temporary Temurin JRE. PEPK reads passwords only from a console and offers no environment or file option, so an `expect` script answers its two prompts from an environment variable without echoing:

   ```tcl
   # pepk.exp — usage: PEPK_PW=... expect pepk.exp <openjdk>/bin/java -jar pepk.jar <pepk args...>
   set pw $env(PEPK_PW)
   unset env(PEPK_PW)
   set timeout 120
   spawn -noecho {*}$argv
   expect {
     -re {Enter password for [^\r\n]*:} { send -- "$pw\r"; exp_continue }
     eof
   }
   catch wait result
   exit [lindex $result 3]
   ```

   ```bash
   PEPK_PW="$(security find-generic-password -s 'Vesqen App Signing' -a vesqen-app-signing-v1 -w)" expect pepk.exp <openjdk>/bin/java -jar pepk.jar --keystore="$HOME/Library/Application Support/Vesqen/keys/vesqen-app-signing-v1.p12" --alias=vesqen-app-signing-v1 --output=vesqen-app-signing-v1-encrypted.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
   ```

   PEPK refuses to overwrite an existing output file. Check that the ZIP's `certificate.pem` has the application certificate SHA-256 before uploading it as *Upload generated ZIP*.
3. **Register the upload certificate.** In the same dialog, expand step 5 (*create a new upload key*) and upload the public [`release-keys/vesqen-play-upload-v1-cert.pem`](release-keys/vesqen-play-upload-v1-cert.pem) as *Upload your upload key certificate*. Without it Play expects bundles signed with the application key and rejects the upload-key signature. Save.
4. **Reconcile.** Play Console must show the application certificate SHA-256 `74:3E:96:FC:…:62:93` and the upload certificate SHA-256 `6A:78:82:9F:…:31:5B` recorded above. Stop if either differs. Then delete the encrypted ZIP, the PEPK download and the temporary runtime.
5. **Sign the bundle.** Download the `unsigned-play-bundle` artifact from the release run, check its SHA-256 against the run, and sign it with the upload key:

   ```bash
   VESQEN_UPLOAD_PW="$(security find-generic-password -s 'Vesqen Play Upload' -a vesqen-play-upload-v1 -w)" jarsigner -storepass:env VESQEN_UPLOAD_PW -keystore "$HOME/Library/Application Support/Vesqen/keys/vesqen-play-upload-v1.p12" -storetype PKCS12 -sigalg SHA256withRSA -digestalg SHA-256 -signedjar Vesqen-<version>-play.aab app-play-release.aab vesqen-play-upload-v1
   keytool -printcert -jarfile Vesqen-<version>-play.aab | grep SHA256
   ```

   The printed SHA-256 must equal the upload certificate. Upload that AAB to the closed testing track and record its SHA-256 in the per-release record.

### Play enrollment record — 2026-10-13

- Play Console had generated an app signing key when the app was created on 2026-10-12 (SHA-256 `F7:8F:EF:E0:…:C6:E3`). Nothing had been uploaded or installed, and it was replaced through *Change key*.
- PEPK came from `www.gstatic.com/play-apps-publisher-rapid/signing-tool/prod/pepk.jar` (SHA-256 `aaccc077…858e24e`) and ran under Temurin JRE 25.0.4.1+1 (archive SHA-256 `5b02bcc9…98f37e1b`, checked against Adoptium). The exported ZIP's certificate was `CN=Vesqen App Signing v1`, SHA-256 `74:3E:96:FC:…:62:93`.
- After saving, Play Console showed the app signing certificate SHA-256 `74:3E:96:FC:B7:1D:C5:81:88:49:68:19:A0:01:A2:7C:D8:8D:90:91:62:C5:AE:92:9C:87:BF:A2:9A:B8:62:93` and the upload certificate SHA-256 `6A:78:82:9F:9C:74:FA:CE:FB:CE:C4:62:57:CF:9B:E6:01:55:1F:3B:86:49:E6:1E:14:6B:1A:98:20:06:31:5B` (SHA-1 `36:84:2D:1B:…:D2:56`). Both match the identities above; the generated key is no longer listed.
- The encrypted ZIP, the PEPK download, the encryption public key and the temporary JRE were deleted afterwards.
- `1.0.0-beta.2` bundle: `unsigned-play-bundle` from run 38213613584 (commit `76781ec`, SHA-256 `b89f24e6…043521`), signed locally with the upload key to SHA-256 `59636eec21b96622d31e3244f4e4c035875662bfe9919c1fa0396ffd4e949d8f`; all 199 original entries unchanged.
- `1.0.0-beta.3` bundle (2026-10-16): `unsigned-play-bundle` from run 38726792266 (commit `f48fece`, SHA-256 `2c4715a6ffe0a4bb90f54006dc503c10165846dc70aea0c5f7f2850bba7c2c9d`), signed locally by the owner with the upload key to SHA-256 `0a601042c61dd9025335c3fa661893944f8436699b1caff8f0be886467c10ddc`. `keytool` shows the upload certificate `6A:78:82:9F:…:31:5B`, `jarsigner -verify` passes, and all 199 original entries are unchanged; only `META-INF/MANIFEST.MF`, `VESQEN-P.SF` and `VESQEN-P.RSA` were added.

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
