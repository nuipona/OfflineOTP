package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.net.Uri

import androidx.documentfile.provider.DocumentFile

import org.shadowice.flocke.andotp.R

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.function.BooleanSupplier

/**
 * Filenames, MIME types and file access for the backup files.
 *
 * Converted from the Java class of the same name. Every member was `static`, so this is the
 * documented `object` delta with `@JvmStatic` forwarders. The unique automatic-backup names, the
 * MIME mapping, the settings-driven names and the encrypted-backup layout (iteration count, salt,
 * then the AES-GCM output) are unchanged.
 */
object BackupHelper {
    /**
     * The resolved backup file, or the error to show if it could not be created.
     *
     * The Java nested class was non-final with two public mutable fields and a public no-argument
     * constructor; it stays `open` with `@JvmField` fields so Java keeps reading/writing them
     * directly. It is not a data class: it carries no secret, but a generated `equals`/`copy`
     * surface was never part of its API.
     */
    open class BackupFile {
        @JvmField var file: DocumentFile? = null
        @JvmField var errorMessage: Int = 0
    }

    private fun backupMimeType(type: Constants.BackupType): String = when (type) {
        Constants.BackupType.ENCRYPTED -> Constants.BACKUP_MIMETYPE_CRYPT
        Constants.BackupType.OPEN_PGP -> Constants.BACKUP_MIMETYPE_PGP
        else -> "application/octet-stream"
    }

    /** Finds (or creates) the backup file in the configured location. */
    @JvmStatic
    fun backupFile(context: Context, backupLocationUri: Uri, type: Constants.BackupType): BackupFile {
        val backupFile = BackupFile()
        val backupLocation = DocumentFile.fromTreeUri(context, backupLocationUri)

        if (backupLocation != null) {
            // Never truncate the last recoverable backup, even with timestamp naming disabled.
            val uniqueName = "otp_accounts_" + UUID.randomUUID() +
                    (if (type == Constants.BackupType.OPEN_PGP) ".json.gpg" else ".json.aes")
            backupFile.file = backupLocation.createFile(backupMimeType(type), uniqueName)

            // Neither an existing nor a new file is available
            if (backupFile.file == null)
                backupFile.errorMessage = R.string.backup_toast_file_creation_failed
        } else {
            backupFile.errorMessage = R.string.backup_toast_location_access_failed
        }

        return backupFile
    }

    /** The file name for the given backup type, optionally with a date/time suffix. */
    @JvmStatic
    fun backupFilename(context: Context, type: Constants.BackupType): String {
        // Constructed before the type is inspected, as in the Java.
        val settings = Settings(context)
        return when (type) {
            Constants.BackupType.ENCRYPTED ->
                if (settings.getIsAppendingDateTimeToBackups())
                    String.format(Constants.BACKUP_FILENAME_CRYPT_FORMAT, Tools.getDateTimeString())
                else Constants.BACKUP_FILENAME_CRYPT
            Constants.BackupType.OPEN_PGP ->
                if (settings.getIsAppendingDateTimeToBackups())
                    String.format(Constants.BACKUP_FILENAME_PGP_FORMAT, Tools.getDateTimeString())
                else Constants.BACKUP_FILENAME_PGP
            else -> Constants.BACKUP_FILENAME_CRYPT
        }
    }

    /** Automatic backups need a backup location and password-encrypted backups enabled. */
    @JvmStatic
    fun autoBackupType(context: Context): Constants.BackupType {
        val settings = Settings(context)

        if (!settings.isBackupLocationSet()) {
            return Constants.BackupType.UNAVAILABLE
        }

        if (!settings.getBackupPasswordEnc().isEmpty()) {
            return Constants.BackupType.ENCRYPTED
        }

        return Constants.BackupType.UNAVAILABLE
    }

    /** Writes a password-encrypted backup: iteration count + salt + nonce + AES-GCM ciphertext. */
    @JvmStatic
    fun backupToFile(context: Context, uri: Uri?, password: String?, plain: String): Boolean =
        backupToFile(context, uri, password, plain, BooleanSupplier { true })

    @JvmStatic
    fun backupToFile(context: Context, uri: Uri?, password: String?, plain: String,
                     permitted: BooleanSupplier): Boolean {
        return try {
            if (password == null || password.isEmpty() || !permitted.getAsBoolean()) return false
            val iter = EncryptionHelper.generateRandomIterations()
            val salt = EncryptionHelper.generateRandom(Constants.ENCRYPTION_IV_LENGTH)

            val key = EncryptionHelper.generateSymmetricKeyPBKDF2(password, iter, salt)
            val encrypted = EncryptionHelper.encrypt(key, plain.toByteArray(StandardCharsets.UTF_8))

            val iterBytes = ByteBuffer.allocate(Constants.INT_LENGTH).putInt(iter).array()
            val data = ByteArray(Constants.INT_LENGTH + Constants.ENCRYPTION_IV_LENGTH + encrypted.size)

            System.arraycopy(iterBytes, 0, data, 0, Constants.INT_LENGTH)
            System.arraycopy(salt, 0, data, Constants.INT_LENGTH, Constants.ENCRYPTION_IV_LENGTH)
            System.arraycopy(encrypted, 0, data, Constants.INT_LENGTH + Constants.ENCRYPTION_IV_LENGTH, encrypted.size)

            StorageAccessHelper.saveFile(context, uri, data, permitted)
        } catch (error: Exception) {
            // Never log passwords or document contents.
            false
        }
    }
}
