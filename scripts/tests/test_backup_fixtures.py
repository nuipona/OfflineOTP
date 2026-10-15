import hashlib
from pathlib import Path
import struct
import unittest

ASSETS = Path(__file__).resolve().parents[2] / "app/src/androidTest/assets/security"


class BackupFixtureTest(unittest.TestCase):
    def test_independent_fixture_bytes_do_not_change_silently(self):
        expected = {
            "current.aes": "234db25ede9e38b1462d123cef4679a7647f0ffd517dd1c809c318a315e6e83a",
            "legacy.aes": "a50e264a35ee585b32a4092234d281fe0db303614fc1c0a6fda30918e7451884",
        }
        for name, digest in expected.items():
            with self.subTest(name=name):
                self.assertEqual(digest, hashlib.sha256((ASSETS / name).read_bytes()).hexdigest())

    def test_current_header_matches_documented_format(self):
        data = (ASSETS / "current.aes").read_bytes()
        self.assertEqual(140000, struct.unpack(">i", data[:4])[0])
        self.assertEqual(bytes(range(12)), data[4:16])
        self.assertEqual(bytes(range(32, 44)), data[16:28])
        self.assertGreaterEqual(len(data), 44)


if __name__ == "__main__":
    unittest.main()
