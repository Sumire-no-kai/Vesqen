"""Writes the ALAC fixtures for Media3PcmCaptureDeviceTest (#270).

Android has no ALAC encoder, so the test cannot write these files itself as it does for WAV, FLAC
and AIFF. This script recreates the exact samples of the test's PcmFixture (a seeded
java.util.Random), writes them as WAV and has ffmpeg encode them losslessly to ALAC in M4A.

Usage: python3 tools/generate_alac_fixtures.py <output directory>
"""
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

CHANNELS = 2
# The test's ALAC fixtures: (sample rate, bit depth, duration in ms, seed).
FIXTURES = [(44_100, 16, 200, 1), (48_000, 24, 200, 1), (96_000, 24, 200, 1)]


class JavaRandom:
    """java.util.Random: the 48-bit linear congruential generator its documentation specifies."""

    MULTIPLIER = 0x5DEECE66D
    MASK = (1 << 48) - 1

    def __init__(self, seed):
        self.seed = (seed ^ self.MULTIPLIER) & self.MASK

    def next_bits(self, bits):
        self.seed = (self.seed * self.MULTIPLIER + 0xB) & self.MASK
        return self.seed >> (48 - bits)

    def next_int_power_of_two(self, bound):
        """nextInt(bound) for a power of two: (bound * next(31)) >> 31."""
        return (bound * self.next_bits(31)) >> 31


def fixture_samples(sample_rate, bit_depth, duration_ms, seed):
    """PcmFixture.samples: full-range stereo noise, interleaved."""
    frame_count = sample_rate // 100 * duration_ms // 10
    random = JavaRandom(seed)
    value_range = 1 << bit_depth
    return [random.next_int_power_of_two(value_range) - value_range // 2 for _ in range(frame_count * CHANNELS)]


def wav_bytes(samples, sample_rate, bit_depth):
    width = bit_depth // 8
    data = b"".join(s.to_bytes(width, "little", signed=True) for s in samples)
    fmt = struct.pack("<HHIIHH", 1, CHANNELS, sample_rate, sample_rate * CHANNELS * width, CHANNELS * width, bit_depth)
    body = b"WAVE" + b"fmt " + struct.pack("<I", len(fmt)) + fmt + b"data" + struct.pack("<I", len(data)) + data
    return b"RIFF" + struct.pack("<I", len(body)) + body


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as scratch:
        for sample_rate, bit_depth, duration_ms, seed in FIXTURES:
            name = f"ALAC-{sample_rate}-{bit_depth}-{duration_ms}-{seed}"
            wav = Path(scratch) / f"{name}.wav"
            wav.write_bytes(wav_bytes(fixture_samples(sample_rate, bit_depth, duration_ms, seed), sample_rate, bit_depth))
            sample_format = "s16p" if bit_depth == 16 else "s32p"
            subprocess.run(
                ["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", str(wav), "-c:a", "alac",
                 "-sample_fmt", sample_format, "-map_metadata", "-1", "-fflags", "+bitexact",
                 str(output / f"{name}.m4a")],
                check=True,
            )
            print(f"{name}.m4a")


if __name__ == "__main__":
    main()
