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
        self.assertGreaterEqual(len(compiled), 12, "the sandbox check should cover the converted utilities")
        for expected in ("Utilities/SecretKeyWrapper.kt", "Utilities/DimensionConverter.kt",
                         "View/SimpleDoubleClickListener.kt"):
            self.assertIn(f"$KOTLIN_APP/{expected}", script)
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

    def test_real_java_sources_are_resolved_instead_of_shadowed(self):
        script = (SANDBOX / "run.sh").read_text()
        constants = ROOT / "app/src/main/java/org/shadowice/flocke/andotp/Utilities/Constants.java"
        # kotlinc reads Java sources for resolution, so the sandbox checks the Kotlin code against
        # the real declarations (including the package-private crypto constant) instead of a copy.
        self.assertIn("$ROOT/app/src/main/java/org/shadowice/flocke/andotp/Utilities/Constants.java", script)
        self.assertIn('"${JAVA_SOURCES[@]}"', script)
        # The task subclasses resolve the real settings/database/crypto helpers, not stubs of them.
        for resolved in ("Utilities/Settings.java", "Utilities/EncryptionHelper.java",
                         "Utilities/KeyStoreHelper.java",
                         "Utilities/BackupHelper.java", "Utilities/StorageAccessHelper.java",
                         "Utilities/EntryThumbnail.java"):
            self.assertIn(f"$ROOT/app/src/main/java/org/shadowice/flocke/andotp/{resolved}", script)
        # The vault repository is Kotlin since the eighth batch, so it is compiled (not resolved)
        # and resolves the same Java helpers it reads (FileHelper/Constants/EncryptionHelper).
        self.assertIn("$KOTLIN_APP/Utilities/DatabaseHelper.kt", script)
        self.assertFalse((ROOT / "app/src/main/java/org/shadowice/flocke/andotp/Utilities/DatabaseHelper.java").exists())
        # EntryThumbnail stays Java (its resource ids need the Android toolchain), so the converted
        # Entry resolves the real declaration rather than a stub of it.
        self.assertIn("$KOTLIN_APP/Database/Entry.kt", script)
        self.assertFalse((ROOT / "app/src/main/java/org/shadowice/flocke/andotp/Database/Entry.java").exists())
        # No production class may be shadowed by a stub of the same name.
        shadows = sorted(path.name for path in (SANDBOX / "stubs/org/shadowice").rglob("*.kt"))
        self.assertEqual(["R.kt"], shadows, f"unexpected production shadows: {shadows}")
        # The reason Constants stays Java: its parameters are package-private, and Kotlin has no
        # package-private visibility, so converting it would widen the Java-visible API.
        source = constants.read_text()
        self.assertIn('final static String ALGORITHM_ASYMMETRIC    = "RSA/ECB/PKCS1Padding";', source)
        self.assertIn("final static String PBKDF2_ALGORITHM", source)

    def test_r_stub_matches_the_sources_the_sandbox_compiles(self):
        # The generated-resource stand-in lists exactly the ids referenced by run.sh's source
        # lists, so it cannot drift behind a new reference or grow unreviewed entries.
        import subprocess
        import sys
        result = subprocess.run(
            [sys.executable, str(SANDBOX / "generate-r-stub.py"), "--check"],
            capture_output=True, text=True, cwd=ROOT)
        self.assertEqual(0, result.returncode,
                         f"R stub is stale: {result.stderr}\nregenerate with "
                         f"python3 scripts/kotlin-sandbox/generate-r-stub.py")
        self.assertIn("up to date", result.stdout)

    def test_bootstrap_script_pins_and_verifies_its_downloads(self):
        script = (SANDBOX / "bootstrap-toolchain.sh").read_text()
        self.assertIn("sha256sum -c -", script)
        self.assertIn("registry.npmjs.org/kotlin-compiler", script)
        self.assertIn("pip download --no-deps", script)
        # Nothing may be unpacked inside the repository.
        self.assertIn("KOTLIN_SANDBOX_TOOLCHAIN", script)
        self.assertIn("${TMPDIR:-/tmp}/kotlin-sandbox-toolchain", script)

    def test_ci_does_not_depend_on_the_local_harness(self):
        for workflow in sorted(WORKFLOWS.glob("*.yml")):
            self.assertNotIn("kotlin-sandbox", workflow.read_text(), workflow.name)


if __name__ == "__main__":
    unittest.main()
