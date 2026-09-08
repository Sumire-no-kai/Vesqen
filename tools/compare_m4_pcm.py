"""Compare two aligned PCM WAV windows byte-for-byte and write an immutable JSON result.

Capture and alignment remain lab responsibilities. This tool does not treat an analog recording,
DAC display, matching format header, or listening result as bit-perfect evidence.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import wave


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(128 * 1024):
            digest.update(block)
    return digest.hexdigest()


def compare(
    expected: Path,
    captured: Path,
    output: Path,
    expected_start_frame: int = 0,
    captured_start_frame: int = 0,
    frame_count: int | None = None,
) -> dict:
    if output.exists():
        raise ValueError(f"refusing to overwrite existing output: {output}")
    if expected_start_frame < 0 or captured_start_frame < 0 or (frame_count is not None and frame_count < 1):
        raise ValueError("frame offsets must be non-negative and frame count must be positive")
    with wave.open(str(expected), "rb") as reference, wave.open(str(captured), "rb") as observation:
        reference_format = (reference.getframerate(), reference.getnchannels(), reference.getsampwidth())
        observation_format = (observation.getframerate(), observation.getnchannels(), observation.getsampwidth())
        if reference_format != observation_format:
            raise ValueError(f"PCM formats differ: expected={reference_format}, captured={observation_format}")
        available = min(
            reference.getnframes() - expected_start_frame,
            observation.getnframes() - captured_start_frame,
        )
        frames = available if frame_count is None else frame_count
        if frames < 1 or frames > available:
            raise ValueError("requested comparison window is outside one of the WAV files")
        reference.setpos(expected_start_frame)
        observation.setpos(captured_start_frame)
        expected_pcm = reference.readframes(frames)
        captured_pcm = observation.readframes(frames)
        bytes_per_frame = reference_format[1] * reference_format[2]

    mismatch_byte = next(
        (index for index, pair in enumerate(zip(expected_pcm, captured_pcm)) if pair[0] != pair[1]),
        None,
    )
    result = {
        "schemaVersion": 1,
        "methodId": "digital_capture.sample_compare",
        "signalPointRequired": "usb_digital_pcm",
        "expectedFileSha256": _sha256(expected),
        "capturedFileSha256": _sha256(captured),
        "sampleRateHz": reference_format[0],
        "channelCount": reference_format[1],
        "bitsPerSample": reference_format[2] * 8,
        "expectedStartFrame": expected_start_frame,
        "capturedStartFrame": captured_start_frame,
        "framesCompared": frames,
        "pcmBytesCompared": len(expected_pcm),
        "exactMatch": mismatch_byte is None and len(expected_pcm) == len(captured_pcm),
        "firstMismatchByte": mismatch_byte,
        "firstMismatchFrame": None if mismatch_byte is None else mismatch_byte // bytes_per_frame,
        "limitations": "Result is valid only if the captured file came from the documented USB digital PCM signal point.",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected", type=Path, required=True)
    parser.add_argument("--captured", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-start-frame", type=int, default=0)
    parser.add_argument("--captured-start-frame", type=int, default=0)
    parser.add_argument("--frames", type=int)
    args = parser.parse_args()
    try:
        result = compare(
            args.expected,
            args.captured,
            args.output,
            args.expected_start_frame,
            args.captured_start_frame,
            args.frames,
        )
    except (ValueError, OSError, wave.Error) as failure:
        parser.error(str(failure))
    print(json.dumps(result, sort_keys=True))
    return 0 if result["exactMatch"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
