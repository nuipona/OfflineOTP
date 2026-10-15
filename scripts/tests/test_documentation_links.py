"""Relative links in the project documentation must point at files that exist.

Each conversion batch deletes Java files that the assessment and the conversion record cite as
evidence, so a stale `../app/src/main/java/...` link would otherwise go unnoticed.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
LINK = re.compile(r"\]\(([^)\s]+)\)")


def documents():
    yield from sorted((ROOT / "docs").glob("*.md"))
    yield from sorted((ROOT / "scripts").rglob("*.md"))


class DocumentationLinksTest(unittest.TestCase):
    def test_relative_links_resolve(self):
        broken = []
        checked = 0
        for document in documents():
            text = document.read_text()
            for match in LINK.finditer(text):
                target = match.group(1)
                if re.match(r"^[a-z][a-z0-9+.-]*:", target) or target.startswith("#"):
                    continue  # external URL or same-document anchor
                path = target.split("#", 1)[0]
                checked += 1
                if not (document.parent / path).exists():
                    line = text.count("\n", 0, match.start()) + 1
                    broken.append(f"{document.relative_to(ROOT)}:{line}: {target}")
        self.assertGreater(checked, 20, "the link scan found almost nothing; is the pattern still right?")
        self.assertEqual([], broken, "stale documentation links (a converted file moved to Kotlin?)")


if __name__ == "__main__":
    unittest.main()
