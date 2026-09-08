"""Generate deterministic, redistributable PCM WAV vectors for M4 digital comparison.

The vectors are synthesized from integer arithmetic and contain no third-party recording. Output
directories must not exist so an earlier evidence set cannot be overwritten accidentally.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import wave


FORMATS = (
    (44_100, 16, "m4-44100hz-16bit-stereo.wav"),
    (48_000, 24, "m4-48000hz-24bit-stereo.wav"),
    (96_000, 24, "m4-96000hz-24bit-stereo.wav"),
)


def _pcm(sample: int, bits: int) -> bytes:
    width = bits // 8
    return int(sample).to_bytes(width, byteorder="little", signed=True)


def _sample(index: int, channel: int, bits: int) -> int:
    maximum = (1 << (bits - 1)) - 1
    period = 997 + channel * 214
    saw = ((index % period) * 2 * maximum // period) - maximum
    square = maximum // 5 if (index // (period // 7)) % 2 == 0 else -(maximum // 5)
    impulse = maximum // 2 if index % 4093 == channel * 31 else 0
    value = (saw // 3) + square + impulse
    return max(-maximum - 1, min(maximum, value))


def generate(output: Path, seconds: int = 3) -> dict:
    if output.exists():
        raise ValueError(f"refusing to overwrite existing output: {output}")
    if seconds < 1 or seconds > 30:
        raise ValueError("seconds must be between 1 and 30")
    output.mkdir(parents=True)
    entries = []
    for sample_rate, bits, file_name in FORMATS:
        path = output / file_name
        with wave.open(str(path), "wb") as target:
            target.setnchannels(2)
            target.setsampwidth(bits // 8)
            target.setframerate(sample_rate)
            block = bytearray()
            for index in range(sample_rate * seconds):
                block.extend(_pcm(_sample(index, 0, bits), bits))
                block.extend(_pcm(_sample(index, 1, bits), bits))
                if len(block) >= 256 * 1024:
                    target.writeframesraw(block)
                    block.clear()
            target.writeframes(block)
        data = path.read_bytes()
        entries.append({
            "file": file_name,
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
            "sampleRateHz": sample_rate,
            "bitsPerSample": bits,
            "channelCount": 2,
            "durationSeconds": seconds,
        })
    manifest = {
        "schemaVersion": 1,
        "origin": "Deterministic integer synthesis by tools/generate_m4_test_vectors.py",
        "redistribution": "CC0-1.0",
        "vectors": entries,
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seconds", type=int, default=3)
    args = parser.parse_args()
    try:
        manifest = generate(args.output, args.seconds)
    except ValueError as failure:
        parser.error(str(failure))
    print(json.dumps(manifest, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
