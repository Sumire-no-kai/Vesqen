import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import github_release as release


COMMIT = "a" * 40


def metadata():
    return {"applicationId": release.APP_ID, "versionName": "1.0.0-beta.1", "versionCode": 10,
            "sourceCommit": COMMIT, "sourceBranch": "release/1.0.0-beta.1", "unsignedApkSha256": "b" * 64}


class GithubReleaseTest(unittest.TestCase):
    def test_rejects_untrusted_candidate_identity(self):
        for field, value in (("versionName", "../other"), ("versionCode", True), ("versionCode", 0),
                             ("applicationId", "com.other.app"), ("sourceCommit", "c" * 40),
                             ("sourceBranch", "untrusted"), ("unsignedApkSha256", "invalid")):
            with self.subTest(field=field):
                data = {**metadata(), field: value}
                with self.assertRaises(release.ReleaseError):
                    release.validate_metadata(data, COMMIT)

    def test_version_trigger_requires_both_fields_to_advance(self):
        old = 'versionName=1.0.0-beta.1\nversionCode=10\n'
        self.assertFalse(release.version_bumped(old, old))
        self.assertTrue(release.version_bumped(old, 'versionName=1.0.0-beta.2\nversionCode=11\n'))
        for new in ('versionName=1.0.0-beta.1\nversionCode=11\n',
                    'versionName=1.0.0-beta.2\nversionCode=10\n',
                    'versionName=1.0.0-beta.2\nversionCode=9\n'):
            with self.subTest(new=new), self.assertRaises(release.ReleaseError):
                release.version_bumped(old, new)

    def test_rejects_shell_syntax_in_version_or_commit(self):
        for value in ("1.0.0;echo leak", "1.0.0\nleak", "1.0.0-$(printenv)"):
            with self.assertRaises(release.ReleaseError):
                release.validate_metadata({**metadata(), "versionName": value}, COMMIT)
        with self.assertRaises(release.ReleaseError):
            release.validate_metadata(metadata(), "HEAD")

    def test_rejects_debug_internet_billing_and_wrong_apk(self):
        header = "package: name='io.github.sumirenokai.vesqen' versionCode='10' versionName='1.0.0-beta.1'\n"
        for badging in (header + "application-debuggable\n",
                        header + "uses-permission: name='android.permission.INTERNET'\n",
                        header + "uses-permission-sdk-23: name='com.android.vending.BILLING'\n",
                        header.replace("versionCode='10'", "versionCode='11'")):
            with patch.object(release, "run", return_value=badging):
                with self.assertRaises(release.ReleaseError):
                    release.inspect_apk(Path("candidate.apk"), Path("tools"), metadata())
        with patch.object(release, "run", return_value=header):
            release.inspect_apk(Path("candidate.apk"), Path("tools"), metadata())

    def test_tool_failure_does_not_expose_credentials(self):
        result = subprocess.CompletedProcess(["apksigner"], 1, "private-key-marker", "password-marker")
        with patch.object(subprocess, "run", return_value=result):
            with self.assertRaises(release.ReleaseError) as caught:
                release.run(["apksigner", "sign"])
        self.assertNotIn("marker", str(caught.exception))
        self.assertIn("exit 1", str(caught.exception))

    def test_refuses_existing_release_and_never_uploads_over_it(self):
        with tempfile.TemporaryDirectory() as directory:
            candidate = Path(directory)
            apk = candidate / "Vesqen-1.0.0-beta.1.apk"
            apk.write_bytes(b"signed-candidate")
            data = {**metadata(), "apkSha256": release.sha256(apk), "certificateSha256": release.CERT_SHA256}
            (candidate / "release-manifest.json").write_text(json.dumps(data))
            results = [json.dumps([[{"tag_name": "v1.0.0-beta.1", "draft": False}]])]
            with patch.object(release, "run", side_effect=results) as command:
                with self.assertRaises(release.ReleaseError):
                    release.draft_release(candidate, "Sumire-no-kai/Vesqen", COMMIT)
                self.assertEqual(1, command.call_count)

    def test_acceptance_is_bound_to_exact_signed_apk(self):
        data = {**metadata(), "apkSha256": "f" * 64, "certificateSha256": release.CERT_SHA256}
        record = {**data, "install": "passed", "sameSignerUpgrade": "passed", "dataRetention": "passed",
                  "deviceEvidence": "docs/M4_DEVICE_ACCEPTANCE.md#candidate"}
        release.validate_acceptance(record, data)
        for field, value in (("apkSha256", "e" * 64), ("sourceCommit", "d" * 40),
                             ("sameSignerUpgrade", "pending"), ("deviceEvidence", "")):
            with self.subTest(field=field), self.assertRaises(release.ReleaseError):
                release.validate_acceptance({**record, field: value}, data)

    def test_manual_candidate_cannot_reuse_published_version_code(self):
        with patch.object(release, "signed_metadata", return_value=metadata()):
            history = [[{"tag_name": "v0.9.0", "draft": False,
                         "assets": [{"name": "release-manifest.json", "id": 123}]}]]
            for code in (10, 11):
                with patch.object(release, "run", side_effect=[json.dumps(history), json.dumps({"versionCode": code})]) as command:
                    with self.assertRaises(release.ReleaseError):
                        release.draft_release(Path("candidate"), "owner/repo", COMMIT)
                    self.assertEqual(2, command.call_count)

    def test_signing_failure_cleans_key_and_keeps_secrets_out_of_other_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate"
            candidate.mkdir()
            (candidate / "candidate.apk").write_bytes(b"unsigned-candidate")
            (candidate / "candidate.json").write_text(json.dumps({**metadata(), "unsignedApkSha256": release.sha256(candidate / "candidate.apk")}))
            key_path = None

            def command(args, *, env=None):
                nonlocal key_path
                if args[0].endswith("apksigner"):
                    key_path = Path(args[args.index("--ks") + 1])
                    self.assertTrue(key_path.exists())
                    self.assertEqual(0o600, key_path.stat().st_mode & 0o777)
                    self.assertNotIn("VESQEN_KEYSTORE_BASE64", env)
                    self.assertNotIn("secret-password-marker", args)
                    raise release.ReleaseError("apksigner failed")
                self.assertNotIn("VESQEN_KEYSTORE_PASSWORD", env)
                return ""

            with patch.dict(os.environ, {"VESQEN_KEYSTORE_BASE64": base64.b64encode(b"test-key").decode(),
                                         "VESQEN_KEYSTORE_PASSWORD": "secret-password-marker", "RUNNER_TEMP": directory}):
                with patch.object(release, "inspect_apk"), patch.object(release, "run", side_effect=command):
                    with self.assertRaises(release.ReleaseError):
                        release.sign(candidate, root / "signed", COMMIT, Path("tools"))
                self.assertNotIn("VESQEN_KEYSTORE_PASSWORD", os.environ)
                self.assertNotIn("VESQEN_KEYSTORE_BASE64", os.environ)
            self.assertIsNotNone(key_path)
            self.assertFalse(key_path.exists())
            self.assertFalse((root / "signed").exists())

    def test_publish_requires_matching_tag_and_never_rebuilds_or_uploads(self):
        for tag_type, target, succeeds in (("missing", COMMIT, True), ("tag", COMMIT, True), ("tag", "d" * 40, False),
                                            ("commit", COMMIT, False)):
            with self.subTest(tag_type=tag_type, target=target), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                receipt = root / "docs/releases/1.0.0-beta.1.acceptance.json"
                receipt.parent.mkdir(parents=True)
                (root / "docs/evidence.md").write_text("Device QA evidence")
                apk = root / "Vesqen-1.0.0-beta.1.apk"
                apk.write_bytes(b"accepted-apk")
                data = {**metadata(), "apkSha256": release.sha256(apk), "certificateSha256": release.CERT_SHA256}
                (root / "release-manifest.json").write_text(json.dumps(data))
                receipt.write_text(json.dumps({**data, "install": "passed", "sameSignerUpgrade": "passed",
                                               "dataRetention": "passed", "deviceEvidence": "docs/evidence.md"}))
                verification = (f"Signer #1 certificate SHA-256 digest: {release.CERT_SHA256}\n"
                                "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                                "Verified using v3 scheme (APK Signature Scheme v3): true\n")
                commands = []

                def command(args, **kwargs):
                    commands.append(args)
                    if "verify" in args:
                        return verification
                    if "view" in args:
                        return json.dumps({"isDraft": True, "targetCommitish": COMMIT, "body": "Accepted release notes"})
                    if "api" in args:
                        if "POST" in args:
                            payload = json.loads(kwargs["input_text"])
                            if args[args.index("POST") + 1].endswith("/git/tags"):
                                self.assertEqual(COMMIT, payload["object"])
                                return json.dumps({"sha": "f" * 40})
                            self.assertEqual("refs/tags/v1.0.0-beta.1", payload["ref"])
                            return "{}"
                        return json.dumps({"object": {"type": "commit", "sha": target}})
                    return ""

                ref = subprocess.CompletedProcess([], 0, json.dumps({"object": {"type": tag_type, "sha": "f" * 40}}), "")
                if tag_type == "missing":
                    ref = subprocess.CompletedProcess([], 1, "", "gh: Not Found (HTTP 404)")
                with patch.object(release, "inspect_apk"), patch.object(release, "run", side_effect=command), \
                        patch.object(subprocess, "run", return_value=ref):
                    if succeeds:
                        release.publish_release(root, "owner/repo", COMMIT, receipt, Path("tools"))
                    else:
                        with self.assertRaises(release.ReleaseError):
                            release.publish_release(root, "owner/repo", COMMIT, receipt, Path("tools"))
                self.assertEqual(succeeds, any("--draft=false" in args for args in commands))
                self.assertFalse(any("upload" in args or "sign" in args or "gradlew" in args for args in commands))


if __name__ == "__main__":
    unittest.main()
