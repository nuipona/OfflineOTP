/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 * Copyright (C) 2017 Carlos Melero
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

package org.shadowice.flocke.andotp.Activities

import android.app.Activity
import android.os.Bundle
import androidx.work.WorkManager

import org.shadowice.flocke.andotp.Receivers.EncryptedBackupBroadcastReceiver
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.VaultSession

/**
 * Handles the PanicKit TRIGGER action: wipes the database/keys and/or the settings as configured.
 *
 * Converted from the Java class of the same name. The order of the steps is unchanged: the
 * automatic backups are disabled, the pending encrypted backup is cancelled and the vault is
 * locked before the accounts or the settings are wiped. The activity always removes its task.
 */
open class PanicResponderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = getIntent()
        if (intent != null && PANIC_TRIGGER_ACTION == intent.getAction()) {
            val settings = Settings(this)

            val response = settings.getPanicResponse()

            if (response.contains("accounts") || response.contains("settings")) {
                settings.disableAutomaticBackups()
                WorkManager.getInstance(this)
                    .cancelUniqueWork(EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME)
                VaultSession.lock()
            }
            if (response.contains("accounts")) {
                DatabaseHelper.wipeDatabase(this)
                KeyStoreHelper.wipeKeys(this)
            }

            if (response.contains("settings"))
                settings.clear(true)
        }

        finishAndRemoveTask()
    }

    companion object {
        /** The PanicKit action this activity answers to. */
        const val PANIC_TRIGGER_ACTION = "info.guardianproject.panic.action.TRIGGER"
    }
}
