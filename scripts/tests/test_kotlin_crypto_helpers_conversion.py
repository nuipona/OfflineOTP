"""Source-level guards for the fifteenth batch (crypto and Android KeyStore helpers).

The sandbox executes the platform-free parts of the converted `EncryptionHelper` — the PBKDF2 split
against an independent derivation and the RFC 6070 vector, the credential bounds, the legacy
SHA-256 key, the RSA helpers with a real JVM key pair and the pre-provider AES-GCM framing guard —
and reflects the whole `KeyStoreHelper` surface. It cannot run the AES-GCM provider calls (the
desktop JDK requires `GCMParameterSpec` while the unchanged Android path passes `IvParameterSpec`),
cannot reflect `EncryptionHelper` (resolving its declared methods eagerly loads the Java-only
`Constants.EncryptionType`) and has no AndroidKeyStore. This guard pins the Java-visible surface and
the security-relevant expressions those checks cannot see; real AndroidKeyStore, AES-GCM and
wrapped-key behaviour needs a device.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
ANDROID_TEST = ROOT / "app/src/androidTest/java/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


class KotlinCryptoHelpersConversionTest(unittest.TestCase):
    def read(self, stem):
        return (KOTLIN / stem).read_text()

    def test_replacements_are_compiled_by_the_sandbox_and_no_longer_resolved_as_java(self):
        harness = (SANDBOX / "run.sh").read_text()
        for stem in ("Utilities/EncryptionHelper", "Utilities/KeyStoreHelper"):
            self.assertFalse((JAVA / f"{stem}.java").exists())
            self.assertTrue((KOTLIN / f"{stem}.kt").is_file())
            self.assertIn(f"$KOTLIN_APP/{stem}.kt", harness)
            self.assertNotIn(f"{stem}.java", harness)
        # The resolution sources left are the ones the compiled sources still read.
        for resolved in ("Constants", "FileHelper"):
            self.assertIn(f"$ROOT/app/src/main/java/org/shadowice/flocke/andotp/Utilities/{resolved}.java", harness)
        self.assertIn("$ROOT/app/src/main/java/org/shadowice/flocke/andotp/Preferences/CredentialsPreference.java",
                      harness)
        checks = (SANDBOX / "Checks.kt").read_text()
        for executed in ("EncryptionHelper.generatePBKDF2Credentials(password, salt, 4096)",
                         "EncryptionHelper.generateSymmetricKeyFromPassword(",
                         "EncryptionHelper.encrypt(pair.public, plain)",
                         "EncryptionHelper.decrypt(pair.private, cipherText)",
                         "EncryptionHelper.generateRandomIterations()",
                         "EncryptionHelper.decrypt(key, ByteArray(length))",
                         "KeyStoreHelper::class.java"):
            self.assertIn(executed, checks)

    def test_object_delta_keeps_the_static_surface_and_nested_types(self):
        helper = self.read("Utilities/EncryptionHelper.kt")
        self.assertIn("object EncryptionHelper {", helper)
        # Every Java-visible member is one @JvmStatic forwarder (Java keeps writing EncryptionHelper.foo()).
        self.assertEqual(16, helper.count("\n    @JvmStatic\n"), "the static forwarder count changed")
        for method in (
                "fun tryEncryptionChange(context: Context, oldKey: SecretKey?, newType: Constants.EncryptionType, newKey: ByteArray?,",
                "fun benchmarkIterations(password: String, salt: ByteArray): Int {",
                "fun generateRandomIterations(): Int {",
                "fun generateRandom(length: Int): ByteArray {",
                "fun generatePBKDF2Credentials(password: String?, salt: ByteArray?, iter: Int): PBKDF2Credentials {",
                "fun generateSymmetricKey(data: ByteArray): SecretKey =",
                "fun generateSymmetricKeyPBKDF2(password: String?, iter: Int, salt: ByteArray?): SecretKey {",
                "fun generateSymmetricKeyFromPassword(password: String): SecretKey {",
                "fun encrypt(secretKey: SecretKey, iv: IvParameterSpec, plainText: ByteArray): ByteArray {",
                "fun encrypt(secretKey: SecretKey, plaintext: ByteArray): ByteArray {",
                "fun encrypt(publicKey: PublicKey, plaintext: ByteArray): ByteArray {",
                "fun decrypt(secretKey: SecretKey, iv: IvParameterSpec, cipherText: ByteArray): ByteArray {",
                "fun decrypt(secretKey: SecretKey, cipherText: ByteArray?): ByteArray {",
                "fun decrypt(privateKey: PrivateKey, cipherText: ByteArray): ByteArray {",
                "fun loadOrGenerateWrappedKey(keyFile: File, keyPair: KeyPair): SecretKey {"):
            self.assertIn(method, helper)
        # The nested types keep their JVM names: the result holder, the error enum and the SAM callback.
        self.assertIn("open class PBKDF2Credentials {", helper)
        self.assertIn("@JvmField\n        var password: ByteArray = ByteArray(0)", helper)
        self.assertIn("@JvmField\n        var key: ByteArray = ByteArray(0)", helper)
        self.assertIn("enum class EncryptionChangeResult {", helper)
        self.assertIn("SUCCESS, CHANGE_FAILURE, BACKUP_FAILURE, MISSING_NEW_KEY, DECRYPTION_FAILED, TASK_CREATION_FAILED", helper)
        self.assertIn("fun interface EncryptionChangeCallback {", helper)
        self.assertIn("fun onSuccessfulEncryptionChange(newEncryptionType: Constants.EncryptionType, newEncryptionKey: SecretKey)",
                      helper)
        self.assertNotIn("newEncryptionKey: SecretKey?", helper)

    def test_checked_exception_surface_survives_for_java_callers(self):
        helper = self.read("Utilities/EncryptionHelper.kt")
        for annotation in (
                "@Throws(NoSuchAlgorithmException::class, InvalidKeySpecException::class)",
                "@Throws(NoSuchAlgorithmException::class)",
                "@Throws(NoSuchPaddingException::class, NoSuchAlgorithmException::class, InvalidAlgorithmParameterException::class,",
                "@Throws(NoSuchPaddingException::class, BadPaddingException::class, InvalidKeyException::class,",
                "@Throws(NoSuchPaddingException::class, BadPaddingException::class, InvalidKeyException::class,\n            NoSuchAlgorithmException::class, IllegalBlockSizeException::class)",
                "@Throws(NoSuchPaddingException::class, InvalidKeyException::class, NoSuchAlgorithmException::class,",
                "@Throws(GeneralSecurityException::class, IOException::class)"):
            self.assertIn(annotation, helper)
        # The two credential derivations, the legacy key, the explicit-nonce pair, both framed overloads,
        # both RSA overloads and the wrapped-key loader all keep their declared checked exceptions.
        self.assertEqual(10, helper.count("@Throws("), "the checked-exception surface changed")
        key_store = self.read("Utilities/KeyStoreHelper.kt")
        self.assertEqual(2, key_store.count("@Throws(GeneralSecurityException::class, IOException::class)"))
        self.assertEqual(2, key_store.count("@Throws("))

    def test_staged_re_encryption_keeps_its_guard_order(self):
        helper = self.read("Utilities/EncryptionHelper.kt")
        order = ["if (!permitted.getAsBoolean() || oldKey == null || VaultSession.getKey() !== oldKey)",
                 "val loaded = DatabaseHelper.readVault(context, oldKey)",
                 "if (loaded !is VaultReadResult.Loaded) return EncryptionChangeResult.DECRYPTION_FAILED",
                 "loaded.close()",
                 "newType == Constants.EncryptionType.KEYSTORE -> KeyStoreHelper.createEncryptionKey(context)",
                 "(newKey.size == 16 || newKey.size == 24 || newKey.size == 32) -> generateSymmetricKey(newKey)",
                 "if (key == null) return EncryptionChangeResult.MISSING_NEW_KEY",
                 "val generation = VaultSession.generation()",
                 "if (!permitted.getAsBoolean() || !VaultSession.isCurrent(generation)) VaultCommitDecision.CANCELED",
                 "VaultCommitDecision.fromCommit(settings.commitVaultSettings(id, newType, null, null, false))",
                 "if (write !is VaultWriteResult.Saved) return EncryptionChangeResult.CHANGE_FAILURE",
                 "callback?.onSuccessfulEncryptionChange(newType, key)"]
        positions = [helper.index(marker) for marker in order]
        self.assertEqual(sorted(positions), positions, "the staged re-encryption order changed")
        self.assertIn("synchronized(DatabaseHelper.DatabaseFileLock) {", helper)
        self.assertIn("else -> return EncryptionChangeResult.MISSING_NEW_KEY", helper)
        # The five-argument overload still delegates with a permissive gate, and both overloads stay
        # callable as statics from Java (they resolve to the same forwarder name).
        self.assertIn("tryEncryptionChange(context, oldKey, newType, newKey, callback, BooleanSupplier { true })", helper)
        self.assertIn("fun tryEncryptionChange(context: Context, oldKey: SecretKey?, newType: Constants.EncryptionType, newKey: ByteArray?,\n"
                      "                            callback: EncryptionChangeCallback?, permitted: BooleanSupplier): EncryptionChangeResult {",
                      helper)
        self.assertEqual(2, helper.count("fun tryEncryptionChange("), "the overload count changed")

    def test_derivation_bounds_split_and_framing_stay_exact(self):
        helper = self.read("Utilities/EncryptionHelper.kt")
        # Both derivation entry points keep the same Java bounds, in the same order.
        bound = "password == null || password.length > InputPolicy.MAX_TEXT_CHARS || salt == null\n            || salt.size < 8 || iter < 1 || iter > 20000000"
        self.assertEqual(2, helper.count(bound), "a derivation bound changed")
        self.assertEqual(2, helper.count('throw IllegalArgumentException("Invalid credential parameters")'))
        # The derived output is split into the auth password (upper half) and the key (lower half).
        self.assertIn("val halfPoint = array.size / 2", helper)
        self.assertIn("credentials.password = Arrays.copyOfRange(array, halfPoint, array.size)", helper)
        self.assertIn("credentials.key = Arrays.copyOfRange(array, 0, halfPoint)", helper)
        self.assertIn("Arrays.fill(array, 0.toByte())", helper)
        self.assertIn("Arrays.fill(passwordChars, '\\u0000')", helper)
        self.assertLess(helper.index("credentials.password = Arrays.copyOfRange"),
                        helper.index("Arrays.fill(array, 0.toByte())"))
        # The framed reader keeps its pre-provider framing guard and throws the Java exception type.
        self.assertIn("if (cipherText == null || cipherText.size < Constants.ENCRYPTION_IV_LENGTH + 16) {", helper)
        self.assertIn('throw IllegalArgumentException("Truncated AES-GCM data")', helper)
        # The nonce is generated per call and prefixed exactly once.
        self.assertIn("SecureRandom().nextBytes(iv)", helper)
        self.assertIn("System.arraycopy(iv, 0, combined, 0, iv.size)", helper)
        self.assertIn("System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)", helper)
        # The wrapped-key loader always reads the stored blob back, even right after generating it.
        self.assertIn("Even if we just generated the key, always read it back to ensure we", helper)
        self.assertIn("return wrapper.unwrap(wrapped)", helper)
        # ... and it still mints and stores a key when the file is missing: the sandbox cannot run
        # this path (FileHelper/AtomicFile are Java), so a dropped generation or write would
        # otherwise pass unnoticed.
        self.assertIn("if (!keyFile.exists()) {", helper)
        self.assertIn("val raw = generateRandom(Constants.ENCRYPTION_KEY_LENGTH)", helper)
        self.assertIn('val key = SecretKeySpec(raw, "AES")', helper)
        self.assertIn("FileHelper.writeBytesToFile(keyFile, wrapped)", helper)
        self.assertNotIn("if (false) {", helper)

    def test_random_backup_iterations_keep_the_offset_the_sandbox_check_caught(self):
        helper = self.read("Utilities/EncryptionHelper.kt")
        # A line break before the leading '+' dropped this addition once (Kotlin ended the statement
        # after nextInt, so raw 0..20000 values were returned). Pin the completed expression.
        self.assertIn("return rand.nextInt((Constants.PBKDF2_MAX_BACKUP_ITERATIONS - Constants.PBKDF2_MIN_BACKUP_ITERATIONS) + 1) +\n"
                      "                Constants.PBKDF2_MIN_BACKUP_ITERATIONS", helper)
        self.assertIn("val rand = Random()", helper)
        self.assertIn("Math.min(2000000, Math.max(scaledIterationTarget, Constants.PBKDF2_MIN_AUTH_ITERATIONS))", helper)

    def test_keystore_helper_keeps_alias_policy_cast_and_nullable_lookups(self):
        key_store = self.read("Utilities/KeyStoreHelper.kt")
        self.assertIn("object KeyStoreHelper {", key_store)
        self.assertEqual(5, key_store.count("\n    @JvmStatic\n"))
        for method in ("fun wipeKeys(context: Context) {",
                       "fun loadAsymmetricKeyPair(context: Context, alias: String): KeyPair? {",
                       "fun loadOrGenerateAsymmetricKeyPair(context: Context, alias: String): KeyPair? {",
                       "fun loadEncryptionKeyFromKeyStore(context: Context, failSilent: Boolean): SecretKey? {",
                       "fun createEncryptionKey(context: Context): SecretKey? {"):
            self.assertIn(method, key_store)
        # Every lookup is explicitly nullable, because every Java failure path returned null.
        self.assertEqual(2, key_store.count(": KeyPair? {"), "the two lookups must stay nullable")
        self.assertEqual(2, key_store.count(": SecretKey? {"), "both key paths must stay nullable")
        # The destructive path takes the session lock and the shared file lock, exactly as before.
        self.assertIn("VaultSession.lock(SessionLockReason.RESET)", key_store)
        self.assertIn("synchronized(DatabaseHelper.DatabaseFileLock) {", key_store)
        self.assertEqual(3, key_store.count("synchronized(DatabaseHelper.DatabaseFileLock) {"),
                         "wipe, read and create must stay serialized on the vault file lock")
        self.assertIn("arrayOf(Constants.KEYSTORE_ALIAS_WRAPPING, Constants.KEYSTORE_ALIAS_PASSWORD)", key_store)
        self.assertIn("if (store.containsAlias(alias)) store.deleteEntry(alias)", key_store)
        # The generated RSA pair keeps its alias-bound subject, padding and validity window.
        self.assertIn("KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)",
                      key_store)
        self.assertIn('X500Principal("CN=$alias")', key_store)
        self.assertIn("setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)", key_store)
        self.assertIn("setCertificateSerialNumber(BigInteger.ONE)", key_store)
        self.assertIn("end.add(Calendar.YEAR, 100)", key_store)
        self.assertIn('KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")', key_store)
        # A wrong entry type still fails like the Java cast did (ClassCastException), not silently null.
        self.assertIn("as KeyStore.PrivateKeyEntry?", key_store)
        self.assertEqual(2, key_store.count("as KeyStore.PrivateKeyEntry?"))
        # Missing aliases/files never mint replacements; only createEncryptionKey loads or generates.
        self.assertIn("if (!FileHelper.exists(wrapped)) return null", key_store)
        self.assertIn("loadAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_WRAPPING) ?: return null", key_store)
        self.assertIn("if (FileHelper.exists(wrapped)) return loadEncryptionKeyFromKeyStore(context, true)", key_store)
        self.assertIn("EncryptionHelper.loadOrGenerateWrappedKey(wrapped, pair)", key_store)
        self.assertIn("return SecretKeyWrapper(pair).unwrap(FileHelper.readFileToBytes(wrapped))", key_store)
        # The unused Java parameter is retained for call-site compatibility.
        self.assertIn("failSilent: Boolean", key_store)

    def test_java_and_kotlin_callers_keep_their_call_sites(self):
        # `Settings` is Kotlin since the sixteenth batch; its call sites into the crypto helpers moved
        # to the Kotlin declarations without changing which helper is called with which argument.
        settings = self.read("Utilities/Settings.kt")
        for call in ("EncryptionHelper.generateRandom(Constants.PBKDF2_SALT_LENGTH)",
                     "EncryptionHelper.benchmarkIterations(password, salt)",
                     "val derived = EncryptionHelper.generatePBKDF2Credentials(password, salt, iterations)",
                     "EncryptionHelper.encrypt(key.public, plainPassword.toByteArray(StandardCharsets.UTF_8))",
                     "EncryptionHelper.decrypt(key.private, encPassword)",
                     "KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_PASSWORD)",
                     "KeyStoreHelper.loadAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_PASSWORD)",
                     "KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, true)"):
            self.assertIn(call, settings)
        activity = (JAVA / "Activities/SettingsActivity.java").read_text()
        self.assertIn("EncryptionHelper.EncryptionChangeCallback", activity)
        self.assertIn("KeyStoreHelper.createEncryptionKey(this)", activity)
        self.assertIn("KeyStoreHelper.wipeKeys(getActivity())", activity)
        panic = (JAVA / "Activities/PanicResponderActivity.java").read_text()
        self.assertIn("KeyStoreHelper.wipeKeys(this)", panic)
        reset = (JAVA / "Activities/SettingsActivity.java").read_text()
        self.assertIn("KeyStoreHelper.wipeKeys(getActivity())", reset)
        self.assertIn("KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, false)",
                      (KOTLIN / "Tasks/EncryptedBackupWorker.kt").read_text())
        preference = self.read("Preferences/PasswordEncryptedPreference.kt")
        self.assertIn("KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_PASSWORD)", preference)
        self.assertIn("EncryptionHelper.encrypt(pair.getPublic(), value.toByteArray(StandardCharsets.UTF_8))", preference)
        self.assertIn("EncryptionHelper.decrypt(pair.getPrivate(), encBytes)", preference)
        # The callback override is non-null now that the interface is Kotlin; the result field stays nullable.
        change = self.read("Tasks/ChangeEncryptionTask.kt")
        self.assertIn("override fun onSuccessfulEncryptionChange(newEncryptionType: Constants.EncryptionType, newEncryptionKey: SecretKey)",
                      change)
        self.assertIn("@JvmField val newEncryptionKey: SecretKey?", change)
        for test in ("Utilities/EncryptionSecurityTest.java", "Tasks/CredentialLifecycleSecurityTest.java",
                     "Utilities/IpcAuthorizationSecurityTest.java", "Utilities/VaultFailureSecurityTest.java",
                     "ApplicationTest.java"):
            self.assertTrue((ANDROID_TEST / test).is_file(), f"{test} disappeared")

    def test_device_test_is_checked_in_for_the_paths_the_sandbox_cannot_run(self):
        interop = (ANDROID_TEST / "Utilities/KotlinCryptoInteropTest.java").read_text()
        # Java call sites, the real AndroidKeyStore and the AES-GCM path the desktop JDK rejects.
        for used in ("EncryptionHelper.PBKDF2Credentials", "EncryptionHelper.EncryptionChangeResult",
                     "EncryptionHelper.EncryptionChangeCallback", "EncryptionHelper.encrypt(",
                     "EncryptionHelper.decrypt(", "KeyStoreHelper.createEncryptionKey(",
                     "KeyStoreHelper.loadEncryptionKeyFromKeyStore(", "KeyStoreHelper.wipeKeys(",
                     "EncryptionHelper.loadOrGenerateWrappedKey("):
            self.assertIn(used, interop)
        # The legacy key is a plain SHA-256 digest (32 bytes), not the 16-byte ENCRYPTION_KEY_LENGTH
        # of the KeyStore wrapping path. A device-only assertion cannot be run here, so pin the one
        # value that was wrong once; the 32-byte digest itself is executed by the sandbox checks.
        self.assertIn('MessageDigest.getInstance("SHA-256")', self.read("Utilities/EncryptionHelper.kt"))
        self.assertIn('assertEquals(32, EncryptionHelper.generateSymmetricKeyFromPassword("synthetic").getEncoded().length)',
                      interop)
        self.assertNotIn("assertEquals(16, EncryptionHelper.generateSymmetricKeyFromPassword", interop)
        self.assertEqual(1, interop.count("generateSymmetricKeyFromPassword(\"synthetic\")"),
                         "the legacy key length must be asserted exactly once")

    def test_sandbox_check_literals_stay_equal_to_the_java_constants(self):
        constants = (JAVA / "Utilities/Constants.java").read_text()
        checks = (SANDBOX / "Checks.kt").read_text()
        # Constants.java keeps these package-private (Kotlin has no package-private visibility), so
        # the default-package sandbox cannot read them and mirrors the values instead. Keep them equal.
        self.assertIn("PBKDF2_MAX_BACKUP_ITERATIONS   = 160000", constants)
        self.assertIn("PBKDF2_MIN_BACKUP_ITERATIONS   = 140000", constants)
        self.assertIn("PBKDF2_MIN_AUTH_ITERATIONS     = 30000", constants)
        self.assertIn("ENCRYPTION_IV_LENGTH   = 12", constants)
        self.assertIn("private const val ENCRYPTION_IV_BYTES = 12", checks)
        self.assertIn("private const val BACKUP_ITERATIONS_MIN = 140000", checks)
        self.assertIn("private const val BACKUP_ITERATIONS_MAX = 160000", checks)
        self.assertIn("private const val AUTH_ITERATIONS_MIN = 30000", checks)

    def test_batch_is_recorded_in_the_conversion_and_sandbox_documentation(self):
        record = (ROOT / "docs/kotlin-extracted-helpers.md").read_text()
        self.assertIn("Fifteenth batch (crypto and KeyStore helpers)", record)
        self.assertIn("EncryptionHelper.kt", record)
        self.assertIn("KeyStoreHelper.kt", record)
        readme = (SANDBOX / "README.md").read_text()
        self.assertIn("`Utilities/EncryptionHelper.kt`", readme)
        self.assertIn("`Utilities/KeyStoreHelper.kt`", readme)
        self.assertIn("KotlinCryptoInteropTest", readme)


if __name__ == "__main__":
    unittest.main()
