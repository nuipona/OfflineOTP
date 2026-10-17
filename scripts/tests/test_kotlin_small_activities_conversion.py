"""Source-level guards for the nineteenth batch (the small standalone activities).

The sandbox compiles the converted panic responder and QR scanner activity against the platform
stand-ins and executes their decision logic. The parts that need a real window, a real camera or a
real file system stay with the Android gates.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
ANDROID_TEST = ROOT / "app/src/androidTest/kotlin/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


class KotlinSmallActivitiesConversionTest(unittest.TestCase):
    def test_the_replacements_keep_their_names_and_are_compiled_by_the_sandbox(self):
        harness = (SANDBOX / "run.sh").read_text()
        for name in ("Activities/PanicResponderActivity", "Activities/SecureCaptureActivity"):
            self.assertFalse((JAVA / f"{name}.java").exists(), f"{name}.java is still there")
            self.assertTrue((KOTLIN / f"{name}.kt").is_file(), f"{name}.kt is missing")
            self.assertIn(f"$KOTLIN_APP/{name}.kt", harness)
        for stub in ("android/android_view_window_insets.kt",
                     "com/journeyapps/barcodescanner/BarcodeScanner.kt"):
            self.assertTrue((SANDBOX / "stubs" / stub).is_file(), f"the {stub} stand-in is missing")

    def test_the_panic_responder_keeps_its_action_and_its_steps(self):
        source = (KOTLIN / "Activities/PanicResponderActivity.kt").read_text()
        self.assertIn('const val PANIC_TRIGGER_ACTION = "info.guardianproject.panic.action.TRIGGER"',
                      source)
        # The action is compared before anything is wiped.
        self.assertIn("if (intent != null && PANIC_TRIGGER_ACTION == intent.getAction())", source)
        body = source[source.index("if (intent != null"):source.index("finishAndRemoveTask()")]
        # The order of the three steps is the security-relevant part: the backups are disabled,
        # the pending backup is cancelled and the vault is locked before anything is wiped.
        steps = ["settings.disableAutomaticBackups()",
                 "cancelUniqueWork(EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME)",
                 "VaultSession.lock()",
                 "DatabaseHelper.wipeDatabase(this)",
                 "KeyStoreHelper.wipeKeys(this)",
                 "settings.clear(true)"]
        positions = [body.index(step) for step in steps]
        self.assertEqual(sorted(positions), positions, "the panic steps are out of order")
        # The accounts and the settings are separate responses.
        self.assertIn('response.contains("accounts") || response.contains("settings")', body)
        self.assertIn('if (response.contains("accounts")) {', body)
        self.assertIn('if (response.contains("settings"))', body)
        # The activity always removes its task.
        self.assertIn("finishAndRemoveTask()", source)
        # The class stays extendable, like the Java class.
        self.assertIn("open class PanicResponderActivity : Activity()", source)

    def test_the_scanner_activity_keeps_its_window_handling(self):
        source = (KOTLIN / "Activities/SecureCaptureActivity.kt").read_text()
        self.assertIn("open class SecureCaptureActivity : CaptureActivity()", source)
        # The theme comes from settings; the locale is applied before the scanner activity is created.
        self.assertIn("setTheme(settings.getTheme())", source)
        self.assertIn("Settings(newBase).getLocale()", source)
        self.assertIn("LocaleContextHelper.createLocalizedContext(newBase, locale)", source)
        # The hardware acceleration is requested before the content view is built.
        body = source[source.index("override fun initializeContent()"):]
        acceleration = body.index("FLAG_HARDWARE_ACCELERATED")
        content = body.index("super.initializeContent()")
        self.assertLess(acceleration, content, "the acceleration flag is set too late")
        # The screenshots are only blocked when the setting says so.
        self.assertIn("if (!settings.getScreenshotsEnabled()) {", body)
        self.assertIn("getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, "
                      "WindowManager.LayoutParams.FLAG_SECURE)", body)
        # The system bars are hidden with the insets controller from Android 11 on.
        self.assertIn("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {", body)
        self.assertIn("insetsController.hide(WindowInsets.Type.statusBars() or "
                      "WindowInsets.Type.navigationBars())", body)
        self.assertIn("insetsController.setSystemBarsBehavior("
                      "WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)", body)
        # Below that the fullscreen flag is used.
        self.assertIn("WindowManager.LayoutParams.FLAG_FULLSCREEN", body)
        # Locale changes after the scanner is launched recreate it with a correctly wrapped base context.
        self.assertIn("if (!LocaleContextHelper.isLocaleApplied(this, locale))", source)
        self.assertIn("recreate()", source)
        self.assertNotIn("updateConfiguration", source)

    def test_the_manifest_and_the_java_call_sites_are_unchanged(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:name=".Activities.SecureCaptureActivity"', manifest)
        self.assertIn('android:name=".Activities.PanicResponderActivity"', manifest)
        # The scanner activity is still referred to by its class literal. MainActivity is Kotlin
        # since the twenty-first batch, so the call site moved with it.
        self.assertIn(".setCaptureActivity(SecureCaptureActivity::class.java)",
                      (KOTLIN / "Activities/MainActivity.kt").read_text())

    def test_the_sandbox_checks_cover_the_converted_activities(self):
        checks = (SANDBOX / "Checks.kt").read_text()
        self.assertIn("the panic responder wipes only what the panic action asks for", checks)
        self.assertIn("the QR scanner activity honours the theme, the language and the screenshot "
                      "setting", checks)

    def test_the_device_test_covers_the_paths_the_sandbox_cannot_reach(self):
        test = ANDROID_TEST / "Activities/KotlinSmallActivitiesInteropTest.kt"
        self.assertTrue(test.is_file(), "the device test for the converted activities is missing")
        source = test.read_text()
        # The destructive path is started for real on the device.
        self.assertIn("PanicResponderActivity.PANIC_TRIGGER_ACTION", source)
        self.assertIn("accountsResponseWipesTheVaultAndDisablesTheBackups", source)
        self.assertIn("settingsResponseClearsTheSettings", source)
        self.assertIn("foreignActionWipesNothing", source)
        # The scanner activity is checked for its shape, because it needs a camera.
        self.assertIn("scannerActivityKeepsItsShape", source)
        self.assertIn("localizedContextUsesTheRequestedLocaleWithoutMutatingTheBaseContext", source)
        self.assertIn("CaptureActivity::class.java", source)
        self.assertIn("Modifier.isProtected(initialize.modifiers)", source)


if __name__ == "__main__":
    unittest.main()
