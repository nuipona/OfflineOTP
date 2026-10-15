package org.shadowice.flocke.andotp.Dialogs;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.R;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Java dialog construction, real layout listeners and settings; no fake window or visible activity. */
@RunWith(AndroidJUnit4.class)
public class KotlinHideableDialogInteropTest {
    private static class RecordingDialog extends HideableDialog {
        int dismissals;
        RecordingDialog(Context context, int hideSettingId) {
            super(context, R.string.dialog_title_auto_time, R.string.dialog_msg_auto_time, hideSettingId);
        }
        @Override public void dismiss() { dismissals++; super.dismiss(); }
    }

    private Context context() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.AppTheme);
    }

    @Test public void checkboxPersistsImmediatelyAndOkDismissesWithoutChangingTheChoice() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            int keyId = R.string.settings_key_dialog_hide_auto_time;
            String key = context.getString(keyId);
            boolean hadValue = prefs.contains(key);
            boolean previous = prefs.getBoolean(key, false);
            RecordingDialog dialog = null;
            try {
                prefs.edit().remove(key).commit();
                dialog = new RecordingDialog(context, keyId);
                TextView content = dialog.findViewById(R.id.dialogContent);
                CheckBox checkbox = dialog.findViewById(R.id.dontShowAgain);
                Button ok = dialog.findViewById(R.id.buttonOk);
                assertNotNull(content);
                assertNotNull(checkbox);
                assertNotNull(ok);
                assertEquals(context.getString(R.string.dialog_msg_auto_time), content.getText().toString());
                assertFalse(checkbox.isChecked());
                checkbox.setChecked(true);
                assertTrue(prefs.getBoolean(key, false));
                checkbox.setChecked(false);
                assertFalse(prefs.getBoolean(key, true));
                checkbox.setChecked(true);
                ok.performClick();
                assertEquals(1, dialog.dismissals);
                assertTrue(prefs.getBoolean(key, false));
                // Suppressed show must not try to create a window with this non-activity context.
                HideableDialog.ShowHidableDialog(context, R.string.dialog_title_auto_time,
                        R.string.dialog_msg_auto_time, keyId);
                assertTrue(prefs.getBoolean(key, false));
            } finally {
                if (dialog != null) dialog.dismiss();
                SharedPreferences.Editor restore = prefs.edit();
                if (hadValue) restore.putBoolean(key, previous);
                else restore.remove(key);
                restore.commit();
            }
        });
    }

    @Test public void nonPositiveHideIdsNeverWriteSettings() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            for (int id : new int[] { 0, -1 }) {
                RecordingDialog dialog = new RecordingDialog(context, id);
                try {
                    Map<String, ?> before = new HashMap<>(prefs.getAll());
                    CheckBox checkbox = dialog.findViewById(R.id.dontShowAgain);
                    assertNotNull(checkbox);
                    checkbox.setChecked(true);
                    dialog.onCheckedChanged(null, false); // The originating button is not used.
                    assertEquals(before, prefs.getAll());
                } finally {
                    dialog.dismiss();
                }
            }
        });
    }

    @Test public void legacyShowHelperRemainsStaticWithTheOriginalJavaSpelling() throws Exception {
        assertTrue(Modifier.isStatic(HideableDialog.class.getMethod("ShowHidableDialog", Context.class,
                int.class, int.class, int.class).getModifiers()));
        HideableDialog.class.getConstructor(Context.class, int.class, int.class, int.class);
    }
}
