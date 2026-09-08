"""Prepare and inspect Vesqen M4 output-verification payloads.

This tool validates the compatibility matrix before signing. It never turns a measurement into
VERIFIED: the maintainer must supply the result, external method, signal point, and evidence link.
Existing output files are not overwritten so failed and superseded records remain auditable.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
from typing import Any


SCHEMA_VERSION = 1
RESULTS = {"VERIFIED", "FAILED", "NOT_TESTED", "MISSING_DEVICE", "DEFERRED"}
STABLE_ID = re.compile(r"[a-z][a-z0-9_]*(\.[a-z0-9_]+)*\Z")
SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")
RECORD_FIELDS = {
    "recordId", "result", "verifiedAtEpochMs", "appVersionName", "appVersionCode",
    "baseApkSha256", "deviceManufacturer", "deviceModel", "androidApiLevel",
    "buildFingerprintSha256", "dacVendorId", "dacProductId", "dacName",
    "dacDescriptorVersion", "sourceFormat", "sinkFormat", "testVectorSha256",
    "methodId", "signalPoint", "evidenceReference",
}
FORMAT_FIELDS = {"sampleRateHz", "channelCount", "encoding"}


class ValidationError(ValueError):
    pass


def _no_duplicate_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValidationError(f"duplicate field: {key}")
        result[key] = value
    return result


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_no_duplicate_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as failure:
        raise ValidationError(f"cannot read JSON: {failure}") from failure


def _exact_int(value: Any, name: str, minimum: int, maximum: int | None = None) -> int:
    if type(value) is not int or value < minimum or (maximum is not None and value > maximum):
        raise ValidationError(f"{name} must be an integer in range")
    return value


def _text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"{name} must be non-empty text")
    return value


def _format(value: Any, name: str) -> None:
    if not isinstance(value, dict) or set(value) != FORMAT_FIELDS:
        raise ValidationError(f"{name} must contain exactly {sorted(FORMAT_FIELDS)}")
    _exact_int(value["sampleRateHz"], f"{name}.sampleRateHz", 1)
    _exact_int(value["channelCount"], f"{name}.channelCount", 1)
    _text(value["encoding"], f"{name}.encoding")


def validate_payload(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict) or set(payload) != {"schemaVersion", "records"}:
        raise ValidationError("payload must contain exactly schemaVersion and records")
    if payload["schemaVersion"] != SCHEMA_VERSION:
        raise ValidationError(f"schemaVersion must be {SCHEMA_VERSION}")
    records = payload["records"]
    if not isinstance(records, list) or len(records) > 128:
        raise ValidationError("records must be an array with at most 128 entries")
    ids: set[str] = set()
    for index, record in enumerate(records):
        prefix = f"records[{index}]"
        if not isinstance(record, dict) or set(record) != RECORD_FIELDS:
            missing = sorted(RECORD_FIELDS - set(record) if isinstance(record, dict) else RECORD_FIELDS)
            extra = sorted(set(record) - RECORD_FIELDS if isinstance(record, dict) else set())
            raise ValidationError(f"{prefix} fields differ; missing={missing}, extra={extra}")
        record_id = _text(record["recordId"], f"{prefix}.recordId")
        if not STABLE_ID.fullmatch(record_id) or record_id in ids:
            raise ValidationError(f"{prefix}.recordId must be unique and stable")
        ids.add(record_id)
        if record["result"] not in RESULTS:
            raise ValidationError(f"{prefix}.result must be one of {sorted(RESULTS)}")
        _exact_int(record["verifiedAtEpochMs"], f"{prefix}.verifiedAtEpochMs", 0)
        _exact_int(record["appVersionCode"], f"{prefix}.appVersionCode", 1)
        _exact_int(record["androidApiLevel"], f"{prefix}.androidApiLevel", 26)
        if record["result"] == "VERIFIED" and record["androidApiLevel"] < 34:
            raise ValidationError(f"{prefix} cannot be VERIFIED below Android API 34")
        _exact_int(record["dacVendorId"], f"{prefix}.dacVendorId", 0, 0xFFFF)
        _exact_int(record["dacProductId"], f"{prefix}.dacProductId", 0, 0xFFFF)
        for field in (
            "appVersionName", "deviceManufacturer", "deviceModel", "dacName",
            "dacDescriptorVersion", "evidenceReference",
        ):
            _text(record[field], f"{prefix}.{field}")
        for field in ("recordId", "methodId", "signalPoint"):
            if not STABLE_ID.fullmatch(_text(record[field], f"{prefix}.{field}")):
                raise ValidationError(f"{prefix}.{field} must be a stable dotted id")
        for field in ("baseApkSha256", "buildFingerprintSha256", "testVectorSha256"):
            if not SHA256.fullmatch(_text(record[field], f"{prefix}.{field}")):
                raise ValidationError(f"{prefix}.{field} must be SHA-256 hex")
            record[field] = record[field].lower()
        _format(record["sourceFormat"], f"{prefix}.sourceFormat")
        _format(record["sinkFormat"], f"{prefix}.sinkFormat")
    return payload


def canonical_bytes(payload: dict[str, Any]) -> bytes:
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def prepare(source: Path, output: Path) -> None:
    if output.exists():
        raise ValidationError(f"refusing to overwrite existing output: {output}")
    payload = validate_payload(load_json(source))
    encoded = canonical_bytes(payload)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(encoded)
    print(json.dumps({
        "payload": str(output),
        "records": len(payload["records"]),
        "sha256": hashlib.sha256(encoded).hexdigest(),
    }, sort_keys=True))


def inspect_registry(source: Path) -> None:
    envelope = load_json(source)
    expected = {"schemaVersion", "signatureAlgorithm", "payload", "signature"}
    if not isinstance(envelope, dict) or set(envelope) != expected:
        raise ValidationError(f"registry envelope must contain exactly {sorted(expected)}")
    if envelope["schemaVersion"] != SCHEMA_VERSION:
        raise ValidationError(f"schemaVersion must be {SCHEMA_VERSION}")
    if envelope["signatureAlgorithm"] not in {"SHA256withRSA", "SHA256withECDSA"}:
        raise ValidationError("unsupported signature algorithm")
    try:
        payload_bytes = base64.b64decode(envelope["payload"], validate=True)
        signature_bytes = base64.b64decode(envelope["signature"], validate=True)
        payload = json.loads(payload_bytes, object_pairs_hook=_no_duplicate_object)
    except (ValueError, TypeError, json.JSONDecodeError) as failure:
        raise ValidationError(f"invalid registry encoding: {failure}") from failure
    validate_payload(payload)
    print(json.dumps({
        "records": len(payload["records"]),
        "payloadSha256": hashlib.sha256(payload_bytes).hexdigest(),
        "signatureBytes": len(signature_bytes),
        "signatureCryptographicallyVerified": False,
    }, sort_keys=True))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prepare_parser = sub.add_parser("prepare", help="validate and canonicalize a payload before signing")
    prepare_parser.add_argument("--source", type=Path, required=True)
    prepare_parser.add_argument("--output", type=Path, required=True)
    inspect_parser = sub.add_parser("inspect", help="inspect a signed registry without claiming crypto verification")
    inspect_parser.add_argument("--source", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(args.source, args.output)
        else:
            inspect_registry(args.source)
    except ValidationError as failure:
        parser.error(str(failure))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
