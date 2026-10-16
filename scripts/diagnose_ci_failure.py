#!/usr/bin/env python3
"""Append host diagnostics to CI Gradle logs after a failed step.

For each log given, appends the cgroup memory limit and events, kernel OOM-kill messages (when
readable without a password), and the tail of the memory sampler log given as the last argument.
A process that dies without writing its own exit status shows up here as an OOM kill.
"""
import subprocess
import sys
from pathlib import Path

CGROUP_FILES = ["/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory.events", "/sys/fs/cgroup/memory.peak"]


def run(*cmd: str) -> str:
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
        return result.stdout + result.stderr
    except (OSError, subprocess.SubprocessError) as error:
        return f"<unavailable: {error}>\n"


def diagnostics(sampler: Path | None) -> str:
    parts = ["--- host diagnostics ---", run("free", "-m")]
    for name in CGROUP_FILES:
        if Path(name).is_file():
            parts.append(f"{name}:\n{Path(name).read_text(errors='replace')}")
    kernel = run("sudo", "-n", "dmesg")
    oom = [line for line in kernel.splitlines() if "killed process" in line.lower() or "out of memory" in line.lower()]
    parts.append("kernel OOM messages:\n" + ("\n".join(oom[-5:]) if oom else "<none readable>"))
    if sampler is not None and sampler.is_file():
        parts.append("memory sampler (last 30 samples):\n" + "\n".join(sampler.read_text(errors="replace").splitlines()[-30:]))
    return "\n".join(parts) + "\n"


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: diagnose_ci_failure.py <log> [<log> ...] <sampler-log>", file=sys.stderr)
        return 2
    *logs, sampler_name = argv[1:]
    sampler = Path(sampler_name)
    text = diagnostics(sampler)
    for name in logs:
        path = Path(name)
        if path.is_file():
            with path.open("a") as handle:
                handle.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
