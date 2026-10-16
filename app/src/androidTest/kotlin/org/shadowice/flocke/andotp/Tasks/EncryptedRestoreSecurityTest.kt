package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupHeader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Arrays

/** Uses synthetic files only, never the real vault. Run on disposable emulators. */
@RunWith(AndroidJUnit4::class)
class EncryptedRestoreSecurityTest {
    private fun fixture(name: String): ByteArray {
        val testContext = InstrumentationRegistry.getInstrumentation().getContext()
        val input: InputStream = testContext.getAssets().open("security/$name")
        return input.use { BoundedInputReader.read(it, 1024) }
    }

    private fun restore(bytes: ByteArray, password: String, legacy: Boolean): BackupTaskResult {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val document = File.createTempFile("restore-security-", ".aes", context.getCacheDir())
        try {
            FileOutputStream(document).use { it.write(bytes) }
            return TestEncryptedRestoreTask(context, Uri.fromFile(document), password, legacy).restoreDirectly()
        } finally {
            assertTrue("Temporary fixture must be removed", document.delete())
        }
    }

    @Test fun readsIndependentCurrentAndLegacyUnicodeFixtures() {
        for (legacy in listOf(false, true)) {
            val result = restore(fixture(if (legacy) "legacy.aes" else "current.aes"), PASSWORD, legacy)
            assertTrue(result.success)
            assertEquals(PLAIN, result.payload)
        }
    }

    @Test fun wrongPasswordNeverReturnsPlaintext() {
        for (legacy in listOf(false, true)) {
            val result = restore(fixture(if (legacy) "legacy.aes" else "current.aes"), "wrong-password", legacy)
            assertFalse(result.success)
            assertNull(result.payload)
        }
    }

    @Test fun tamperedCiphertextNeverReturnsPlaintext() {
        for (legacy in listOf(false, true)) {
            val bytes = fixture(if (legacy) "legacy.aes" else "current.aes")
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
            val result = restore(bytes, PASSWORD, legacy)
            assertFalse(result.success)
            assertNull(result.payload)
        }
    }

    @Test fun truncatedFramingFailsClosed() {
        val bytes = fixture("current.aes")
        val result = restore(
            Arrays.copyOf(bytes, EncryptedBackupHeader.MIN_BACKUP_BYTES - 1),
            PASSWORD,
            false
        )
        assertFalse(result.success)
        assertNull(result.payload)
        assertFalse(restore(ByteArray(27), PASSWORD, true).success)
    }

    @Test(timeout = 10000)
    fun hostileIterationCountsAreRejectedBeforeDerivation() {
        for (count in intArrayOf(-1, 0, EncryptedBackupHeader.MAX_ITERATIONS + 1, Int.MAX_VALUE)) {
            val bytes = fixture("current.aes")
            ByteBuffer.wrap(bytes).putInt(count)
            val result = restore(bytes, PASSWORD, false)
            assertFalse(result.success)
            assertNull(result.payload)
        }
    }

    private class TestEncryptedRestoreTask(
        context: Context,
        uri: Uri,
        password: String,
        legacy: Boolean
    ) : EncryptedRestoreTask(context, uri, password, legacy) {
        fun restoreDirectly(): BackupTaskResult = doInBackground()
    }

    companion object {
        private const val PASSWORD = "test-only-password"
        private const val PLAIN = "[{\"label\":\"fixture-Å-☃\",\"secret\":\"GEZDGNBVGY3TQOJQ\"}]"
    }
}
