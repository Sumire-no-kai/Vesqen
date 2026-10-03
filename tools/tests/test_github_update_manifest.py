import hashlib
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import github_release as release
import github_update_manifest as updates


class UpdateManifestTests(unittest.TestCase):
    def test_channels_promote_stable_to_beta_and_stable_never_contains_beta(self):
        beta = {"versionName": "1.0.0-beta.2", "versionCode": 11}
        stable = {"versionName": "1.0.0", "versionCode": 12}
        future = {"versionName": "1.1.0-beta.1", "versionCode": 13}
        self.assertIsNone(updates.channel_files([beta])["stable"]["release"])
        self.assertEqual(stable, updates.channel_files([beta, stable])["beta"]["release"])
        self.assertEqual(future, updates.channel_files([beta, stable, future])["beta"]["release"])
        self.assertEqual(stable, updates.channel_files([beta, stable, future])["stable"]["release"])
        with self.assertRaises(release.ReleaseError):
            updates.channel_files([beta, beta])

    def test_notes_require_both_languages_and_are_plain_text(self):
        notes = updates.public_notes("**English** · [中文](#中文)\n\n## Change\n**Fix** `playback`.\n---\n## 中文\n修复播放。\n## Build record\nprivate build context")
        self.assertEqual("Change\nFix playback.", notes["en"])
        self.assertEqual("修复播放。", notes["zh-CN"])
        with self.assertRaises(release.ReleaseError):
            updates.public_notes("English only")

    def test_manifest_binds_the_exact_asset_and_minimum_sdk(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            apk.write_bytes(b"signed asset")
            metadata = {"applicationId": release.APP_ID, "sourceCommit": "a" * 40,
                        "sourceBranch": "master", "versionName": "1.0.0-beta.2", "versionCode": 11,
                        "unsignedApkSha256": "b" * 64, "certificateSha256": release.CERT_SHA256,
                        "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
            args = [metadata, apk, "sdkVersion:'26'\n", "English notes\n## 中文\n中文说明", "Sumire-no-kai/Vesqen"]
            result = updates.make_release(*args)
            self.assertEqual(metadata["apkSha256"], result["sha256"])
            self.assertEqual(26, result["minimumAndroidApi"])
            self.assertIn("Vesqen-1.0.0-beta.2.apk", result["apkUrls"][0])
            apk.write_bytes(b"substitution")
            with self.assertRaises(release.ReleaseError):
                updates.make_release(*args)
