package org.shadowice.flocke.andotp.Dialogs

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.View.TagsAdapter
import java.lang.reflect.Modifier
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicReference

/** Real Android widgets for the converted password dialog; no visible window. */
@RunWith(AndroidJUnit4::class)
class KotlinPasswordDialogsInteropTest {
    private class RecordingDialog(
        context: Context,
        mode: PasswordEntryDialog.Mode,
        blockAccessibility: Boolean,
        blockAutofill: Boolean,
        callback: PasswordEntryDialog.PasswordEnteredCallback?
    ) : PasswordEntryDialog(context, mode, blockAccessibility, blockAutofill, callback) {
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
    fun enterModeUsesTheMinimumLengthAndDeliversOnlyFromOk() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val delivered = AtomicReference<String?>()
            val dialog = RecordingDialog(
                context(), PasswordEntryDialog.Mode.ENTER, false, false,
                PasswordEntryDialog.PasswordEnteredCallback { delivered.set(it) }
            )
            try {
                val password = dialog.findViewById<TextInputEditText>(R.id.passwordInput)
                val confirm = dialog.findViewById<EditText>(R.id.passwordConfirm)
                val warning = dialog.findViewById<TextView>(R.id.tooShortWarning)
                val ok = dialog.findViewById<Button>(R.id.buttonOk)
                val cancel = dialog.findViewById<Button>(R.id.buttonCancel)
                assertNotNull(password)
                assertNotNull(confirm)
                assertNotNull(warning)
                assertNotNull(ok)
                assertNotNull(cancel)
                assertEquals(View.GONE, confirm.visibility)
                assertFalse(ok.isEnabled)

                password.setText(repeat('a', Constants.AUTH_MIN_PASSWORD_LENGTH - 1))
                assertEquals(View.VISIBLE, warning.visibility)
                assertFalse(ok.isEnabled)

                val accepted = repeat('b', Constants.AUTH_MIN_PASSWORD_LENGTH)
                password.setText(accepted)
                assertEquals(View.GONE, warning.visibility)
                assertTrue(ok.isEnabled)

                assertTrue(dialog.onEditorAction(password, EditorInfo.IME_ACTION_DONE, null))
                assertEquals(accepted, delivered.get())
                assertEquals(1, dialog.dismissals)
                assertTrue(
                    dialog.onEditorAction(
                        password, 0, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                    )
                )
                assertEquals(1, dialog.dismissals)

                delivered.set(null)
                cancel.performClick()
                assertNull(delivered.get())
                assertEquals(2, dialog.dismissals)
            } finally {
                dialog.dismiss()
            }
        }
    }

    @Test
    fun updateModeRequiresMatchingInputsAndAppliesPrivacyFlags() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val delivered = AtomicReference<String?>()
            val dialog = RecordingDialog(
                context(), PasswordEntryDialog.Mode.UPDATE, true, true,
                PasswordEntryDialog.PasswordEnteredCallback { delivered.set(it) }
            )
            try {
                val layout = dialog.findViewById<TextInputLayout>(R.id.passwordInputLayout)
                val password = dialog.findViewById<TextInputEditText>(R.id.passwordInput)
                val confirm = dialog.findViewById<EditText>(R.id.passwordConfirm)
                val ok = dialog.findViewById<Button>(R.id.buttonOk)
                assertNotNull(layout)
                assertNotNull(password)
                assertNotNull(confirm)
                assertNotNull(ok)
                assertEquals(View.VISIBLE, confirm.visibility)
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    layout.importantForAccessibility
                )
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, confirm.importantForAccessibility)
                assertEquals(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                    layout.importantForAutofill
                )
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, confirm.importantForAutofill)

                val passwordText = repeat('c', Constants.AUTH_MIN_PASSWORD_LENGTH)
                password.setText(passwordText)
                confirm.setText(passwordText + "x")
                assertFalse(ok.isEnabled)
                confirm.setText(passwordText)
                assertTrue(ok.isEnabled)
                ok.performClick()
                assertEquals(passwordText, delivered.get())
                assertEquals(1, dialog.dismissals)
            } finally {
                dialog.dismiss()
            }
        }
    }

    @Test
    fun apiKeepsTheConstructorEnumsListenersAndStaticTagEntryPoint() {
        PasswordEntryDialog::class.java.getConstructor(
            Context::class.java,
            PasswordEntryDialog.Mode::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            PasswordEntryDialog.PasswordEnteredCallback::class.java
        )
        assertArrayEquals(
            arrayOf(PasswordEntryDialog.Mode.ENTER, PasswordEntryDialog.Mode.UPDATE),
            PasswordEntryDialog.Mode.values()
        )
        PasswordEntryDialog.PasswordEnteredCallback::class.java.getMethod(
            "onPasswordEntered", String::class.java
        )
        assertTrue(View.OnClickListener::class.java.isAssignableFrom(PasswordEntryDialog::class.java))
        assertTrue(
            Modifier.isStatic(
                TagsDialog::class.java.getMethod(
                    "show", Context::class.java, TagsAdapter::class.java,
                    Callable::class.java, Callable::class.java
                ).modifiers
            )
        )
    }

    private fun repeat(value: Char, count: Int): String = value.toString().repeat(count)
}
