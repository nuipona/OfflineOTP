/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Utilities;

import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Atomic private-file replacement; callers serialize access with DatabaseFileLock. */
class FileHelper {
    static boolean exists(File file) {
        return file.exists() || new File(file.getPath() + ".bak").exists();
    }

    static byte[] readFileToBytes(File file) throws IOException {
        try (InputStream input = new AtomicFile(file).openRead()) {
            return BoundedInputReader.read(input, BoundedInputReader.MAX_DOCUMENT_BYTES + 1024);
        }
    }

    static void writeBytesToFile(File file, byte[] data) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(data);
            output.getFD().sync();
            atomic.finishWrite(output);
            output = null;
            if (!java.util.Arrays.equals(readFileToBytes(file), data)) throw new IOException("Atomic write verification failed");
        } catch (IOException | RuntimeException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
    }

    static void delete(File file) {
        new AtomicFile(file).delete();
        // Cover both legacy and current platform AtomicFile staging names.
        new File(file.getPath() + ".new").delete();
    }
}
