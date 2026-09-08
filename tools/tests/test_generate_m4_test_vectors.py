import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generate_m4_test_vectors import FORMATS, generate


class GenerateM4TestVectorsTest(unittest.TestCase):
    def test_vectors_match_manifest_and_pcm_headers(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "vectors"
            manifest = generate(output, seconds=1)

            self.assertEqual(len(FORMATS), len(manifest["vectors"]))
            for entry in manifest["vectors"]:
                path = output / entry["file"]
                self.assertEqual(entry["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
                with wave.open(str(path), "rb") as source:
                    self.assertEqual(2, source.getnchannels())
                    self.assertEqual(entry["bitsPerSample"] // 8, source.getsampwidth())
                    self.assertEqual(entry["sampleRateHz"], source.getframerate())
                    self.assertEqual(entry["sampleRateHz"], source.getnframes())

    def test_existing_evidence_directory_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "vectors"
            output.mkdir()
            marker = output / "keep.txt"
            marker.write_text("prior evidence", encoding="utf-8")

            with self.assertRaises(ValueError):
                generate(output, seconds=1)

            self.assertEqual("prior evidence", marker.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
