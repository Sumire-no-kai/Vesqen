#!/usr/bin/env python3
"""Generate static channel files from verified release assets; never publish the website."""

import json
from pathlib import Path
import re
import tempfile

import github_release as release


def public_notes(body):
    """The release-note template has an English body followed by a Chinese heading."""
    english, marker, chinese = body.partition("\n## 中文\n")
    if not marker:
        raise release.ReleaseError("Update notes require English and Chinese sections")
    english = re.sub(r"^\*\*English\*\*[^\n]*\n", "", english.strip())
    chinese = chinese.partition("\n## Build record\n")[0]

    def plain(text):
        text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"\1 (\2)", text)
        text = re.sub(r"^#{1,6}\s+", "", text, flags=re.M)
        text = re.sub(r"^---\s*$", "", text, flags=re.M)
        text = text.replace("`", "").replace("**", "").strip()
        if not 0 < len(text) <= 32768:
            raise release.ReleaseError("Invalid update-note length")
        return text
    return {"en": plain(english), "zh-CN": plain(chinese)}


def make_release(metadata, apk, badging, body, repository):
    release.validate_metadata(metadata, metadata.get("sourceCommit"))
    if (metadata.get("certificateSha256") != release.CERT_SHA256
            or release.sha256(apk) != metadata.get("apkSha256")):
        raise release.ReleaseError("Update asset does not match its signed release ledger")
    minimum = re.search(r"^sdkVersion:'(\d+)'$", badging, re.M)
    if not minimum or not 26 <= int(minimum[1]) <= 1000:
        raise release.ReleaseError("APK minimum SDK is missing or unsupported")
    return {"versionName": metadata["versionName"], "versionCode": metadata["versionCode"],
            "minimumAndroidApi": int(minimum[1]),
            "apkUrls": [f"https://github.com/{repository}/releases/download/v{metadata['versionName']}/Vesqen-{metadata['versionName']}.apk"],
            "sha256": metadata["apkSha256"], "releaseNotes": public_notes(body)}


def channel_files(releases):
    if len({item["versionCode"] for item in releases}) != len(releases):
        raise release.ReleaseError("Published version codes must be unique")
    stable = [item for item in releases if "-" not in item["versionName"]]
    return {channel: {"schemaVersion": 1, "channel": channel,
                      "release": max(candidates, key=lambda item: item["versionCode"], default=None)}
            for channel, candidates in (("stable", stable), ("beta", releases))}


def generate(candidate, repository, tools, output):
    """Run in the publication job before exposing the accepted draft. No tags or uploads here."""
    current = json.loads((candidate / "release-manifest.json").read_text())
    current = release.signed_metadata(candidate, current["sourceCommit"])
    pages = json.loads(release.run(["gh", "api", "--paginate", "--slurp",
                                   f"repos/{repository}/releases?per_page=100"]))
    records = [(current, candidate)]
    bodies = {}
    for page in pages:
        for item in page:
            if item["tag_name"] == f"v{current['versionName']}":
                bodies[current["versionName"]] = item["body"]
            if item["draft"]:
                continue
            assets = [asset for asset in item["assets"] if asset["name"] == "release-manifest.json"]
            if len(assets) != 1:
                raise release.ReleaseError("Published release needs a unique ledger")
            ledger = json.loads(release.run(["gh", "api", f"repos/{repository}/releases/assets/{int(assets[0]['id'])}",
                                            "--header", "Accept: application/octet-stream"]))
            release.validate_metadata(ledger, ledger.get("sourceCommit"))
            if item["tag_name"] != f"v{ledger['versionName']}":
                raise release.ReleaseError("Published tag and ledger disagree")
            records.append((ledger, None))
            bodies[ledger["versionName"]] = item["body"]
    if current["versionName"] not in bodies:
        raise release.ReleaseError("Accepted draft is missing")
    if len({record[0]["versionCode"] for record in records}) != len(records):
        raise release.ReleaseError("Published version codes must be unique")
    stable = [record for record in records if "-" not in record[0]["versionName"]]
    selected = {max(records, key=lambda record: record[0]["versionCode"])[0]["versionName"]}
    if stable:
        selected.add(max(stable, key=lambda record: record[0]["versionCode"])[0]["versionName"])
    payloads = []
    with tempfile.TemporaryDirectory(prefix="vesqen-update-manifest-") as temporary:
        for metadata, directory in records:
            version = metadata["versionName"]
            if version not in selected:
                continue
            filename = f"Vesqen-{version}.apk"
            if directory is None:
                directory = Path(temporary) / version
                directory.mkdir()
                release.run(["gh", "release", "download", f"v{version}", "--repo", repository,
                             "--pattern", filename, "--dir", str(directory)])
            apk = directory / filename
            release.inspect_apk(apk, tools, metadata)
            badging = release.run([str(tools / "aapt"), "dump", "badging", str(apk)])
            payloads.append(make_release(metadata, apk, badging, bodies[version], repository))
    output.mkdir(parents=True, exist_ok=False)
    for channel, manifest in channel_files(payloads).items():
        data = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
        if len(data.encode("utf-8")) > 128 * 1024:
            raise release.ReleaseError("Update manifest exceeds client size limit")
        (output / f"{channel}.json").write_text(data, encoding="utf-8")
