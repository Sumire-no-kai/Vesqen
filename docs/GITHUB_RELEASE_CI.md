# GitHub APK release CI

Approved on 2026-10-01; tracked in #63. GitHub is the current release channel.
Google Play upload and the output-verification issuer are outside this workflow.

## One signing approval

1. Push a paired `versionName` change and `versionCode` increase to `master`, with
   release notes in `docs/releases/<versionName>.md`. This automatically starts
   **GitHub APK release**. Ordinary pushes continue through **Android CI**.
   Before that push, set the repository variable `VESQEN_USAGE_ENDPOINT` to the
   deployed usage service (`https://HOST/v1/usage`, #70). The release build passes
   it as `vesqen.usageEndpoint`; left unset, the APK has no usage statistics and no
   report uploads, and the release notes must not mention them.
2. Tests, lint, Debug/Release builds and the final privacy-policy guard run without
   signing secrets. The candidate is pinned to the triggering commit.
3. Review the source commit, checks and workflow revision, then approve the
   `github-apk-signing` job. It aligns and signs the APK, verifies package/version,
   non-debuggable/offline permissions, v2/v3 signatures, the fixed application
   certificate, alignment and SHA-256. It produces a draft Release by default.
   After the upload it reads the SHA-256 that GitHub computed for each attachment
   and requires it to match the APK, `SHA256SUMS` and `release-manifest.json` it
   verified; a mismatch stops the job and leaves the draft for inspection.
4. Download that draft's APK, record its exact SHA-256, and complete #42 device,
   installation, same-signer upgrade and data-retention checks. Test the signed
   artifact itself; an earlier locally built APK has a different identity.
5. Commit the acceptance receipt below and device evidence to `master`. Finalize
   the draft's release notes, including the known limitations from the device QA.
6. Run **Publish accepted GitHub APK** from `master` with the version and accepted
   APK SHA-256. This manual action needs no second environment approval. It
   downloads and verifies the existing APK, requires its matching receipt,
   creates/verifies the annotated version tag, and publishes the existing draft.
   Right before publishing it checks again that GitHub's SHA-256 for each of the
   three attachments matches the downloaded, verified files, so the hash shown on
   the release page, `SHA256SUMS`, the manifest and the accepted APK agree.
   It does not rebuild, re-sign or replace attachments.
   Both draft creation and publication require a versionCode higher than every
   already-published release, so an older pending draft cannot become a downgrade.

For the frozen first beta, run **GitHub APK release** manually from `master`,
selecting `release/1.0.0-beta.1` and its reviewed full 40-character commit SHA.
Do not build this beta from `master`: the development branch already contains
changes intended for beta.2. Manual runs may also target a reviewed `master`
commit. `create_draft=false` retains signed artifacts without creating a Release.

Unsigned artifacts expire after 7 days; signed workflow artifacts after 30 days.
Draft Release attachments are the durable candidate. Re-running a signing job
does not replace an existing draft or public release. Keep the existing draft
for QA; a new distributed build requires a new version and versionCode.
An interrupted publication can be retried with the same accepted artifact; an
existing annotated tag must already point to the exact source commit.

## Environment setup

See [local recovery](#local-recovery-release) when a hosted run is unavailable.

Before storing secrets, configure `github-apk-signing` in this repository:

- deployment branch: exactly `master`, with no tag pattern;
- required reviewer: repository owner; self-review remains available for this
  single-maintainer repository;
- environment secret `VESQEN_APP_SIGNING_P12_BASE64`: application signing v1 PKCS12
  encoded as one base64 string (base64 is transport encoding, not encryption);
- environment secret `VESQEN_APP_SIGNING_PASSWORD`: its PKCS12 password.

Provision through `gh secret set --env github-apk-signing` using stdin. Retrieve
the original password privately from Keychain; never paste it into a command,
chat, issue, documentation or captured terminal output. Verify the keystore's
public certificate against [RELEASE_SIGNING.md](RELEASE_SIGNING.md) before upload.
GitHub encrypts secrets for storage; the approved runner needs plaintext to sign.
Keep account recovery and offline key backups intact. Neither repository-level
secrets nor the Play upload/verification issuer keys are needed.

Approval is a check on use, not a guarantee against a compromised administrator
who can edit environment rules. Restrict repository write/admin access and review
workflow changes. Ordinary app builds must never run in the secret-bearing job.

## Local recovery release

Local recovery signing uses the same artifact contract as CI. Build the reviewed
source commit with the same checks. Use `tools/github_release.py` from a reviewed
tooling checkout of `master`, which may differ from the frozen source checkout;
run `prepare` with `--source` pointing to that source checkout, then run `sign`.
Provide the source commit, matching branch, Android build-tools path, and fresh
output directories outside the repository. Supply the existing
application PKCS12 as `VESQEN_KEYSTORE_BASE64` and its Keychain password as
`VESQEN_KEYSTORE_PASSWORD` only to the signing process, without displaying them
or putting values into command arguments or files. No GitHub workflow variables
are required; leave them unset for a local run rather than inventing a CI URL.

The helper produces the signed APK, `SHA256SUMS`, `release-manifest.json`, and
public release notes. Keep the manifest generated from those exact bytes.
Complete the same device acceptance, versionCode and annotated-tag checks before
manual publication, and attach **all three** public files (APK, checksums,
manifest) even when using GitHub's web UI. Record why recovery signing was used
and the tooling commit in the development log. These attachment requirements
also apply if another signing tool is used: produce and verify an equivalent
manifest before release.
The manifest records applicationId, versionName/versionCode, sourceCommit,
sourceBranch, unsignedApkSha256, apkSha256 and certificateSha256; workflowCommit
and workflowRun are empty for a local run.

A local release with this manifest participates in subsequent CI versionCode
checks normally. Missing or duplicate published manifests stop CI intentionally;
do not bypass that check or guess values. Resolve the release ledger explicitly
before continuing, without replacing an already-published APK or tag.

## Acceptance receipt

Create `docs/releases/<versionName>.acceptance.json` **only after** the signed APK
passes the checks. Copy the first six values exactly from `release-manifest.json`:

```json
{
  "applicationId": "io.github.sumirenokai.vesqen",
  "versionName": "<accepted version>",
  "versionCode": 0,
  "sourceCommit": "<40-character source SHA>",
  "apkSha256": "<64-character accepted APK SHA-256>",
  "certificateSha256": "743e96fcb71dc58188496819a001a27cd88d909162c5ae929c87bfa29ab86293",
  "install": "pending",
  "sameSignerUpgrade": "pending",
  "dataRetention": "pending",
  "deviceEvidence": "docs/M4_DEVICE_ACCEPTANCE.md#<actual-evidence-section>"
}
```

Replace the placeholders and change each check to `passed` only when supported
by device evidence. This example is intentionally not a passing receipt. For a
first public release, test the documented same-signer upgrade rehearsal; a debug
key installation cannot serve as the upgrade baseline. A receipt is a maintainer
attestation of real QA, not an automated device test.

Published versions and their tags/APKs remain immutable. Any correction requires
a higher versionCode and a new version. Record the CI run URL, workflow/source
commits, signed APK hash and certificate alongside the device results.
