"""Guards for the OfflineOTP branding, logo assets and unified Dark Forest theme."""
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

    def test_dark_forest_theme_colors(self):
        colors_path = ROOT / "app/src/main/res/values/colors.xml"
        root = ET.parse(colors_path).getroot()
        colors = {el.attrib["name"]: (el.text or "").strip() for el in root.findall("color")}
        self.assertEqual("#4CAF50", colors.get("colorPrimary"))
        self.assertEqual("#1B4D2E", colors.get("colorPrimaryDark"))
        self.assertEqual("#2E7D32", colors.get("colorPrimaryVariant"))
        self.assertEqual("#81C784", colors.get("colorAccent"))
        self.assertEqual("#101A14", colors.get("forest_background"))
        self.assertEqual("#0A110D", colors.get("forest_background_deep"))
        self.assertEqual("#1A2C21", colors.get("forest_surface"))
        self.assertEqual("#21372A", colors.get("forest_surface_elevated"))
        self.assertEqual("#E6F0E8", colors.get("forest_text_primary"))
        self.assertEqual("#A3BCA9", colors.get("forest_text_secondary"))
        self.assertEqual("#2B4434", colors.get("forest_divider"))
        self.assertEqual("#1A2C21", colors.get("about_libraries_card"))
        self.assertEqual("#E6F0E8", colors.get("about_libraries_title_openSource"))
        self.assertEqual("#A3BCA9", colors.get("about_libraries_text_openSource"))
        self.assertEqual("#81C784", colors.get("about_libraries_special_button_openSource"))

        main_bg = (ROOT / "app/src/main/res/values/ic_launcher_background.xml").read_text()
        debug_bg = (ROOT / "app/src/debug/res/values/ic_launcher_background.xml").read_text()
        self.assertIn('<color name="ic_launcher_background">#6ABF69</color>', main_bg)
        self.assertIn('<color name="ic_launcher_background">#FF6659</color>', debug_bg)

    def test_unified_dark_forest_theme_styles_and_aboutlibraries_compatibility(self):
        styles_path = ROOT / "app/src/main/res/values/styles.xml"
        v27_styles_path = ROOT / "app/src/main/res/values-v27/styles.xml"
        dimens_path = ROOT / "app/src/main/res/values/dimens.xml"
        attrs_path = ROOT / "app/src/main/res/values/attrs.xml"

        for path in (styles_path, v27_styles_path, dimens_path, attrs_path):
            ET.parse(path)

        styles_root = ET.parse(styles_path).getroot()
        styles = {}
        for style_el in styles_root.findall("style"):
            items = {item.attrib["name"]: (item.text or "").strip() for item in style_el.findall("item")}
            styles[style_el.attrib["name"]] = {
                "parent": style_el.attrib.get("parent", ""),
                "items": items,
            }

        # Core unified homemade theme inherits from Theme.MaterialComponents.Bridge
        self.assertIn("Theme.OfflineOTP.DarkForest", styles)
        forest = styles["Theme.OfflineOTP.DarkForest"]
        self.assertEqual("Theme.MaterialComponents.Bridge", forest["parent"])
        forest_items = forest["items"]
        self.assertEqual("true", forest_items.get("isMaterialTheme"))
        self.assertEqual("@color/colorPrimary", forest_items.get("colorPrimary"))
        self.assertEqual("@color/colorPrimaryDark", forest_items.get("colorPrimaryDark"))
        self.assertEqual("@color/colorPrimaryVariant", forest_items.get("colorPrimaryVariant"))
        self.assertEqual("@color/colorAccent", forest_items.get("colorSecondary"))
        self.assertEqual("@color/colorAccent", forest_items.get("colorAccent"))
        self.assertEqual("@color/forest_surface", forest_items.get("colorSurface"))
        self.assertEqual("@color/forest_text_primary", forest_items.get("colorOnSurface"))
        self.assertEqual("@color/forest_background", forest_items.get("windowBackground"))
        self.assertEqual("@color/forest_background", forest_items.get("android:windowBackground"))
        self.assertEqual("@style/CardViewStyle", forest_items.get("cardStyle"))
        self.assertEqual("@style/Widget.OfflineOTP.DarkForest.MaterialCardView",
                         forest_items.get("materialCardViewStyle"))
        self.assertEqual("@style/DialogTheme", forest_items.get("dialogTheme"))

        # AboutLibraries 14.x + legacy attributes are all present on the unified theme
        for attr in ("aboutLibrariesStyle",
                     "aboutLibrariesCardBackground",
                     "aboutLibrariesDescriptionTitle",
                     "aboutLibrariesDescriptionText",
                     "aboutLibrariesDescriptionDivider",
                     "aboutLibrariesOpenSourceTitle",
                     "aboutLibrariesOpenSourceText",
                     "aboutLibrariesSpecialButtonText",
                     "aboutLibrariesOpenSourceDivider",
                     "about_libraries_card",
                     "about_libraries_title_openSource",
                     "about_libraries_text_openSource",
                     "about_libraries_dividerDark_openSource",
                     "about_libraries_dividerLight_openSource"):
            self.assertIn(attr, forest_items, f"Missing {attr} in Theme.OfflineOTP.DarkForest")

        # All theme hooks resolve to the unified Dark Forest theme
        self.assertEqual("Theme.OfflineOTP.DarkForest", styles["AppBaseTheme"]["parent"])
        self.assertEqual("AppBaseTheme", styles["AppTheme"]["parent"])
        self.assertEqual("AppBaseTheme", styles["AppBaseTheme.Dark"]["parent"])
        self.assertEqual("AppBaseTheme.Dark", styles["AppTheme.Dark"]["parent"])
        self.assertEqual("AppBaseTheme", styles["AppBaseTheme.Black"]["parent"])
        self.assertEqual("AppBaseTheme.Black", styles["AppTheme.Black"]["parent"])
        self.assertEqual("CardViewStyle", styles["CardViewStyle.Black"]["parent"])
        self.assertEqual("DialogTheme", styles["DialogTheme.Dark"]["parent"])

        for no_bar in ("AppTheme.NoActionBar", "AppTheme.Dark.NoActionBar", "AppTheme.Black.NoActionBar"):
            self.assertEqual("false", styles[no_bar]["items"].get("windowActionBar"))
            self.assertEqual("true", styles[no_bar]["items"].get("windowNoTitle"))

        # API 27+ navigation bar stays dark for all theme hooks
        v27_root = ET.parse(v27_styles_path).getroot()
        for style_el in v27_root.findall("style"):
            items = {item.attrib["name"]: (item.text or "").strip() for item in style_el.findall("item")}
            self.assertEqual("false", items.get("android:windowLightNavigationBar"),
                             f"{style_el.attrib['name']} in values-v27 must use dark navigation bar")

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
