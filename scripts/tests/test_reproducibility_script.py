"""Test fail-closed orchestration using fake tools, NOT real Android builds."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ReproducibilityScriptTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / "project"
        scripts = self.root / "scripts"
        scripts.mkdir(parents=True)
        for name in ("verify_reproducible_build.sh", "check_manifest.py", "manifest-policy.json"):
            shutil.copyfile(ROOT / "scripts" / name, scripts / name)
        # SDK and tools live outside the copied source tree.
        sdk = Path(self.directory.name) / "sdk"
        for package in ("platforms/android-36", "build-tools/36.0.0"):
            directory = sdk / package
            directory.mkdir(parents=True)
            (directory / "package.xml").write_text("synthetic SDK package, not a real SDK")
        tools = Path(self.directory.name) / "tools"
        tools.mkdir()
        for name, text in {
            "java": "#!/bin/sh\necho 'synthetic Java, not a real JVM' >&2\n",
            "apkanalyzer": """#!/bin/sh
set -eu
if [ "${TEST_MODE:-}" = network ]; then permission='<uses-permission android:name="android.permission.INTERNET"/>'; else permission=''; fi
printf '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.shadowice.flocke.andotp"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="35"/>%s<permission android:name="org.shadowice.flocke.andotp.permission.CONTROL_VAULT" android:protectionLevel="signature"/><application android:allowBackup="false" android:fullBackupContent="@xml/backup_rules" android:dataExtractionRules="@xml/data_extraction_rules"/></manifest>\n' "$permission"
""",
        }.items():
            path = tools / name
            path.write_text(text)
            path.chmod(0o755)
        gradlew = self.root / "gradlew"
        gradlew.write_text("""#!/bin/sh
set -eu
echo "isolated-home=$GRADLE_USER_HOME"
echo "isolated-source=$PWD"
mkdir -p build/reports
inventory=stable
if [ "${TEST_MODE:-}" = dependency ] && [ "${PWD##*/}" = source-2 ]; then inventory=changed; fi
printf '%s\n' "$inventory" > build/reports/dependency-inventory.txt
for flavor in fdroid play; do
  dir="app/build/outputs/apk/$flavor/release"
  mkdir -p "$dir"
  bytes=stable
  if [ "${TEST_MODE:-}" = apk ] && [ "${PWD##*/}" = source-2 ]; then bytes=changed; fi
  if [ "${TEST_MODE:-}" != missing ]; then printf '%s\n' "$bytes" > "$dir/app-$flavor-release-unsigned.apk"; fi
done
""")
        gradlew.chmod(0o755)
        self.env = dict(os.environ, ANDROID_HOME=str(sdk), PATH=str(tools) + os.pathsep + os.environ["PATH"])

    def run_check(self, mode=""):
        return subprocess.run(
            ["bash", str(self.root / "scripts/verify_reproducible_build.sh")],
            env=dict(self.env, TEST_MODE=mode), capture_output=True, text=True, timeout=20)

    def marker(self):
        return self.root / "reports/reproducibility/result.txt"

    def test_equal_mock_outputs_pass_and_sources_and_homes_are_independent(self):
        result = self.run_check()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(self.marker().exists())
        homes = [line for line in result.stdout.splitlines() if line.startswith("isolated-home=")]
        sources = [line for line in result.stdout.splitlines() if line.startswith("isolated-source=")]
        self.assertEqual(2, len(set(homes)))
        self.assertEqual(2, len(set(sources)))

    def test_dependency_drift_fails_even_with_matching_apks(self):
        result = self.run_check("dependency")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.marker().exists())

    def test_changed_apk_and_missing_apk_fail(self):
        for mode in ("apk", "missing"):
            with self.subTest(mode=mode):
                self.assertNotEqual(0, self.run_check(mode).returncode)
                self.assertFalse(self.marker().exists())

    def test_network_permission_fails_and_removes_prior_success_marker(self):
        self.assertEqual(0, self.run_check().returncode)
        self.assertTrue(self.marker().exists())
        result = self.run_check("network")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("android.permission.INTERNET", result.stderr)
        self.assertFalse(self.marker().exists())


if __name__ == "__main__":
    unittest.main()
