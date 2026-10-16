#!/usr/bin/env python3
"""Publish the failing instrumentation tests of an emulator run as a GitHub check annotation.

Gradle's connected-test output only says "There were failing tests". The per-test results are in
the androidTest XML reports, which name the class, the test, and the first line of the failure.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RESULTS = Path("app/build/outputs/androidTest-results")
MESSAGE_CHARS = 3500


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main() -> int:
    files = sorted(RESULTS.rglob("*.xml")) if RESULTS.is_dir() else []
    if not files:
        print(f"::warning title=No androidTest results::{RESULTS} has no XML reports")
        return 0
    failed, tests = [], 0
    for file in files:
        try:
            root = ET.parse(file).getroot()
        except ET.ParseError as error:
            failed.append(f"  unreadable report {file.name}: {error}")
            continue
        for case in root.iter("testcase"):
            tests += 1
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is None:
                continue
            message = (problem.get("message") or problem.text or "").strip().splitlines()
            detail = message[0][:160] if message else ""
            name = f"{case.get('classname', '?').rsplit('.', 1)[-1]}.{case.get('name', '?')}"
            failed.append(f"  {name[:200]}" + (f" -- {detail}" if detail else ""))
    lines = [f"androidTest: {len(failed)} failing of {tests} cases in {len(files)} report(s)"]
    lines.extend(failed)
    message = "\n".join(lines)
    if len(message) > MESSAGE_CHARS:
        message = message[:MESSAGE_CHARS - 20] + "\n  ...more not listed"
    print(f"::error title=Failing instrumentation tests::{escape(message)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
