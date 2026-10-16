package org.shadowice.flocke.andotp.Preferences;

import android.content.Context;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.textfield.TextInputEditText;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.R;

import java.lang.reflect.Modifier;

import static org.junit.Assert.*;

/** Java subclass hooks, real AndroidKeyStore encryption and real password preference layout. */
@RunWith(AndroidJUnit4.class)
public class KotlinPasswordEncryptedPreferenceInteropTest {
    private static class RecordingPreference extends PasswordEncryptedPreference {
        String persisted;
        String restored;

        RecordingPreference(Context context) {
            super(context, null);
        }

        @Override protected boolean persistString(String value) {
            persisted = value;
            return true;
        }

        @Override protected String getPersistedString(String fallback) {
            return restored == null ? fallback : restored;
        }

        void initialize(boolean restore, Object value) {
            onSetInitialValue(restore, value);
        }

        View inflateAndBind() {
            View view = LayoutInflater.from(getContext()).inflate(R.layout.component_password, null, false);
            onBindDialogView(view);
            return view;
        }
    }

    private Context context() {
        return new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.AppTheme);
    }

    @Test public void valueRoundTripsThroughTheKeystoreWithoutPersistingPlaintext() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // The XML declares no default; first attachment must remain an empty, unwritten value.
            RecordingPreference fresh = new RecordingPreference(context());
            fresh.initialize(false, null);
            assertNull(fresh.persisted);
            TextInputEditText empty = fresh.inflateAndBind().findViewById(R.id.passwordEdit);
            assertNotNull(empty);
            assertEquals("", empty.getText().toString());

            String plain = "synthetic-backup-password";
            RecordingPreference writer = new RecordingPreference(context());
            writer.initialize(false, plain);
            assertNotNull(writer.persisted);
            assertFalse(writer.persisted.isEmpty());
            assertNotEquals(plain, writer.persisted);

            RecordingPreference reader = new RecordingPreference(context());
            reader.restored = writer.persisted;
            reader.initialize(true, null);
            View view = reader.inflateAndBind();
            TextInputEditText password = view.findViewById(R.id.passwordEdit);
            Button save = view.findViewById(R.id.btnSave);
            assertNotNull(password);
            assertNotNull(save);
            assertEquals(plain, password.getText().toString());
            assertFalse(save.isEnabled());
        });
    }

    @Test public void passwordAndPinModesKeepTheirInputTypesAndMatchingRule() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (PasswordEncryptedPreference.Mode mode : PasswordEncryptedPreference.Mode.values()) {
                RecordingPreference preference = new RecordingPreference(context());
                preference.initialize(false, "");
                preference.setMode(mode);
                View view = preference.inflateAndBind();
                TextInputEditText password = view.findViewById(R.id.passwordEdit);
                EditText confirm = view.findViewById(R.id.passwordConfirm);
                Button save = view.findViewById(R.id.btnSave);
                assertNotNull(password);
                assertNotNull(confirm);
                assertNotNull(save);

                int expectedClass = mode == PasswordEncryptedPreference.Mode.PASSWORD
                        ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_NUMBER;
                assertEquals(expectedClass, password.getInputType() & InputType.TYPE_MASK_CLASS);
                assertEquals(expectedClass, confirm.getInputType() & InputType.TYPE_MASK_CLASS);
                assertFalse(save.isEnabled());
                password.setText("123456");
                assertFalse(save.isEnabled());
                confirm.setText("12345");
                assertFalse(save.isEnabled());
                confirm.setText("123456");
                assertTrue(save.isEnabled());
            }
        });
    }

    @Test public void JavaApiKeepsTheXmlConstructorEnumAndOverridableSetter() throws Exception {
        PasswordEncryptedPreference.class.getConstructor(Context.class, android.util.AttributeSet.class);
        assertArrayEquals(new PasswordEncryptedPreference.Mode[] {
                PasswordEncryptedPreference.Mode.PASSWORD, PasswordEncryptedPreference.Mode.PIN
        }, PasswordEncryptedPreference.Mode.values());
        assertFalse(Modifier.isFinal(PasswordEncryptedPreference.class.getModifiers()));
        assertFalse(Modifier.isFinal(PasswordEncryptedPreference.class
                .getDeclaredMethod("setMode", PasswordEncryptedPreference.Mode.class).getModifiers()));
    }
}
