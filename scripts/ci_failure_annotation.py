#!/usr/bin/env python3
"""Publish the most useful part of a CI log as a GitHub check annotation.

Used only from the failure steps of the Android CI workflow, so a failed job says why it failed
without downloading the raw job log. GitHub keeps only about 4 KB of an annotation message from its
start, so the message is cut to fit and the Gradle deprecation noise is removed first.
"""
import re
import sys
from pathlib import Path

MESSAGE_CHARS = 3500
NOISE = (
    "deprecat",            # Gradle 10 deprecation warnings; they hide the real failure
    "Run with --stacktrace",
    "Consult the upgrading guide",
    "propName",
    "Build file '",
    "Node.js 20 is deprecated",
)


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def useful_lines(text: str) -> list[str]:
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("at ") or any(marker in line for marker in NOISE):
            continue
        lines.append(line.rstrip())
    return lines


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: ci_failure_annotation.py <log>", file=sys.stderr)
        return 2
    path = Path(argv[1])
    if not path.is_file():
        print(f"::warning title=Log missing::{escape(str(path))} was not written")
        return 0
    raw = path.read_text(errors="replace")
    lines = useful_lines(raw)
    # Resource-compiler errors repeat once per occurrence; list each distinct one once with a count.
    distinct = {}
    for line in lines:
        key = None
        if "error:" in line:
            key = line.split("error:", 1)[1].split("(aka", 1)[0].strip()
        elif line.lstrip().startswith("e: "):
            # Kotlin compiler: "e: file:///.../Name.kt:12:5 message" -> "Name.kt:12: message"
            match = re.match(r"\s*e: \S*?([^/\s]+\.kt):(\d+):\d+ (.*)", line)
            key = f"{match.group(1)}:{match.group(2)}: {match.group(3)}" if match else line.strip()
        if key:
            distinct[key[:220]] = distinct.get(key[:220], 0) + 1
    summary = []
    if distinct:
        summary.append(f"Distinct compiler errors: {len(distinct)} ({sum(distinct.values())} occurrences)")
        summary.extend(f"  {count}x {key}" for key, count in list(distinct.items())[:20])
    # Failing unit tests: Gradle prints "Class > method() FAILED" followed by the exception line.
    raw_lines = raw.splitlines()
    failed = []
    for index, line in enumerate(raw_lines):
        if line.rstrip().endswith(" FAILED") and " > " in line:
            detail = raw_lines[index + 1].strip()[:160] if index + 1 < len(raw_lines) else ""
            failed.append(f"  {line.strip()[:200]}" + (f" -- {detail}" if detail else ""))
    totals = [line.strip() for line in raw_lines if "tests completed" in line]
    if failed or totals:
        summary.append(f"Failing unit tests: {len(failed)} listed; {totals[-1] if totals else 'no totals line'}")
        summary.extend(failed[:15])
    # Start at the first failure marker when there is one, so the error text is not cut off.
    starts = [i for i, line in enumerate(lines) if "What went wrong" in line or line.startswith("FAILURE:")]
    if starts:
        lines = lines[starts[0]:]
    # The summary is kept whole at the front; the tail is trimmed to the room that is left.
    head = "\n".join(summary)
    tail = "\n".join(lines)
    room = MESSAGE_CHARS - len(head) - 1
    if len(tail) > room:
        tail = "...\n" + tail[-max(room - 4, 0):]
    message = "\n".join(part for part in (head, tail) if part)
    print(f"::error title=Failure context ({path.name})::{escape(message)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
