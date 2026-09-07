"""Run installed, matching Vesqen instrumentation APKs without clearing user data.

--foreground-host is an explicit device-lab launch aid: on the tested vivo ROM,
startActivitySync can wait indefinitely while the test process has no foreground
activity. Launch the same MAIN/LAUNCHER test host once per case from adb; never
retry assertions or change Android's security/background policy.
"""

import argparse
import json
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--classes", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--foreground-host", nargs="?", const="androidx.activity.ComponentActivity")
    parser.add_argument("--case-timeout", type=float, default=120)
    parser.add_argument("--instrumentation-arg", action="append", default=[], metavar="KEY=VALUE")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-s", args.serial]
    package = "io.github.sumirenokai.vesqen"
    command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", args.classes]
    for argument in args.instrumentation_arg:
        key, value = argument.split("=", 1)
        command += ["-e", key, value]
    command.append(package + ".test/androidx.test.runner.AndroidJUnitRunner")
    lines = queue.Queue()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, encoding="utf-8", errors="replace")

    def read_output():
        for line in process.stdout:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    transcript = []
    started = time.monotonic()
    deadline = started + args.case_timeout
    timed_out = False
    passed = False
    try:
        with (args.output / "instrumentation.txt").open("w", encoding="utf-8") as log:
            while True:
                if time.monotonic() > deadline:
                    timed_out = True
                    raise TimeoutError("No completed test within the case timeout")
                try:
                    line = lines.get(timeout=1)
                except queue.Empty:
                    continue
                if line is None:
                    break
                transcript.append(line)
                log.write(line)
                log.flush()
                print(line, end="", flush=True)
                if line.strip() == "INSTRUMENTATION_STATUS_CODE: 1":
                    deadline = time.monotonic() + args.case_timeout
                    if args.foreground_host:
                        launch = subprocess.run(adb + ["shell", "am", "start", "-W", "-a",
                            "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                            "--activity-single-top", "-n", package + "/" + args.foreground_host],
                            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20)
                        with (args.output / "host-launches.txt").open("a", encoding="utf-8") as hosts:
                            hosts.write(launch.stdout + launch.stderr)
                        if launch.returncode != 0 or "Status: ok" not in launch.stdout:
                            raise RuntimeError("Test-host foreground launch failed")
                elif line.strip() == "INSTRUMENTATION_STATUS_CODE: 0":
                    deadline = time.monotonic() + args.case_timeout
        exit_code = process.wait(timeout=10)
        output = "".join(transcript)
        statuses = re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)", output, re.MULTILINE)
        passed = (exit_code == 0 and "INSTRUMENTATION_CODE: -1" in output and "\nOK (" in output
                  and "0" in statuses and all(status in {"0", "1"} for status in statuses))
        if not passed:
            raise RuntimeError("Instrumentation did not report a complete successful run")
    finally:
        statuses = re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)", "".join(transcript), re.MULTILINE)
        (args.output / "result.json").write_text(json.dumps({
            "serial": args.serial, "classes": args.classes,
            "foreground_host": args.foreground_host, "passed": passed,
            "instrumentation_arguments": args.instrumentation_arg,
            "process_exit_code": process.poll(), "timed_out": timed_out,
            "elapsed_seconds": round(time.monotonic() - started, 3),
            "tests_passed": statuses.count("0"),
            "tests_failed": statuses.count("-1") + statuses.count("-2"),
            "tests_skipped": statuses.count("-3") + statuses.count("-4"),
        }, indent=2), encoding="utf-8")
        if process.poll() is None:
            subprocess.run(adb + ["shell", "am", "force-stop", package], timeout=15, check=True)
            process.terminate()
            process.wait(timeout=10)


if __name__ == "__main__":
    main()
