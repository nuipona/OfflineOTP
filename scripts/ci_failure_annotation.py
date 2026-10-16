#!/usr/bin/env python3
"""Publish the tail of a Gradle log as a GitHub check annotation.

Used only from the failure steps of the Android CI workflow, so a failed job says why it failed
without downloading the raw job log.
"""
import sys
from pathlib import Path

TAIL_LINES = 120
MAX_CHARS = 8000


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: ci_failure_annotation.py <gradle-log>", file=sys.stderr)
        return 2
    path = Path(argv[1])
    if not path.is_file():
        print(f"::warning title=Gradle log missing::{escape(str(path))} was not written")
        return 0
    lines = [line for line in path.read_text(errors="replace").splitlines() if line.strip()]
    tail = "\n".join(lines[-TAIL_LINES:])[-MAX_CHARS:]
    print(f"::error title=Gradle failure tail ({path.name})::{escape(tail)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
