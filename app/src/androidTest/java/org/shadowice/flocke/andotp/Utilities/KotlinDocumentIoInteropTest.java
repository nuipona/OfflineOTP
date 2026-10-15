package org.shadowice.flocke.andotp.Utilities;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Real-platform characterization of the Kotlin StorageAccessHelper/BackupHelper and their Java
 * call sites. The desktop sandbox drives StorageAccessHelper against a recording fake provider;
 * this test covers the real ContentResolver, Uri and DocumentFile paths that need a device. It
 * uses synthetic files in the app's private directories only, never the vault or a real backup
 * location, and restores every setting it changes. Run on disposable emulators.
 */
@RunWith(AndroidJUnit4.class)
public class KotlinDocumentIoInteropTest {
    // No provider is registered under this authority, so every provider call fails.
    private static final String MISSING_AUTHORITY = "org.shadowice.flocke.andotp.test.missing";

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static File write(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
        return file;
    }

    @Test public void cacheFixturesAreReadThroughTheResolverAndEverythingElseIsRefused() throws Exception {
        byte[] bytes = "{\"fixture\":\"Å☃\"}".getBytes(StandardCharsets.UTF_8);
        File cache = context.getCacheDir();
        File fixture = write(new File(cache, "document-io-fixture.json"), bytes);
        File outside = write(new File(context.getFilesDir(), "document-io-outside.json"), bytes);
        File sibling = write(new File(new File(cache.getParentFile(), cache.getName() + "-sibling"), "f.json"), bytes);
        try {
            // The Java call site still compiles against a declared IOException.
            try {
                assertArrayEquals(bytes, StorageAccessHelper.loadFile(context, Uri.fromFile(fixture)));
            } catch (IOException unexpected) {
                throw new AssertionError("a private cache fixture was refused", unexpected);
            }
            assertEquals("{\"fixture\":\"Å☃\"}", StorageAccessHelper.loadFileString(context, Uri.fromFile(fixture)));

            Uri[] refused = {
                    null,
                    Uri.fromFile(outside),
                    Uri.fromFile(sibling),                                       // shares the cache prefix
                    Uri.fromFile(new File(cache, "../files/document-io-outside.json")),
                    Uri.fromFile(cache),
                    Uri.parse("https://example.org/document"),
            };
            for (Uri uri : refused) {
                try {
                    StorageAccessHelper.loadFile(context, uri);
                    fail("Expected IOException for " + uri);
                } catch (IOException expected) { }
                assertEquals("", StorageAccessHelper.loadFileString(context, uri));
            }
            // An unknown provider is an I/O failure, not a crash.
            Uri missing = Uri.parse("content://" + MISSING_AUTHORITY + "/document/1");
            try {
                StorageAccessHelper.loadFile(context, missing);
                fail("Expected IOException for a missing provider");
            } catch (IOException expected) { }
        } finally {
            assertTrue(fixture.delete() && outside.delete() && sibling.delete() && sibling.getParentFile().delete());
        }
    }

    @Test public void writesAreContentOnlyAndProviderFailuresAreFalse() throws Exception {
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        File fixture = write(new File(context.getCacheDir(), "document-io-write.json"), original);
        try {
            // File URIs are never written, even into the private cache.
            assertFalse(StorageAccessHelper.saveFile(context, Uri.fromFile(fixture), new byte[] { 1, 2, 3 }));
            assertFalse(StorageAccessHelper.saveFile(context, Uri.fromFile(fixture), "replacement"));
            assertArrayEquals(original, StorageAccessHelper.loadFile(context, Uri.fromFile(fixture)));

            Uri missing = Uri.parse("content://" + MISSING_AUTHORITY + "/document/1");
            assertFalse(StorageAccessHelper.saveFile(context, missing, new byte[] { 1 }));
            assertFalse(StorageAccessHelper.saveFile(context, null, new byte[] { 1 }));
            assertFalse(StorageAccessHelper.saveFile(context, missing, (byte[]) null));
            AtomicInteger asked = new AtomicInteger();
            assertFalse(StorageAccessHelper.saveFile(context, missing, new byte[] { 1 },
                    () -> asked.incrementAndGet() > 1));
            assertEquals("a refused write must not ask again", 1, asked.get());
        } finally {
            assertTrue(fixture.delete());
        }
    }

    @Test public void displayNamesFallBackAndLoseAsciiControlCharacters() {
        // File URIs are not queried: the decoded last segment is used, minus control characters.
        assertEquals("name.json",
                StorageAccessHelper.getContentFileName(context, Uri.fromFile(new File(context.getCacheDir(), "na\u0007me\n.json"))));
        // U+0085 is not in the ASCII \p{Cntrl} class, so it stays (unchanged from the Java).
        assertEquals("name\u0085", StorageAccessHelper.getContentFileName(context,
                Uri.fromFile(new File(context.getCacheDir(), "name\u0085"))));
        // An unknown provider yields no metadata, so the last path segment is used.
        assertEquals("last.json", StorageAccessHelper.getContentFileName(context,
                Uri.parse("content://" + MISSING_AUTHORITY + "/document/last.json")));
        assertEquals("backup", StorageAccessHelper.getContentFileName(context, null));
        char[] longName = new char[InputPolicy.MAX_TEXT_CHARS + 1];
        Arrays.fill(longName, 'n');
        assertEquals("backup", StorageAccessHelper.getContentFileName(context,
                Uri.fromFile(new File(context.getCacheDir(), new String(longName)))));
    }

    @Test public void backupHelperKeepsItsJavaSurfaceNamesAndFailureMessages() {
        // The nested holder keeps public mutable fields and a public constructor for Java.
        BackupHelper.BackupFile holder = new BackupHelper.BackupFile();
        assertNull(holder.file);
        assertEquals(0, holder.errorMessage);
        holder.errorMessage = R.string.backup_toast_location_access_failed;
        assertEquals(R.string.backup_toast_location_access_failed, holder.errorMessage);

        // A tree whose provider is missing resolves, but no document can be created in it.
        BackupHelper.BackupFile missing = BackupHelper.backupFile(context,
                Uri.parse("content://" + MISSING_AUTHORITY + "/tree/root"), Constants.BackupType.ENCRYPTED);
        assertNull(missing.file);
        assertEquals(R.string.backup_toast_file_creation_failed, missing.errorMessage);

        Settings settings = new Settings(context);
        boolean appending = settings.getIsAppendingDateTimeToBackups();
        try {
            settings.setBoolean(R.string.settings_key_backup_append_date_time, false);
            assertEquals("otp_accounts.json.aes", BackupHelper.backupFilename(context, Constants.BackupType.ENCRYPTED));
            assertEquals("otp_accounts.json.gpg", BackupHelper.backupFilename(context, Constants.BackupType.OPEN_PGP));
            assertEquals("otp_accounts.json.aes", BackupHelper.backupFilename(context, Constants.BackupType.UNAVAILABLE));
            settings.setBoolean(R.string.settings_key_backup_append_date_time, true);
            assertTrue(BackupHelper.backupFilename(context, Constants.BackupType.ENCRYPTED)
                    .matches("otp_accounts_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.json\\.aes"));
            assertTrue(BackupHelper.backupFilename(context, Constants.BackupType.OPEN_PGP)
                    .matches("otp_accounts_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.json\\.gpg"));
        } finally {
            settings.setBoolean(R.string.settings_key_backup_append_date_time, appending);
        }

        Uri location = settings.isBackupLocationSet() ? settings.getBackupLocation() : null;
        try {
            settings.setBackupLocation(Uri.parse(""));
            assertSame(Constants.BackupType.UNAVAILABLE, BackupHelper.autoBackupType(context));
        } finally {
            settings.setBackupLocation(location != null ? location : Uri.parse(""));
        }
    }

    @Test public void encryptedBackupWritesRefuseBeforeAnyProviderWrite() throws Exception {
        File fixture = write(new File(context.getCacheDir(), "document-io-backup.aes"), new byte[] { 7 });
        try {
            Uri missing = Uri.parse("content://" + MISSING_AUTHORITY + "/document/backup.aes");
            assertFalse(BackupHelper.backupToFile(context, missing, null, "[]"));
            assertFalse(BackupHelper.backupToFile(context, missing, "", "[]"));
            assertFalse(BackupHelper.backupToFile(context, null, "test-only-password", "[]"));
            // A file URI is encrypted for but never written.
            assertFalse(BackupHelper.backupToFile(context, Uri.fromFile(fixture), "test-only-password", "[]"));
            assertArrayEquals(new byte[] { 7 }, StorageAccessHelper.loadFile(context, Uri.fromFile(fixture)));
            AtomicInteger asked = new AtomicInteger();
            assertFalse(BackupHelper.backupToFile(context, missing, "test-only-password", "[]",
                    () -> asked.incrementAndGet() > 1));
            assertEquals("a refused backup must not derive a key or ask again", 1, asked.get());
        } finally {
            assertTrue(fixture.delete());
        }
    }
}
