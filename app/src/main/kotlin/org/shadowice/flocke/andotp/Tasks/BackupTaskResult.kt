package org.shadowice.flocke.andotp.Tasks

import android.content.Intent
import android.net.Uri

import org.shadowice.flocke.andotp.R

/** Result of a backup or restore task, including the toast/message to show for it. */
/*
 * The Java class was non-final and stayed extendable; Kotlin classes are final by default. The
 * fields stay public (the Java callers read them directly) and the class stays a plain class
 * instead of a data class: {@code payload} can hold the whole parsed database, and the generated
 * toString/equals/copy surface would broadcast it.
 */
open class BackupTaskResult(type: ResultType, success: Boolean, payload: String?, messageId: Int) {
    /** Whether the result belongs to a backup or a restore (NONE for neither). */
    enum class ResultType {
        NONE, BACKUP, RESTORE
    }

    @JvmField val resultType: ResultType = type
    @JvmField val success: Boolean = success
    @JvmField val payload: String? = payload
    @JvmField val messageId: Int = messageId

    @JvmField var isPGP: Boolean = false
    @JvmField var decryptIntent: Intent? = null
    @JvmField var uri: Uri? = null

    constructor(type: ResultType, success: Boolean, payload: String?, messageId: Int,
                isPGP: Boolean, decryptIntent: Intent?, uri: Uri?) : this(type, success, payload, messageId) {
        this.isPGP = isPGP
        this.decryptIntent = decryptIntent
        this.uri = uri
    }

    companion object {
        /** Reports a successful backup ({@code payload} is the file name) or restore (the parsed database). */
        @JvmStatic
        fun success(type: ResultType, payload: String?): BackupTaskResult {
            return BackupTaskResult(type, true, payload, R.string.backup_toast_export_success)
        }

        /** Reports a failed backup/restore; {@code messageId} is the error message to show (0 for the default). */
        @JvmStatic
        fun failure(type: ResultType, messageId: Int): BackupTaskResult {
            return BackupTaskResult(type, false, null, messageId)
        }
    }
}
