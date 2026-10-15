from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DOMAINS = {
    "root", "file", "database", "sharedpref", "external",
    "device_root", "device_file", "device_database", "device_sharedpref",
}


class BackupExclusionSourceTest(unittest.TestCase):
    """Source-resource guard; not a substitute for decoded APK-resource inspection."""

    def assert_excludes_all(self, section):
        self.assertIsNotNone(section)
        self.assertEqual([], section.findall("include"))
        self.assertEqual(
            {(domain, ".") for domain in DOMAINS},
            {(node.get("domain"), node.get("path")) for node in section.findall("exclude")},
        )

    def test_legacy_full_backup_excludes_all_app_storage(self):
        rules = ET.parse(ROOT / "app/src/main/res/xml/backup_rules.xml").getroot()
        self.assertEqual("full-backup-content", rules.tag)
        self.assert_excludes_all(rules)

    def test_cloud_and_device_transfer_exclude_all_app_storage(self):
        rules = ET.parse(ROOT / "app/src/main/res/xml/data_extraction_rules.xml").getroot()
        self.assertEqual("data-extraction-rules", rules.tag)
        self.assert_excludes_all(rules.find("cloud-backup"))
        self.assert_excludes_all(rules.find("device-transfer"))
