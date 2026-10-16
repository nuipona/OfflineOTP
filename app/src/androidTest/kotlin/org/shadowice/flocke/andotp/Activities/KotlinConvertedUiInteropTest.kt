package org.shadowice.flocke.andotp.Activities

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask
import org.shadowice.flocke.andotp.Utilities.Constants
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.View.EntriesCardAdapter
import org.shadowice.flocke.andotp.View.EntryViewHolder
import org.shadowice.flocke.andotp.View.TagsAdapter
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap
import javax.crypto.SecretKey

/** Real Android characterization of the classes converted in the final UI batch. */
@RunWith(AndroidJUnit4::class)
class KotlinConvertedUiInteropTest {
    companion object {
        private const val ENTRY_SEED = "GEZDGNBVGY3TQOJQ"
    }

    private fun context(): Context = ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
        R.style.AppTheme
    )

    @Test
    fun entryCardBindsTheTokenLabelsTagsAndCounter() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context()
            val card = LayoutInflater.from(context)
                .inflate(R.layout.component_card_default, null, false)
            val holder = EntryViewHolder(context, card, false)

            val entry = Entry(
                Entry.OTPType.TOTP, ENTRY_SEED, 30, 6, "issuer", "label",
                TokenCalculator.HashAlgorithm.SHA1,
                ArrayList(Arrays.asList("tag1", "tag2"))
            )
            entry.updateOTP(true)
            holder.updateValues(entry)

            val value = requireNotNull(card.findViewById<TextView>(R.id.valueText))
            val label = requireNotNull(card.findViewById<TextView>(R.id.textViewLabel))
            val tags = requireNotNull(card.findViewById<TextView>(R.id.textViewTags))
            val counter = requireNotNull(card.findViewById<TextView>(R.id.counter))
            val counterLayout = requireNotNull(card.findViewById<View>(R.id.counterLayout))
            val valueLayout = requireNotNull(card.findViewById<View>(R.id.valueLayout))
            val coverLayout = requireNotNull(card.findViewById<View>(R.id.coverLayout))

            assertEquals(entry.getCurrentOTP(), value.tag)
            assertFalse(value.text.toString().isEmpty())
            assertTrue(label.text.toString().contains("issuer"))
            assertTrue(label.text.toString().contains("label"))
            assertEquals("tag1, tag2", tags.text.toString())
            assertEquals(View.GONE, counterLayout.visibility)
            assertEquals(View.VISIBLE, valueLayout.visibility)
            assertEquals(View.GONE, coverLayout.visibility)

            val hotp = Entry(
                Entry.OTPType.HOTP, ENTRY_SEED, 42L, 6, "issuer", "label",
                TokenCalculator.HashAlgorithm.SHA1,
                ArrayList(Arrays.asList("tag1", "tag2"))
            )
            holder.updateValues(hotp)
            assertEquals(View.VISIBLE, counterLayout.visibility)
            assertEquals("42", counter.text.toString())
        }
    }

    @Test
    fun cardAdapterKeepsItsVisibleMembers() {
        // Views and handlers must be created on the main thread.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context()
            val known = HashMap<String, Boolean?>()
            val adapter = EntriesCardAdapter(context, TagsAdapter(context, known))

            assertNull(adapter.encryptionKey)
            val key = javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES")
            adapter.encryptionKey = key
            assertSame(key, adapter.encryptionKey)

            assertNotNull(adapter.getEntries())
            assertEquals(0, adapter.getItemCount())
            assertNotNull(adapter.getFilter())
            assertTrue(adapter.hasStableIds())
            adapter.updateTagsFilter()
            adapter.filterByTags(emptyList())
            adapter.updateTimeBasedTokens()
            adapter.lockEntries()
            assertNull(adapter.encryptionKey)
        }
    }

    @Test
    fun mainActivityKeepsItsPublicStaticAnimatorDuration() {
        assertEquals(1000L, MainActivity.animatorDuration)
        val field = MainActivity::class.java.getField("animatorDuration")
        assertTrue(Modifier.isPublic(field.modifiers))
        assertTrue(Modifier.isStatic(field.modifiers))
        // Not final: MainActivity assigns it from the animator duration scale at runtime.
        assertFalse(Modifier.isFinal(field.modifiers))
        assertEquals(Long::class.javaPrimitiveType, field.type)
    }

    @Test
    fun credentialPreferenceKeepsItsXmlNameAndDefault() {
        assertEquals(
            "org.shadowice.flocke.andotp.Preferences.CredentialsPreference",
            Class.forName("org.shadowice.flocke.andotp.Preferences.CredentialsPreference").name
        )
        assertEquals(
            Constants.AuthMethod.NONE,
            org.shadowice.flocke.andotp.Preferences.CredentialsPreference.DEFAULT_VALUE
        )
        assertNotNull(
            org.shadowice.flocke.andotp.Preferences.CredentialsPreference::class.java.getConstructor(
                Context::class.java, android.util.AttributeSet::class.java
            )
        )
    }

    @Test
    fun manualEntryDialogStaysAStaticEntryPoint() {
        assertTrue(Modifier.isFinal(ManualEntryDialog::class.java.modifiers))
        val show = ManualEntryDialog::class.java.getMethod(
            "show", MainActivity::class.java, Settings::class.java, EntriesCardAdapter::class.java
        )
        assertTrue(Modifier.isStatic(show.modifiers))
        assertTrue(Modifier.isPublic(show.modifiers))
        assertNotNull(
            ManualEntryDialog::class.java.getMethod(
                "show", MainActivity::class.java, Settings::class.java,
                EntriesCardAdapter::class.java, Entry::class.java,
                ManualEntryDialog.UpdateCallback::class.java
            )
        )
        assertTrue(ManualEntryDialog.UpdateCallback::class.java.isInterface)
    }

    @Test
    fun settingsFragmentKeepsItsVisibleMembers() {
        val fragment = Class.forName(
            "org.shadowice.flocke.andotp.Activities.SettingsActivity\$SettingsFragment"
        )
        assertTrue(Modifier.isPublic(fragment.modifiers))
        assertTrue(Modifier.isStatic(fragment.modifiers))
        assertNotNull(fragment.getMethod("updateAutoBackup"))
        assertNotNull(fragment.getMethod("setEncryptionKey", SecretKey::class.java))
        assertNotNull(fragment.getMethod("encryptionChangeWithDialog", Constants.EncryptionType::class.java))
        val onCreate = fragment.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
        assertTrue(Modifier.isPublic(onCreate.modifiers))
    }

    @Test
    fun introWizardKeepsItsFourSlides() {
        for (name in arrayOf("EncryptionFragment", "AuthenticationFragment", "AndroidSyncFragment", "IntroFinishedFragment")) {
            val nested = Class.forName(
                "org.shadowice.flocke.andotp.Activities.IntroScreenActivity\$$name"
            )
            assertTrue(
                "$name must be a public static nested class",
                Modifier.isPublic(nested.modifiers) && Modifier.isStatic(nested.modifiers)
            )
        }
        val onSendActivityResult = IntroScreenActivity::class.java.getMethod(
            "onSendActivityResult", Int::class.javaPrimitiveType
        )
        assertEquals(Intent::class.java, onSendActivityResult.returnType)
    }

    @Test
    fun retainedTaskFragmentKeepsItsMembers() {
        val fragment = Class.forName(
            "org.shadowice.flocke.andotp.Activities.BackgroundTaskActivity\$TaskFragment"
        )
        assertTrue(Modifier.isPublic(fragment.modifiers))
        assertTrue(Modifier.isStatic(fragment.modifiers))
        assertNotNull(fragment.getMethod("startTask", UiBasedBackgroundTask::class.java))
        assertNotNull(fragment.getMethod("setOutcomeCallback", UiBasedBackgroundTask.OutcomeCallback::class.java))
        assertNotNull(fragment.getMethod("isCanceled"))
        assertNotNull(fragment.getMethod("cancelTask"))
    }

    @Test
    fun backupActivityKeepsItsVisibleMembers() {
        val onDestroy = BackupActivity::class.java.getDeclaredMethod("onDestroy")
        assertTrue(Modifier.isPublic(onDestroy.modifiers))
        assertNotNull(BackupActivity::class.java.getMethod("outputStreamToString", ByteArrayOutputStream::class.java))
        assertNotNull(
            BackupActivity::class.java.getMethod(
                "handleOpenPGPResult", Intent::class.java, ByteArrayOutputStream::class.java,
                android.net.Uri::class.java, Int::class.javaPrimitiveType
            )
        )
    }

    @Test
    fun authenticateActivityKeepsItsVisibleMembers() {
        val onBackPressed = AuthenticateActivity::class.java.getDeclaredMethod("onBackPressed")
        assertTrue(Modifier.isPublic(onBackPressed.modifiers))
    }
}
