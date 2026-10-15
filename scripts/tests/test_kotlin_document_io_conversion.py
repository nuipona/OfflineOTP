"""Source-level guards for the twelfth batch (document/backup I/O helpers).

The sandbox executes StorageAccessHelper against a recording provider, but BackupHelper's members
take the Java-only Constants.BackupType and call the Java Settings/EncryptionHelper, so they only
compile there. These guards pin what a mechanical conversion could silently drop. Provider,
DocumentFile and real encrypted-backup behaviour still need the Android gates.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


class KotlinDocumentIoConversionTest(unittest.TestCase):
    def test_replacements_keep_their_names_and_are_compiled_by_the_sandbox(self):
        harness = (SANDBOX / "run.sh").read_text()
        for name in ("Utilities/StorageAccessHelper", "Utilities/BackupHelper"):
            self.assertFalse((JAVA / f"{name}.java").exists())
            self.assertTrue((KOTLIN / f"{name}.kt").is_file())
            self.assertIn(f"$KOTLIN_APP/{name}.kt", harness)
        checks = (SANDBOX / "Checks.kt").read_text()
        # Execution, not only compilation: the storage checks drive the real helper.
        for executed in ("StorageAccessHelper.saveFile(", "StorageAccessHelper.loadFile(",
                         "StorageAccessHelper.getContentFileName(", "BackupHelper.BackupFile()"):
            self.assertIn(executed, checks)

    def test_storage_helper_keeps_its_static_surface_and_nullability(self):
        source = (KOTLIN / "Utilities/StorageAccessHelper.kt").read_text()
        self.assertIn("object StorageAccessHelper {", source)
        self.assertEqual(6, source.count("    @JvmStatic\n"), "a former static member lost @JvmStatic")
        self.assertEqual(6, source.count("\n    fun "), "a member was added or removed")
        # Java callers (ScanQRCodeFromFile) catch IOException, so the checked exception stays declared.
        self.assertIn("@JvmStatic\n    @Throws(IOException::class)\n    fun loadFile(context: Context, file: Uri?): ByteArray", source)
        # Parameters the Java null-checked stay nullable; the rest fail fast (documented).
        for signature in ("fun saveFile(context: Context, file: Uri?, data: ByteArray?): Boolean",
                          "fun saveFile(context: Context, file: Uri?, data: ByteArray?, permitted: BooleanSupplier): Boolean",
                          "fun saveFile(context: Context, file: Uri?, data: String): Boolean",
                          "fun getContentFileName(context: Context, uri: Uri?): String",
                          "fun loadFileString(context: Context, file: Uri?): String"):
            self.assertIn(signature, source)
        self.assertNotIn("!!", source)

    def test_storage_helper_keeps_bounds_scheme_policy_and_chunked_permission(self):
        source = (KOTLIN / "Utilities/StorageAccessHelper.kt").read_text()
        save = source[source.index("permitted: BooleanSupplier): Boolean {"):source.index("fun getContentFileName(")]
        # Every refusal happens before the provider is opened; the permission is asked last.
        guard = save[:save.index("context.getContentResolver()")]
        for bound in ('"content" != file.getScheme()', "file.toString().length > InputPolicy.MAX_URI_CHARS",
                      "data == null", "data.size > BoundedInputReader.MAX_DOCUMENT_BYTES"):
            self.assertIn(bound, guard)
        self.assertLess(guard.index("data.size > BoundedInputReader.MAX_DOCUMENT_BYTES"),
                        guard.index("!permitted.getAsBoolean()"))
        # Truncating write mode, re-check after opening, before every chunk and after the flush.
        self.assertIn('openFileDescriptor(file, "wt")', save)
        self.assertIn("private const val WRITE_CHUNK_BYTES = 8192", source)
        self.assertEqual(4, save.count("permitted.getAsBoolean()"), "a permission re-check disappeared")
        loop = save[save.index("while (offset < data.size)"):save.index("output.flush()")]
        self.assertLess(loop.index("if (!permitted.getAsBoolean()) return false"), loop.index("output.write("))
        self.assertIn("minOf(WRITE_CHUNK_BYTES, data.size - offset)", loop)
        self.assertIn("output.flush()\n                    permitted.getAsBoolean()", save)
        # Failures are false, never a thrown provider exception.
        self.assertIn("} catch (error: IOException) {\n            false\n        } catch (error: RuntimeException) {\n            false", save)

        load = source[source.index("fun loadFile("):source.index("fun loadFileString(")]
        # File URIs are confined to the private cache, with the separator so a sibling prefix fails.
        self.assertIn('throw IOException("Invalid document URI")', load)
        self.assertIn('"file" != file.getScheme() || path == null', load)
        self.assertIn("File(path).canonicalPath.startsWith(context.getCacheDir().canonicalPath + File.separator)", load)
        self.assertIn('throw IOException("Unsupported document URI")', load)
        self.assertIn("BoundedInputReader.read(inputStream, BoundedInputReader.MAX_DOCUMENT_BYTES)", load)

        name = source[source.index("fun getContentFileName("):source.index("fun saveFile(context: Context, file: Uri?, data: String)")]
        self.assertIn("arrayOf(OpenableColumns.DISPLAY_NAME)", name)
        self.assertIn("if (column >= 0 && !cursor.isNull(column)) result = cursor.getString(column)", name)
        self.assertIn("} catch (ignored: RuntimeException) {", name)
        # The bound is applied before stripping, and the class is the ASCII \p{Cntrl} of java.util.regex.
        self.assertLess(name.index("name.length > InputPolicy.MAX_TEXT_CHARS"), name.index("name.replace("))
        self.assertIn('return name.replace(Regex("[\\\\p{Cntrl}]"), "")', name)
        self.assertEqual(2, name.count('return "backup"'), "a placeholder fallback changed")

    def test_backup_helper_keeps_names_mime_types_layout_and_result_holder(self):
        source = (KOTLIN / "Utilities/BackupHelper.kt").read_text()
        self.assertIn("object BackupHelper {", source)
        self.assertEqual(5, source.count("@JvmStatic\n    fun "), "a former static member lost @JvmStatic")
        self.assertEqual(1, source.count("    private fun "), "only the MIME mapping is private")
        for signature in ("fun backupFile(context: Context, backupLocationUri: Uri, type: Constants.BackupType): BackupFile",
                          "fun backupFilename(context: Context, type: Constants.BackupType): String",
                          "fun autoBackupType(context: Context): Constants.BackupType",
                          "fun backupToFile(context: Context, uri: Uri?, password: String?, plain: String): Boolean",
                          "fun backupToFile(context: Context, uri: Uri?, password: String?, plain: String,\n"
                          "                     permitted: BooleanSupplier): Boolean"):
            self.assertIn(signature, source)
        # The nested holder stays an extendable class with public mutable fields, not a data class.
        self.assertIn("open class BackupFile {\n        @JvmField var file: DocumentFile? = null\n"
                      "        @JvmField var errorMessage: Int = 0\n    }", source)
        self.assertNotRegex(source, r"(?m)^\s*(open\s+)?data class")
        # Automatic backups never reuse a name, and the MIME mapping keeps its fallback.
        self.assertIn('"otp_accounts_" + UUID.randomUUID() +', source)
        self.assertIn('(if (type == Constants.BackupType.OPEN_PGP) ".json.gpg" else ".json.aes")', source)
        for mapping in ("Constants.BackupType.ENCRYPTED -> Constants.BACKUP_MIMETYPE_CRYPT",
                        "Constants.BackupType.OPEN_PGP -> Constants.BACKUP_MIMETYPE_PGP",
                        'else -> "application/octet-stream"'):
            self.assertIn(mapping, source)
        self.assertIn("backupFile.errorMessage = R.string.backup_toast_file_creation_failed", source)
        self.assertIn("backupFile.errorMessage = R.string.backup_toast_location_access_failed", source)
        filename = source[source.index("fun backupFilename("):source.index("fun autoBackupType(")]
        self.assertLess(filename.index("val settings = Settings(context)"), filename.index("when (type)"))
        self.assertEqual(2, filename.count("settings.getIsAppendingDateTimeToBackups()"))
        self.assertIn("else -> Constants.BACKUP_FILENAME_CRYPT", filename)
        auto = source[source.index("fun autoBackupType("):source.index("fun backupToFile(")]
        self.assertLess(auto.index("isBackupLocationSet()"), auto.index("getBackupPasswordEnc().isEmpty()"))
        self.assertEqual(2, auto.count("return Constants.BackupType.UNAVAILABLE"))

        write = source[source.index("permitted: BooleanSupplier): Boolean {"):]
        # Refuse before key derivation; iteration count, then salt, then the AES-GCM output.
        self.assertLess(write.index("password == null || password.isEmpty() || !permitted.getAsBoolean()"),
                        write.index("EncryptionHelper.generateSymmetricKeyPBKDF2(password, iter, salt)"))
        self.assertIn("EncryptionHelper.generateRandom(Constants.ENCRYPTION_IV_LENGTH)", write)
        self.assertIn("ByteBuffer.allocate(Constants.INT_LENGTH).putInt(iter).array()", write)
        layout = re.findall(r"System\.arraycopy\(([^)]*)\)", write)
        self.assertEqual(["iterBytes, 0, data, 0, Constants.INT_LENGTH",
                          "salt, 0, data, Constants.INT_LENGTH, Constants.ENCRYPTION_IV_LENGTH",
                          "encrypted, 0, data, Constants.INT_LENGTH + Constants.ENCRYPTION_IV_LENGTH, encrypted.size"],
                         layout)
        self.assertIn("StorageAccessHelper.saveFile(context, uri, data, permitted)", write)
        self.assertIn("} catch (error: Exception) {\n            // Never log passwords or document contents.\n            false", write)

    def test_neither_helper_logs_or_widens_its_dependencies(self):
        for name in ("StorageAccessHelper", "BackupHelper"):
            source = (KOTLIN / f"Utilities/{name}.kt").read_text()
            for forbidden in ("android.util.Log", "Log.", "printStackTrace", "println(", "kotlinx.coroutines",
                              "VaultSession", "DatabaseHelper"):
                self.assertNotIn(forbidden, source, f"{name} references {forbidden}")


if __name__ == "__main__":
    unittest.main()
