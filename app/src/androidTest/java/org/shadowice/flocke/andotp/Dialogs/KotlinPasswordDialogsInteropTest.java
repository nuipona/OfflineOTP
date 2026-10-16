package org.shadowice.flocke.andotp.Dialogs;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.View.TagsAdapter;

import java.lang.reflect.Modifier;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Java callers plus real Android widgets for the converted password dialog; no visible window. */
@RunWith(AndroidJUnit4.class)
public class KotlinPasswordDialogsInteropTest {
    private static class RecordingDialog extends PasswordEntryDialog {
        int dismissals;

        RecordingDialog(Context context, Mode mode, boolean blockAccessibility, boolean blockAutofill,
                        PasswordEnteredCallback callback) {
            super(context, mode, blockAccessibility, blockAutofill, callback);
        }

        @Override public void dismiss() {
            dismissals++;
            super.dismiss();
        }
    }

    private Context context() {
        return new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.AppTheme);
    }

    @Test public void enterModeUsesTheMinimumLengthAndDeliversOnlyFromOk() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AtomicReference<String> delivered = new AtomicReference<>();
            RecordingDialog dialog = new RecordingDialog(context(), PasswordEntryDialog.Mode.ENTER,
                    false, false, delivered::set);
            try {
                TextInputEditText password = dialog.findViewById(R.id.passwordInput);
                EditText confirm = dialog.findViewById(R.id.passwordConfirm);
                TextView warning = dialog.findViewById(R.id.tooShortWarning);
                Button ok = dialog.findViewById(R.id.buttonOk);
                Button cancel = dialog.findViewById(R.id.buttonCancel);
                assertNotNull(password);
                assertNotNull(confirm);
                assertNotNull(warning);
                assertNotNull(ok);
                assertNotNull(cancel);
                assertEquals(View.GONE, confirm.getVisibility());
                assertFalse(ok.isEnabled());

                password.setText(repeat('a', Constants.AUTH_MIN_PASSWORD_LENGTH - 1));
                assertEquals(View.VISIBLE, warning.getVisibility());
                assertFalse(ok.isEnabled());

                String accepted = repeat('b', Constants.AUTH_MIN_PASSWORD_LENGTH);
                password.setText(accepted);
                assertEquals(View.GONE, warning.getVisibility());
                assertTrue(ok.isEnabled());

                assertTrue(dialog.onEditorAction(password, EditorInfo.IME_ACTION_DONE, null));
                assertEquals(accepted, delivered.get());
                assertEquals(1, dialog.dismissals);
                assertTrue(dialog.onEditorAction(password, 0,
                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)));
                assertEquals(1, dialog.dismissals);

                delivered.set(null);
                cancel.performClick();
                assertNull(delivered.get());
                assertEquals(2, dialog.dismissals);
            } finally {
                dialog.dismiss();
            }
        });
    }

    @Test public void updateModeRequiresMatchingInputsAndAppliesPrivacyFlags() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AtomicReference<String> delivered = new AtomicReference<>();
            RecordingDialog dialog = new RecordingDialog(context(), PasswordEntryDialog.Mode.UPDATE,
                    true, true, delivered::set);
            try {
                TextInputLayout layout = dialog.findViewById(R.id.passwordInputLayout);
                TextInputEditText password = dialog.findViewById(R.id.passwordInput);
                EditText confirm = dialog.findViewById(R.id.passwordConfirm);
                Button ok = dialog.findViewById(R.id.buttonOk);
                assertNotNull(layout);
                assertNotNull(password);
                assertNotNull(confirm);
                assertNotNull(ok);
                assertEquals(View.VISIBLE, confirm.getVisibility());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                        layout.getImportantForAccessibility());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        confirm.getImportantForAccessibility());
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                        layout.getImportantForAutofill());
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO,
                        confirm.getImportantForAutofill());

                String passwordText = repeat('c', Constants.AUTH_MIN_PASSWORD_LENGTH);
                password.setText(passwordText);
                confirm.setText(passwordText + "x");
                assertFalse(ok.isEnabled());
                confirm.setText(passwordText);
                assertTrue(ok.isEnabled());
                ok.performClick();
                assertEquals(passwordText, delivered.get());
                assertEquals(1, dialog.dismissals);
            } finally {
                dialog.dismiss();
            }
        });
    }

    @Test public void JavaApiKeepsTheConstructorEnumsListenersAndStaticTagEntryPoint() throws Exception {
        PasswordEntryDialog.class.getConstructor(Context.class, PasswordEntryDialog.Mode.class,
                boolean.class, boolean.class, PasswordEntryDialog.PasswordEnteredCallback.class);
        assertArrayEquals(new PasswordEntryDialog.Mode[] {
                PasswordEntryDialog.Mode.ENTER, PasswordEntryDialog.Mode.UPDATE
        }, PasswordEntryDialog.Mode.values());
        PasswordEntryDialog.PasswordEnteredCallback.class.getMethod("onPasswordEntered", String.class);
        assertTrue(View.OnClickListener.class.isAssignableFrom(PasswordEntryDialog.class));
        assertTrue(Modifier.isStatic(TagsDialog.class.getMethod("show", Context.class,
                TagsAdapter.class, Callable.class, Callable.class).getModifiers()));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
