from pathlib import Path
import re
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
        # The card adapter is Kotlin since the twenty-first batch, so the guard reads the moved
        # source.
        source = (KOTLIN / "View/EntriesCardAdapter.kt").read_text()
        for dependency in ("presentation: EntryListPresentation", "persistence: EntryPersistence",
                           "presentation.search(", "presentation.publishSearch(", "persistence.save(", "persistence.load("):
            self.assertIn(dependency, source)
        for forbidden in ("DatabaseHelper", "EncryptedBackupTask(", "private var sortMode",
                          "private val displayedEntries"):
            self.assertNotIn(forbidden, source)
        self.assertIn("VaultSession.isCurrent(epoch)", source)
        self.assertIn("projection.close()", source)
        persistence = (KOTLIN / "Utilities/EntryPersistence.kt").read_text()
        self.assertLess(persistence.index("if (result !is VaultWriteResult.Saved) return result"),
                        persistence.index("EncryptedBackupTask("))
        self.assertIn("context.getApplicationContext()", persistence)

    def test_main_activity_wires_timer_lifecycle_without_owning_scheduling(self):
        # The main activity is Kotlin since the twenty-first batch, so the guard reads the moved
        # source.
        source = (KOTLIN / "Activities/MainActivity.kt").read_text()
        self.assertIn("ScreenTimerController(HandlerTimerScheduler()", source)
        self.assertIn("screenTimers?.pause()", source)
        self.assertIn("screenTimers?.close()", source)
        self.assertNotIn("CountDownTimer", source)
        self.assertNotIn("Handler(", source)
        self.assertNotIn("handlerTask", source)
        # The bridge is converted too, so the architecture guard reads the moved source.
        scheduler = (KOTLIN / "Utilities/HandlerTimerScheduler.kt").read_text()
        self.assertIn("Looper.getMainLooper()", scheduler)
        self.assertIn("SystemClock.elapsedRealtime()", scheduler)
        self.assertIn("ScreenTimerController.Scheduler", scheduler)

    def test_restore_screen_delegates_merge_save_and_ownership_cleanup(self):
        # The backup activity is Kotlin since the twenty-first batch, so the guard reads the moved
        # source.
        activity = (KOTLIN / "Activities/BackupActivity.kt").read_text()
        body = activity.split("private fun restoreEntries(", 1)[1].split("/* Encrypted backup functions */", 1)[0]
        self.assertIn("restoreOperation!!.restore(", body)
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
        adapter = (KOTLIN / "View/EntriesCardAdapter.kt").read_text()
        self.assertIn("dialogs.dismissAll()", adapter)
        self.assertIn("target.resolve(entries, VaultSession.generation())", adapter)
        # The Java `!=` on the key object is reference identity, so the Kotlin conversion keeps
        # the identity comparison instead of switching to `equals()`.
        self.assertIn("VaultSession.getKey() !== encryptionKey", adapter)
        self.assertNotIn("EditText(", adapter)

    def test_converted_leaf_helpers_have_one_implementation_and_keep_java_api_shapes(self):
        for stem in ("Utilities/ScreenTimerController", "View/EntryActionTarget",
                     "Utilities/InputPolicy", "View/EntryListPresentation",
                     "Utilities/EntryPersistence", "Utilities/VaultRestoreOperation",
                     "View/EntryActionDialogs", "Utilities/VaultSession",
                     "Utilities/BoundedInputReader", "Utilities/BoundedOutput",
                     "Utilities/EncryptedBackupHeader", "Utilities/TokenCalculator",
                     "Utilities/EditorActionHelper", "Utilities/HandlerTimerScheduler",
                     "Utilities/SecretKeyWrapper", "Utilities/DimensionConverter",
                     "View/SimpleDoubleClickListener",
                     "View/ItemTouchHelper/ItemTouchHelperAdapter",
                     "View/ItemTouchHelper/ItemTouchHelperViewHolder",
                     "View/ItemTouchHelper/SimpleItemTouchHelperCallback",
                     "Utilities/UIHelper", "Utilities/ConfirmedPasswordTransformationHelper",
                     "Tasks/BackupTaskResult", "Tasks/UiBasedBackgroundTask",
                     "Tasks/AuthenticationTask", "Tasks/GenericBackupTask", "Tasks/GenericRestoreTask",
                     "Tasks/PGPBackupTask", "Tasks/PGPRestoreTask", "Tasks/EncryptedBackupTask",
                     "Tasks/EncryptedRestoreTask", "Tasks/ChangeEncryptionTask",
                     "Tasks/ChangeCredentialsTask", "Tasks/FinishIntroTask",
                     "Database/Entry", "Database/EntryJson", "Database/EntryJsonResult",
                     "Utilities/DatabaseHelper", "Utilities/EncryptionHelper", "Utilities/KeyStoreHelper",
                     "Utilities/StorageAccessHelper", "Utilities/BackupHelper",
                     "Utilities/NotificationHelper", "Utilities/ScanQRCodeFromFile",
                     "Dialogs/PasswordEntryDialog", "Dialogs/TagsDialog",
                     "Preferences/PasswordEncryptedPreference",
                     "Utilities/Settings",
                     "Receivers/BackupBroadcastReceiver", "Receivers/EncryptedBackupBroadcastReceiver",
                     "Tasks/EncryptedBackupWorker", "Utilities/BackupAgent",
                     "Activities/ThemedActivity", "Activities/BaseActivity",
                     "Activities/PanicResponderActivity", "Activities/SecureCaptureActivity",
                     "Activities/AboutActivity", "Fragments/BaseAboutFragment"):
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

    def test_converted_entry_model_and_document_boundary_keep_their_shapes(self):
        """Entry is executed by Gradle/instrumentation only; these additional Java-visible shapes
        and preserved semantics are pinned here at source level, not verified by Android execution."""
        entry = (KOTLIN / "Database/Entry.kt").read_text()
        codec = (KOTLIN / "Database/EntryJson.kt").read_text()
        contract = (KOTLIN / "Database/EntryJsonResult.kt").read_text()

        # The Java class was extendable, exposed a public tags field and a nested enum.
        self.assertIn("open class Entry {", entry)
        self.assertIn("enum class OTPType {\n        TOTP, HOTP, MOTP, STEAM\n    }", entry)
        self.assertIn("@JvmField\n    var tags: MutableList<String>", entry)
        self.assertIn("fun getTags(): MutableList<String> = tags", entry)
        self.assertIn("fun setTags(tags: MutableList<String>) {", entry)
        self.assertIn("const val COLOR_RED = 1", entry)
        self.assertIn("@JvmStatic\n        fun validateSecret(secret: String?, type: OTPType?): Boolean", entry)

        # Both legacy adapters keep the checked exception their Java callers catch, and their
        # failure text carries the closed error name instead of the rejected input.
        self.assertEqual(2, entry.count("@Throws(Exception::class)\n    constructor("))
        self.assertIn('throw IllegalArgumentException("Invalid OTP URI: " + result.error.name)', entry)
        self.assertIn('throw IllegalArgumentException("Invalid entry JSON: " + result.error.name)', entry)
        self.assertIn("@Throws(JSONException::class)\n    fun toJSON(): JSONObject = EntryJson.serialize(", entry)

        # One definition of the stored format: the document field names live in the boundary only.
        for json_key in ("last_used", "used_frequency", "thumbnail", "type", "label"):
            self.assertNotIn(f'"{json_key}"', entry)
            self.assertIn(f'"{json_key}"', codec)
        self.assertNotIn("Base32().decode", entry)
        for signature in ("@JvmStatic\n    fun parse(json: JSONObject): EntryJsonResult",
                          "@JvmStatic\n    @Throws(JSONException::class)\n    fun serialize(",
                          "@JvmStatic\n    fun decodeBase32Secret(encoded: String?): ByteArray?"):
            self.assertIn(signature, codec)
        for import_line in ("import android.", "import androidx.", "import javax.crypto."):
            self.assertNotIn(import_line, codec)
            self.assertNotIn(import_line, contract)
        # The serialized field order is part of the stored format and of the JSON round-trip test.
        body = codec[codec.index("fun serialize("):]
        self.assertEqual(["JSON_SECRET", "JSON_ISSUER", "JSON_LABEL", "JSON_DIGITS", "JSON_TYPE",
                          "JSON_ALGORITHM", "JSON_THUMBNAIL", "JSON_LAST_USED", "JSON_USED_FREQUENCY",
                          "JSON_PERIOD", "JSON_PERIOD", "JSON_COUNTER", "JSON_PIN", "JSON_TAGS"],
                         re.findall(r"jsonObj\.put\((JSON_\w+)", body))

        # A closed failure set, an owned one-use seed and redacted diagnostics. No generated
        # data-class members: they would add seed-based equality/copy/destructuring.
        self.assertIn("INVALID_TEXT, TEXT_TOO_LONG, MISSING_SECRET, INVALID_SECRET, INVALID_NUMBER,\n"
                      "    MISSING_COUNTER, INVALID_PARAMETERS, UNSUPPORTED_TYPE, INVALID_ALGORITHM,\n"
                      "    INVALID_TAGS, TOO_MANY_TAGS", contract)
        self.assertNotIn("data class ValidatedEntry", contract)
        self.assertNotIn("data class Entry", entry)
        self.assertIn('"ValidatedEntry(redacted)"', contract)
        self.assertIn('"EntryJsonResult.Success(redacted)"', contract)
        self.assertIn("private val seed: ByteArray =", contract)
        self.assertIn('check(!closed) { "Entry value already closed" }', contract)

        # Semantics a mechanical conversion could silently drop.
        self.assertIn("if (type == OTPType.HOTP || type == OTPType.TOTP || type == OTPType.STEAM) {", entry)
        self.assertIn("Objects.hash(type, period, counter, digits, algorithm, secret?.contentHashCode(), label, issuer)", entry)
        self.assertIn('secret ?: throw NullPointerException("Entry has no secret")', entry)
        self.assertIn("if (!json.has(JSON_SECRET)) reject(EntryJsonError.MISSING_SECRET)", codec)
        self.assertIn("InputPolicy.tokenParameters(period, digits, counter)", codec)
        self.assertIn("private fun thumbnailFromName(name: String?): EntryThumbnail.EntryThumbnails", entry)
        self.assertIn("fun updateOTP(updateNow: Boolean): Boolean", entry)
        self.assertIn('private const val MOTP_NO_PIN_CODE = "PINREQ"', entry)

    def test_converted_entry_keeps_the_whole_java_member_inventory(self):
        """The sandbox cannot reflect Entry's members (its members reference the Java
        EntryThumbnail), so the complete Java surface and the member policies it encoded are pinned
        here, at source level, member by member."""
        entry = (KOTLIN / "Database/Entry.kt").read_text()

        # Every public member the Java class exposed is still declared with the same JVM name.
        members = """validate destroy toJSON toUri isTimeBased isCounterBased getType setType getSecret
        getSecretEncoded setSecret getIssuer setIssuer getLabel setLabel getPeriod setPeriod getCounter
        setCounter getDigits setDigits getTags setTags getThumbnail setThumbnail getAlgorithm
        hasNonDefaultPeriod isVisible setVisible setHideTask getHideTask getLastUsed setLastUsed
        getUsedFrequency setUsedFrequency getCurrentOTP getPrevOTP getPin setPin getListId setListId
        updateOTP hasColorChanged equals hashCode setColor getColor validateSecret""".split()
        for member in members:
            self.assertRegex(entry, rf"\bfun {member}\s*\(", f"Entry.{member} disappeared")
        self.assertIn("fun toUri(): Uri?", entry)
        self.assertIn("fun getSecret(): ByteArray?", entry)
        self.assertIn("fun getSecretEncoded(): String {", entry)
        self.assertIn("if (type == OTPType.MOTP) String(seed) else String(Base32().encode(seed))", entry)
        self.assertIn("fun setSecret(secret: ByteArray?)", entry)
        self.assertIn("this.secret = secret?.clone()", entry)
        self.assertIn("fun getHideTask(): Runnable?", entry)
        self.assertIn("fun setHideTask(newTask: Runnable?)", entry)

        # `setIssuer` was the only Java setter that re-validated; the others must not start to.
        self.assertIn("fun setIssuer(issuer: String?, updateThumbnail: Boolean) {", entry)
        self.assertIn("if (updateThumbnail && issuer != null)", entry)
        for setter in ("setType", "setSecret", "setLabel", "setPeriod", "setCounter", "setDigits",
                       "setTags", "setThumbnail", "setVisible", "setHideTask", "setLastUsed",
                       "setUsedFrequency", "setPin", "setListId", "setColor"):
            body = re.search(rf"fun {setter}\([^)]*\)[^{{]*{{(.*?)\n    }}", entry, re.S)
            self.assertIsNotNone(body, f"missing body for {setter}")
            self.assertNotIn("validate()", body.group(1), f"{setter} must not validate, as Java did not")
        issuer = re.search(r"fun setIssuer\([^)]*\)[^{]*{(.*?)\n    }", entry, re.S)
        self.assertIn("validate()", issuer.group(1))

        # destroy() keeps the Java reset list: previously shown tokens and metadata are cleared.
        destroy = re.search(r"fun destroy\(\)[^{]*{(.*?)\n    }", entry, re.S).group(1)
        for reset in ("secret?.fill(0)", "secret = null", 'currentOTP = ""', 'prevOTP = ""', 'pin = ""',
                      'issuer = ""', 'label = ""', "tags = ArrayList()", "visible = false",
                      "hideTask = null"):
            self.assertIn(reset, destroy)

        # The URI writer keeps the Java parameter policy, including the HOTP fall-through that also
        # emits a non-default period, and the default-digest/default-digit omissions.
        to_uri = entry[entry.index("fun toUri()"):entry.index("fun isTimeBased()")]
        for written in ('builder.appendQueryParameter("secret", String(ownedSecret()))',
                        'builder.appendQueryParameter("secret", Base32().encodeAsString(ownedSecret()))',
                        'builder.appendQueryParameter("issuer", issuer)',
                        'builder.appendQueryParameter("counter", counter.toString())',
                        'builder.appendQueryParameter("period", period.toString())',
                        'builder.appendQueryParameter("digits", digits.toString())',
                        'builder.appendQueryParameter("algorithm", algorithm.name)',
                        'builder.appendQueryParameter("tags", tag)'):
            self.assertIn(written, to_uri)
        for guard_line in ("if (type == OTPType.HOTP || type == OTPType.TOTP || type == OTPType.STEAM) {",
                           "if (period != TokenCalculator.TOTP_DEFAULT_PERIOD)",
                           "if (digits != TokenCalculator.TOTP_DEFAULT_DIGITS)",
                           "if (algorithm != TokenCalculator.DEFAULT_ALGORITHM)"):
            self.assertIn(guard_line, to_uri)
        self.assertIn('.scheme("otpauth")', to_uri)
        self.assertIn(".appendPath(label)", to_uri)

        # updateOTP keeps the Java early return, its timeout guard and the mOTP PIN placeholder.
        update = entry[entry.index("fun updateOTP("):entry.index("fun hasColorChanged()")]
        self.assertIn("val seed = secret ?: return false", update)
        self.assertIn("if (updateNow || counter > last_update)", update)
        # The cached token is reused as the previous one only after a single-period advance without an edit.
        self.assertIn("val reusePrevious = !updateNow && counter == last_update + 1", update)
        self.assertIn("prevOTP = if (reusePrevious && !currentOTP.isNullOrEmpty()) currentOTP else \"\"", update)
        self.assertIn("prevOTP = \"\" // the placeholder is not a token", update)
        self.assertIn("currentOTP = MOTP_NO_PIN_CODE", update)
        self.assertIn("currentOTP = TokenCalculator.HOTP(seed, counter, digits, algorithm)", update)
        self.assertIn("last_update = counter", update)
        self.assertIn("setColor(COLOR_DEFAULT)", update)

    def test_converted_vault_repository_keeps_the_transaction_and_its_java_surface(self):
        """The vault file transaction runs on a device only: FileHelper/AtomicFile, SharedPreferences
        and EncryptionHelper have no class files in the sandbox, so its ordering, its fail-closed
        mappings and its Java-visible surface are pinned here (the outcome mapping itself is also
        executed in the sandbox check for the errors that need no I/O)."""
        helper = (KOTLIN / "Utilities/DatabaseHelper.kt").read_text()

        # A static-only Java class becomes an object with every former static kept callable.
        self.assertIn("object DatabaseHelper {", helper)
        self.assertIn("@JvmField\n    val DatabaseFileLock: Any = VaultSession.MutationLock", helper)
        self.assertIn("const val TRANSACTION_ID = \"vault_transaction_id\"", helper)
        self.assertIn("const val PENDING_FILE = \"secrets.pending\"", helper)
        self.assertEqual(14, helper.count("@JvmStatic\n    fun "),
                         "a former static member lost @JvmStatic")
        for member in ("recoverPendingDatabase", "readVault", "loadDatabaseResult", "loadDatabase",
                       "saveVault", "saveDatabase", "changeEncryption", "changeVaultEncryption",
                       "canInitialize", "backupDatabase", "restoreDatabaseBackup", "wipeDatabase",
                       "entriesToString", "stringToEntries"):
            self.assertRegex(helper, rf"@JvmStatic\n    fun {member}\(", f"{member} is no longer @JvmStatic")
        # Both metadata callbacks stay SAM interfaces for the Java lambdas that implement them.
        self.assertIn("fun interface SettingsCommit {", helper)
        self.assertIn("fun interface VaultSettingsCommit {", helper)
        # The non-public constructor is not widened into a Java-visible one, and both legacy
        # `status` members stay public final fields (Java reads `loadDatabaseResult(...).status`).
        self.assertIn("class VaultException private constructor(@JvmField val status: Status)", helper)
        self.assertIn("class LoadResult private constructor(private val result: VaultReadResult)", helper)
        self.assertIn("@JvmField\n        val status: Status = statusOf(", helper)
        # A null entry list stays a caller error reported as Rejected(INVALID_INPUT) after the read
        # (the Java reached entriesToString's null check), not a Kotlin parameter-check NPE.
        for signature in ("fun saveVault(context: Context, entries: ArrayList<Entry>?, key: SecretKey?)",
                          "fun saveDatabase(context: Context, entries: ArrayList<Entry>?, key: SecretKey?)",
                          "fun encryptedEntries(entries: ArrayList<Entry>?, key: SecretKey?)",
                          "fun writeEntries(context: Context, entries: ArrayList<Entry>?, key: SecretKey?)",
                          "fun entriesToString(entries: ArrayList<Entry>?)",
                          "fun stringToEntries(data: String?)"):
            self.assertIn(signature, helper)

        # Read outcomes keep their order of precedence: a missing file outranks a missing key, and
        # the authentication failure is caught before the broader IO/corrupt arms.
        read = helper[helper.index("fun readVault("):helper.index("fun loadDatabaseResult(")]
        self.assertLess(read.index("unavailable(VaultReadError.MISSING)"),
                        read.index("unavailable(VaultReadError.MISSING_KEY)"))
        self.assertLess(read.index("catch (error: BadPaddingException)"),
                        read.index("catch (error: IOException)"))
        self.assertLess(read.index("catch (error: IOException)"),
                        read.index("catch (error: Exception)"))
        self.assertIn("catch (error: Exception) {\n                return unavailable(VaultReadError.CORRUPT)",
                      read)
        # Decrypted plaintext is wiped on every path, and no diagnostic carries key or seed text.
        self.assertIn("if (plain != null) Arrays.fill(plain, 0.toByte())", read)
        for forbidden in ("\"Invalid entry JSON: \" + result", "Base32", "$key", "${'$'}plain"):
            self.assertNotIn(forbidden, helper)
        self.assertIn('IllegalStateException("Vault unavailable: $status")', helper)

        # Recovery is quiescent after an ambiguous commit and never promotes an uncommitted stage.
        self.assertIn("if (transactionPoisoned) return false", helper)
        self.assertIn("transactionPoisoned = false", helper)  # wipeDatabase clears it
        recover = helper[helper.index("fun recoverPendingDatabase("):helper.index("fun readVault(")]
        self.assertLess(recover.index("FileHelper.readFileToBytes(pending)"), recover.index("if (id == committed)"))
        self.assertLess(recover.index("if (id == committed)"), recover.index("FileHelper.delete(pending)"))
        # Wiping is an explicit, session-locking, serialized destructive operation.
        wipe = helper[helper.index("fun wipeDatabase("):helper.index("fun entriesToString(")]
        self.assertIn("VaultSession.lock(SessionLockReason.RESET)", wipe)
        self.assertLess(wipe.index("VaultSession.lock("), wipe.index("FileHelper.delete("))
        for name in ("Constants.FILENAME_DATABASE", "Constants.FILENAME_DATABASE_BACKUP", "PENDING_FILE"):
            self.assertIn(name, wipe)
        # Entry ownership is released on every failure path: the failed-backup branch, the
        # change-encryption finally block, and the all-or-nothing document parser.
        self.assertEqual(3, helper.count("for (entry in entries) entry.destroy()"),
                         "an entry cleanup path disappeared")
        self.assertIn("} finally {\n                for (entry in entries) entry.destroy()", helper)
        parse = helper[helper.index("fun stringToEntries("):]
        self.assertIn("for (entry in entries) entry.destroy()", parse)
        self.assertIn("throw VaultException.create(Status.CORRUPT)", parse)
        self.assertNotIn("!!", helper)

    def test_live_metadata_callbacks_cannot_recover_or_silently_lose_their_journal(self):
        helper = (KOTLIN / "Utilities/DatabaseHelper.kt").read_text()
        recover = helper[helper.index("private fun recoverPendingDatabaseLocked("):
                         helper.index("fun readVault(")]
        self.assertLess(recover.index("if (metadataCommitInProgress) return false"),
                        recover.index("FileHelper.exists(pending)"))
        self.assertIn("if (!FileHelper.exists(pending)) return expectedId == null", recover)
        self.assertIn("if (expectedId != null && (id != expectedId || id != committed)) return false", recover)
        commit = helper[helper.index("val decision = try {"):helper.index("val marker = try {")]
        self.assertLess(commit.index("metadataCommitInProgress = true"), commit.index("commit.commit(id)"))
        self.assertIn("finally {\n                    metadataCommitInProgress = false", commit)
        self.assertIn("recoverPendingDatabaseLocked(context, id)", helper)

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

    def test_converted_crypto_tap_and_dimension_helpers_keep_their_boundaries(self):
        wrapper = (KOTLIN / "Utilities/SecretKeyWrapper.kt").read_text()
        # Non-final class, both checked exceptions and the RSA transformation suppression are kept.
        self.assertIn("open class SecretKeyWrapper", wrapper)
        self.assertIn('@SuppressLint("GetInstance")', wrapper)
        self.assertIn("@Throws(GeneralSecurityException::class, IOException::class)", wrapper)
        self.assertIn("Cipher.getInstance(Constants.ALGORITHM_ASYMMETRIC)", wrapper)
        self.assertEqual(2, wrapper.count("@Throws(GeneralSecurityException::class)"),
                         "wrap()/unwrap() must keep their checked exception for Java callers")
        self.assertIn("cipher.init(Cipher.WRAP_MODE, pair.public)", wrapper)
        self.assertIn("cipher.init(Cipher.UNWRAP_MODE, pair.private)", wrapper)
        self.assertIn('cipher.unwrap(blob, "AES", Cipher.SECRET_KEY) as SecretKey', wrapper)
        for forbidden in ("data class", "Parcelable", "Serializable"):
            self.assertNotIn(forbidden, wrapper)

        listener = (KOTLIN / "View/SimpleDoubleClickListener.kt").read_text()
        # The Java class was abstract with a public no-argument constructor and two abstract callbacks.
        self.assertIn("abstract class SimpleDoubleClickListener : View.OnClickListener", listener)
        self.assertIn("override fun onClick(v: View)", listener)
        self.assertIn("abstract fun onSingleClick(v: View)", listener)
        self.assertIn("abstract fun onDoubleClick(v: View)", listener)
        self.assertIn("const val DOUBLE_CLICK_TIME_DELTA = 300L", listener)
        # The pending callback re-reads firstTap, so a second tap suppresses the single click.
        self.assertIn("v.postDelayed({ if (firstTap) onSingleClick(v) }, DOUBLE_CLICK_TIME_DELTA)", listener)
        self.assertNotIn("!!", listener)

        dimensions = (KOTLIN / "Utilities/DimensionConverter.kt").read_text()
        # Static-only utility: members stay Java-callable, the lookup table stays unmodifiable.
        for api in ("object DimensionConverter", "@JvmField", "val dimensionConstantLookup: Map<String, Int>",
                    "Collections.unmodifiableMap(m)", "@JvmStatic",
                    "fun stringToDimensionPixelSize(dimension: String, metrics: DisplayMetrics): Int",
                    "fun stringToDimension(dimension: String, metrics: DisplayMetrics): Float",
                    "throw NumberFormatException()"):
            self.assertIn(api, dimensions)
        # The default-locale lower-casing of the unit suffix is inherited from the Java implementation.
        self.assertIn("matcher.group(3).lowercase(Locale.getDefault())", dimensions)
        self.assertIn(r'Pattern.compile("^\\s*(\\d+(\\.\\d+)*)\\s*([a-zA-Z]+)\\s*$")', dimensions)

    def test_converted_drag_drop_dialog_and_result_helpers_keep_java_api_shapes(self):
        callback = (KOTLIN / "View/ItemTouchHelper/SimpleItemTouchHelperCallback.kt").read_text()
        # The Java class was non-final; drag/drop stays one reviewable boundary with its interfaces.
        self.assertIn("open class SimpleItemTouchHelperCallback(private val adapter: ItemTouchHelperAdapter) : ItemTouchHelper.Callback()", callback)
        self.assertIn("const val ALPHA_FULL = 1.0f", callback)
        self.assertIn("fun setDragEnabled(newDragState: Boolean)", callback)
        self.assertIn("adapter.onItemMove(source.adapterPosition, target.adapterPosition)", callback)
        self.assertIn("viewHolder is ItemTouchHelperViewHolder", callback)
        self.assertIn("return false", callback)  # cross-view-type moves are rejected
        self.assertNotIn("data class", callback)

        adapter = (KOTLIN / "View/ItemTouchHelper/ItemTouchHelperAdapter.kt").read_text()
        self.assertIn("interface ItemTouchHelperAdapter", adapter)
        self.assertIn("fun onItemMove(fromPosition: Int, toPosition: Int): Boolean", adapter)
        holder = (KOTLIN / "View/ItemTouchHelper/ItemTouchHelperViewHolder.kt").read_text()
        self.assertIn("interface ItemTouchHelperViewHolder", holder)
        self.assertIn("fun onItemSelected()", holder)
        self.assertIn("fun onItemClear()", holder)

        ui = (KOTLIN / "Utilities/UIHelper.kt").read_text()
        self.assertIn("object UIHelper", ui)
        self.assertIn("@JvmStatic", ui)
        self.assertIn("fun showGenericDialog(context: Context, titleId: Int, messageId: Int, onOk: Runnable?)", ui)
        self.assertIn("fun showKeyboard(context: Context, view: View?, showForced: Boolean?)", ui)
        # A null flag keeps failing like the Java unboxing did; it must not silently mean "not forced".
        self.assertIn('throw NullPointerException("Missing showForced flag")', ui)

        passwords = (KOTLIN / "Utilities/ConfirmedPasswordTransformationHelper.kt").read_text()
        self.assertIn("object ConfirmedPasswordTransformationHelper", passwords)
        self.assertIn("@JvmStatic", passwords)
        self.assertIn("fun setup(passwordLayout: TextInputLayout, passwordInput: TextInputEditText, passwordConfirmInput: EditText)", passwords)
        self.assertIn("passwordLayout.setEndIconOnClickListener {", passwords)
        self.assertIn("dispatchPasswordVisibilityChange(passwordConfirmInput, wasShowingPassword)", passwords)

        result = (KOTLIN / "Tasks/BackupTaskResult.kt").read_text()
        # Plain (non-data) class: payload can hold the whole parsed database and must not leak
        # through generated equals/toString/copy, and the Java class stayed extendable.
        self.assertIn("open class BackupTaskResult(type: ResultType, success: Boolean, payload: String?, messageId: Int)", result)
        self.assertNotIn("data class BackupTaskResult", result)
        for field in ("@JvmField val resultType: ResultType = type", "@JvmField val success: Boolean = success",
                      "@JvmField val payload: String? = payload", "@JvmField val messageId: Int = messageId",
                      "@JvmField var isPGP: Boolean = false", "@JvmField var decryptIntent: Intent? = null",
                      "@JvmField var uri: Uri? = null"):
            self.assertIn(field, result)
        self.assertIn("enum class ResultType", result)
        self.assertIn("@JvmStatic", result)
        self.assertIn("R.string.backup_toast_export_success", result)

    def test_converted_task_runner_keeps_the_java_surface_and_scheduling_contract(self):
        source = (KOTLIN / "Tasks/UiBasedBackgroundTask.kt").read_text()
        # Non-final generic abstract class with the same single failedResult constructor.
        self.assertIn("abstract class UiBasedBackgroundTask<Result>(private val failedResult: Result)", source)
        self.assertIn("protected final fun taskGeneration(): Long", source)
        self.assertIn("@Throws(Exception::class)\n    protected abstract fun doInBackground(): Result", source)
        self.assertIn("protected open fun onCancellation()", source)
        self.assertIn("protected open fun discardResult(result: Result)", source)
        # The delivery/cancellation contract and its legacy adapter keep their shapes.
        self.assertIn("fun setCallback(callback: UiCallback<Result>?)", source)
        self.assertIn("fun setOutcomeCallback(callback: OutcomeCallback<Result>?)", source)
        self.assertIn('throw IllegalStateException("Task already submitted")', source)
        self.assertIn("synchronized(VaultSession.MutationLock)", source)
        self.assertIn("VaultSession.addChangeListener(sessionChanged)", source)
        self.assertIn("VaultSession.removeChangeListener(sessionChanged)", source)
        self.assertIn("fun interface OutcomeCallback<Result>", source)
        self.assertIn("fun interface UiCallback<Result>", source)
        self.assertIn("mainThreadHandler.post { deliverPending() }", source)
        for forbidden in ("data class", "Parcelable", "Serializable"):
            self.assertNotIn(forbidden, source)
        # The Java isCanceled() was overridable (EncryptedBackupTask replaces it for broadcast
        # mode); a final declaration could not even compile against the subclass override.
        self.assertIn("open fun isCanceled(): Boolean", source)

    def test_converted_task_subclasses_keep_the_java_api_and_security_shapes(self):
        """Source guards for members whose signatures reference the Java-only resolution classes
        (Constants.BackupType, Settings.PreparedCredentials, EncryptionHelper callback): the
        sandbox reflects what it can, these pin the rest."""

        def read(name):
            return (KOTLIN / ("Tasks/" + name)).read_text()

        runner = read("UiBasedBackgroundTask.kt")
        self.assertIn("open fun isCanceled(): Boolean", runner)

        # Every Java class was non-final: the conversions stay open instead of silently final.
        for name in ("AuthenticationTask.kt", "GenericBackupTask.kt", "GenericRestoreTask.kt",
                     "PGPBackupTask.kt", "PGPRestoreTask.kt", "EncryptedBackupTask.kt",
                     "EncryptedRestoreTask.kt", "ChangeEncryptionTask.kt",
                     "ChangeCredentialsTask.kt", "FinishIntroTask.kt"):
            source = read(name)
            self.assertIn("open class " if "Generic" not in name else "abstract class ", source, name)
            self.assertNotIn("data class", source, name)
            self.assertNotIn("!!", source, name)
            self.assertNotIn("Parcelable", source, name)
            self.assertNotIn("Serializable", source, name)

        backup = read("GenericBackupTask.kt")
        # The abstract hooks and the overridable write policy keep their protected visibility.
        for api in ("protected val applicationContext: Context", "protected val settings: Settings",
                    "protected val type: Constants.BackupType = getBackupType()",
                    "protected var uri: Uri? = uri",
                    "protected override fun doInBackground(): BackupTaskResult",
                    "protected abstract fun getBackupType(): Constants.BackupType",
                    "protected abstract fun doBackup(): Boolean",
                    "protected open fun canWrite(): Boolean",
                    "VaultSession.getKey() != null"):
            self.assertIn(api, backup)
        # A failed backup deletes a file it created; the finally block survives the conversion.
        self.assertIn("if (!success && created != null)", backup)
        self.assertIn("try { created.delete() } catch (ignored: RuntimeException) { }", backup)
        self.assertIn("catch (error: RuntimeException)", backup)

        restore = read("GenericRestoreTask.kt")
        self.assertIn("protected abstract override fun doInBackground(): BackupTaskResult", restore)

        encrypted = read("EncryptedBackupTask.kt")
        # Both Java constructors, the broadcast/user cancellation split and the snapshot ownership.
        self.assertIn("constructor(context: Context, entries: ArrayList<Entry>, password: String?, uri: Uri?) :", encrypted)
        self.assertIn("constructor(context: Context, entries: ArrayList<Entry>, password: String?, uri: Uri?, mode: Mode,", encrypted)
        self.assertIn("enum class Mode { USER, AUTO_EDIT, BROADCAST }", encrypted)
        self.assertIn("this.entries = DatabaseHelper.stringToEntries(DatabaseHelper.entriesToString(entries))", encrypted)
        self.assertIn("override fun isCanceled(): Boolean", encrypted)
        self.assertIn("return if (mode == Mode.BROADCAST) wasCanceled() else super.isCanceled()", encrypted)
        self.assertIn("protected override fun onCancellation() { destroySnapshot() }", encrypted)
        self.assertIn("protected override fun discardResult(result: BackupTaskResult) { destroySnapshot() }", encrypted)
        self.assertIn("protected override fun doInBackground(): BackupTaskResult", encrypted)
        self.assertIn("try {\n            return super.doInBackground()\n        } finally {\n            destroySnapshot()\n        }", encrypted)
        # Key material and snapshot entries are destroyed exactly once per path.
        self.assertIn("private fun destroySnapshot()", encrypted)
        self.assertIn("synchronized(entries) {", encrypted)
        self.assertIn("for (entry in entries) entry.destroy()", encrypted)
        self.assertNotIn("settings.getBackupPasswordEnc() == null", encrypted)

        pgp_backup = read("PGPBackupTask.kt")
        self.assertIn("this::canWrite", pgp_backup)
        self.assertIn("Constants.BackupType.OPEN_PGP", pgp_backup)
        pgp_restore = read("PGPRestoreTask.kt")
        # The Java multi-catch (IOException | RuntimeException) stays two clauses, not one broadened catch.
        self.assertIn("catch (error: IOException)", pgp_restore)
        self.assertIn("catch (error: RuntimeException)", pgp_restore)
        self.assertIn("catch (error: Exception)", read("EncryptedRestoreTask.kt"))

        auth = read("AuthenticationTask.kt")
        # The legacy upgrade keeps its guard lambda; the confirmed path wipes both derived halves.
        self.assertIn("!isCanceled()", auth)
        self.assertIn("settings.getAuthCredentials().isEmpty()", auth)
        self.assertIn("existingAuthCredentials == settings.getOldCredentials(settings.getAuthMethod())", auth)
        self.assertIn("MessageDigest.isEqual(passwordArray, derived.password)", auth)
        self.assertIn("AuthenticationOutcome.verified(key, taskGeneration())", auth)
        self.assertIn("Arrays.fill(key, 0.toByte())", auth)
        for error in ("NoSuchAlgorithmException", "InvalidKeySpecException",
                      "IllegalArgumentException", "IllegalStateException"):
            self.assertIn(f"catch (error: {error})", auth)
        self.assertIn("AuthenticationOutcome.Rejected(AuthenticationError.INVALID_CONFIGURATION)", auth)

        change_encryption = read("ChangeEncryptionTask.kt")
        # The Java callback interface stays implemented with a public override (interface methods
        # are public; a protected override would not compile), and key material is wiped.
        self.assertIn("EncryptionChangeCallback", change_encryption)
        # Non-null since the fifteenth batch: the converted Kotlin interface never calls the
        # callback without a key, so the override no longer widens the parameter to SecretKey?.
        self.assertIn("override fun onSuccessfulEncryptionChange(newEncryptionType: Constants.EncryptionType, newEncryptionKey: SecretKey)", change_encryption)
        self.assertIn("newKey?.clone()", change_encryption)
        self.assertIn("newKeyMaterial?.fill(0.toByte())", change_encryption)
        self.assertIn("EncryptionHelper.tryEncryptionChange(context, oldEncryptionKey, newEncryptionType, newKeyMaterial, this) { !isCanceled() }", change_encryption)
        self.assertIn("open class Result(@JvmField val result: EncryptionChangeResult", change_encryption)
        self.assertIn("@JvmField val newEncryptionKey: SecretKey?", change_encryption)
        self.assertIn("@JvmField val encryptionType: Constants.EncryptionType?", change_encryption)

        change_credentials = read("ChangeCredentialsTask.kt")
        # Session authority stays reference identity (Java !=), not structural equality.
        self.assertIn("VaultSession.getKey() !== oldEncryptionKey", change_credentials)
        self.assertIn("synchronized(DatabaseHelper.DatabaseFileLock)", change_credentials)
        self.assertIn("if (isCanceled() || !VaultSession.isCurrent(epoch)) VaultCommitDecision.CANCELED", change_credentials)
        self.assertIn("VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, encryptionType, newAuthMethod, credentials, false))", change_credentials)
        self.assertIn("prepared?.destroy()", change_credentials)
        self.assertIn("write is VaultWriteResult.Saved", change_credentials)
        self.assertIn("open class Result(@JvmField val success: Boolean", change_credentials)

        finish = read("FinishIntroTask.kt")
        self.assertIn("DatabaseHelper.canInitialize(settingsContext)", finish)
        self.assertIn("KeyStoreHelper.createEncryptionKey(settingsContext)", finish)
        self.assertIn("VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, encryptionType, authMethod, credentials, true))", finish)
        self.assertIn("result.encryptionKey?.fill(0.toByte())", finish)
        self.assertIn("prepared?.destroy()", finish)
        # Both Java result constructors survive: (boolean, byte[]) and (boolean, byte[], long).
        self.assertIn("open class Result(saveSuccessful: Boolean, encryptionKey: ByteArray?, generation: Long)", finish)
        self.assertIn("this(saveSuccessful, encryptionKey, VaultSession.generation())", finish)
        for field in ("@JvmField val generation: Long = generation",
                      "@JvmField val saveSuccessful: Boolean = saveSuccessful",
                      "@JvmField val encryptionKey: ByteArray? = encryptionKey"):
            self.assertIn(field, finish)
