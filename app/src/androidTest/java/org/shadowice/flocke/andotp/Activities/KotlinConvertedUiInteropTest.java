package org.shadowice.flocke.andotp.Activities;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.TokenCalculator;
import org.shadowice.flocke.andotp.View.EntriesCardAdapter;
import org.shadowice.flocke.andotp.View.EntryViewHolder;
import org.shadowice.flocke.andotp.View.TagsAdapter;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import javax.crypto.SecretKey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The Java callers of the classes that were converted last, on the real Android runtime.
 *
 * The desktop sandbox cannot load the Java-only {@code Utilities/Constants}, so the members that
 * mention one of its types are asserted here instead. This is a device test, not a sandbox check.
 */
@RunWith(AndroidJUnit4.class)
public class KotlinConvertedUiInteropTest {
    private static final String ENTRY_SEED = "GEZDGNBVGY3TQOJQ";

    private Context context() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.AppTheme);
    }

    @Test public void theEntryCardBindsTheTokenTheLabelsTheTagsAndTheCounter() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            View card = LayoutInflater.from(context).inflate(R.layout.component_card_default, null, false);
            EntryViewHolder holder = new EntryViewHolder(context, card, false);

            Entry entry = new Entry(Entry.OTPType.TOTP, ENTRY_SEED, 30, 6, "issuer", "label",
                    TokenCalculator.HashAlgorithm.SHA1, new ArrayList<>(Arrays.asList("tag1", "tag2")));
            entry.updateOTP(true);
            holder.updateValues(entry);

            TextView value = card.findViewById(R.id.valueText);
            TextView label = card.findViewById(R.id.textViewLabel);
            TextView tags = card.findViewById(R.id.textViewTags);
            TextView counter = card.findViewById(R.id.counter);
            View counterLayout = card.findViewById(R.id.counterLayout);
            View valueLayout = card.findViewById(R.id.valueLayout);
            View coverLayout = card.findViewById(R.id.coverLayout);

            // The unformatted token stays in the view tag, so the copy action has the raw value.
            assertEquals(entry.getCurrentOTP(), value.getTag());
            assertFalse(value.getText().toString().isEmpty());
            // The issuer and the label end up in one line, the tags in a comma separated list.
            assertTrue(label.getText().toString().contains("issuer"));
            assertTrue(label.getText().toString().contains("label"));
            assertEquals("tag1, tag2", tags.getText().toString());
            // A time based entry with the default period has no counter.
            assertEquals(View.GONE, counterLayout.getVisibility());
            // Without tap to reveal the token is shown and the cover is not.
            assertEquals(View.VISIBLE, valueLayout.getVisibility());
            assertEquals(View.GONE, coverLayout.getVisibility());

            // A counter based entry shows the counter instead.
            Entry hotp = new Entry(Entry.OTPType.HOTP, ENTRY_SEED, 42L, 6, "issuer", "label",
                    TokenCalculator.HashAlgorithm.SHA1, new ArrayList<>(Arrays.asList("tag1", "tag2")));
            holder.updateValues(hotp);
            assertEquals(View.VISIBLE, counterLayout.getVisibility());
            assertEquals("42", counter.getText().toString());
        });
    }

    @Test public void theCardAdapterKeepsItsJavaVisibleMembers() throws Exception {
        Context context = context();
        HashMap<String, Boolean> known = new HashMap<>();
        EntriesCardAdapter adapter = new EntriesCardAdapter(context, new TagsAdapter(context, known));

        // The Java getEncryptionKey()/setEncryptionKey() pair is the read/write key property.
        assertNull(adapter.getEncryptionKey());
        SecretKey key = new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
        adapter.setEncryptionKey(key);
        assertSame(key, adapter.getEncryptionKey());

        assertNotNull(adapter.getEntries());
        assertEquals(0, adapter.getItemCount());
        assertNotNull(adapter.getFilter());
        assertTrue(adapter.hasStableIds());
        adapter.updateTagsFilter();
        adapter.filterByTags(new ArrayList<>());
        adapter.updateTimeBasedTokens();
        adapter.lockEntries();
        assertNull(adapter.getEncryptionKey());
    }

    @Test public void theMainActivityKeepsItsPublicStaticAnimatorDuration() throws Exception {
        assertEquals(1000L, MainActivity.animatorDuration);
        Field field = MainActivity.class.getField("animatorDuration");
        assertTrue(Modifier.isPublic(field.getModifiers()));
        assertTrue(Modifier.isStatic(field.getModifiers()));
        assertTrue(Modifier.isFinal(field.getModifiers()));
        assertEquals(long.class, field.getType());
    }

    @Test public void theCredentialPreferenceKeepsItsXmlNameAndItsDefault() throws Exception {
        // The preference XML instantiates the class by name, so the name and the default stay.
        assertEquals("org.shadowice.flocke.andotp.Preferences.CredentialsPreference",
                Class.forName("org.shadowice.flocke.andotp.Preferences.CredentialsPreference").getName());
        assertEquals(Constants.AuthMethod.NONE,
                org.shadowice.flocke.andotp.Preferences.CredentialsPreference.DEFAULT_VALUE);
        assertNotNull(org.shadowice.flocke.andotp.Preferences.CredentialsPreference.class
                .getConstructor(Context.class, android.util.AttributeSet.class));
    }

    @Test public void theManualEntryDialogStaysAStaticEntryPoint() throws Exception {
        assertTrue(Modifier.isFinal(org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog.class.getModifiers()));
        Method show = org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog.class.getMethod("show",
                MainActivity.class, org.shadowice.flocke.andotp.Utilities.Settings.class,
                EntriesCardAdapter.class);
        assertTrue(Modifier.isStatic(show.getModifiers()));
        assertTrue(Modifier.isPublic(show.getModifiers()));
        assertNotNull(org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog.class.getMethod("show",
                MainActivity.class, org.shadowice.flocke.andotp.Utilities.Settings.class,
                EntriesCardAdapter.class, Entry.class,
                org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog.UpdateCallback.class));
        assertTrue(org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog.UpdateCallback.class.isInterface());
    }

    @Test public void theSettingsFragmentKeepsItsJavaVisibleMembers() throws Exception {
        Class<?> fragment = Class.forName(
                "org.shadowice.flocke.andotp.Activities.SettingsActivity$SettingsFragment");
        assertTrue(Modifier.isPublic(fragment.getModifiers()));
        assertTrue(Modifier.isStatic(fragment.getModifiers()));
        assertNotNull(fragment.getMethod("updateAutoBackup"));
        assertNotNull(fragment.getMethod("setEncryptionKey", SecretKey.class));
        assertNotNull(fragment.getMethod("encryptionChangeWithDialog", Constants.EncryptionType.class));
        // The Java class widened the platform fragment's protected onCreate() to public.
        Method onCreate = fragment.getDeclaredMethod("onCreate", android.os.Bundle.class);
        assertTrue(Modifier.isPublic(onCreate.getModifiers()));
    }

    @Test public void theIntroWizardKeepsItsFourSlides() throws Exception {
        for (String name : new String[]{"EncryptionFragment", "AuthenticationFragment", "AndroidSyncFragment",
                "IntroFinishedFragment"}) {
            Class<?> nested = Class.forName(
                    "org.shadowice.flocke.andotp.Activities.IntroScreenActivity$" + name);
            assertTrue(name + " must be a public static nested class",
                    Modifier.isPublic(nested.getModifiers()) && Modifier.isStatic(nested.getModifiers()));
        }
        // The result of the wizard carries the setup flag.
        Method onSendActivityResult = IntroScreenActivity.class.getMethod("onSendActivityResult", int.class);
        assertEquals(Intent.class, onSendActivityResult.getReturnType());
    }

    @Test public void theRetainedTaskFragmentKeepsItsMembers() throws Exception {
        Class<?> fragment = Class.forName(
                "org.shadowice.flocke.andotp.Activities.BackgroundTaskActivity$TaskFragment");
        assertTrue(Modifier.isPublic(fragment.getModifiers()));
        assertTrue(Modifier.isStatic(fragment.getModifiers()));
        assertNotNull(fragment.getMethod("startTask", UiBasedBackgroundTask.class));
        assertNotNull(fragment.getMethod("setOutcomeCallback", UiBasedBackgroundTask.OutcomeCallback.class));
        assertNotNull(fragment.getMethod("isCanceled"));
        assertNotNull(fragment.getMethod("cancelTask"));
    }

    @Test public void theBackupActivityKeepsItsJavaVisibleMembers() throws Exception {
        // The Java class widened the platform's protected onDestroy() to public.
        Method onDestroy = BackupActivity.class.getDeclaredMethod("onDestroy");
        assertTrue(Modifier.isPublic(onDestroy.getModifiers()));
        assertNotNull(BackupActivity.class.getMethod("outputStreamToString", ByteArrayOutputStream.class));
        assertNotNull(BackupActivity.class.getMethod("handleOpenPGPResult", Intent.class,
                ByteArrayOutputStream.class, android.net.Uri.class, int.class));
    }

    @Test public void theAuthenticateActivityKeepsItsJavaVisibleMembers() throws Exception {
        Method onBackPressed = AuthenticateActivity.class.getDeclaredMethod("onBackPressed");
        assertTrue(Modifier.isPublic(onBackPressed.getModifiers()));
    }
}
