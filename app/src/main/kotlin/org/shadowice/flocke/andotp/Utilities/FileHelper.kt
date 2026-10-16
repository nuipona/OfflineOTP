package org.shadowice.flocke.andotp.Utilities

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Atomic private-file replacement; callers serialize access with DatabaseFileLock.
 *
 * Replaces `FileHelper.java`. The class was package-private in Java; it is `internal` here, which
 * keeps it out of the app's API and is public in the bytecode for the same-package Java tests.
 */
internal object FileHelper {
    @JvmStatic
    fun exists(file: File): Boolean = file.exists() || File(file.path + ".bak").exists()

    @JvmStatic
    @Throws(IOException::class)
    fun readFileToBytes(file: File): ByteArray {
        return AtomicFile(file).openRead().use { input ->
            BoundedInputReader.read(input, BoundedInputReader.MAX_DOCUMENT_BYTES + 1024)
        }
    }

    /**
     * Writes through the platform staging file, syncs it and then reads the promoted file back. A
     * failure before the promotion discards the staged write; once the promotion has returned, the
     * staged stream is no longer discarded, so a failed verification leaves the written file in place
     * (exactly as the Java version did).
     */
    @JvmStatic
    @Throws(IOException::class)
    fun writeBytesToFile(file: File, data: ByteArray) {
        val atomic = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            val stream = atomic.startWrite()
            output = stream
            stream.write(data)
            stream.fd.sync()
            atomic.finishWrite(stream)
            output = null
            if (!readFileToBytes(file).contentEquals(data)) {
                throw IOException("Atomic write verification failed")
            }
        } catch (error: Exception) {
            // Only IOException and RuntimeException can leave this block; Errors pass through untouched.
            output?.let { atomic.failWrite(it) }
            throw error
        }
    }

    @JvmStatic
    fun delete(file: File) {
        AtomicFile(file).delete()
        // Cover both legacy and current platform AtomicFile staging names.
        File(file.path + ".new").delete()
    }
}
