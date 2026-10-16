"""Source-level guards for the sixteenth batch (the settings facade).

`Utilities/Settings.kt` replaces the Java class of the same name. The sandbox *executes* the platform-
free half of it against an in-memory `SharedPreferences` (keys, defaults, string sets, the single
commit of the credential transaction, the audit of which keys were written, the broadcast-backup
throttle, the salt/verifier split and the boxed setter failure modes) and reads the compiled class
file for the whole Java-visible surface, because `Class.getDeclaredMethods()` resolves every declared
signature eagerly and the Java-only `Constants` enums have no class file in the sandbox. It cannot
execute `clear`, the credential staging (`prepareAuthCredentials` derives real PBKDF2 material but the
`setAuthCredentials` path needs the AndroidKeyStore and the vault file transaction) or the deprecated-
key migration (reached only through `DatabaseHelper.recoverPendingDatabase`), and it never renders a
theme. This guard pins the source-level expressions those checks cannot see, the resource references,
and the call sites the converted production and instrumentation callers keep.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
ANDROID_TEST = ROOT / "app/src/androidTest/kotlin/org/shadowice/flocke/andotp"
ANDROID_KOTLIN_TEST = ROOT / "app/src/androidTest/kotlin/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"
RES = ROOT / "app/src/main/res"


class KotlinSettingsConversionTest(unittest.TestCase):
    def read(self, stem):
        return (KOTLIN / stem).read_text()

    def settings(self):
        return self.read("Utilities/Settings.kt")

    @staticmethod
    def code(source):
        """The source without comment/KDoc lines, so counted expressions are code and not prose."""
        return "\n".join(line for line in source.splitlines()
                         if not line.strip().startswith(("*", "/*", "//")))

    def test_replacements_are_compiled_by_the_sandbox_and_no_longer_resolved_as_java(self):
        harness = (SANDBOX / "run.sh").read_text()
        self.assertFalse((JAVA / "Utilities/Settings.java").exists())
        self.assertTrue((KOTLIN / "Utilities/Settings.kt").is_file())
        self.assertIn("$KOTLIN_APP/Utilities/Settings.kt", harness)
        self.assertNotIn("Utilities/Settings.java", harness)
        # Constants and FileHelper are Kotlin since the twenty-second batch: compiled, not resolved.
        for compiled in ("Constants", "FileHelper"):
            self.assertIn(f"$KOTLIN_APP/Utilities/{compiled}.kt", harness)
            self.assertNotIn(f"Utilities/{compiled}.java", harness)
        # The credential preference is Kotlin since the twenty-first batch: compiled, not resolved.
        self.assertIn("$KOTLIN_APP/Preferences/CredentialsPreference.kt", harness)
        self.assertNotIn("Preferences/CredentialsPreference.java", harness)
        self.assertNotIn("JAVA_SOURCES", harness)
        checks = (SANDBOX / "Checks.kt").read_text()
        # The settings checks execute the converted class and read its compiled surface.
        for executed in ("SettingsFixture", "SettingsResources", "withPoisonedTransaction",
                         "settings.getAuthCredentials()", "settings.prepareAuthCredentials(",
                         "settings.setAuthCredentials(", "settings.tryAcquireBroadcastBackupSlot()",
                         "settings.getTagToggle(", "settings.setTagToggle(",
                         "settings.getAndroidBackupServiceEnabled()", "compiledClass(Settings::class.java)"):
            self.assertIn(executed, checks)
        for pinned in ('check("the compiled settings class keeps the Java-visible surface of Settings.java")',
                       '"setAllTagsToggle(Ljava/lang/Boolean;)V"',
                       '"setTagToggle(Ljava/lang/String;Ljava/lang/Boolean;)V"',
                       '"getBoolean(IZ)Z"', '"clear(Z)V"',
                       r'"commitVaultSettings(Ljava/lang/String;Lorg/shadowice/flocke/andotp/Utilities/Constants\$EncryptionType;'
                       r'Lorg/shadowice/flocke/andotp/Utilities/Constants\$AuthMethod;'
                       r'Lorg/shadowice/flocke/andotp/Utilities/Settings\$PreparedCredentials;Z)Z"',
                       r'"migrateDeprecatedSettings()V"',
                       r'"getAutoBackupEncryptedSetting()Lorg/shadowice/flocke/andotp/Utilities/Constants\$AutoBackup;"',
                       '"registerPreferenceChangeListener(Landroid/content/SharedPreferences\\$OnSharedPreferenceChangeListener;)V"'):
            self.assertIn(pinned, checks)

    def test_class_delta_keeps_the_java_visible_surface(self):
        source = self.settings()
        # One public constructor, an extendable class and a private collaborator pair — the
        # instrumentation test subclasses `Settings` and overrides a preference getter.
        self.assertIn("open class Settings(private val context: Context) {", source)
        self.assertNotIn("constructor(", source)
        self.assertIn("    private val settings: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)",
                      source)
        self.assertIn("        if (DatabaseHelper.recoverPendingDatabase(context)) migrateDeprecatedSettings()", source)
        # Every Java member was public and virtual; the Kotlin class keeps them all open. The count is
        # the reviewed Java surface (88 methods), asserted member by member by the class-file check.
        self.assertEqual(88, source.count("\n    open fun "), "the public method count changed")
        self.assertIn("    open fun getBoolean(keyId: Int, defaultValue: Boolean): Boolean {", source)
        self.assertIn("    open fun setBoolean(keyId: Int, value: Boolean) {", source)
        self.assertIn("    open fun getTagToggle(tag: String): Boolean {", source)
        # The three tag setters keep the boxed parameter and its unboxing failure mode.
        self.assertIn("    open fun setAllTagsToggle(value: Boolean?) {\n"
                      "        setBoolean(R.string.settings_key_all_tags_toggle, value!!)", source)
        self.assertIn("    open fun setNoTagsToggle(value: Boolean?) {\n"
                      "        setBoolean(R.string.settings_key_no_tags_toggle, value!!)", source)
        self.assertIn("    open fun setTagToggle(tag: String, value: Boolean?) {", source)
        self.assertIn("        if (value!!) toggledTags.remove(tag) else toggledTags.add(tag)", source)
        # The nullable platform accessors are normalized in exactly two private helpers, nowhere else.
        self.assertIn("        return settings.getString(getResString(keyId), defaultValue)!!", source)
        self.assertIn("        return settings.getStringSet(getResString(keyId), defaultValue)!!", source)
        self.assertEqual(5, self.code(source).count("!!"),
                         "the null-assertion sites must stay these five")
        # The nested holder keeps the JVM shape Java callers read fields from.
        self.assertIn("    class PreparedCredentials(", source)
        self.assertNotIn("inner class PreparedCredentials", source)
        for field in ("key: ByteArray", "verifier: String", "salt: String", "iterations: Int"):
            self.assertIn(f"        @JvmField val {field}", source, f"PreparedCredentials lost its {field} field")
        self.assertIn("    fun destroy() {\n            key.fill(0)", source)
        # Checked exceptions stay declared for Java callers.
        self.assertIn("    @Throws(NoSuchAlgorithmException::class, InvalidKeySpecException::class)", source)
        self.assertIn("    open fun prepareAuthCredentials(password: String?): PreparedCredentials {", source)
        # The companion holds the two private migration lists, as the Java statics did.
        self.assertIn("    private val newLangs = listOf(", source)
        self.assertIn("    private val oldLangs = listOf(", source)

    def test_every_settings_resource_reference_exists_and_the_fixture_matches_the_xml_defaults(self):
        source = self.settings()
        references = re.findall(r"R\.(\w+)\.(\w+)", source)
        self.assertGreater(len(references), 50, "the resource references disappeared")
        settings_xml = (RES / "values" / "settings.xml").read_text()
        declared = set(re.findall(r'<(?:string|integer|string-array) name="(\w+)"', settings_xml))
        styles = "".join(p.read_text() for p in RES.glob("values*/styles.xml"))
        dimens = "".join(p.read_text() for p in RES.glob("values*/dimens.xml"))
        for kind, name in references:
            if kind in ("string", "integer", "array"):
                self.assertIn(name, declared, f"R.{kind}.{name} is not declared in settings.xml")
            elif kind == "style":
                # aapt turns the dots of a style name into underscores in the R field.
                self.assertIn(f'name="{name.replace("_", ".")}"', styles, f"R.style.{name} is not declared")
            elif kind == "dimen":
                self.assertIn(f'name="{name}"', dimens, f"R.dimen.{name} is not declared")
            elif kind == "xml":
                self.assertTrue((RES / "xml" / f"{name}.xml").is_file(), f"R.xml.{name} is missing")
            else:
                self.fail(f"unexpected resource type R.{kind}.{name}")

        # The sandbox fixture serves the *default* values the code parses; it is checked against the
        # XML here, so an edited default cannot silently diverge from what a fresh install reads.
        checks = (SANDBOX / "Checks.kt").read_text()
        fixture = dict(re.findall(r'R\.string\.(\w+) -> "([^"]*)"', checks))
        self.assertIn("settings_default_encryption", fixture)
        xml_string_defaults = dict(re.findall(r'<string name="(\w+)"[^>]*>([^<]*)</string>', settings_xml))
        for name, value in fixture.items():
            self.assertIn(name, xml_string_defaults, f"{name} is not a string resource")
            self.assertEqual(xml_string_defaults[name], value, f"the fixture default for {name} drifted")
        self.assertIn("settings_default_split_group_size", fixture)
        self.assertEqual("3", fixture["settings_default_split_group_size"])
        # The two integer defaults and the search-include array are read through Resources, not strings.
        fixture_ints = dict(re.findall(r"R\.integer\.(\w+) -> (\d+)", checks))
        xml_ints = dict(re.findall(r'<integer name="(\w+)">(\d+)</integer>', settings_xml))
        self.assertEqual({"settings_default_tap_to_reveal_timeout": "30", "settings_default_label_size": "18"},
                         fixture_ints)
        for name, value in fixture_ints.items():
            self.assertEqual(xml_ints.get(name), value, f"the fixture default for {name} drifted")
        array_body = re.search(r"if \(id == R\.array\.settings_defaults_search_includes\) arrayOf\(([^)]*)\)",
                               checks)
        self.assertIsNotNone(array_body, "the search-include default array disappeared from the fixture")
        fixture_items = re.findall(r'"(\w+)"', array_body.group(1))
        array_block = re.search(r'<string-array name="settings_defaults_search_includes">(.*?)</string-array>',
                                settings_xml, re.S)
        self.assertIsNotNone(array_block, "settings_defaults_search_includes is not declared in settings.xml")
        self.assertEqual(sorted(fixture_items), sorted(re.findall(r"<item>(\w+)</item>", array_block.group(1))))

    def test_credential_derivation_and_the_staged_commit_keep_their_order(self):
        source = self.settings()
        # Derivation: bounds first, then salt/iterations/derivation, and the verifier half is wiped.
        self.assertIn('if (password == null || password.isEmpty() || password.length > InputPolicy.MAX_TEXT_CHARS) '
                      'throw IllegalArgumentException("Invalid password length")', source)
        self.assertIn("        val salt = EncryptionHelper.generateRandom(Constants.PBKDF2_SALT_LENGTH)", source)
        self.assertIn("        val iterations = EncryptionHelper.benchmarkIterations(password, salt)", source)
        self.assertIn("        val derived = EncryptionHelper.generatePBKDF2Credentials(password, salt, iterations)", source)
        self.assertIn("            derived.password.fill(0)", source)
        self.assertIn("                Base64.encodeToString(derived.password, Base64.URL_SAFE),", source)
        self.assertIn("                Base64.encodeToString(salt, Base64.URL_SAFE),", source)
        # Staging: consent twice around the vault read, keystore-only, destroy in the finally branch.
        self.assertIn("                if (!permitted.asBoolean || getEncryption() != Constants.EncryptionType.KEYSTORE) "
                      "return null", source)
        self.assertIn("                val key: SecretKey? = KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, true)",
                      source)
        self.assertIn("                val loaded = DatabaseHelper.readVault(context, key)", source)
        self.assertIn("                if (loaded !is VaultReadResult.Loaded) return null", source)
        self.assertIn("                loaded.close()", source)
        self.assertIn("                if (!permitted.asBoolean) return null", source)
        self.assertIn("                if (commitVaultSettings(null, getEncryption(), null, prepared, false)) "
                      "return prepared.key.clone()", source)
        self.assertIn("            credentials?.destroy()", source)
        self.assertIn("        return setAuthCredentials(plainPassword, BooleanSupplier { true })", source)
        # One synchronous commit, transaction id included, and the platform backup service stays off.
        self.assertIn("        transaction: String?, type: Constants.EncryptionType, auth: Constants.AuthMethod?,",
                      source)
        self.assertIn("        if (transaction != null) editor.putString(DatabaseHelper.TRANSACTION_ID, transaction)",
                      source)
        self.assertIn("        editor.putBoolean(getResString(R.string.settings_key_enable_android_backup_service), false)\n"
                      "        return editor.commit()", source)
        self.assertEqual(3, source.count("synchronized(DatabaseHelper.DatabaseFileLock)"),
                         "the vault lock must guard clear, staging and the backup slot")
        # The salt is Base64 URL_SAFE in both directions and a missing one is an error, not a default.
        self.assertIn("        val encodedSalt = Base64.encodeToString(bytes, Base64.URL_SAFE)", source)
        self.assertIn('        if (storedSalt.isEmpty()) throw IllegalStateException("Missing authentication salt")', source)
        self.assertIn("        return Base64.decode(storedSalt, Base64.URL_SAFE)", source)
        self.assertIn("        return getIntValue(R.string.settings_key_auth_iterations, Constants.PBKDF2_MIN_AUTH_ITERATIONS)",
                      source)

    def test_automatic_backup_and_platform_backup_policy_is_unchanged(self):
        source = self.settings()
        # `clear` keeps the auth half and the single commit, then re-applies the XML defaults.
        self.assertIn("    open fun clear(keep_auth: Boolean) {", source)
        self.assertIn("            if (!DatabaseHelper.recoverPendingDatabase(context)) return", source)
        self.assertIn("            if (!editor.commit()) return", source)
        self.assertIn("            PreferenceManager.setDefaultValues(context, R.xml.preferences, true)", source)
        self.assertIn("editor.putString(getResString(R.string.settings_key_encryption), "
                      "encryptionType.name.lowercase(Locale.ROOT))", source)
        # The platform backup service can never be read back as enabled, and the setter ignores its
        # argument exactly like the Java method did.
        self.assertIn("    open fun getAndroidBackupServiceEnabled(): Boolean {\n        return false\n    }", source)
        self.assertIn("        setBoolean(R.string.settings_key_enable_android_backup_service, false)", source)
        self.assertIn("        return getBackupBroadcasts().contains(\"encrypted\")", source)
        self.assertIn("        settings.edit().putStringSet(getResString(R.string.settings_key_backup_broadcasts), "
                      "Collections.emptySet())", source)
        self.assertIn('            .putString(getResString(R.string.settings_key_auto_backup_password_enc), "off").commit()',
                      source)
        # The broadcast-backup throttle keeps the elapsed-realtime window and the single commit.
        self.assertIn("            val now = SystemClock.elapsedRealtime()", source)
        self.assertIn('            val last = settings.getLong("last_broadcast_backup_elapsed", -60000)', source)
        self.assertIn("            if (now >= last && now - last < 60000) return false", source)
        self.assertIn('            return settings.edit().putLong("last_broadcast_backup_elapsed", now).commit()', source)
        # The auto-backup mode falls back to ENCRYPTED for a value that is no longer a valid type.
        self.assertIn("        } catch (e: IllegalArgumentException) {", source)
        self.assertIn("            Constants.BackupType.ENCRYPTED", source)
        self.assertIn("        return getAutoBackupEncryptedSetting() != Constants.AutoBackup.OFF", source)
        # The deprecated-key migration runs only at construction and through the vault recovery guard.
        self.assertIn('        if (settings.contains("perf_theme_mode")) {', source)
        self.assertIn('.putString(getResString(R.string.settings_key_theme_mode), '
                      'settings.getString("perf_theme_mode", ""))', source)
        self.assertIn('.remove("perf_theme_mode")', source)
        self.assertIn("            if (oldLangs.contains(lang)) setLocale(newLangs[oldLangs.indexOf(lang)])", source)
        self.assertIn("                setString(R.string.settings_key_tap_single, "
                      "Constants.TapMode.REVEAL.toString().lowercase(Locale.ENGLISH))", source)
        self.assertIn("                setString(R.string.settings_key_label_display, "
                      "Constants.LabelDisplay.SCROLL.toString().lowercase(Locale.ENGLISH))", source)
        self.assertIn("            if (setAuthCredentials(getString(R.string.settings_key_auth_password, \"\")) != null)",
                      source)

    def test_locale_handling_matches_the_java_call_sites(self):
        source = self.settings()
        # Kotlin's no-argument lowercase()/uppercase() are locale-invariant, so every Java
        # default-locale call site passes the default locale explicitly.
        self.assertNotIn(".lowercase()", source)
        self.assertNotIn(".uppercase()", source)
        for explicit in ("return Constants.EncryptionType.valueOf(encType.uppercase(Locale.getDefault()))",
                         "setEncryption(encryptionType.name.lowercase(Locale.getDefault()))",
                         "return Constants.TagFunctionality.valueOf(tagFunctionality.uppercase(Locale.getDefault()))",
                         "editor.putString(getResString(R.string.settings_key_auth), "
                         "authMethod.toString().lowercase(Locale.getDefault()))"):
            self.assertIn(explicit, source)
        # Locale.ROOT stays exactly where the Java class used it (three sites) and nothing else moved.
        code = self.code(source)
        self.assertEqual(3, code.count("Locale.ROOT"))
        for root in ("editor.putString(getResString(R.string.settings_key_encryption), encryptionType.name.lowercase(Locale.ROOT))",
                     "editor.putString(getResString(R.string.settings_key_encryption), type.name.lowercase(Locale.ROOT))",
                 "editor.putString(getResString(R.string.settings_key_auth), auth.name.lowercase(Locale.ROOT))"):
            self.assertIn(root, code)
        self.assertEqual(13, code.count("Locale.ENGLISH"))
        self.assertEqual(4, code.count("Locale.getDefault()"))

    def test_no_kotlin_caller_uses_java_bean_property_syntax_on_the_converted_class(self):
        # A Kotlin class does not synthesize properties for its accessors, so `settings.blockAccessibility`
        # stops compiling the moment `Settings` becomes Kotlin. The converted callers use method calls.
        getters = re.findall(r"open fun (get\w+|is\w+)\(", self.code(self.settings()))
        self.assertGreaterEqual(len(getters), 60, "the accessor list disappeared")
        properties = [re.sub(r"^(get|is)", "", name) for name in getters]
        properties = [p[0].lower() + p[1:] for p in properties]
        offenders = []
        for path in sorted(KOTLIN.rglob("*.kt")):
            if path.name == "Settings.kt":
                continue
            text = path.read_text()
            for prop in properties:
                for match in re.finditer(r"\bsettings\." + re.escape(prop) + r"\b(?!\s*\()", text):
                    offenders.append(f"{path.relative_to(KOTLIN)}:{text[:match.start()].count(chr(10)) + 1}")
        self.assertEqual([], offenders, "Java-bean property syntax on a Kotlin class does not compile")

    def test_java_and_instrumentation_call_sites_still_match(self):
        # The Android suite reaches the members the sandbox cannot load (enum-typed signatures).
        interop = (ANDROID_TEST / "View" / "KotlinSmallAdaptersInteropTest.kt").read_text()
        self.assertIn("val settings = object : Settings(context()) {", interop)
        self.assertIn("override fun getTagToggle(tag: String): Boolean", interop)
        self.assertIn("Settings(context).getThumbnailSize()", interop)
        for suite in ("Tasks/CredentialLifecycleSecurityTest.kt", "Utilities/IpcAuthorizationSecurityTest.kt",
                      "ApplicationTest.kt"):
            text = (ANDROID_KOTLIN_TEST / suite).read_text()
            normalized = re.sub(r"\s+", " ", text)
            self.assertIn("Settings", text, f"{suite} lost its Settings call site")
            self.assertIn("commitVaultSettings( id, Constants.EncryptionType.", normalized,
                          f"{suite} no longer stages the transaction through the facade")
        credential = (ANDROID_KOTLIN_TEST / "Tasks" / "CredentialLifecycleSecurityTest.kt").read_text()
        normalized_credential = re.sub(r"\s+", " ", credential)
        self.assertIn("settings.commitVaultSettings( id, Constants.EncryptionType.PASSWORD, "
                      "Constants.AuthMethod.PASSWORD, null, true )", normalized_credential)
        self.assertIn("settings.getAuthCredentials()", credential)
        # The converted preference now calls the accessors as methods, because a Kotlin class does not
        # synthesize Java-bean properties for them.
        preference = self.read("Preferences/PasswordEncryptedPreference.kt")
        self.assertIn("if (settings.getBlockAccessibility()) {", preference)
        self.assertIn("if (settings.getBlockAutofill()) {", preference)
        self.assertNotIn("settings.blockAccessibility", preference)
        # The instrumentation callers keep constructing the class with its single Context
        # parameter, and the app sources do the same in Kotlin since the twenty-first batch.
        sites = sum(text.read_text().count("new Settings(")
                    for text in list(JAVA.rglob("*.java")) + list(ANDROID_TEST.rglob("*.java")))
        for source in KOTLIN.rglob("*.kt"):
            sites += len(re.findall(r"(?<![.\w])Settings\(", source.read_text()))
        self.assertGreaterEqual(sites, 26, "the construction sites disappeared")


if __name__ == "__main__":
    unittest.main()
