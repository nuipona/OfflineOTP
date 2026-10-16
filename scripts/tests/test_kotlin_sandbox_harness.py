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
            relative = file.relative_to(KOTLIN).as_posix()
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

    def test_no_java_source_is_resolved_any_more(self):
        script = (SANDBOX / "run.sh").read_text()
        # The sandbox used to resolve Constants.java and FileHelper.java for their declarations. Both
        # are Kotlin since the twenty-second batch, so kotlinc compiles every declaration it checks and
        # no Java path is listed at all.
        self.assertNotIn("JAVA_SOURCES", script)
        self.assertNotIn(".java", script)
        for compiled in ("Utilities/Constants", "Utilities/FileHelper"):
            self.assertIn(f"$KOTLIN_APP/{compiled}.kt", script)
            self.assertFalse((ROOT / f"app/src/main/java/org/shadowice/flocke/andotp/{compiled}.java").exists())
        # The file helper runs over the AtomicFile stand-in, which must keep refusing every operation,
        # so a check that reaches the disk cannot touch the working directory (getFilesDir() is ".").
        stubs = (SANDBOX / "stubs/android/android_util.kt").read_text()
        atomic = stubs[stubs.index("class AtomicFile("):]
        self.assertNotIn("inputStream()", atomic)
        self.assertNotIn("FileOutputStream(file)", atomic)
        self.assertIn("UnsupportedOperationException", atomic)
        # The credential preference is Kotlin since the twenty-first batch: compiled, never resolved.
        self.assertIn("$KOTLIN_APP/Preferences/CredentialsPreference.kt", script)
        self.assertNotIn("Preferences/CredentialsPreference.java", script)
        self.assertFalse(
            (ROOT / "app/src/main/java/org/shadowice/flocke/andotp/Preferences/CredentialsPreference.java").exists())
        # The settings facade is Kotlin since the sixteenth batch: compiled, never resolved.
        self.assertIn("$KOTLIN_APP/Utilities/Settings.kt", script)
        self.assertNotIn("Utilities/Settings.java", script)
        self.assertFalse((ROOT / "app/src/main/java/org/shadowice/flocke/andotp/Utilities/Settings.java").exists())
        # The crypto/keystore helpers are Kotlin since the fifteenth batch: compiled, never resolved.
        for converted in ("EncryptionHelper", "KeyStoreHelper"):
            self.assertIn(f"$KOTLIN_APP/Utilities/{converted}.kt", script)
            self.assertNotIn(f"Utilities/{converted}.java", script)
            self.assertFalse((ROOT / f"app/src/main/java/org/shadowice/flocke/andotp/Utilities/{converted}.java").exists())
        # The document/backup helpers are Kotlin since the twelfth batch: compiled, never resolved.
        for converted in ("StorageAccessHelper", "BackupHelper"):
            self.assertIn(f"$KOTLIN_APP/Utilities/{converted}.kt", script)
            self.assertNotIn(f"Utilities/{converted}.java", script)
            self.assertFalse((ROOT / f"app/src/main/java/org/shadowice/flocke/andotp/Utilities/{converted}.java").exists())
        # The vault repository is Kotlin since the eleventh batch, so it is compiled (not resolved)
        # and resolves the same Java helpers it reads (FileHelper/Constants/EncryptionHelper).
        self.assertIn("$KOTLIN_APP/Utilities/DatabaseHelper.kt", script)
        self.assertFalse((ROOT / "app/src/main/java/org/shadowice/flocke/andotp/Utilities/DatabaseHelper.java").exists())
        self.assertNotIn("Utilities/DatabaseHelper.java", script)
        # The model and thumbnail pair are compiled as real Kotlin, not stubbed.
        for converted in ("EntryThumbnail", "LetterBitmap"):
            self.assertIn(f"$KOTLIN_APP/Utilities/{converted}.kt", script)
            self.assertFalse((ROOT / f"app/src/main/java/org/shadowice/flocke/andotp/Utilities/{converted}.java").exists())
        self.assertIn("$KOTLIN_APP/Database/Entry.kt", script)
        self.assertFalse((ROOT / "app/src/main/java/org/shadowice/flocke/andotp/Database/Entry.java").exists())
        # No production class may be shadowed by a stub of the same name.
        shadows = sorted(path.name for path in (SANDBOX / "stubs/org/shadowice").rglob("*.kt"))
        self.assertEqual(["R.kt"], shadows, f"unexpected production shadows: {shadows}")
        # Constants is Kotlin since the twenty-second batch. Its package-private Java members are
        # `internal const val`s; the values themselves are pinned by test_kotlin_utilities_conversion.
        source = (KOTLIN / "Utilities/Constants.kt").read_text()
        self.assertIn('internal const val ALGORITHM_ASYMMETRIC = "RSA/ECB/PKCS1Padding"', source)
        self.assertIn("internal const val PBKDF2_ALGORITHM", source)

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
