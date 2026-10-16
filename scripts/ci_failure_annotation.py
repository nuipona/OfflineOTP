#!/usr/bin/env python3
"""Publish the most useful part of a CI log as a GitHub check annotation.

Used only from the failure steps of the Android CI workflow, so a failed job says why it failed
without downloading the raw job log. GitHub keeps only about 4 KB of an annotation message from its
start, so the message is cut to fit and the Gradle deprecation noise is removed first.
"""
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
    lines = useful_lines(path.read_text(errors="replace"))
    # Start at the first failure marker when there is one, so the error text is not cut off.
    starts = [i for i, line in enumerate(lines) if "What went wrong" in line or line.startswith("FAILURE:")]
    if starts:
        lines = lines[starts[0]:]
    message = "\n".join(lines)
    if len(message) > MESSAGE_CHARS:
        message = "...\n" + message[-MESSAGE_CHARS:]
    print(f"::error title=Failure context ({path.name})::{escape(message)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
