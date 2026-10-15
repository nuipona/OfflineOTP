package org.shadowice.flocke.andotp.Tasks;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader;
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupHeader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Uses synthetic files only, never the real vault. Run on disposable emulators. */
@RunWith(AndroidJUnit4.class)
public class EncryptedRestoreSecurityTest {
    private static final String PASSWORD = "test-only-password";
    private static final String PLAIN = "[{\"label\":\"fixture-Å-☃\",\"secret\":\"GEZDGNBVGY3TQOJQ\"}]";

    private byte[] fixture(String name) throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = testContext.getAssets().open("security/" + name)) {
            return BoundedInputReader.read(input, 1024);
        }
    }

    private BackupTaskResult restore(byte[] bytes, String password, boolean legacy) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File document = File.createTempFile("restore-security-", ".aes", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(document)) { output.write(bytes); }
            return new EncryptedRestoreTask(context, Uri.fromFile(document), password, legacy).doInBackground();
        } finally {
            assertTrue("Temporary fixture must be removed", document.delete());
        }
    }

    @Test public void readsIndependentCurrentAndLegacyUnicodeFixtures() throws Exception {
        for (boolean legacy : new boolean[] {false, true}) {
            BackupTaskResult result = restore(fixture(legacy ? "legacy.aes" : "current.aes"), PASSWORD, legacy);
            assertTrue(result.success);
            assertEquals(PLAIN, result.payload);
        }
    }

    @Test public void wrongPasswordNeverReturnsPlaintext() throws Exception {
        for (boolean legacy : new boolean[] {false, true}) {
            BackupTaskResult result = restore(fixture(legacy ? "legacy.aes" : "current.aes"), "wrong-password", legacy);
            assertFalse(result.success);
            assertNull(result.payload);
        }
    }

    @Test public void tamperedCiphertextNeverReturnsPlaintext() throws Exception {
        for (boolean legacy : new boolean[] {false, true}) {
            byte[] bytes = fixture(legacy ? "legacy.aes" : "current.aes");
            bytes[bytes.length - 1] ^= 1;
            BackupTaskResult result = restore(bytes, PASSWORD, legacy);
            assertFalse(result.success);
            assertNull(result.payload);
        }
    }

    @Test public void truncatedFramingFailsClosed() throws Exception {
        byte[] bytes = fixture("current.aes");
        BackupTaskResult result = restore(Arrays.copyOf(bytes, EncryptedBackupHeader.MIN_BACKUP_BYTES - 1), PASSWORD, false);
        assertFalse(result.success);
        assertNull(result.payload);
        assertFalse(restore(new byte[27], PASSWORD, true).success);
    }

    @Test(timeout = 10000) public void hostileIterationCountsAreRejectedBeforeDerivation() throws Exception {
        for (int count : new int[] {-1, 0, EncryptedBackupHeader.MAX_ITERATIONS + 1, Integer.MAX_VALUE}) {
            byte[] bytes = fixture("current.aes");
            ByteBuffer.wrap(bytes).putInt(count);
            BackupTaskResult result = restore(bytes, PASSWORD, false);
            assertFalse(result.success);
            assertNull(result.payload);
        }
    }
}
