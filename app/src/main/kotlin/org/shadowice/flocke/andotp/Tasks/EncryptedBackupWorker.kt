package org.shadowice.flocke.andotp.Tasks

import android.content.Context
import android.util.Log

import androidx.work.ListenableWorker.Result
import androidx.work.Worker
import androidx.work.WorkerParameters

import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import org.shadowice.flocke.andotp.Utilities.NotificationHelper
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultReadResult
import org.shadowice.flocke.andotp.Utilities.VaultSession

import java.util.ArrayList
import java.util.function.BooleanSupplier

import javax.crypto.SecretKey

/**
 * Runs the password-encrypted backup triggered by the ENCRYPTED_BACKUP broadcast.
 *
 * Converted from the Java class of the same name. The check order, the failure notifications, the
 * vault read under the file lock and the entry cleanup are unchanged; the class stays extendable
 * and the WorkManager entry point keeps its constructor and `doWork()` result. The Java
 * `@NonNull` annotations are not repeated: the Kotlin types are not nullable.
 */
open class EncryptedBackupWorker(context: Context, params: WorkerParameters) :
        Worker(context, params) {

    companion object {
        private val TAG = EncryptedBackupWorker::class.java.simpleName
    }

    /**
     * The Java worker called the task's protected `doInBackground()` from the same package.
     * Kotlin's `protected` has no package-private part, so the call is made from this private
     * subclass, which may reach the inherited member on itself. The task class itself and its
     * Java-visible surface are unchanged, and the backup still runs on the caller's thread.
     */
    private class BroadcastBackupTask(
        context: Context,
        entries: ArrayList<Entry>,
        password: String?,
        permitted: BooleanSupplier
    ) : EncryptedBackupTask(context, entries, password, null, EncryptedBackupTask.Mode.BROADCAST, permitted) {
        fun run(): BackupTaskResult = doInBackground()
    }

    override fun doWork(): Result {
        val context = getApplicationContext()
        val settings = Settings(context)

        if (isStopped() || !settings.isEncryptedBackupBroadcastEnabled()) return Result.failure()
        // Settings may have changed since the broadcast was received, so check again.
        // Never write a backup without a password.
        val password: String? = settings.getBackupPasswordEnc()
        if (password == null || password.isEmpty()) {
            return fail(context, R.string.backup_receiver_no_password_set)
        }
        if (!settings.isBackupLocationSet()) {
            return fail(context, R.string.backup_receiver_no_location_set)
        }
        if (settings.getEncryption() != Constants.EncryptionType.KEYSTORE) {
            return fail(context, R.string.backup_receiver_custom_encryption_failed)
        }

        try {
            val generation: Long
            val entries: ArrayList<Entry>
            synchronized(DatabaseHelper.DatabaseFileLock) {
                if (isStopped() || !settings.isEncryptedBackupBroadcastEnabled()) return Result.failure()
                generation = VaultSession.generation()
                val encryptionKey: SecretKey? = KeyStoreHelper.loadEncryptionKeyFromKeyStore(context, false)
                val loaded = DatabaseHelper.readVault(context, encryptionKey)
                if (loaded !is VaultReadResult.Loaded) return fail(context, R.string.backup_receiver_unexpected_error)
                entries = loaded.takeEntries()
            }
            val result: BackupTaskResult
            try {
                result = BroadcastBackupTask(context, entries, password,
                        BooleanSupplier { !isStopped() && VaultSession.isCurrent(generation) }).run()
            } finally {
                for (entry in entries) entry.destroy()
            }

            if (result.success) {
                NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_SUCCESS, R.string.backup_receiver_title_backup_success, result.payload)
                return Result.success()
            }
            return fail(context, result.messageId)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Encrypted backup failed")
            return fail(context, R.string.backup_receiver_unexpected_error)
        }
    }

    /** Shows the failure notification. No retry: a failed backup would most likely fail again. */
    private fun fail(context: Context, messageId: Int): Result {
        NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.backup_receiver_title_backup_failed, messageId)
        return Result.failure()
    }
}
