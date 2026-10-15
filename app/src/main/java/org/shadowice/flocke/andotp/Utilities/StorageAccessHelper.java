package org.shadowice.flocke.andotp.Utilities;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads and writes documents through the Storage Access Framework (content URIs). */
public class StorageAccessHelper {
    /** Provider writes are not atomic; automatic backups always use a new document. */
    public static boolean saveFile(Context context, Uri file, byte[] data) {
        return saveFile(context, file, data, () -> true);
    }

    public static boolean saveFile(Context context, Uri file, byte[] data, java.util.function.BooleanSupplier permitted) {
        if (file == null || file.toString().length() > InputPolicy.MAX_URI_CHARS || !"content".equals(file.getScheme()) || data == null || data.length > BoundedInputReader.MAX_DOCUMENT_BYTES || !permitted.getAsBoolean()) return false;
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(file, "wt")) {
            if (descriptor == null || !permitted.getAsBoolean()) return false;
            try (FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                for (int offset = 0; offset < data.length; offset += 8192) {
                    if (!permitted.getAsBoolean()) return false;
                    output.write(data, offset, Math.min(8192, data.length - offset));
                }
                output.flush();
                return permitted.getAsBoolean();
            }
        } catch (IOException | RuntimeException error) { return false; }
    }

    /** The display name of the document, falling back to the last path segment. */
    public static String getContentFileName(Context context, Uri uri) {
        if (uri == null || uri.toString().length() > InputPolicy.MAX_URI_CHARS) return "backup";
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (column >= 0 && !cursor.isNull(column)) result = cursor.getString(column);
                }
            } catch (RuntimeException ignored) { /* Metadata is advisory, never a reason to crash. */ }
        }
        if (result == null) result = uri.getLastPathSegment();
        if (result == null || result.length() > InputPolicy.MAX_TEXT_CHARS) return "backup";
        return result.replaceAll("[\\p{Cntrl}]", "");
    }

    public static boolean saveFile(Context context, Uri file, String data) {
        return saveFile(context, file, data.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads a document with a hard byte limit, independent of provider metadata. */
    public static byte[] loadFile(Context context, Uri file) throws IOException {
        if (file == null || file.toString().length() > InputPolicy.MAX_URI_CHARS) throw new IOException("Invalid document URI");
        // File URIs are for private cache fixtures only, not arbitrary caller-supplied paths.
        if (!"content".equals(file.getScheme())) {
            if (!"file".equals(file.getScheme()) || file.getPath() == null
                    || !new java.io.File(file.getPath()).getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath() + java.io.File.separator)) {
                throw new IOException("Unsupported document URI");
            }
        }
        try (InputStream inputStream = context.getContentResolver().openInputStream(file)) {
            return BoundedInputReader.read(inputStream, BoundedInputReader.MAX_DOCUMENT_BYTES);
        }
    }

    /** Reads the document as UTF-8 text, or "" if it cannot be read. */
    public static String loadFileString(Context context, Uri file) {
        String result = "";

        try {
            byte[] content = loadFile(context, file);
            result = new String(content, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // No provider messages or document content in logs.
        }

        return result;
    }
}
