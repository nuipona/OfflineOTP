from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"
WORKFLOWS = ROOT / ".github/workflows"


class KotlinSandboxHarnessTest(unittest.TestCase):
    """Guards for a documented, non-authoritative local check; nothing here compiles Kotlin."""

    def test_harness_compiles_existing_sources_only(self):
        script = (SANDBOX / "run.sh").read_text()
        compiled = re.findall(r"\$KOTLIN_APP/([\w/]+\.kt)", script)
        self.assertGreaterEqual(len(compiled), 8, "the sandbox check should cover the converted utilities")
        for relative in compiled:
            self.assertTrue((KOTLIN / relative).is_file(), f"{relative} is listed but missing")
        self.assertIn("stubs/android/*.kt", script)
        self.assertTrue((SANDBOX / "Checks.kt").is_file())
        self.assertTrue((SANDBOX / "README.md").is_file())

    def test_every_converted_source_is_covered_or_documented_as_excluded(self):
        documentation = (SANDBOX / "README.md").read_text()
        script = (SANDBOX / "run.sh").read_text()
        for file in sorted(KOTLIN.rglob("*.kt")):
            relative = f"{file.parent.name}/{file.name}"
            self.assertTrue(relative in documentation or f"$KOTLIN_APP/{relative}" in script,
                            f"{relative} is neither compiled nor documented as excluded in {SANDBOX}")

    def test_harness_results_cannot_be_mistaken_for_acceptance(self):
        script = (SANDBOX / "run.sh").read_text()
        readme = (SANDBOX / "README.md").read_text()
        checks = (SANDBOX / "Checks.kt").read_text()
        self.assertIn("non-authoritative", script.lower())
        self.assertIn("not a substitute", readme)
        self.assertIn("non-authoritative", checks.lower())
        # The authoritative gates must stay named, so a green sandbox run is not read as release acceptance.
        for gate in ("testFdroidDebugUnitTest", "lint", "instrumentation"):
            self.assertIn(gate, readme)

    def test_ci_does_not_depend_on_the_local_harness(self):
        for workflow in sorted(WORKFLOWS.glob("*.yml")):
            self.assertNotIn("kotlin-sandbox", workflow.read_text(), workflow.name)


if __name__ == "__main__":
    unittest.main()
