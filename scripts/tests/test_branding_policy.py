"""Guards for the OfflineOTP branding, logo assets and preserved theme colors."""
from pathlib import Path
import struct
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class BrandingPolicyTest(unittest.TestCase):
    def test_app_name_and_user_facing_strings_use_offlineotp(self):
        main_strings = (ROOT / "app/src/main/res/values/strings_main.xml").read_text()
        debug_strings = (ROOT / "app/src/debug/res/values/strings_main.xml").read_text()
        self.assertIn('<string name="app_name" translatable="false">OfflineOTP</string>', main_strings)
        self.assertIn('<string name="app_name" translatable="false">OfflineOTP (dev)</string>', debug_strings)
        for name in ("strings_main.xml", "strings_about.xml", "strings_auth.xml",
                     "strings_backup.xml", "strings_intro.xml", "strings_settings.xml"):
            text = (ROOT / "app/src/main/res/values" / name).read_text()
            self.assertNotIn("andOTP", text, f"{name} still mentions andOTP")
            self.assertIn("OfflineOTP", text, f"{name} is missing OfflineOTP")

    def test_theme_colors_remain_unchanged(self):
        colors = (ROOT / "app/src/main/res/values/colors.xml").read_text()
        self.assertIn('<color name="colorPrimary">#388E3C</color>', colors)
        self.assertIn('<color name="colorPrimaryDark">#00600F</color>', colors)
        self.assertIn('<color name="colorAccent">#FFCC00</color>', colors)
        main_bg = (ROOT / "app/src/main/res/values/ic_launcher_background.xml").read_text()
        debug_bg = (ROOT / "app/src/debug/res/values/ic_launcher_background.xml").read_text()
        self.assertIn('<color name="ic_launcher_background">#6ABF69</color>', main_bg)
        self.assertIn('<color name="ic_launcher_background">#FF6659</color>', debug_bg)

    def test_adaptive_icon_foreground_depicts_black_lock_and_forest(self):
        for variant in ("main", "debug"):
            path = ROOT / f"app/src/{variant}/res/drawable/ic_launcher_foreground.xml"
            ET.parse(path)
            content = path.read_text()
            self.assertIn('#000000', content, f"{variant} foreground is missing the black lock")
            self.assertIn('#1B5E20', content, f"{variant} foreground is missing the forest")
            self.assertIn('#388E3C', content, f"{variant} foreground is missing the forest")

    def test_png_launcher_and_logo_icons_have_expected_dimensions(self):
        expected = {
            "assets/logo.png": (256, 256),
            "assets/logo_legacy.png": (96, 96),
            "assets/feature_graphic.png": (1024, 500),
            "assets/feature_graphic_small.png": (512, 250),
            "fastlane/metadata/android/en-US/images/icon.png": (96, 96),
            "fastlane/metadata/android/en-US/images/featureGraphic.png": (1024, 500),
            "app/src/main/ic_launcher-web.png": (512, 512),
            "app/src/debug/ic_launcher-web.png": (512, 512),
        }
        densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
        for variant in ("main", "debug"):
            for density, size in densities.items():
                expected[f"app/src/{variant}/res/mipmap-{density}/ic_launcher.png"] = (size, size)
                expected[f"app/src/{variant}/res/mipmap-{density}/ic_launcher_round.png"] = (size, size)

        for rel_path, dims in expected.items():
            data = (ROOT / rel_path).read_bytes()
            self.assertEqual(b"\x89PNG\r\n\x1a\n", data[:8], f"{rel_path} is not a PNG")
            width, height = struct.unpack(">II", data[16:24])
            self.assertEqual(dims, (width, height), f"{rel_path} has size {(width, height)}, expected {dims}")


if __name__ == "__main__":
    unittest.main()
