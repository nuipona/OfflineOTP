"""Source-level guards for the eighteenth batch (the activity base classes).

The sandbox compiles the converted base activities against the AppCompat/activity stand-ins and
executes the screen-off handling and the obscured-touch rejection. The parts that need a real
window, a real configuration change or a real broadcast dispatch stay with the Android gates.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


class KotlinActivityBasesConversionTest(unittest.TestCase):
    def test_replacements_keep_their_names_and_are_compiled_by_the_sandbox(self):
        harness = (SANDBOX / "run.sh").read_text()
        for name in ("Activities/ThemedActivity", "Activities/BaseActivity"):
            self.assertFalse((JAVA / f"{name}.java").exists(), f"{name}.java is still there")
            self.assertTrue((KOTLIN / f"{name}.kt").is_file(), f"{name}.kt is missing")
            self.assertIn(f"$KOTLIN_APP/{name}.kt", harness)
        for stub in ("androidx/appcompat/app/AppCompatActivity.kt", "android/android_view_window.kt",
                     "android/android_view_motion_event.kt", "android/android_os_bundle.kt"):
            self.assertTrue((SANDBOX / "stubs" / stub).is_file(), f"the {stub} stand-in is missing")

    def test_the_settings_field_stays_a_field_the_java_activities_can_read(self):
        source = (KOTLIN / "Activities/ThemedActivity.kt").read_text()
        # `@JvmField` keeps the public field the Java activities read; `lateinit` cannot be
        # combined with it, so the field is nullable exactly like the Java field was.
        self.assertIn("@JvmField\n    var settings: Settings? = null", source)
        self.assertIn("val current = Settings(this)\n        settings = current", source)
        # The theme is applied before `super.onCreate()`, which is what AppCompat expects.
        body = source[source.index("override fun onCreate("):source.index("public override fun onResume()")]
        order = ["settings = current", "setTheme(current.getTheme())",
                 "getWindow().setNavigationBarColor(", "super.onCreate(savedInstanceState)"]
        positions = [body.index(marker) for marker in order]
        self.assertEqual(sorted(positions), positions, "the theme is no longer applied before super.onCreate()")
        # Locale changes are applied through an activity-specific configuration context, never by
        # mutating the process-wide Resources instance.
        attach = source[source.index("override fun attachBaseContext(newBase: Context)"):
                        source.index("override fun onCreate(savedInstanceState: Bundle?)")]
        self.assertIn("Settings(newBase).getLocale()", attach)
        self.assertIn("LocaleContextHelper.createLocalizedContext(newBase, locale)", attach)
        locale = source[source.index("public fun setLocale()"):]
        self.assertIn("LocaleContextHelper.isLocaleApplied(this, locale)", locale)
        self.assertIn("recreate()", locale)
        self.assertNotIn("updateConfiguration", source)

        helper = (KOTLIN / "Utilities/LocaleContextHelper.kt").read_text()
        self.assertIn("Configuration(context.resources.configuration)", helper)
        self.assertIn("configuration.setLocale(locale)", helper)
        self.assertIn("context.createConfigurationContext(configuration)", helper)
        self.assertNotIn("updateConfiguration", helper)

    def test_the_converted_activities_keep_reading_the_settings_field(self):
        # The field name did not change, so the call sites still read the same field. The
        # activities are Kotlin since the twenty-first batch, so they read the nullable field
        # through `settings!!`/`settings?.` instead of the Java null-forgiving field access.
        for name in ("Activities/MainActivity.kt", "Activities/AuthenticateActivity.kt",
                     "Activities/BackupActivity.kt", "Activities/SettingsActivity.kt",
                     "Activities/IntroScreenActivity.kt"):
            source = (KOTLIN / name).read_text()
            self.assertRegex(source, r"settings(!!|\?)?\.", name)
        # The overridden hooks stay protected and overridable.
        for name, hook in (("Activities/MainActivity.kt", "override fun onVaultLocked()"),
                           ("Activities/BackupActivity.kt", "override fun requiresVaultSession()"),
                           ("Activities/SettingsActivity.kt", "override fun requiresVaultSession()"),
                           ("Activities/AuthenticateActivity.kt", "override fun onVaultLocked()"),
                           ("Activities/BackupActivity.kt", "override fun shouldDestroyOnScreenOff()"),
                           ("Activities/MainActivity.kt", "override fun shouldDestroyOnScreenOff()"),
                           ("Activities/AuthenticateActivity.kt", "override fun shouldDestroyOnScreenOff()")):
            self.assertIn(hook, (KOTLIN / name).read_text(), f"{name}: {hook}")

    def test_the_base_activity_keeps_the_screen_off_and_the_lock_wiring(self):
        source = (KOTLIN / "Activities/BaseActivity.kt").read_text()
        body = source[source.index("override fun onCreate("):source.index("override fun onDestroy()")]
        self.assertIn("VaultSession.addLockListener(lockListener)", body)
        self.assertIn("registerReceiver(receiver, receiver.filter)", body)
        destroy = source[source.index("override fun onDestroy()"):source.index("public fun setBroadcastCallback(")]
        self.assertIn("VaultSession.removeLockListener(lockListener)", destroy)
        self.assertIn("screenOffReceiver?.let { unregisterReceiver(it) }", destroy)
        # The lock listener reaches the activity through the UI thread, as in Java.
        self.assertIn("private val lockListener = Runnable { runOnUiThread { onVaultLocked() } }", source)
        receiver = source[source.index("inner class ScreenOffReceiver"):]
        self.assertIn("var filter: IntentFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)", receiver)
        self.assertIn("if (settings!!.getRelockOnScreenOff()) VaultSession.lock(SessionLockReason.SCREEN_OFF)", receiver)
        self.assertIn("broadcastReceivedCallback?.onReceivedScreenOff()", receiver)
        self.assertIn("if (shouldDestroyOnScreenOff()) finish()", receiver)
        # The callback stays a single-method interface, so the Java call sites keep their lambda.
        self.assertIn("fun interface BroadcastReceivedCallback {", source)
        self.assertIn("fun onReceivedScreenOff()", source)
        self.assertIn("setBroadcastCallback {",
                      (KOTLIN / "Activities/MainActivity.kt").read_text())
        self.assertIn("if (settings!!.getRelockOnScreenOff()) onVaultLocked()",
                      (KOTLIN / "Activities/MainActivity.kt").read_text())

    def test_the_base_activity_keeps_the_obscured_touch_rejection(self):
        source = (KOTLIN / "Activities/BaseActivity.kt").read_text()
        body = source[source.index("override fun dispatchTouchEvent("):]
        self.assertIn("var obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED", body)
        self.assertIn("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {", body)
        self.assertIn("obscured = obscured or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED", body)
        self.assertIn("return (event.getFlags() and obscured) == 0 && super.dispatchTouchEvent(event)", body)
        # The session requirement is still checked on resume.
        self.assertIn("if (requiresVaultSession() && VaultSession.getKey() == null) finish()", source)


if __name__ == "__main__":
    unittest.main()
