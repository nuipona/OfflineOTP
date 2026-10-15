from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"


class UiDecompositionSourceTest(unittest.TestCase):
    """Architecture source guards, not substitutes for compilation or UI/runtime tests."""

    def test_display_projection_and_timer_policy_have_no_android_or_io_dependencies(self):
        for file in (KOTLIN / "View/EntryListPresentation.kt", KOTLIN / "Utilities/ScreenTimerController.kt"):
            source = file.read_text()
            for forbidden in ("import android.", "import androidx.", "import javax.crypto.",
                              "import java.io.", "import org.shadowice.flocke.andotp.Utilities.VaultSession"):
                self.assertNotIn(forbidden, source)
        projection = (KOTLIN / "View/EntryListPresentation.kt").read_text()
        self.assertNotIn("entry.destroy()", projection)
        self.assertNotIn("DatabaseHelper", projection)

    def test_card_adapter_delegates_display_and_persistence_instead_of_owning_their_implementations(self):
        source = (JAVA / "View/EntriesCardAdapter.java").read_text()
        for dependency in ("EntryListPresentation presentation", "EntryPersistence persistence",
                           "presentation.search(", "presentation.publishSearch(", "persistence.save(", "persistence.load("):
            self.assertIn(dependency, source)
        for forbidden in ("DatabaseHelper", "new EncryptedBackupTask", "private SortMode sortMode",
                          "private ArrayList<Entry> displayedEntries"):
            self.assertNotIn(forbidden, source)
        self.assertIn("VaultSession.isCurrent(epoch)", source)
        self.assertIn("projection.close()", source)
        persistence = (KOTLIN / "Utilities/EntryPersistence.kt").read_text()
        self.assertLess(persistence.index("if (result !is VaultWriteResult.Saved) return result"),
                        persistence.index("EncryptedBackupTask("))
        self.assertIn("context.getApplicationContext()", persistence)

    def test_main_activity_wires_timer_lifecycle_without_owning_scheduling(self):
        source = (JAVA / "Activities/MainActivity.java").read_text()
        self.assertIn("new ScreenTimerController", source)
        self.assertIn("screenTimers.pause()", source)
        self.assertIn("screenTimers.close()", source)
        self.assertNotIn("new CountDownTimer", source)
        self.assertNotIn("new Handler(", source)
        self.assertNotIn("handlerTask", source)
        # The bridge is converted too, so the architecture guard reads the moved source.
        scheduler = (KOTLIN / "Utilities/HandlerTimerScheduler.kt").read_text()
        self.assertIn("Looper.getMainLooper()", scheduler)
        self.assertIn("SystemClock.elapsedRealtime()", scheduler)
        self.assertIn("ScreenTimerController.Scheduler", scheduler)

    def test_restore_screen_delegates_merge_save_and_ownership_cleanup(self):
        activity = (JAVA / "Activities/BackupActivity.java").read_text()
        body = activity.split("private boolean restoreEntries(", 1)[1].split("/* Encrypted backup functions */", 1)[0]
        self.assertIn("restoreOperation.restore(", body)
        self.assertNotIn("DatabaseHelper", body)
        self.assertNotIn("iterator", body)
        operation = (KOTLIN / "Utilities/VaultRestoreOperation.kt").read_text()
        self.assertIn("synchronized (VaultSession.MutationLock)", operation)
        self.assertIn("VaultSession.isCurrent(generation)", operation)
        self.assertIn("VaultWriteResult.RecoveryRequired", operation)
        self.assertIn("for (entry in imported) entry.destroy()", operation)
        self.assertIn("for (entry in current) entry.destroy()", operation)

    def test_dialog_rendering_does_not_own_entry_mutations_or_session_authority(self):
        dialogs = (KOTLIN / "View/EntryActionDialogs.kt").read_text()
        for forbidden in ("DatabaseHelper", "VaultSession", "SecretKey", "import org.shadowice.flocke.andotp.Database.Entry;"):
            self.assertNotIn(forbidden, dialogs)
        self.assertIn("input.isSaveEnabled = false", dialogs)
        self.assertIn('input.setText("")', dialogs)
        adapter = (JAVA / "View/EntriesCardAdapter.java").read_text()
        self.assertIn("dialogs.dismissAll()", adapter)
        self.assertIn("target.resolve(entries, VaultSession.generation())", adapter)
        self.assertIn("VaultSession.getKey() != encryptionKey", adapter)
        self.assertNotIn("new EditText(", adapter)

    def test_converted_leaf_helpers_have_one_implementation_and_keep_java_api_shapes(self):
        for stem in ("Utilities/ScreenTimerController", "View/EntryActionTarget",
                     "Utilities/InputPolicy", "View/EntryListPresentation",
                     "Utilities/EntryPersistence", "Utilities/VaultRestoreOperation",
                     "View/EntryActionDialogs", "Utilities/VaultSession",
                     "Utilities/BoundedInputReader", "Utilities/BoundedOutput",
                     "Utilities/EncryptedBackupHeader", "Utilities/TokenCalculator",
                     "Utilities/EditorActionHelper", "Utilities/HandlerTimerScheduler"):
            self.assertTrue((KOTLIN / (stem + ".kt")).is_file())
            self.assertFalse((JAVA / (stem + ".java")).exists())
        timer = (KOTLIN / "Utilities/ScreenTimerController.kt").read_text()
        for api in ("interface Scheduler", "onTick: Runnable", "onInactivity: Runnable",
                    "fun postDelayed(runnable: Runnable, delayMillis: Long)", "override fun close()"):
            self.assertIn(api, timer)
        self.assertNotIn("!!", timer)
        target = (KOTLIN / "View/EntryActionTarget.kt").read_text()
        self.assertIn("candidate === expected", target)
        self.assertIn("fun resolve(source: EntryList, currentGeneration: Long): Entry?", target)
        self.assertNotIn("data class EntryActionTarget", target)
        self.assertIn('"EntryActionTarget(redacted)"', target)
        for forbidden in ("import android.", "Parcelable", "Serializable"):
            self.assertNotIn(forbidden, target)

    def test_converted_io_and_otp_utilities_keep_java_visible_shapes(self):
        reader = (KOTLIN / "Utilities/BoundedInputReader.kt").read_text()
        for api in ("object BoundedInputReader", "const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024",
                    "@JvmStatic", "@Throws(IOException::class)", "fun read(input: InputStream?, limit: Int): ByteArray"):
            self.assertIn(api, reader)
        # A null stream stays an IOException, and the zero-read progress guarantee is kept.
        self.assertIn('throw IOException("Document provider returned no stream")', reader)
        self.assertIn('throw IOException("Document exceeds size limit")', reader)
        self.assertIn("count = 1", reader)

        output = (KOTLIN / "Utilities/BoundedOutput.kt").read_text()
        for api in ("class BoundedOutput(limit: Int) : ByteArrayOutputStream()", "@Synchronized",
                    "override fun write(value: Int)", "override fun write(bytes: ByteArray, offset: Int, length: Int)",
                    "fun overflowed(): Boolean", 'throw IllegalStateException("Provider output exceeds limit")',
                    'throw IllegalArgumentException("Invalid output limit")'):
            self.assertIn(api, output)
        self.assertIn("overflowed = true", output)

        header = (KOTLIN / "Utilities/EncryptedBackupHeader.kt").read_text()
        for api in ("class EncryptedBackupHeader private constructor(", "companion object", "@JvmStatic",
                    "const val MIN_BACKUP_BYTES", "const val MAX_ITERATIONS = 1_000_000",
                    "fun getIterations(): Int", "fun getSalt(): ByteArray = salt.clone()",
                    "fun getEncrypted(): ByteArray = encrypted.clone()"):
            self.assertIn(api, header)
        for api in ('throw IllegalArgumentException("Truncated encrypted backup")',
                    'throw IllegalArgumentException("Encrypted backup exceeds size limit")',
                    'throw IllegalArgumentException("Unsupported backup iteration count")'):
            self.assertIn(api, header)

        tokens = (KOTLIN / "Utilities/TokenCalculator.kt").read_text()
        for api in ("object TokenCalculator", "const val TOTP_DEFAULT_PERIOD = 30", "const val TOTP_DEFAULT_DIGITS = 6",
                    "const val HOTP_INITIAL_COUNTER = 1", "const val STEAM_DEFAULT_DIGITS = 5",
                    "enum class HashAlgorithm", "@JvmField", "val DEFAULT_ALGORITHM: HashAlgorithm = HashAlgorithm.SHA1",
                    "fun TOTP_RFC6238(", "fun TOTP_Steam(", "fun HOTP(", "fun MOTP("):
            self.assertIn(api, tokens)
        # The truncation and Steam mapping keep the original bit/long arithmetic.
        for api in ("hash[hash.size - 1].toInt() and 0xF", "shl 0x18", "shl 0x10", "shl 0x08",
                    "steamChars[fullToken % steamChars.size]", "fullToken /= steamChars.size",
                    'IllegalStateException("OTP calculation failed")', "Tools.formatTokenString(",
                    "InputPolicy.tokenParameters("):
            self.assertIn(api, tokens)

        editor = (KOTLIN / "Utilities/EditorActionHelper.kt").read_text()
        self.assertIn("object EditorActionHelper", editor)
        self.assertIn("fun isActionDoneOrKeyboardEnter(actionId: Int, event: KeyEvent?): Boolean", editor)
        self.assertIn("fun isActionUpKeyboardEnter(event: KeyEvent?): Boolean", editor)
        # An IME-reported action arrives without a KeyEvent; that case must stay non-fatal.
        self.assertLess(editor.index("if (actionId == EditorInfo.IME_ACTION_DONE) return true"),
                        editor.index("keyEvent.action == KeyEvent.ACTION_DOWN"))
        self.assertNotIn("!!", editor)

        scheduler = (KOTLIN / "Utilities/HandlerTimerScheduler.kt").read_text()
        self.assertIn("class HandlerTimerScheduler : ScreenTimerController.Scheduler", scheduler)
        self.assertIn("private val handler = Handler(Looper.getMainLooper())", scheduler)
