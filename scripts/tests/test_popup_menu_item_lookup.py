"""Popup-menu items must be reached by id, never by a hard-coded position.

`EntriesCardAdapter.showPopupMenu` used to reveal the mOTP "Establish PIN" row with
`getItem(ESTABLISH_PIN_MENU_INDEX)` where the constant was `4`. In `R.menu.menu_popup` the
`menu_popup_establishPin` item is the *fourth* `<item>`, i.e. index 3, so index 4 was
`menu_popup_remove` — already visible. The lookup was a silent no-op and the mOTP PIN row stayed
`android:visible="false"`, making the action unreachable from the per-entry menu.

A positional index into an inflated menu is invisible to the type checker and drifts whenever the
resource is reordered, so these guards require id lookups and check that the ids actually exist.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
MENU_DIR = ROOT / "app/src/main/res/menu"
ADAPTER = KOTLIN / "View/EntriesCardAdapter.kt"

DECLARED_ID = re.compile(r'android:id="@\+id/(menu_[A-Za-z0-9_]+)"')
REFERENCED_ID = re.compile(r"R\.id\.(menu_[A-Za-z0-9_]+)")
POSITIONAL_LOOKUP = re.compile(r"getMenu\(\)\s*\.\s*getItem\s*\(")
INDEX_CONSTANT = re.compile(r"\b[A-Z][A-Z0-9_]*MENU_INDEX\b\s*=\s*\d+")


def declared_menu_ids():
    ids = set()
    for path in sorted(MENU_DIR.glob("*.xml")):
        ids.update(DECLARED_ID.findall(path.read_text()))
    return ids


def kotlin_sources():
    return sorted(KOTLIN.rglob("*.kt"))


class PopupMenuItemLookupTest(unittest.TestCase):
    """Source guards, not substitutes for compilation or UI/runtime tests."""

    def test_every_menu_id_referenced_from_kotlin_is_declared_in_a_menu_resource(self):
        declared = declared_menu_ids()
        self.assertGreater(len(declared), 10, "the menu resource scan found almost nothing")
        referenced = set()
        for source in kotlin_sources():
            referenced.update(REFERENCED_ID.findall(source.read_text()))
        self.assertGreater(len(referenced), 10, "no R.id.menu_* references were found at all")
        self.assertEqual([], sorted(referenced - declared),
                         "a menu id referenced from Kotlin is not declared in res/menu")

    def test_the_card_adapter_reaches_popup_items_by_id_not_by_position(self):
        source = ADAPTER.read_text()
        self.assertIsNone(POSITIONAL_LOOKUP.search(source),
                          "the popup menu is addressed positionally; use findItem(R.id...) instead")
        self.assertIsNone(INDEX_CONSTANT.search(source),
                          "a positional menu index constant reappeared; ids are the contract")

    def test_the_establish_pin_row_stays_reachable_for_motp_entries(self):
        menu = (MENU_DIR / "menu_popup.xml").read_text()
        item = re.search(r'<item\b[^>]*android:id="@\+id/menu_popup_establishPin"[^>]*/?>', menu)
        self.assertIsNotNone(item, "menu_popup_establishPin disappeared from menu_popup.xml")
        # Hidden by default so only mOTP entries see it; the adapter must reveal it by id.
        self.assertIn('android:visible="false"', item.group(0))
        source = ADAPTER.read_text()
        self.assertIn("findItem(R.id.menu_popup_establishPin)", source)
        self.assertIn("OTPType.MOTP", source,
                      "the mOTP condition guarding the PIN row is gone")


if __name__ == "__main__":
    unittest.main()
