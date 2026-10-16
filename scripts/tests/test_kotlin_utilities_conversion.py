"""Source-level guards for the twenty-second batch (the last two Java sources of the app).

`Utilities/Constants.java` and `Utilities/FileHelper.java` are Kotlin now, and no main-source Java is
left. The sandbox compiles both and reads their real declarations. This guard pins what it cannot
check: the literal value of every constant (the Java-visible contract the Java callers compile
against), the eleven enums with their constants in order, which members were package-private (and are
`internal` now), the write ordering of the atomic file helper, and the refusing `AtomicFile` stand-in
that keeps the sandbox away from the working directory.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
MAIN_JAVA = ROOT / "app/src/main/java"
SANDBOX = ROOT / "scripts/kotlin-sandbox"

# The 48 static members of the removed Constants.java, with their literal values (Kotlin spelling).
EXPECTED_CONSTANTS = {
    'INTENT_MAIN_AUTHENTICATE': '100',
    'INTENT_MAIN_SETTINGS': '101',
    'INTENT_MAIN_BACKUP': '102',
    'INTENT_MAIN_INTRO': '103',
    'INTENT_MAIN_QR_OPEN_IMAGE': '104',
    'INTENT_BACKUP_OPEN_DOCUMENT_CRYPT': '202',
    'INTENT_BACKUP_SAVE_DOCUMENT_CRYPT': '203',
    'INTENT_BACKUP_OPEN_DOCUMENT_PGP': '204',
    'INTENT_BACKUP_SAVE_DOCUMENT_PGP': '205',
    'INTENT_BACKUP_ENCRYPT_PGP': '206',
    'INTENT_BACKUP_DECRYPT_PGP': '207',
    'INTENT_BACKUP_OPEN_DOCUMENT_CRYPT_OLD': '208',
    'INTENT_SETTINGS_AUTHENTICATE': '300',
    'INTENT_SETTINGS_BACKUP_LOCATION': '301',
    'EXTRA_AUTH_PASSWORD_KEY': '"password_key"',
    'EXTRA_AUTH_NEW_ENCRYPTION': '"new_encryption"',
    'EXTRA_AUTH_MESSAGE': '"message"',
    'EXTRA_BACKUP_ENCRYPTION_KEY': '"encryption_key"',
    'EXTRA_INTRO_FINISHED': '"setup_finished"',
    'EXTRA_INTRO_ENCRYPTION_KEY': '"encryption_key"',
    'EXTRA_SETTINGS_ENCRYPTION_CHANGED': '"encryption_changed"',
    'EXTRA_SETTINGS_ENCRYPTION_KEY': '"encryption_key"',
    'ALGORITHM_SYMMETRIC': '"AES/GCM/NoPadding"',
    'ALGORITHM_ASYMMETRIC': '"RSA/ECB/PKCS1Padding"',
    'ENCRYPTION_KEY_LENGTH': '16',
    'ENCRYPTION_IV_LENGTH': '12',
    'INT_LENGTH': '4',
    'PBKDF2_ALGORITHM': '"PBKDF2WithHmacSHA1"',
    'PBKDF2_LENGTH': '256',
    'PBKDF2_SALT_LENGTH': '16',
    'PBKDF2_TARGET_AUTH_TIME': '1000',
    'PBKDF2_MIN_AUTH_ITERATIONS': '30000',
    'PBKDF2_BENCHMARK_ITERATIONS': '50000',
    'PBKDF2_MIN_BACKUP_ITERATIONS': '140000',
    'PBKDF2_MAX_BACKUP_ITERATIONS': '160000',
    'AUTH_MIN_PIN_LENGTH': '4',
    'AUTH_MIN_PASSWORD_LENGTH': '6',
    'KEYSTORE_ALIAS_PASSWORD': '"password"',
    'KEYSTORE_ALIAS_WRAPPING': '"settings"',
    'FILENAME_ENCRYPTED_KEY': '"otp.key"',
    'FILENAME_DATABASE': '"secrets.dat"',
    'FILENAME_DATABASE_BACKUP': '"secrets.dat.bck"',
    'BACKUP_FILENAME_CRYPT': '"otp_accounts.json.aes"',
    'BACKUP_FILENAME_PGP': '"otp_accounts.json.gpg"',
    'BACKUP_FILENAME_CRYPT_FORMAT': '"otp_accounts_%s.json.aes"',
    'BACKUP_FILENAME_PGP_FORMAT': '"otp_accounts_%s.json.gpg"',
    'BACKUP_MIMETYPE_CRYPT': '"binary/aes"',
    'BACKUP_MIMETYPE_PGP': '"application/pgp-encrypted"',
}

# The eleven nested enums of the removed Constants.java, in declaration order.
EXPECTED_ENUMS = {
    'AuthMethod': ['NONE', 'PASSWORD', 'PIN', 'DEVICE'],
    'EncryptionType': ['KEYSTORE', 'PASSWORD'],
    'SortMode': ['UNSORTED', 'ISSUER', 'LABEL', 'LAST_USED', 'MOST_USED'],
    'BackupType': ['ENCRYPTED', 'OPEN_PGP', 'UNAVAILABLE'],
    'TagFunctionality': ['OR', 'AND', 'SINGLE'],
    'NotificationChannel': ['BACKUP_FAILED', 'BACKUP_SUCCESS'],
    'SearchIncludes': ['LABEL', 'ISSUER', 'TAGS'],
    'CardLayouts': ['COMPACT', 'DEFAULT', 'FULL'],
    'AutoBackup': ['OFF', 'NEW_ENTRIES', 'ALL_EDITS'],
    'TapMode': ['NOTHING', 'REVEAL', 'COPY', 'COPY_BACKGROUND', 'SEND_KEYSTROKES'],
    'LabelDisplay': ['TRUNCATE', 'SCROLL', 'MULTILINE'],
}

# Members that were package-private in Java (no modifier): `internal const val` now.
PACKAGE_PRIVATE = {
    'ALGORITHM_SYMMETRIC',
    'ALGORITHM_ASYMMETRIC',
    'ENCRYPTION_KEY_LENGTH',
    'PBKDF2_ALGORITHM',
    'PBKDF2_LENGTH',
    'PBKDF2_SALT_LENGTH',
    'PBKDF2_TARGET_AUTH_TIME',
    'PBKDF2_MIN_AUTH_ITERATIONS',
    'PBKDF2_BENCHMARK_ITERATIONS',
    'PBKDF2_MIN_BACKUP_ITERATIONS',
    'PBKDF2_MAX_BACKUP_ITERATIONS',
}


def kotlin_constants(source):
    found = {}
    for line in source.splitlines():
        match = re.match(r"\s*(internal )?const val (\w+) = (.+)$", line)
        if match:
            found[match.group(2)] = (bool(match.group(1)), match.group(3).split("//")[0].strip())
    return found


def kotlin_enums(source):
    return {name: [item.strip() for item in body.split(",") if item.strip()]
            for name, body in re.findall(r"enum class (\w+) \{\s*([^}]*)\}", source)}


class KotlinUtilitiesConversionTest(unittest.TestCase):
    def read(self, relative):
        return (KOTLIN / relative).read_text()

    def test_no_main_source_java_is_left(self):
        remaining = sorted(path.relative_to(ROOT).as_posix() for path in MAIN_JAVA.rglob("*.java"))
        self.assertEqual([], remaining, "the app's main source must be Kotlin only")
        self.assertTrue((KOTLIN / "Utilities/Constants.kt").is_file())
        self.assertTrue((KOTLIN / "Utilities/FileHelper.kt").is_file())

    def test_constants_keep_every_java_name_and_value(self):
        found = kotlin_constants(self.read("Utilities/Constants.kt"))
        self.assertEqual(sorted(EXPECTED_CONSTANTS), sorted(found),
                         "Constants.kt must declare exactly the members of the removed Constants.java")
        for name, value in EXPECTED_CONSTANTS.items():
            self.assertEqual(value, found[name][1], f"{name} changed its value")

    def test_only_the_formerly_package_private_members_are_internal(self):
        found = kotlin_constants(self.read("Utilities/Constants.kt"))
        internal = sorted(name for name, (is_internal, _) in found.items() if is_internal)
        self.assertEqual(sorted(PACKAGE_PRIVATE), internal)

    def test_the_enums_keep_their_constants_in_order(self):
        source = self.read("Utilities/Constants.kt")
        self.assertEqual(EXPECTED_ENUMS, kotlin_enums(source))
        # The enums stay nested in the object, so Java spells them Constants.Enum.VALUE.
        self.assertIn("object Constants {", source)
        self.assertNotIn("\nclass Constants", source)

    def test_file_helper_keeps_the_java_write_ordering(self):
        source = self.read("Utilities/FileHelper.kt")
        self.assertIn("internal object FileHelper {", source)
        self.assertEqual(4, source.count("@JvmStatic"), "exists, readFileToBytes, writeBytesToFile and delete")
        write = source[source.index("fun writeBytesToFile("):source.index("fun delete(")]
        # The staged stream is synced and promoted before the read-back verification, and the staged
        # write is discarded only while the promotion has not completed (output is cleared after it).
        ordering = [write.index("stream.fd.sync()"), write.index("atomic.finishWrite(stream)"),
                    write.index("output = null"), write.index("readFileToBytes(file).contentEquals(data)")]
        self.assertEqual(sorted(ordering), ordering, "the write steps must keep their order")
        self.assertLess(write.index("catch (error: Exception)"), write.index("output?.let { atomic.failWrite(it) }"))
        self.assertIn("Atomic write verification failed", write)

    def test_the_atomic_file_stand_in_refuses_every_operation(self):
        stub = (SANDBOX / "stubs/android/android_util.kt").read_text()
        start = stub.index("class AtomicFile(")
        atomic = stub[start:stub.index("\n}\n", start) + 3]
        self.assertEqual(5, atomic.count("= unavailable()"), "delete, openRead, startWrite, finishWrite, failWrite")
        self.assertIn("throw UnsupportedOperationException", atomic)
        self.assertNotIn("inputStream()", atomic)
        self.assertNotIn("FileOutputStream(file)", atomic)

    def test_run_sh_compiles_both_files_from_kotlin(self):
        script = (SANDBOX / "run.sh").read_text()
        start = script.index("APP_SOURCES=(")
        block = script[start:script.index("\n)\n", start)]
        self.assertIn("$KOTLIN_APP/Utilities/Constants.kt", block)
        self.assertIn("$KOTLIN_APP/Utilities/FileHelper.kt", block)
        self.assertNotIn("JAVA_SOURCES", script)

    def test_the_panic_check_expects_the_refusal_not_a_missing_class(self):
        checks = (SANDBOX / "Checks.kt").read_text()
        self.assertIn("expectThrows(UnsupportedOperationException::class.java) { activity.create() }", checks)
        self.assertNotIn("expectThrows(NoClassDefFoundError::class.java) { activity.create() }", checks)

    def test_batch_is_recorded_in_the_conversion_and_sandbox_documentation(self):
        record = (ROOT / "docs/kotlin-extracted-helpers.md").read_text()
        self.assertIn("Twenty-second batch", record)
        self.assertIn("`Utilities/Constants.kt`", record)
        self.assertIn("`Utilities/FileHelper.kt`", record)
        readme = (SANDBOX / "README.md").read_text()
        self.assertIn("Utilities follow-up (twenty-second batch)", readme)


if __name__ == "__main__":
    unittest.main()
