"""Source-level migration guards, not Android rendering or Java compilation tests."""
from pathlib import Path
import hashlib
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
UTIL = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities"


class KotlinThumbnailConversionTest(unittest.TestCase):
    def test_persisted_names_order_and_resource_mapping_are_unchanged(self):
        source = (UTIL / "EntryThumbnail.kt").read_text()
        entries = re.findall(
            r"^\s*(\w+)\((R\.(?:drawable|mipmap)\.\w+)(?:, (AssetType\.\w+))?\)[,;]",
            source, re.M)
        # Fingerprint of name|resource|explicit-asset-type lines from the pre-conversion Java enum.
        self.assertEqual(346, len(entries))
        digest = hashlib.sha256("\n".join("|".join(e) for e in entries).encode()).hexdigest()
        self.assertEqual("d2993c2258ad6a5276f905baef10d40e8ffa021fa0af46608c7b6980ba30c68b", digest)

    def test_renderers_are_replaced_not_wrapped_or_duplicated(self):
        for name in ("EntryThumbnail", "LetterBitmap"):
            self.assertFalse((ROOT / f"app/src/main/java/org/shadowice/flocke/andotp/Utilities/{name}.java").exists())
            self.assertTrue((UTIL / f"{name}.kt").is_file())
        source = (UTIL / "EntryThumbnail.kt").read_text()
        self.assertIn("@JvmStatic\n        fun getThumbnailGraphic(", source)
        self.assertIn("@JvmStatic\n            fun valueOfIgnoreCase(", source)
        self.assertIn("@JvmStatic\n            fun valueOfFuzzy(", source)
