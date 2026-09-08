import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from m4_verification import ValidationError, canonical_bytes, load_json, prepare, validate_payload


def payload():
    format_value = {"sampleRateHz": 96000, "channelCount": 2, "encoding": "pcm 24-bit"}
    return {
        "schemaVersion": 1,
        "records": [{
            "recordId": "m4.reference_96k24",
            "result": "VERIFIED",
            "verifiedAtEpochMs": 1788800000000,
            "appVersionName": "0.4.0-beta.1",
            "appVersionCode": 9,
            "baseApkSha256": "A" * 64,
            "deviceManufacturer": "Example",
            "deviceModel": "Reference Phone",
            "androidApiLevel": 35,
            "buildFingerprintSha256": "B" * 64,
            "dacVendorId": 0x1234,
            "dacProductId": 0x5678,
            "dacName": "Reference DAC",
            "dacDescriptorVersion": "2.10",
            "sourceFormat": format_value,
            "sinkFormat": dict(format_value),
            "testVectorSha256": "C" * 64,
            "methodId": "digital_capture.sample_compare",
            "signalPoint": "usb_digital_pcm",
            "evidenceReference": "evidence/m4-reference-96k24",
        }],
    }


class M4VerificationTest(unittest.TestCase):
    def test_valid_payload_is_normalized_and_canonical(self):
        validated = validate_payload(payload())
        record = validated["records"][0]

        self.assertEqual("a" * 64, record["baseApkSha256"])
        self.assertEqual(canonical_bytes(validated), canonical_bytes(validated))

    def test_each_compatibility_identity_is_required(self):
        for field in ("baseApkSha256", "buildFingerprintSha256", "dacDescriptorVersion", "sinkFormat"):
            candidate = payload()
            del candidate["records"][0][field]
            with self.subTest(field=field), self.assertRaises(ValidationError):
                validate_payload(candidate)

    def test_duplicate_json_field_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "duplicate.json"
            source.write_text('{"schemaVersion":1,"schemaVersion":1,"records":[]}', encoding="utf-8")
            with self.assertRaises(ValidationError):
                load_json(source)

    def test_prepare_refuses_to_overwrite_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.json"
            output = Path(directory) / "payload.json"
            source.write_text(json.dumps(payload()), encoding="utf-8")
            prepare(source, output)
            original = output.read_bytes()

            with self.assertRaises(ValidationError):
                prepare(source, output)

            self.assertEqual(original, output.read_bytes())

    def test_failed_result_never_becomes_verified(self):
        candidate = copy.deepcopy(payload())
        candidate["records"][0]["result"] = "FAILED"
        self.assertEqual("FAILED", validate_payload(candidate)["records"][0]["result"])

    def test_verified_result_is_rejected_below_android_14(self):
        candidate = payload()
        candidate["records"][0]["androidApiLevel"] = 33
        with self.assertRaises(ValidationError):
            validate_payload(candidate)


if __name__ == "__main__":
    unittest.main()
