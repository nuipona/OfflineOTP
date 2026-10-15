#!/usr/bin/env python3
"""Regenerate the sandbox-only R stand-in from the sources the sandbox compiles.

`scripts/kotlin-sandbox/run.sh` type-checks Kotlin sources against platform stubs and real Java
resolution sources. Several of those sources reference generated resource ids (`R.string.*`,
`R.drawable.*`, ...). The stub exists only so those references resolve; the id values are inert.

This script recomputes the exact id set from the same source lists run.sh uses, so the checked-in
stub cannot drift behind (or silently grow beyond) what the sandbox actually compiles. It is a
source scan, not a build step: run it after changing the compiled sources, then commit the result.

Usage: python3 scripts/kotlin-sandbox/generate-r-stub.py [--check]
  --check exits non-zero when the checked-in stub is stale instead of rewriting it.
"""
from pathlib import Path
import re
import sys
from collections import defaultdict

SANDBOX = Path(__file__).resolve().parent
ROOT = SANDBOX.parents[1]
KOTLIN_APP = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
JAVA_APP = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"

# Keep in sync with the APP_SOURCES/JAVA_SOURCES lists in run.sh. Parsing run.sh itself keeps the
# two from drifting apart.
RUN = (SANDBOX / "run.sh").read_text()


def source_lists(variable):
    match = re.search(rf"^{variable}=\((.*?)^\)", RUN, re.MULTILINE | re.DOTALL)
    if not match:
        raise SystemExit(f"could not find {variable} in run.sh")
    return re.findall(r'"([^"]+)"', match.group(1))


def expand(path):
    return str(path).replace("$KOTLIN_APP", str(KOTLIN_APP)).replace("$ROOT", str(ROOT)) \
                    .replace("$JAVA_APP", str(JAVA_APP))


def collect():
    groups = defaultdict(set)
    for variable in ("APP_SOURCES", "JAVA_SOURCES"):
        for entry in source_lists(variable):
            source = Path(expand(entry))
            if not source.is_file():
                raise SystemExit(f"listed source is missing: {source}")
            for found in re.finditer(r"\bR\.(\w+)\.(\w+)", source.read_text()):
                groups[found.group(1)].add(found.group(2))
    return groups


def render(groups):
    lines = [
        "// Sandbox-only stand-in for the generated resource class.",
        "// Regenerate with scripts/kotlin-sandbox/generate-r-stub.py after changing run.sh's",
        "// source lists; the id values are inert, only the references need to resolve.",
        "package org.shadowice.flocke.andotp",
        "",
        "object R {",
    ]
    for group in sorted(groups):
        lines.append(f"    object {group} {{")
        for name in sorted(groups[group]):
            lines.append(f"        const val {name} = 0")
        lines.append("    }")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def main():
    stub_path = SANDBOX / "stubs/org/shadowice/flocke/andotp/R.kt"
    rendered = render(collect())
    if "--check" in sys.argv[1:]:
        current = stub_path.read_text()
        if current != rendered:
            print("stale R stub: regenerate with "
                  "python3 scripts/kotlin-sandbox/generate-r-stub.py", file=sys.stderr)
            return 1
        print("R stub is up to date")
        return 0
    stub_path.write_text(rendered)
    print(f"rewrote {stub_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
