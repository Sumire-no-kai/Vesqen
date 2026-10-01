#!/usr/bin/env python3
"""Prepare and sign GitHub APK candidates without running app code with secrets."""

import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


APP_ID = "io.github.sumirenokai.vesqen"
KEY_ALIAS = "vesqen-app-signing-v1"
CERT_SHA256 = "743e96fcb71dc58188496819a001a27cd88d909162c5ae929c87bfa29ab86293"
VERSION = re.compile(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")


class ReleaseError(ValueError):
    pass


def run(command, *, env=None, input_text=None):
    result = subprocess.run(command, env=env, input=input_text, capture_output=True, text=True, check=False)
    if result.returncode:
        # A signing tool can echo credential-bearing input on failure.
        raise ReleaseError(f"{Path(command[0]).name} failed (exit {result.returncode}); output withheld")
    return result.stdout


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def validate_metadata(data, commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ReleaseError("A full lowercase source commit SHA is required")
    version = data.get("versionName")
    code = data.get("versionCode")
    if not isinstance(version, str) or not VERSION.fullmatch(version):
        raise ReleaseError("Invalid release version")
    if type(code) is not int or not 0 < code <= 2_100_000_000:
        raise ReleaseError("Invalid versionCode")
    if data.get("applicationId") != APP_ID or data.get("sourceCommit") != commit:
        raise ReleaseError("Candidate package or source commit does not match")
    if data.get("sourceBranch") not in ("master", f"release/{version}"):
        raise ReleaseError("Candidate must come from master or its matching release branch")
    if not re.fullmatch(r"[0-9a-f]{64}", data.get("unsignedApkSha256", "")):
        raise ReleaseError("Invalid unsigned APK hash")
    return data


def properties(text):
    return dict(line.split("=", 1) for line in text.splitlines() if line and not line.startswith("#"))


def version_bumped(previous, current):
    old, new = properties(previous), properties(current)
    if old == new:
        return False
    if not VERSION.fullmatch(new["versionName"]):
        raise ReleaseError("Invalid release version")
    if old["versionName"] == new["versionName"] or int(new["versionCode"]) <= int(old["versionCode"]):
        raise ReleaseError("A release must change versionName and increase versionCode together")
    return True


def inspect_apk(apk, tools, metadata):
    badging = run([str(tools / "aapt"), "dump", "badging", str(apk)])
    identity = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    expected = (APP_ID, str(metadata["versionCode"]), metadata["versionName"])
    if not identity or identity.groups() != expected:
        raise ReleaseError("APK package or version does not match the candidate")
    if "application-debuggable" in badging:
        raise ReleaseError("Refusing a debuggable APK")
    for permission in ("android.permission.INTERNET", "com.android.vending.BILLING"):
        if re.search(r"^uses-permission[^:]*: name='" + re.escape(permission) + "'", badging, re.M):
            raise ReleaseError("GitHub APK contains a prohibited permission")


def prepare(source, output, commit, branch, tools):
    version = properties((source / "version.properties").read_text())
    apk = source / "app/build/outputs/apk/release/app-release-unsigned.apk"
    metadata = validate_metadata({
        "applicationId": APP_ID,
        "versionName": version["versionName"],
        "versionCode": int(version["versionCode"]),
        "sourceCommit": commit,
        "sourceBranch": branch,
        "unsignedApkSha256": sha256(apk),
    }, commit)
    actual_commit = run(["git", "-C", str(source), "rev-parse", "HEAD"]).strip()
    if actual_commit != commit:
        raise ReleaseError("Checked-out source commit does not match")
    inspect_apk(apk, tools, metadata)
    notes = (source / f"docs/releases/{metadata['versionName']}.md").read_text(encoding="utf-8")
    _, separator, body = notes.partition("\n---\n")
    if not separator:
        raise ReleaseError("Release notes need a separator before the public body")
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(apk, output / "candidate.apk")
    (output / "candidate.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (output / "release-notes.md").write_text(body, encoding="utf-8")
    return metadata


def sign(candidate, output, commit, tools, *, expected_certificate=CERT_SHA256):
    encoded = os.environ.pop("VESQEN_KEYSTORE_BASE64", "")
    password = os.environ.pop("VESQEN_KEYSTORE_PASSWORD", "")
    metadata = validate_metadata(json.loads((candidate / "candidate.json").read_text()), commit)
    apk = candidate / "candidate.apk"
    if sha256(apk) != metadata["unsignedApkSha256"]:
        raise ReleaseError("Unsigned APK hash does not match")
    inspect_apk(apk, tools, metadata)
    # Secrets are step-scoped. Only apksigner receives the password, never the
    # encoded keystore, and app/Gradle code is never invoked in this process.
    if not encoded or not password:
        raise ReleaseError("Signing environment secrets are missing")
    try:
        key_bytes = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ReleaseError("Invalid encoded signing keystore") from error
    public_env = dict(os.environ)
    with tempfile.TemporaryDirectory(prefix="vesqen-sign-", dir=os.environ.get("RUNNER_TEMP")) as directory:
        staging = Path(directory)
        key = staging / "signing.p12"
        with key.open("xb") as target:
            os.chmod(key, 0o600)
            target.write(key_bytes)
        aligned = staging / "aligned.apk"
        signed = staging / "signed.apk"
        run([str(tools / "zipalign"), "-P", "16", "4", str(apk), str(aligned)], env=public_env)
        signing_env = {**public_env, "VESQEN_KEYSTORE_PASSWORD": password}
        run([str(tools / "apksigner"), "sign", "--ks", str(key), "--ks-type", "PKCS12",
             "--ks-key-alias", KEY_ALIAS, "--ks-pass", "env:VESQEN_KEYSTORE_PASSWORD",
             "--key-pass", "env:VESQEN_KEYSTORE_PASSWORD", "--debuggable-apk-permitted", "false",
             "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
             "--v4-signing-enabled", "false", "--out", str(signed), str(aligned)], env=signing_env)
        verification = run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(signed)], env=public_env)
        certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", verification)
        if [value.lower() for value in certificates] != [expected_certificate]:
            raise ReleaseError("Signed APK certificate does not match application signing v1")
        for scheme in ("v2", "v3"):
            if not re.search(r"Verified using " + scheme + r" scheme[^\n]*: true", verification):
                raise ReleaseError(f"APK signature scheme {scheme} did not verify")
        run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(signed)], env=public_env)
        inspect_apk(signed, tools, metadata)
        filename = f"Vesqen-{metadata['versionName']}.apk"
        digest = sha256(signed)
        metadata.update({"apkSha256": digest, "certificateSha256": expected_certificate,
                         "workflowCommit": os.environ.get("GITHUB_WORKFLOW_SHA", ""),
                         "workflowRun": os.environ.get("VESQEN_WORKFLOW_RUN_URL", "")})
        output.mkdir(parents=True, exist_ok=False)
        shutil.copyfile(signed, output / filename)
        (output / "SHA256SUMS").write_text(f"{digest}  {filename}\n")
        (output / "release-manifest.json").write_text(json.dumps(metadata, indent=2) + "\n")
        notes = (candidate / "release-notes.md").read_text(encoding="utf-8").replace("(发布时填写)", digest)
        provenance = (f"\n\n## Build record\n\nSource commit: `{commit}`\n\n"
                      f"versionCode: `{metadata['versionCode']}`\n\n"
                      f"Signing certificate SHA-256: `{expected_certificate}`\n\n"
                      f"Build and signing run: {metadata['workflowRun']}\n\n"
                      "Device QA and upgrade acceptance must be recorded before publishing.\n")
        (output / "release-notes.md").write_text(notes + provenance, encoding="utf-8")
    return metadata


def signed_metadata(candidate, commit):
    metadata = validate_metadata(json.loads((candidate / "release-manifest.json").read_text()), commit)
    filename = f"Vesqen-{metadata['versionName']}.apk"
    if sha256(candidate / filename) != metadata.get("apkSha256") or metadata.get("certificateSha256") != CERT_SHA256:
        raise ReleaseError("Signed candidate integrity does not match")
    return metadata


def draft_release(candidate, repository, commit):
    metadata = signed_metadata(candidate, commit)
    filename = f"Vesqen-{metadata['versionName']}.apk"
    tag = f"v{metadata['versionName']}"
    releases = json.loads(run(["gh", "api", "--paginate", "--slurp", f"repos/{repository}/releases?per_page=100"]))
    if any(release["tag_name"] == tag for page in releases for release in page):
        raise ReleaseError("A release already exists; refusing to replace any assets")
    for page in releases:
        for previous in page:
            if previous["draft"]:
                continue
            manifests = [asset for asset in previous["assets"] if asset["name"] == "release-manifest.json"]
            if len(manifests) != 1:
                raise ReleaseError("A published release lacks a unique version ledger")
            ledger = json.loads(run(["gh", "api", f"repos/{repository}/releases/assets/{int(manifests[0]['id'])}",
                                     "--header", "Accept: application/octet-stream"]))
            if type(ledger.get("versionCode")) is not int or metadata["versionCode"] <= ledger["versionCode"]:
                raise ReleaseError("versionCode must exceed every published release")
    command = ["gh", "release", "create", tag, "--repo", repository, "--target", commit, "--draft",
               "--title", f"Vesqen {metadata['versionName']}", "--notes-file", str(candidate / "release-notes.md")]
    if "-" in metadata["versionName"]:
        command.append("--prerelease")
    command += [str(candidate / name) for name in (filename, "SHA256SUMS", "release-manifest.json")]
    return run(command)


def validate_acceptance(record, metadata):
    for field in ("applicationId", "versionName", "versionCode", "sourceCommit", "apkSha256", "certificateSha256"):
        if record.get(field) != metadata.get(field):
            raise ReleaseError("Acceptance record does not describe this exact APK")
    for check in ("install", "sameSignerUpgrade", "dataRetention"):
        if record.get(check) != "passed":
            raise ReleaseError(f"Acceptance check {check} has not passed")
    evidence = record.get("deviceEvidence")
    if (not isinstance(evidence, str) or not evidence.startswith("docs/")
            or ".." in Path(evidence.split("#", 1)[0]).parts):
        raise ReleaseError("Acceptance record must link recorded device evidence")


def publish_release(candidate, repository, commit, acceptance, tools):
    metadata = signed_metadata(candidate, commit)
    record = json.loads(acceptance.read_text())
    validate_acceptance(record, metadata)
    evidence = acceptance.parents[2] / record["deviceEvidence"].split("#", 1)[0]
    if not evidence.is_file():
        raise ReleaseError("Recorded device evidence file is missing")
    tag = f"v{metadata['versionName']}"
    apk = candidate / f"Vesqen-{metadata['versionName']}.apk"
    inspect_apk(apk, tools, metadata)
    verification = run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)])
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", verification)
    if [value.lower() for value in certificates] != [CERT_SHA256]:
        raise ReleaseError("Publication APK signature does not match")
    for scheme in ("v2", "v3"):
        if not re.search(r"Verified using " + scheme + r" scheme[^\n]*: true", verification):
            raise ReleaseError(f"Publication APK signature scheme {scheme} did not verify")
    info = json.loads(run(["gh", "release", "view", tag, "--repo", repository,
                           "--json", "isDraft,targetCommitish,body"]))
    if not info["isDraft"] or info["targetCommitish"] != commit:
        raise ReleaseError("Only the matching draft can be published")
    if not info["body"].strip() or any(marker in info["body"] for marker in ("(发布时填写)", "(按 #42 的结果更新)")):
        raise ReleaseError("Finalize draft release notes before publication")
    ref_command = ["gh", "api", f"repos/{repository}/git/ref/tags/{tag}"]
    ref_result = subprocess.run(ref_command, capture_output=True, text=True, check=False)
    if ref_result.returncode == 0:
        ref = json.loads(ref_result.stdout)
        if ref["object"]["type"] != "tag":
            raise ReleaseError("An existing version tag must be annotated")
        annotation = json.loads(run(["gh", "api", f"repos/{repository}/git/tags/{ref['object']['sha']}"]))
        if annotation["object"]["type"] != "commit" or annotation["object"]["sha"] != commit:
            raise ReleaseError("Release tag does not point to the accepted commit")
    elif "(HTTP 404)" in ref_result.stderr:
        annotation = json.loads(run(["gh", "api", "--method", "POST", f"repos/{repository}/git/tags", "--input", "-"],
                                    input_text=json.dumps({"tag": tag, "message": f"Vesqen {metadata['versionName']}",
                                                           "object": commit, "type": "commit"})))
        run(["gh", "api", "--method", "POST", f"repos/{repository}/git/refs", "--input", "-"],
            input_text=json.dumps({"ref": f"refs/tags/{tag}", "sha": annotation["sha"]}))
    else:
        raise ReleaseError("Could not verify the release tag; publication stopped")
    return run(["gh", "release", "edit", tag, "--repo", repository, "--draft=false"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "sign", "draft", "publish"))
    parser.add_argument("--source", type=Path)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--branch")
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--repository")
    parser.add_argument("--acceptance", type=Path)
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            result = prepare(args.source, args.output, args.commit, args.branch, args.build_tools)
        elif args.action == "sign":
            result = sign(args.candidate, args.output, args.commit, args.build_tools)
        elif args.action == "draft":
            result = draft_release(args.candidate, args.repository, args.commit)
        else:
            result = publish_release(args.candidate, args.repository, args.commit, args.acceptance, args.build_tools)
    except ReleaseError as error:
        parser.exit(1, f"Release step failed: {error}\n")
    except (OSError, ValueError, KeyError) as error:
        # Raw exception text can contain file/input bytes; preserve the type.
        parser.exit(1, f"Release step failed: {type(error).__name__} while processing candidate files.\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
