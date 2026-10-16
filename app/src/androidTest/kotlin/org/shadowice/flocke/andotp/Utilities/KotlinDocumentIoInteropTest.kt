package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger

/** Real ContentResolver/Uri/DocumentFile paths for the converted document helpers. */
@RunWith(AndroidJUnit4::class)
class KotlinDocumentIoInteropTest {
    // No provider is registered under this authority, so every provider call fails.
    companion object {
        private const val MISSING_AUTHORITY = "org.shadowice.flocke.andotp.test.missing"
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun write(file: File, bytes: ByteArray): File {
        val parent = requireNotNull(file.parentFile)
        assertTrue(parent.isDirectory || parent.mkdirs())
        FileOutputStream(file).use { output -> output.write(bytes) }
        return file
    }

    @Test
    fun cacheFixturesAreReadThroughTheResolverAndEverythingElseIsRefused() {
        val bytes = "{\"fixture\":\"Å☃\"}".toByteArray(StandardCharsets.UTF_8)
        val cache = context.cacheDir
        val fixture = write(File(cache, "document-io-fixture.json"), bytes)
        val outside = write(File(context.filesDir, "document-io-outside.json"), bytes)
        val sibling = write(
            File(File(requireNotNull(cache.parentFile), cache.name + "-sibling"), "f.json"), bytes
        )
        try {
            try {
                assertArrayEquals(bytes, StorageAccessHelper.loadFile(context, Uri.fromFile(fixture)))
            } catch (unexpected: IOException) {
                throw AssertionError("a private cache fixture was refused", unexpected)
            }
            assertEquals(
                "{\"fixture\":\"Å☃\"}",
                StorageAccessHelper.loadFileString(context, Uri.fromFile(fixture))
            )

            val refused = arrayOf<Uri?>(
                null,
                Uri.fromFile(outside),
                Uri.fromFile(sibling),
                Uri.fromFile(File(cache, "../files/document-io-outside.json")),
                Uri.fromFile(cache),
                Uri.parse("https://example.org/document")
            )
            for (uri in refused) {
                try {
                    StorageAccessHelper.loadFile(context, uri)
                    fail("Expected IOException for $uri")
                } catch (_: IOException) {
                }
                assertEquals("", StorageAccessHelper.loadFileString(context, uri))
            }
            val missing = Uri.parse("content://$MISSING_AUTHORITY/document/1")
            try {
                StorageAccessHelper.loadFile(context, missing)
                fail("Expected IOException for a missing provider")
            } catch (_: IOException) {
            }
        } finally {
            assertTrue(fixture.delete() && outside.delete() && sibling.delete() && sibling.parentFile!!.delete())
        }
    }

    @Test
    fun writesAreContentOnlyAndProviderFailuresAreFalse() {
        val original = "original".toByteArray(StandardCharsets.UTF_8)
        val fixture = write(File(context.cacheDir, "document-io-write.json"), original)
        try {
            assertFalse(StorageAccessHelper.saveFile(context, Uri.fromFile(fixture), byteArrayOf(1, 2, 3)))
            assertFalse(StorageAccessHelper.saveFile(context, Uri.fromFile(fixture), "replacement"))
            assertArrayEquals(original, StorageAccessHelper.loadFile(context, Uri.fromFile(fixture)))

            val missing = Uri.parse("content://$MISSING_AUTHORITY/document/1")
            assertFalse(StorageAccessHelper.saveFile(context, missing, byteArrayOf(1)))
            assertFalse(StorageAccessHelper.saveFile(context, null, byteArrayOf(1)))
            assertFalse(StorageAccessHelper.saveFile(context, missing, null as ByteArray?))
            val asked = AtomicInteger()
            assertFalse(
                StorageAccessHelper.saveFile(context, missing, byteArrayOf(1)) {
                    asked.incrementAndGet() > 1
                }
            )
            assertEquals("a refused write must not ask again", 1, asked.get())
        } finally {
            assertTrue(fixture.delete())
        }
    }

    @Test
    fun displayNamesFallBackAndLoseAsciiControlCharacters() {
        assertEquals(
            "name.json",
            StorageAccessHelper.getContentFileName(
                context, Uri.fromFile(File(context.cacheDir, "na\u0007me\n.json"))
            )
        )
        // U+0085 is not in the ASCII \p{Cntrl} class, so it stays.
        assertEquals(
            "name\u0085",
            StorageAccessHelper.getContentFileName(
                context, Uri.fromFile(File(context.cacheDir, "name\u0085"))
            )
        )
        assertEquals(
            "last.json",
            StorageAccessHelper.getContentFileName(
                context, Uri.parse("content://$MISSING_AUTHORITY/document/last.json")
            )
        )
        assertEquals("backup", StorageAccessHelper.getContentFileName(context, null))
        val longName = CharArray(InputPolicy.MAX_TEXT_CHARS + 1) { 'n' }
        assertEquals(
            "backup",
            StorageAccessHelper.getContentFileName(
                context, Uri.fromFile(File(context.cacheDir, String(longName)))
            )
        )
    }

    @Test
    fun backupHelperKeepsItsSurfaceNamesAndFailureMessages() {
        val holder = BackupHelper.BackupFile()
        assertNull(holder.file)
        assertEquals(0, holder.errorMessage)
        holder.errorMessage = R.string.backup_toast_location_access_failed
        assertEquals(R.string.backup_toast_location_access_failed, holder.errorMessage)

        val missing = BackupHelper.backupFile(
            context,
            Uri.parse("content://$MISSING_AUTHORITY/tree/root"),
            Constants.BackupType.ENCRYPTED
        )
        assertNull(missing.file)
        assertEquals(R.string.backup_toast_file_creation_failed, missing.errorMessage)

        val settings = Settings(context)
        val appending = settings.getIsAppendingDateTimeToBackups()
        try {
            settings.setBoolean(R.string.settings_key_backup_append_date_time, false)
            assertEquals(
                "otp_accounts.json.aes",
                BackupHelper.backupFilename(context, Constants.BackupType.ENCRYPTED)
            )
            assertEquals(
                "otp_accounts.json.gpg",
                BackupHelper.backupFilename(context, Constants.BackupType.OPEN_PGP)
            )
            assertEquals(
                "otp_accounts.json.aes",
                BackupHelper.backupFilename(context, Constants.BackupType.UNAVAILABLE)
            )
            settings.setBoolean(R.string.settings_key_backup_append_date_time, true)
            assertTrue(
                BackupHelper.backupFilename(context, Constants.BackupType.ENCRYPTED)
                    .matches(Regex("otp_accounts_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.json\\.aes"))
            )
            assertTrue(
                BackupHelper.backupFilename(context, Constants.BackupType.OPEN_PGP)
                    .matches(Regex("otp_accounts_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.json\\.gpg"))
            )
        } finally {
            settings.setBoolean(R.string.settings_key_backup_append_date_time, appending)
        }

        val location = if (settings.isBackupLocationSet()) settings.getBackupLocation() else null
        try {
            settings.setBackupLocation(Uri.parse(""))
            assertSame(Constants.BackupType.UNAVAILABLE, BackupHelper.autoBackupType(context))
        } finally {
            settings.setBackupLocation(location ?: Uri.parse(""))
        }
    }

    @Test
    fun encryptedBackupWritesRefuseBeforeAnyProviderWrite() {
        val fixture = write(File(context.cacheDir, "document-io-backup.aes"), byteArrayOf(7))
        try {
            val missing = Uri.parse("content://$MISSING_AUTHORITY/document/backup.aes")
            assertFalse(BackupHelper.backupToFile(context, missing, null, "[]"))
            assertFalse(BackupHelper.backupToFile(context, missing, "", "[]"))
            assertFalse(BackupHelper.backupToFile(context, null, "test-only-password", "[]"))
            assertFalse(
                BackupHelper.backupToFile(
                    context, Uri.fromFile(fixture), "test-only-password", "[]"
                )
            )
            assertArrayEquals(byteArrayOf(7), StorageAccessHelper.loadFile(context, Uri.fromFile(fixture)))
            val asked = AtomicInteger()
            assertFalse(
                BackupHelper.backupToFile(context, missing, "test-only-password", "[]") {
                    asked.incrementAndGet() > 1
                }
            )
            assertEquals("a refused backup must not derive a key or ask again", 1, asked.get())
        } finally {
            assertTrue(fixture.delete())
        }
    }
}
