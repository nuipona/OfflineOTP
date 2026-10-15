import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_manifest", ROOT / "scripts/check_manifest.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
A = checker.ANDROID


class ManifestPolicyTest(unittest.TestCase):
    def setUp(self):
        self.manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        ET.SubElement(self.manifest, "uses-sdk", {A + "minSdkVersion": "28", A + "targetSdkVersion": "35"})
        for node in self.manifest.iter():
            for key, value in list(node.attrib.items()):
                node.set(key, value.replace("${applicationId}", "org.shadowice.flocke.andotp"))
        self.app = self.manifest.find("application")

    def errors(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            ET.ElementTree(self.manifest).write(path, encoding="utf-8")
            return checker.check_manifest(path)

    def test_existing_surface_is_explicit_baseline_not_security_approval(self):
        self.assertEqual([], self.errors())

    def test_internet_is_rejected_even_when_sdk_limited(self):
        for tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
            with self.subTest(tag=tag):
                node = ET.SubElement(self.manifest, tag, {A + "name": "android.permission.INTERNET", A + "maxSdkVersion": "28"})
                self.assertTrue(any("permission" in error for error in self.errors()))
                self.manifest.remove(node)

    def test_unreviewed_permissions_are_rejected(self):
        ET.SubElement(self.manifest, "uses-permission", {A + "name": "android.permission.READ_CONTACTS"})
        self.assertTrue(self.errors())

    def test_private_provider_is_allowed_but_exported_provider_is_rejected(self):
        provider = ET.SubElement(self.app, "provider", {A + "name": "example.Provider"})
        self.assertEqual([], self.errors())
        provider.set(A + "exported", "true")
        self.assertTrue(any("exported component" in error for error in self.errors()))

    def test_internal_credential_activity_cannot_become_exported(self):
        for activity in self.app.findall("activity"):
            if activity.get(A + "name") == ".Activities.AuthenticateActivity":
                activity.set(A + "exported", "true")
        self.assertTrue(self.errors())

    def test_implicit_exported_receiver_and_alias_are_rejected(self):
        receiver = ET.SubElement(self.app, "receiver", {A + "name": "example.Receiver"})
        ET.SubElement(ET.SubElement(receiver, "intent-filter"), "action", {A + "name": "example.ACTION"})
        self.assertTrue(self.errors())
        self.app.remove(receiver)
        ET.SubElement(self.app, "activity-alias", {A + "name": "example.Alias", A + "exported": "true"})
        self.assertTrue(self.errors())

    def test_protected_system_job_service_permission_is_required(self):
        service = ET.SubElement(self.app, "service", {
            A + "name": "androidx.work.impl.background.systemjob.SystemJobService",
            A + "exported": "true", A + "permission": "android.permission.BIND_JOB_SERVICE"})
        self.assertEqual([], self.errors())
        service.attrib.pop(A + "permission")
        self.assertTrue(any("caller permission" in error for error in self.errors()))

    def test_changed_public_actions_are_rejected(self):
        main = next(a for a in self.app.findall("activity") if a.get(A + "name") == ".Activities.MainActivity")
        ET.SubElement(ET.SubElement(main, "intent-filter"), "action", {A + "name": "example.NEW_PUBLIC_ACTION"})
        self.assertTrue(self.errors())

    def test_expanded_uri_scheme_is_rejected(self):
        main = next(a for a in self.app.findall("activity") if a.get(A + "name") == ".Activities.MainActivity")
        ET.SubElement(main.find("intent-filter"), "data", {A + "scheme": "https"})
        self.assertTrue(any("URI/data" in error for error in self.errors()))

    def test_fully_qualified_names_are_supported(self):
        for node in self.app:
            name = node.get(A + "name")
            if name and name.startswith("."):
                node.set(A + "name", "org.shadowice.flocke.andotp" + name)
        self.assertEqual([], self.errors())

    def test_control_ipc_cannot_lose_signature_permission(self):
        panic = next(a for a in self.app.findall("activity") if a.get(A + "name") == ".Activities.PanicResponderActivity")
        panic.attrib.pop(A + "permission")
        self.assertTrue(self.errors())
        panic.set(A + "permission", "org.shadowice.flocke.andotp.permission.CONTROL_VAULT")
        control = next(a for a in self.manifest.findall("permission") if a.get(A + "name").endswith("CONTROL_VAULT"))
        control.set(A + "protectionLevel", "normal")
        self.assertTrue(self.errors())

    def test_platform_backup_cannot_be_reenabled(self):
        self.app.set(A + "allowBackup", "true")
        self.assertTrue(self.errors())

    def test_unresolved_export_flag_is_rejected(self):
        ET.SubElement(self.app, "service", {A + "name": "example.Service", A + "exported": "@bool/public"})
        self.assertTrue(self.errors())

    def test_release_flags_are_rejected(self):
        for flag in ("debuggable", "testOnly", "usesCleartextTraffic"):
            with self.subTest(flag=flag):
                self.app.set(A + flag, "true")
                self.assertTrue(self.errors())
                self.app.attrib.pop(A + flag)

    def test_api_contract_and_application_id_cannot_drift(self):
        sdk = self.manifest.find("uses-sdk")
        sdk.set(A + "minSdkVersion", "29")
        self.assertTrue(self.errors())
        sdk.set(A + "minSdkVersion", "28")
        self.manifest.set("package", "org.shadowice.flocke.andotp.dev")
        self.assertTrue(self.errors())

    def test_missing_sdk_is_rejected_for_packaged_manifest(self):
        self.manifest.remove(self.manifest.find("uses-sdk"))
        self.assertTrue(self.errors())

    def test_signature_receiver_isolation_permission_cannot_be_weakened(self):
        permission = ET.SubElement(self.manifest, "permission", {
            A + "name": "org.shadowice.flocke.andotp.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            A + "protectionLevel": "signature"})
        self.assertEqual([], self.errors())
        permission.set(A + "protectionLevel", "normal")
        self.assertTrue(self.errors())


if __name__ == "__main__":
    unittest.main()
