"""Source-level guards for the twenty-first batch (the remaining user interface classes).

The ten classes that were still Java are Kotlin now: the six activities, the manual entry dialog,
the credential preference and the two card adapters. The sandbox compiles all of them and asserts
the shapes it can resolve; the Java-only ``Utilities/Constants`` has no class file there, so the
members that mention one of its types cannot be reflected. Those members, the rule order of the
settings screen, the slide wiring of the intro wizard and the locale handling are pinned here; the
runtime behaviour belongs to the device test (``KotlinConvertedUiInteropTest``) and to the Android
gates.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"
ANDROID_TEST = ROOT / "app/src/androidTest/kotlin/org/shadowice/flocke/andotp"

CONVERTED = (
    "Activities/BackgroundTaskActivity",
    "Activities/AuthenticateActivity",
    "Activities/BackupActivity",
    "Activities/MainActivity",
    "Activities/IntroScreenActivity",
    "Activities/SettingsActivity",
    "Dialogs/ManualEntryDialog",
    "Preferences/CredentialsPreference",
    "View/EntryViewHolder",
    "View/EntriesCardAdapter",
)


class KotlinConvertedUiConversionTest(unittest.TestCase):
    """Guards for the twenty-first batch of the Java to Kotlin conversion."""

    def test_the_converted_classes_replaced_their_java_sources(self):
        harness = (SANDBOX / "run.sh").read_text()
        for name in CONVERTED:
            self.assertFalse((JAVA / f"{name}.java").exists(), f"{name}.java is still there")
            self.assertTrue((KOTLIN / f"{name}.kt").is_file(), f"{name}.kt is missing")
            self.assertIn(f"$KOTLIN_APP/{name}.kt", harness)
        # No main-source Java is left: the two documented exclusions became Kotlin in the
        # twenty-second batch.
        remaining = sorted(path.relative_to(JAVA).as_posix()
                           for path in JAVA.rglob("*.java"))
        self.assertEqual([], remaining)

    def test_the_credential_preference_keeps_its_xml_name_and_default(self):
        # The preference XML instantiates the class by name, so the fully qualified name, the
        # (Context, AttributeSet) constructor and the DEFAULT_VALUE constant must not move.
        preferences = (ROOT / "app/src/main/res/xml/preferences.xml").read_text()
        self.assertIn("org.shadowice.flocke.andotp.Preferences.CredentialsPreference", preferences)
        source = (KOTLIN / "Preferences/CredentialsPreference.kt").read_text()
        self.assertIn("open class CredentialsPreference(context: Context, attrs: AttributeSet?) :", source)
        self.assertIn("@JvmField", source)
        self.assertIn("val DEFAULT_VALUE: AuthMethod = AuthMethod.NONE", source)
        # The two setters the settings screen and the activity use stay public.
        self.assertIn("fun setOldEncryptionKey(oldKey: SecretKey?) {", source)
        self.assertIn("fun setEncryptionChangeCallback(changeCallback: EncryptionHelper.EncryptionChangeCallback?) {",
                      source)
        # The auth method is stored in English, exactly like the Java class did.
        self.assertIn("DEFAULT_VALUE.name.lowercase(Locale.ENGLISH)", source)
        self.assertIn("AuthMethod.valueOf(stringValue.uppercase(Locale.ENGLISH))", source)

    def test_the_manual_entry_dialog_stays_a_static_entry_point(self):
        source = (KOTLIN / "Dialogs/ManualEntryDialog.kt").read_text()
        self.assertIn("object ManualEntryDialog {", source)
        self.assertIn("@JvmStatic", source)
        self.assertIn("fun show(callingActivity: MainActivity, settings: Settings, adapter: EntriesCardAdapter) {",
                      source)
        self.assertIn("fun show(callingActivity: MainActivity, settings: Settings, adapter: EntriesCardAdapter,",
                      source)
        self.assertIn("fun interface UpdateCallback {", source)
        # The dialog is still opened the same way from the converted callers.
        main = (KOTLIN / "Activities/MainActivity.kt").read_text()
        self.assertIn("ManualEntryDialog.show(this, settings!!, adapter!!)", main)

    def test_the_entry_card_keeps_its_bases_and_its_members(self):
        source = (KOTLIN / "View/EntryViewHolder.kt").read_text()
        self.assertIn("open class EntryViewHolder(context: Context, v: View, tapToReveal: Boolean) :", source)
        self.assertIn("RecyclerView.ViewHolder(v), ItemTouchHelperViewHolder {", source)
        for member in ("fun updateValues(entry: Entry) {",
                       "fun setLabelSize(size: Int) {",
                       "fun setThumbnailSize(size: Int) {",
                       "fun setLabelScroll(labelDisplay: Constants.LabelDisplay, cardLayout: Constants.CardLayouts) {",
                       "fun setCallback(cb: Callback?) {",
                       "fun updateColor(color: Int) {",
                       "interface Callback {"):
            self.assertIn(member, source)
        # The unformatted token stays in the view tag, so the copy action has the raw value.
        self.assertIn("value.tag = entry.getCurrentOTP()", source)
        # The counter is only shown for a counter based entry.
        self.assertIn("if (entry.getType() == Entry.OTPType.HOTP) {", source)

    def test_the_card_adapter_keeps_its_bases_and_the_key_field(self):
        source = (KOTLIN / "View/EntriesCardAdapter.kt").read_text()
        self.assertIn("open class EntriesCardAdapter(context: Context, private val tagsFilterAdapter: TagsAdapter) :",
                      source)
        self.assertIn("RecyclerView.Adapter<EntryViewHolder>(), ItemTouchHelperAdapter, Filterable {", source)
        # The Java getEncryptionKey()/setEncryptionKey() pair became a read/write field.
        self.assertIn("var encryptionKey: SecretKey? = null", source)
        self.assertNotIn("fun getEncryptionKey", source)
        self.assertNotIn("fun setEncryptionKey", source)
        for member in ("fun getEntries(): ArrayList<Entry> = entries.getEntries()",
                       "fun addEntry(e: Entry) {",
                       "fun saveAndRefresh(auto_backup: Boolean) {",
                       "fun saveAndRefresh(auto_backup: Boolean, itemPos: Int) {",
                       "fun updateTagsFilter() {",
                       "fun saveEntries(auto_backup: Boolean) {",
                       "fun lockEntries() {",
                       "fun loadEntries(): Boolean {",
                       "fun filterByTags(tags: List<String>) {",
                       "fun updateTimeBasedTokens() {"):
            self.assertIn(member, source)
        # The tag drawer is rebuilt from the tags that are in use, and the selection is applied.
        self.assertIn("presentation.reconcileTags(tagsFilterAdapter.getTags(), tagsFilterAdapter.getActiveTags())",
                      source)
        self.assertIn("tagsFilterAdapter.setTags(reconciled)", source)
        self.assertIn("presentation.selectTags(tagsFilterAdapter.getActiveTags())", source)

    def test_the_main_activity_keeps_the_public_static_animator_duration(self):
        source = (KOTLIN / "Activities/MainActivity.kt").read_text()
        # The Java field was `public static long`; the entry view holder reads it by that name.
        self.assertIn("@JvmField", source)
        self.assertIn("var animatorDuration: Long = 1000", source)
        holder = (KOTLIN / "View/EntryViewHolder.kt").read_text()
        self.assertIn("org.shadowice.flocke.andotp.Activities.MainActivity.animatorDuration", holder)
        # The Java callers of the activity keep their members.
        for member in ("fun authenticate(messageId: Int) {",
                       "fun stopUpdater() {",
                       "fun startUpdater() {",
                       "fun refreshTags() {"):
            self.assertIn(member, source)

    def test_the_settings_fragment_keeps_the_auto_backup_rule(self):
        source = (KOTLIN / "Activities/SettingsActivity.kt").read_text()
        body = source[source.index("fun updateAutoBackup() {"):]
        body = body[:body.index("\n        }")]
        # The rule: the preference follows the stored backup type, and it is forced to "off" when
        # the backup is not available.
        self.assertIn("val autoBackup = useAutoBackup ?: return", body)
        self.assertIn("autoBackup.isEnabled = BackupHelper.autoBackupType(getActivity()!!) == "
                      "Constants.BackupType.ENCRYPTED", body)
        self.assertIn("autoBackup.value = Constants.AutoBackup.OFF.toString().lowercase(Locale.ENGLISH)", body)
        self.assertIn("autoBackup.setSummary(R.string.settings_desc_auto_backup_password_enc)", body)
        self.assertIn("autoBackup.setSummary(R.string.settings_desc_auto_backup_requirements)", body)
        # The fragment stays a public nested class with the members the activity uses.
        self.assertIn("open class SettingsFragment : PreferenceFragment() {", source)
        for member in ("fun encryptionChangeWithDialog(encryptionType: EncryptionType) {",
                       "fun setEncryptionKey(encryptionKey: SecretKey?) {",
                       "fun setEncryptionChangeCallback(changeCallback: EncryptionHelper.EncryptionChangeCallback?) {"):
            self.assertIn(member, source)
        # The OpenPGP provider still drives the signing key.
        self.assertIn("signingKey?.setOpenPgpProvider(pgpProvider?.value)", source)
        self.assertIn("signingKey?.setOpenPgpProvider(newValue as? String)", source)

    def test_the_intro_wizard_keeps_its_four_slides(self):
        source = (KOTLIN / "Activities/IntroScreenActivity.kt").read_text()
        for nested in ("open class EncryptionFragment : SlideFragment() {",
                       "open class AuthenticationFragment : SlideFragment(), TextView.OnEditorActionListener {",
                       "open class AndroidSyncFragment(private val encryptionFragment: EncryptionFragment) : SlideFragment() {",
                       "open class IntroFinishedFragment(private val context: Context, private val callback: SaveSettingsCallback) :"):
            self.assertIn(nested, source)
        # Five slides: the welcome slide and the four fragment slides.
        self.assertEqual(5, source.count("addSlide("))
        self.assertIn("setButtonBackFunction(BUTTON_BACK_FUNCTION_BACK)", source)
        self.assertIn("authentication.setSlidePos(getSlides().size)", source)
        # The encryption change is forwarded to the authentication slide.
        self.assertIn("encryption.setEncryptionChangedCallback(EncryptionFragment.EncryptionChangedCallback",
                      source)
        # The result carries the setup flag and the new key.
        self.assertIn("data.putExtra(Constants.EXTRA_INTRO_FINISHED, setupFinished)", source)
        self.assertIn("VaultSession.offerDerivedKey(key, setupGeneration)", source)

    def test_the_background_task_fragment_keeps_its_members(self):
        source = (KOTLIN / "Activities/BackgroundTaskActivity.kt").read_text()
        self.assertIn("abstract class BackgroundTaskActivity<Result> : BaseActivity() {", source)
        self.assertIn("class TaskFragment<Result> : Fragment() {", source)
        for member in ("fun startTask(task: UiBasedBackgroundTask<Result>) {",
                       "fun setOutcomeCallback(callback: UiBasedBackgroundTask.OutcomeCallback<Result>?) {",
                       "fun isCanceled(): Boolean {",
                       "fun cancelTask() {"):
            self.assertIn(member, source)
        # The task hook of the Java class was package private; Kotlin has no such visibility.
        self.assertIn("protected abstract fun onTaskResult(result: Result)", source)
        self.assertIn("the Java `onTaskResult` was package-private", source)

    def test_backup_success_reports_the_returned_filename(self):
        activity = (KOTLIN / "Activities/BackupActivity.kt").read_text()
        result = activity.split("private fun handleBackupTaskResult(", 1)[1].split(
            "private fun handleRestoreTaskResult(", 1
        )[0]
        self.assertIn("notifyBackupState(successMessage, result.payload)", result)
        self.assertIn("result.payload", result)
        self.assertIn("val message = getString(msgId)", activity)
        self.assertIn('"$message\\n$detail"', activity)
        self.assertNotIn("TODO: Show more information about the finished backup", activity)

    def test_encryption_progress_is_modal_lifecycle_safe_and_keeps_navigation_blocked(self):
        activity = (KOTLIN / "Activities/SettingsActivity.kt").read_text()
        state = activity.split("override fun setupUiForTaskState(", 1)[1].split(
            "/** Asks for a folder", 1
        )[0]
        self.assertIn("AlertDialog.Builder(this)", state)
        self.assertIn("R.layout.dialog_encryption_progress", state)
        self.assertIn(".setCancelable(false)", state)
        self.assertIn("canGoBack = false", state)
        self.assertIn("inProgress?.dismiss()", state)
        self.assertIn("inProgress = null", state)
        self.assertNotIn("LENGTH_INDEFINITE", state)
        destroy = activity.split("override fun onDestroy()", 1)[1].split("/** The preference screen", 1)[0]
        self.assertIn("inProgress?.dismiss()", destroy)
        self.assertNotIn("TODO: Better in-progress notification", activity)

    def test_the_widened_visibility_is_kept_and_documented(self):
        # The Java classes widened two platform methods to public; the Kotlin classes keep that.
        backup = (KOTLIN / "Activities/BackupActivity.kt").read_text()
        self.assertIn("public override fun onDestroy() {", backup)
        self.assertIn("The Java class widened the platform's protected `onDestroy()` to public", backup)
        settings = (KOTLIN / "Activities/SettingsActivity.kt").read_text()
        self.assertIn("public override fun onCreate(savedInstanceState: Bundle?) {", settings)
        self.assertIn("The Java class widened the platform fragment's protected `onCreate()` to public",
                      settings)

    def test_main_activity_uses_normal_configuration_recreation_without_serializing_a_session(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        activity = manifest.split('android:name=".Activities.MainActivity"', 1)[1].split(">", 1)[0]
        self.assertNotIn("android:configChanges", activity)
        self.assertNotIn("TODO: Rework configuration changes", manifest)

        source = (KOTLIN / "Activities/MainActivity.kt").read_text()
        create = source.split("override fun onCreate(savedInstanceState: Bundle?)", 1)[1].split(
            "private fun checkIntent()", 1
        )[0]
        self.assertNotIn("VaultSession.lock()", create)
        self.assertIn("requireAuthentication = VaultSession.getKey() == null &&", create)
        resume = source.split("public override fun onResume()", 1)[1].split("public override fun onPause()", 1)[0]
        self.assertIn("val sessionKey = VaultSession.getKey()", resume)
        self.assertIn("currentAdapter.encryptionKey = sessionKey", resume)
        self.assertIn("updateEncryption(null)", resume)
        self.assertIn('outState.putString("filterString", filterString)', source)
        self.assertNotIn("onConfigurationChanged", source)

    def test_authentication_and_intro_clear_secret_widgets_after_transfer_and_teardown(self):
        auth = (KOTLIN / "Activities/AuthenticateActivity.kt").read_text()
        start = auth.split("private fun startAuthTask(", 1)[1].split("override fun onTaskFailure", 1)[0]
        self.assertIn("startBackgroundTask(task)", start)
        self.assertIn("finally", start)
        self.assertIn("clearPasswordInput()", start)
        finish = auth.split(
            "private fun finishWithResult(success: Boolean, encryptionKey: ByteArray?, expected: Long) {", 1
        )[1].split("\n    @Suppress", 1)[0]
        self.assertIn("clearPasswordInput()", finish)
        self.assertIn("override fun onDestroy()", auth)

        intro = (KOTLIN / "Activities/IntroScreenActivity.kt").read_text()
        self.assertIn("authentication.clearCredentialInputs()", intro)
        self.assertIn("fun clearCredentialInputs()", intro)
        self.assertIn("override fun onDestroy()", intro)
        self.assertIn("authenticationFragment?.clearCredentialInputs()", intro)

    def test_the_locale_sensitive_conversions_keep_the_java_semantics(self):
        # Java's bare toUpperCase()/toLowerCase() use the default locale; the Kotlin calls say so.
        settings = (KOTLIN / "Activities/SettingsActivity.kt").read_text()
        self.assertIn("newEncryptionType.name.lowercase(Locale.getDefault())", settings)
        self.assertIn("EncryptionType.valueOf(newEncryption.uppercase(Locale.getDefault()))", settings)
        intro = (KOTLIN / "Activities/IntroScreenActivity.kt").read_text()
        self.assertIn("Constants.EncryptionType.valueOf(encValues[i].uppercase(Locale.getDefault()))", intro)
        # The Java class of the credential preference used English on purpose; it stays English.
        credentials = (KOTLIN / "Preferences/CredentialsPreference.kt").read_text()
        self.assertNotIn("Locale.getDefault()", credentials)

    def test_the_device_test_covers_the_members_the_sandbox_cannot_reflect(self):
        test = ANDROID_TEST / "Activities/KotlinConvertedUiInteropTest.kt"
        self.assertTrue(test.is_file(), "the device test for the converted classes is missing")
        source = test.read_text()
        # The sandbox cannot load every Android-only dependency, so the same members are asserted
        # by the Kotlin device test.
        for member in ("CredentialsPreference.DEFAULT_VALUE",
                       "ManualEntryDialog.UpdateCallback::class.java.isInterface",
                       "SettingsActivity\\$SettingsFragment",
                       "IntroScreenActivity\\$",
                       "BackgroundTaskActivity\\$TaskFragment",
                       "MainActivity.animatorDuration",
                       "adapter.encryptionKey = key",
                       "adapter.encryptionKey",
                       "entryCardBindsTheTokenLabelsTagsAndCounter"):
            self.assertIn(member, source)

    def test_the_manifest_and_the_preferences_xml_still_resolve(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        for name in ("MainActivity", "AuthenticateActivity", "BackupActivity", "IntroScreenActivity",
                     "SettingsActivity"):
            self.assertIn(f'android:name=".Activities.{name}"', manifest)
        # No Java source of a converted class is left for the build to pick up.
        for name in CONVERTED:
            self.assertFalse((JAVA / f"{name}.java").exists())


if __name__ == "__main__":
    unittest.main()
