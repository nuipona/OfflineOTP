package org.shadowice.flocke.andotp.Preferences

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.R
import java.lang.reflect.Modifier

/** Subclass hooks, real AndroidKeyStore encryption and real password preference layout. */
@RunWith(AndroidJUnit4::class)
class KotlinPasswordEncryptedPreferenceInteropTest {
    private class RecordingPreference(context: Context) : PasswordEncryptedPreference(context, null) {
        var persisted: String? = null
        var restored: String? = null

        override fun persistString(value: String): Boolean {
            persisted = value
            return true
        }

        override fun getPersistedString(fallback: String): String = restored ?: fallback

        fun initialize(restore: Boolean, value: Any?) = onSetInitialValue(restore, value)

        fun closeDialog() = onDialogClosed(false)

        fun inflateAndBind(): View {
            val view = LayoutInflater.from(context).inflate(R.layout.component_password, null, false)
            onBindDialogView(view)
            return view
        }
    }

    private fun context(): Context = ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
        R.style.AppTheme
    )

    @Test
    fun valueRoundTripsThroughTheKeystoreWithoutPersistingPlaintext() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // The XML declares no default; first attachment must remain an empty, unwritten value.
            val fresh = RecordingPreference(context())
            fresh.initialize(false, null)
            assertNull(fresh.persisted)
            val empty = requireNotNull(fresh.inflateAndBind().findViewById<TextInputEditText>(R.id.passwordEdit))
            assertNotNull(empty)
            assertEquals("", empty.text.toString())

            val plain = "synthetic-backup-password"
            val writer = RecordingPreference(context())
            writer.initialize(false, plain)
            assertNotNull(writer.persisted)
            assertFalse(writer.persisted!!.isEmpty())
            assertNotEquals(plain, writer.persisted)

            val reader = RecordingPreference(context())
            reader.restored = writer.persisted
            reader.initialize(true, null)
            val view = reader.inflateAndBind()
            val password = requireNotNull(view.findViewById<TextInputEditText>(R.id.passwordEdit))
            val save = requireNotNull(view.findViewById<Button>(R.id.btnSave))
            assertNotNull(password)
            assertNotNull(save)
            assertEquals(plain, password.text.toString())
            assertFalse(password.isSaveEnabled)
            assertFalse(save.isEnabled)
        }
    }

    @Test
    fun passwordAndPinModesKeepTheirInputTypesAndMatchingRule() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (mode in PasswordEncryptedPreference.Mode.values()) {
                val preference = RecordingPreference(context())
                preference.initialize(false, "")
                preference.setMode(mode)
                val view = preference.inflateAndBind()
                val password = requireNotNull(view.findViewById<TextInputEditText>(R.id.passwordEdit))
                val confirm = requireNotNull(view.findViewById<EditText>(R.id.passwordConfirm))
                val save = requireNotNull(view.findViewById<Button>(R.id.btnSave))
                assertNotNull(password)
                assertNotNull(confirm)
                assertNotNull(save)
                assertFalse(password.isSaveEnabled)
                assertFalse(confirm.isSaveEnabled)

                val expectedClass = if (mode == PasswordEncryptedPreference.Mode.PASSWORD) {
                    InputType.TYPE_CLASS_TEXT
                } else {
                    InputType.TYPE_CLASS_NUMBER
                }
                assertEquals(expectedClass, password.inputType and InputType.TYPE_MASK_CLASS)
                assertEquals(expectedClass, confirm.inputType and InputType.TYPE_MASK_CLASS)
                assertFalse(save.isEnabled)
                password.setText("123456")
                assertFalse(save.isEnabled)
                confirm.setText("12345")
                assertFalse(save.isEnabled)
                confirm.setText("123456")
                assertTrue(save.isEnabled)
                preference.closeDialog()
                assertEquals("", password.text.toString())
                assertEquals("", confirm.text.toString())
            }
        }
    }

    @Test
    fun apiKeepsTheXmlConstructorEnumAndOverridableSetter() {
        PasswordEncryptedPreference::class.java.getConstructor(
            Context::class.java, android.util.AttributeSet::class.java
        )
        assertArrayEquals(
            arrayOf(
                PasswordEncryptedPreference.Mode.PASSWORD,
                PasswordEncryptedPreference.Mode.PIN
            ),
            PasswordEncryptedPreference.Mode.values()
        )
        assertFalse(Modifier.isFinal(PasswordEncryptedPreference::class.java.modifiers))
        assertFalse(
            Modifier.isFinal(
                PasswordEncryptedPreference::class.java.getDeclaredMethod(
                    "setMode", PasswordEncryptedPreference.Mode::class.java
                ).modifiers
            )
        )
    }
}
