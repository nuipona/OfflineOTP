package org.shadowice.flocke.andotp.securityclient

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/** Runs in a different UID/signing identity, not inside the target's instrumentation UID. */
class AttackActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (getIntent()?.getStringExtra("target") != TARGET_PACKAGE) {
            finish()
            return
        }

        try {
            startActivity(
                Intent(PANIC_ACTION).setClassName(
                    TARGET_PACKAGE,
                    "org.shadowice.flocke.andotp.Activities.PanicResponderActivity"
                )
            )
            Log.i(TAG, "PANIC_ALLOWED")
        } catch (_: SecurityException) {
            Log.i(TAG, "PANIC_DENIED")
        }

        Log.i(TAG, "BROADCAST_ATTEMPTED")
        try {
            sendBroadcast(
                Intent(BACKUP_ACTION).setClassName(
                    TARGET_PACKAGE,
                    "org.shadowice.flocke.andotp.Receivers.EncryptedBackupBroadcastReceiver"
                )
            )
            Log.i(TAG, "BROADCAST_SENT")
        } catch (_: SecurityException) {
            Log.i(TAG, "BROADCAST_DENIED")
        }
        finish()
    }

    companion object {
        private const val TARGET_PACKAGE = "org.shadowice.flocke.andotp.dev"
        private const val PANIC_ACTION = "info.guardianproject.panic.action.TRIGGER"
        private const val BACKUP_ACTION = "org.shadowice.flocke.andotp.broadcast.ENCRYPTED_BACKUP"
        private const val TAG = "andOTP-SecurityClient"
    }
}
