package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupTask
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask

import java.util.ArrayList
import javax.crypto.SecretKey

/**
 * Session-authorized entry I/O and automatic-backup scheduling. Owns no screen, entry collection or
 * key. Read ownership transfers to the caller; saves borrow entries only during this call.
 */
class EntryPersistence(context: Context) {
    private val context: Context = context.getApplicationContext()
    private val settings: Settings = Settings(this.context)

    private fun authorized(key: SecretKey?): Boolean = key != null && VaultSession.getKey() === key

    fun load(key: SecretKey?): VaultReadResult {
        synchronized (VaultSession.MutationLock) {
            if (!authorized(key)) return VaultReadResult.Unavailable(VaultReadError.MISSING_KEY)
            return DatabaseHelper.readVault(context, key!!)
        }
    }

    fun save(entries: ArrayList<Entry>?, key: SecretKey?, autoBackup: Boolean,
            backupCallback: UiBasedBackgroundTask.OutcomeCallback<BackupTaskResult>?): VaultWriteResult {
        synchronized (VaultSession.MutationLock) {
            if (!authorized(key)) return VaultWriteResult.Rejected(VaultWriteError.MISSING_KEY)
            if (entries == null) return VaultWriteResult.Rejected(VaultWriteError.INVALID_INPUT)
            val result = DatabaseHelper.saveVault(context, entries, key!!)
            if (result !is VaultWriteResult.Saved) return result
            if (autoBackup && BackupHelper.autoBackupType(context) == Constants.BackupType.ENCRYPTED) {
                // Task constructor owns a separate validated snapshot; a failed save never exports.
                val task = EncryptedBackupTask(context, entries,
                        settings.getBackupPasswordEnc(), null, EncryptedBackupTask.Mode.AUTO_EDIT, { true })
                task.setOutcomeCallback(backupCallback)
                task.execute()
            }
            return result
        }
    }
}
