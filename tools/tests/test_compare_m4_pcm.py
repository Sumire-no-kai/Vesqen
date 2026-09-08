from pathlib import Path
import sys
import tempfile
import unittest
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from compare_m4_pcm import compare


def write_wav(path: Path, frames: bytes, rate: int = 48000):
    with wave.open(str(path), "wb") as target:
        target.setnchannels(2)
        target.setsampwidth(2)
        target.setframerate(rate)
        target.writeframes(frames)


class CompareM4PcmTest(unittest.TestCase):
    def test_aligned_identical_pcm_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = root / "expected.wav"
            captured = root / "captured.wav"
            frames = b"\x01\x00\x02\x00" * 32
            write_wav(expected, frames)
            write_wav(captured, b"\x00\x00\x00\x00" + frames)

            result = compare(expected, captured, root / "result.json", captured_start_frame=1)

            self.assertTrue(result["exactMatch"])
            self.assertEqual(32, result["framesCompared"])

    def test_first_mismatch_is_reported_without_false_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = root / "expected.wav"
            captured = root / "captured.wav"
            write_wav(expected, b"\x01\x00\x02\x00" * 4)
            write_wav(captured, b"\x01\x00\x03\x00" + b"\x01\x00\x02\x00" * 3)

            result = compare(expected, captured, root / "result.json")

            self.assertFalse(result["exactMatch"])
            self.assertEqual(2, result["firstMismatchByte"])
            self.assertEqual(0, result["firstMismatchFrame"])


if __name__ == "__main__":
    unittest.main()
