"""Pins the completed Kotlin test-source migration without claiming it was compiled or run."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app"
HOST_JAVA = APP / "src/test/java"
HOST_KOTLIN = APP / "src/test/kotlin/org/shadowice/flocke/andotp"
ANDROID_JAVA_ROOT = APP / "src/androidTest/java"
ANDROID_JAVA = ANDROID_JAVA_ROOT / "org/shadowice/flocke/andotp"
ANDROID_KOTLIN = APP / "src/androidTest/kotlin/org/shadowice/flocke/andotp"
SECURITY_CLIENT = ROOT / "security-test-client"

HOST_TESTS = (
    "Utilities/BoundedInputReaderTest.kt",
    "Utilities/EncryptedBackupHeaderTest.kt",
    "Utilities/InputPolicyTest.kt",
    "Utilities/ScreenTimerControllerTest.kt",
    "Utilities/VaultSessionTest.kt",
    "View/EntryActionTargetTest.kt",
    "View/EntryListPresentationTest.kt",
)
ANDROID_TESTS = (
    "ApplicationTest.kt",
    "Database/EntryJsonTest.kt",
    "Tasks/BackgroundTaskCancellationSecurityTest.kt",
    "Tasks/CredentialLifecycleSecurityTest.kt",
    "Tasks/EncryptedRestoreSecurityTest.kt",
    "Utilities/EncryptionSecurityTest.kt",
    "Utilities/EntryInputSecurityTest.kt",
    "Utilities/IpcAuthorizationSecurityTest.kt",
    "Utilities/ToolsTest.kt",
    "Utilities/VaultFailureSecurityTest.kt",
    "Utilities/VaultRestoreOperationTest.kt",
)
INTEROP_TESTS = (
    "Activities/KotlinConvertedUiInteropTest.kt",
    "Activities/KotlinSmallActivitiesInteropTest.kt",
    "Dialogs/KotlinHideableDialogInteropTest.kt",
    "Dialogs/KotlinPasswordDialogsInteropTest.kt",
    "Fragments/KotlinAboutScreensInteropTest.kt",
    "Preferences/KotlinNumberPickerPreferenceInteropTest.kt",
    "Preferences/KotlinPasswordEncryptedPreferenceInteropTest.kt",
    "Utilities/KotlinBackupComponentsInteropTest.kt",
    "Utilities/KotlinCryptoInteropTest.kt",
    "Utilities/KotlinDocumentIoInteropTest.kt",
    "Utilities/KotlinNotificationQrInteropTest.kt",
    "Utilities/KotlinOtpInteropTest.kt",
    "Utilities/KotlinScreenTimerInteropTest.kt",
    "Utilities/KotlinThumbnailInteropTest.kt",
    "View/KotlinSmallAdaptersInteropTest.kt",
)


class KotlinTestSourceMigrationTest(unittest.TestCase):
    def test_host_tests_are_kotlin_at_the_original_packages(self):
        self.assertEqual([], sorted(path.relative_to(ROOT).as_posix()
                                    for path in HOST_JAVA.rglob("*.java")) if HOST_JAVA.exists() else [])
        for relative in HOST_TESTS:
            self.assertTrue((HOST_KOTLIN / relative).is_file(), relative)

    def test_ordinary_instrumentation_tests_are_kotlin(self):
        for relative in ANDROID_TESTS:
            self.assertTrue((ANDROID_KOTLIN / relative).is_file(), relative)
            self.assertFalse((ANDROID_JAVA / (relative.removesuffix(".kt") + ".java")).exists(), relative)

    def test_all_instrumentation_suites_are_kotlin(self):
        self.assertFalse(ANDROID_JAVA_ROOT.exists(), "the androidTest source set must not retain Java files")
        for relative in INTEROP_TESTS:
            self.assertTrue((ANDROID_KOTLIN / relative).is_file(), relative)

    def test_independently_signed_security_client_is_kotlin_without_changing_its_identity(self):
        self.assertFalse((SECURITY_CLIENT / "src/main/java").exists())
        activity = (SECURITY_CLIENT / "src/main/kotlin/org/shadowice/flocke/andotp/securityclient/AttackActivity.kt").read_text()
        for marker in (
            'TARGET_PACKAGE = "org.shadowice.flocke.andotp.dev"',
            'info.guardianproject.panic.action.TRIGGER',
            'org.shadowice.flocke.andotp.broadcast.ENCRYPTED_BACKUP',
            'PANIC_DENIED',
            'BROADCAST_ATTEMPTED',
            'BROADCAST_DENIED',
            'catch (_: SecurityException)',
        ):
            self.assertIn(marker, activity)
        manifest = (SECURITY_CLIENT / "src/main/AndroidManifest.xml").read_text()
        build = (SECURITY_CLIENT / "build.gradle").read_text()
        self.assertIn(".AttackActivity", manifest)
        self.assertIn("apply plugin: 'org.jetbrains.kotlin.android'", build)
        self.assertIn("org.jetbrains.kotlin:kotlin-stdlib:2.4.21", build)
        self.assertIn("strictly '2.4.21'", build)
        self.assertIn("JvmTarget.JVM_1_8", build)
        self.assertIn("debugCompileClasspath", (ROOT / "gradle/dependency-inventory.gradle").read_text())
        self.assertIn("security-test-client:", (ROOT / "gradle/dependency-inventory.gradle").read_text())

    def test_nullable_invalid_save_is_rejected_before_backup_scheduling(self):
        persistence = (APP / "src/main/kotlin/org/shadowice/flocke/andotp/Utilities/EntryPersistence.kt").read_text()
        signature = "fun save(entries: ArrayList<Entry>?"
        self.assertIn(signature, persistence)
        invalid = persistence.index("if (entries == null) return VaultWriteResult.Rejected(VaultWriteError.INVALID_INPUT)")
        save = persistence.index("DatabaseHelper.saveVault(context, entries, key!!)")
        backup = persistence.index("EncryptedBackupTask(")
        self.assertLess(invalid, save)
        self.assertLess(save, backup)

    def test_conversion_record_documents_the_completed_boundary(self):
        docs = (ROOT / "docs/kotlin-extracted-helpers.md").read_text()
        self.assertIn("Twenty-third batch (Kotlin test sources and independently signed caller)", docs)
        self.assertIn("all 15 `Kotlin*InteropTest` suites are Kotlin", docs)


if __name__ == "__main__":
    unittest.main()
