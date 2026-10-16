package org.shadowice.flocke.andotp.Dialogs

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.R
import java.lang.reflect.Modifier
import java.util.HashMap

/** Dialog construction, real layout listeners and settings; no fake window or visible activity. */
@RunWith(AndroidJUnit4::class)
class KotlinHideableDialogInteropTest {
    private class RecordingDialog(context: Context, hideSettingId: Int) :
        HideableDialog(context, R.string.dialog_title_auto_time, R.string.dialog_msg_auto_time, hideSettingId) {
        var dismissals = 0

        override fun dismiss() {
            dismissals++
            super.dismiss()
        }
    }

    private fun context(): Context = ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
        R.style.AppTheme
    )

    @Test
    fun checkboxPersistsImmediatelyAndOkDismissesWithoutChangingTheChoice() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context()
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val keyId = R.string.settings_key_dialog_hide_auto_time
            val key = context.getString(keyId)
            val hadValue = prefs.contains(key)
            val previous = prefs.getBoolean(key, false)
            var dialog: RecordingDialog? = null
            try {
                prefs.edit().remove(key).commit()
                val currentDialog = RecordingDialog(context, keyId)
                dialog = currentDialog
                val content = currentDialog.findViewById<TextView>(R.id.dialogContent)
                val checkbox = currentDialog.findViewById<CheckBox>(R.id.dontShowAgain)
                val ok = currentDialog.findViewById<Button>(R.id.buttonOk)
                assertNotNull(content)
                assertNotNull(checkbox)
                assertNotNull(ok)
                assertEquals(context.getString(R.string.dialog_msg_auto_time), content.text.toString())
                assertFalse(checkbox.isChecked)
                checkbox.isChecked = true
                assertTrue(prefs.getBoolean(key, false))
                checkbox.isChecked = false
                assertFalse(prefs.getBoolean(key, true))
                checkbox.isChecked = true
                ok.performClick()
                assertEquals(1, currentDialog.dismissals)
                assertTrue(prefs.getBoolean(key, false))
                // Suppressed show must not try to create a window with this non-activity context.
                HideableDialog.ShowHidableDialog(
                    context, R.string.dialog_title_auto_time, R.string.dialog_msg_auto_time, keyId
                )
                assertTrue(prefs.getBoolean(key, false))
            } finally {
                dialog?.dismiss()
                val restore = prefs.edit()
                if (hadValue) restore.putBoolean(key, previous) else restore.remove(key)
                restore.commit()
            }
        }
    }

    @Test
    fun nonPositiveHideIdsNeverWriteSettings() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context()
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            for (id in intArrayOf(0, -1)) {
                val dialog = RecordingDialog(context, id)
                try {
                    val before: Map<String, *> = HashMap(prefs.all)
                    val checkbox = requireNotNull(dialog.findViewById<CheckBox>(R.id.dontShowAgain))
                    checkbox.isChecked = true
                    dialog.onCheckedChanged(checkbox, false)
                    assertEquals(before, prefs.all)
                } finally {
                    dialog.dismiss()
                }
            }
        }
    }

    @Test
    fun legacyShowHelperRemainsStaticWithTheOriginalSpelling() {
        assertTrue(
            Modifier.isStatic(
                HideableDialog::class.java.getMethod(
                    "ShowHidableDialog", Context::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ).modifiers
            )
        )
        HideableDialog::class.java.getConstructor(
            Context::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
    }
}
