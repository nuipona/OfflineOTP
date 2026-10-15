import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_build_inputs", ROOT / "scripts/check_build_inputs.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class BuildInputsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for name in [*checker.HASHES, "gradle/wrapper/gradle-wrapper.properties", ".github/workflows/android-ci.yml"]:
            destination = self.root / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((ROOT / name).read_bytes())

    def test_official_wrapper_and_pinned_actions_pass(self):
        self.assertEqual([], checker.check(self.root))

    def test_modified_wrapper_is_rejected(self):
        path = self.root / "gradle/wrapper/gradle-wrapper.jar"
        path.write_bytes(path.read_bytes() + b"tampered")
        self.assertTrue(any("provenance" in error for error in checker.check(self.root)))

    def test_missing_or_duplicate_distribution_checksum_is_rejected(self):
        path = self.root / "gradle/wrapper/gradle-wrapper.properties"
        original = path.read_text()
        path.write_text("\n".join(line for line in original.splitlines() if not line.startswith("distributionSha256Sum=")))
        self.assertTrue(checker.check(self.root))
        path.write_text(original + "distributionSha256Sum=" + checker.DISTRIBUTION_HASH + "\n")
        self.assertTrue(any("Duplicate" in error for error in checker.check(self.root)))

    def test_changed_distribution_url_is_rejected(self):
        path = self.root / "gradle/wrapper/gradle-wrapper.properties"
        path.write_text(path.read_text().replace("gradle-8.13-all.zip", "gradle-8.14-all.zip"))
        self.assertTrue(checker.check(self.root))

    def test_mutable_action_tag_is_rejected(self):
        path = self.root / ".github/workflows/android-ci.yml"
        path.write_text("jobs:\n  job:\n    steps:\n      - uses: actions/checkout@v4\n")
        self.assertTrue(any("Unpinned" in error for error in checker.check(self.root)))


if __name__ == "__main__":
    unittest.main()
