"""Collect a non-destructive M4 startup/resource baseline from one explicit Android device.

This is a repeatable local baseline, not a long-run power or release acceptance claim. Library and
Chain interaction jank remain separate runs of measure_library_scroll.py with a controlled viewport.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


def parse_start_output(raw: str) -> dict[str, int | str]:
    result: dict[str, int | str] = {}
    for key in ("Status", "Activity", "ThisTime", "TotalTime", "WaitTime", "LaunchState"):
        match = re.search(rf"^{key}:\s*(.+)$", raw, re.MULTILINE)
        if match:
            value = match.group(1).strip()
            result[key] = int(value) if key.endswith("Time") and value.isdigit() else value
    if result.get("Status") != "ok" or "TotalTime" not in result:
        raise ValueError("am start -W did not report a successful timed launch")
    return result


def resolve_activity(raw: str) -> str:
    candidates = [line.strip() for line in raw.splitlines() if re.fullmatch(r"[^\s/]+/[^\s]+", line.strip())]
    if len(candidates) != 1:
        raise ValueError("launcher activity could not be resolved unambiguously")
    return candidates[0]


class Collector:
    def __init__(self, adb: str, serial: str, package: str, output: Path, allow_debuggable: bool):
        self.adb = [adb, "-s", serial]
        self.serial = serial
        self.package = package
        self.output = output
        self.allow_debuggable = allow_debuggable

    def call(self, *args: str, timeout: int = 90) -> str:
        return subprocess.check_output(
            self.adb + list(args), stderr=subprocess.STDOUT, timeout=timeout,
        ).decode("utf-8", errors="replace")

    def optional(self, *args: str) -> str:
        try:
            return self.call(*args)
        except (subprocess.SubprocessError, OSError):
            return "UNAVAILABLE\n"

    def collect(self, repeats: int) -> dict:
        if self.output.exists():
            raise ValueError(f"refusing to overwrite existing output: {self.output}")
        if repeats < 3 or repeats > 20:
            raise ValueError("startup repeats must be between 3 and 20")
        self.output.mkdir(parents=True)
        package_dump = self.call("shell", "dumpsys", "package", self.package)
        debuggable = bool(re.search(r"\bDEBUGGABLE\b", package_dump))
        if debuggable and not self.allow_debuggable:
            raise ValueError("Debug APK installed; use --allow-debuggable only for a labelled comparison")
        activity = resolve_activity(
            self.call("shell", "cmd", "package", "resolve-activity", "--brief", self.package),
        )

        apk_paths = [
            line.removeprefix("package:") for line in self.call("shell", "pm", "path", self.package).splitlines()
            if line.startswith("package:")
        ]
        if not apk_paths:
            raise ValueError("app is not installed")
        apks = []
        for path in apk_paths:
            digest = self.call("shell", "sha256sum", path).split()[0].lower()
            size_text = self.optional("shell", "stat", "-c", "%s", path).strip()
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError("installed APK hash is unavailable")
            apks.append({"name": Path(path).name, "sha256": digest, "bytes": int(size_text) if size_text.isdigit() else None})

        fingerprint = self.call("shell", "getprop", "ro.build.fingerprint").strip()
        device = {
            "serial": self.serial,
            "manufacturer": self.call("shell", "getprop", "ro.product.manufacturer").strip(),
            "model": self.call("shell", "getprop", "ro.product.model").strip(),
            "androidApiLevel": int(self.call("shell", "getprop", "ro.build.version.sdk").strip()),
            "androidRelease": self.call("shell", "getprop", "ro.build.version.release").strip(),
            "buildFingerprintSha256": hashlib.sha256(fingerprint.encode("utf-8")).hexdigest(),
        }
        starts = []
        for index in range(repeats):
            self.call("shell", "am", "force-stop", self.package)
            time.sleep(0.5)
            raw = self.call("shell", "am", "start", "-W", "-n", activity)
            (self.output / f"startup-{index + 1}.txt").write_text(raw, encoding="utf-8")
            starts.append(parse_start_output(raw))
            time.sleep(1)

        for name, command in {
            "meminfo.txt": ("shell", "dumpsys", "meminfo", self.package),
            "gfxinfo.txt": ("shell", "dumpsys", "gfxinfo", self.package, "framestats"),
            "battery.txt": ("shell", "dumpsys", "battery"),
            "thermal.txt": ("shell", "dumpsys", "thermalservice"),
            "cpuinfo.txt": ("shell", "dumpsys", "cpuinfo"),
        }.items():
            (self.output / name).write_text(self.optional(*command), encoding="utf-8")

        total_times = [int(item["TotalTime"]) for item in starts]
        this_times = [int(item["ThisTime"]) for item in starts if "ThisTime" in item]
        summary = {
            "schemaVersion": 1,
            "scope": "local_repeatable_baseline_not_release_acceptance",
            "package": self.package,
            "debuggable": debuggable,
            "device": device,
            "apks": apks,
            "startup": {
                "runs": starts,
                "totalTimeMsMin": min(total_times),
                "totalTimeMsMax": max(total_times),
                "totalTimeMsMedian": sorted(total_times)[len(total_times) // 2],
                "thisTimeMsMin": min(this_times) if this_times else None,
                "thisTimeMsMax": max(this_times) if this_times else None,
                "thisTimeMsMedian": sorted(this_times)[len(this_times) // 2] if this_times else None,
            },
            "separateRequiredRuns": ["library_scroll", "chain_scroll", "long_run_power", "audio_underrun"],
        }
        (self.output / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--package", default="io.github.sumirenokai.vesqen")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--allow-debuggable", action="store_true")
    args = parser.parse_args()
    try:
        summary = Collector(args.adb, args.serial, args.package, args.output, args.allow_debuggable).collect(args.repeats)
    except (ValueError, subprocess.SubprocessError, OSError) as failure:
        parser.error(str(failure))
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
