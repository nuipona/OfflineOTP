package org.shadowice.flocke.andotp.View

import android.content.Context
import android.database.DataSetObserver
import android.view.LayoutInflater
import android.view.View
import android.view.autofill.AutofillValue
import android.widget.AbsListView
import android.widget.CheckedTextView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.EntryThumbnail.EntryThumbnails
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.View.AutoFillable.AutoFillableTextInputEditText
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Real Android adapters/widgets and their Kotlin-facing callers. */
@RunWith(AndroidJUnit4::class)
class KotlinSmallAdaptersInteropTest {
    private fun context(): Context = ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
        R.style.AppTheme
    )

    @Test
    fun tagsKeepSortedRowsBorrowedStateAndObserverNotifications() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context()
            val states = HashMap<String, Boolean?>()
            states["zeta"] = false
            states["Alpha"] = true
            states["alpha"] = false
            val adapter = TagsAdapter(context, states)
            assertEquals(Arrays.asList("Alpha", "alpha", "zeta"), adapter.getTags())
            assertEquals(Arrays.asList("Alpha"), adapter.getActiveTags())
            val changes = AtomicInteger()
            adapter.registerDataSetObserver(object : DataSetObserver() {
                override fun onChanged() {
                    changes.incrementAndGet()
                }
            })
            adapter.setTagState("unknown", true)
            assertFalse(states.containsKey("unknown"))
            assertEquals(false, adapter.getTagState("unknown"))
            assertTrue(changes.get() > 0)
            adapter.setTagState("zeta", true)
            assertEquals(true, states["zeta"])
            states["alpha"] = true
            assertTrue(adapter.allTagsActive())
            adapter.getTagsWithState()["Alpha"] = false
            assertEquals(true, adapter.getTagState("Alpha"))

            val parent = FrameLayout(context)
            val row = adapter.getView(0, null, parent) as CheckedTextView
            assertEquals("Alpha", row.text.toString())
            assertTrue(row.isChecked)
            assertSame(row, adapter.getView(2, row, parent))
            assertEquals("zeta", row.text.toString())

            val replacement = HashMap<String, Boolean?>()
            replacement["two"] = false
            replacement["one"] = true
            adapter.setTags(replacement)
            assertEquals(2, adapter.count)
            assertEquals("one", adapter.getItem(0))
            assertEquals(Arrays.asList("one", "two"), adapter.getTags())
            adapter.setTagState("two", true)
            assertEquals(true, replacement["two"])
            adapter.setTagState("one", null)
            assertNull(adapter.getTagState("one"))
            adapter.setTags(HashMap())
            assertTrue(adapter.allTagsActive())
            assertTrue(adapter.getActiveTags().isEmpty())
        }
    }

    @Test
    fun staticTagFactoryDeDuplicatesAndUsesRememberedStates() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val settings = object : Settings(context()) {
                override fun getTagToggle(tag: String): Boolean = tag != "disabled"
            }
            val first = Entry()
            val second = Entry()
            try {
                first.setTags(ArrayList(Arrays.asList("shared", "disabled")))
                second.setTags(ArrayList(Arrays.asList("shared", "other")))
                val tags = TagsAdapter.createTagsMap(
                    ArrayList(Arrays.asList(first, second)), settings
                )
                assertEquals(3, tags.size)
                assertEquals(true, tags["shared"])
                assertEquals(true, tags["other"])
                assertEquals(false, tags["disabled"])
            } finally {
                first.destroy()
                second.destroy()
            }
        }
    }

    @Test
    fun thumbnailFiltersKeepLocaleSemanticsOrdinalsAndIndexFallback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val original = Locale.getDefault()
            try {
                val adapter = ThumbnailSelectionAdapter(context(), "Example", "Example")
                for (locale in arrayOf(Locale.US, Locale("tr", "TR"))) {
                    Locale.setDefault(locale)
                    for (filter in arrayOf("", "gIt", "I", "no such thumbnail")) {
                        adapter.filter(filter)
                        val expected = ArrayList<EntryThumbnails>()
                        for (thumbnail in EntryThumbnails.values()) {
                            if (thumbnail.name.lowercase(locale).contains(filter.lowercase(locale))) {
                                expected.add(thumbnail)
                            }
                        }
                        assertEquals(expected.size, adapter.count)
                        for (i in expected.indices) {
                            assertSame(expected[i], adapter.getItem(i))
                            assertEquals(expected[i].ordinal, adapter.getRealIndex(i))
                            assertEquals(expected[i].ordinal.toLong(), adapter.getItemId(i))
                        }
                        assertSame(EntryThumbnails.Default, adapter.getItem(adapter.count))
                        assertEquals(0, adapter.getRealIndex(Int.MAX_VALUE))
                        try {
                            adapter.getItem(-1)
                            fail("Negative positions must still be rejected")
                        } catch (_: IndexOutOfBoundsException) {
                        }
                    }
                }
            } finally {
                Locale.setDefault(original)
            }
        }
    }

    @Test
    fun thumbnailViewsKeepSizeDescriptionsAndRecycling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context()
            val adapter = ThumbnailSelectionAdapter(context, "Example", "Example")
            val parent = GridView(context)
            adapter.filter("GitHub")
            val image = adapter.getView(0, null, parent) as ImageView
            val size = Settings(context).getThumbnailSize()
            assertEquals(size, image.layoutParams.width)
            assertEquals(size, image.layoutParams.height)
            assertEquals("GitHub", image.contentDescription.toString())
            assertNotNull(image.drawable)
            image.layoutParams = AbsListView.LayoutParams(7, 9)
            adapter.filter("Google")
            assertSame(image, adapter.getView(0, image, parent))
            assertEquals("Google", image.contentDescription.toString())
            assertEquals(7, image.layoutParams.width) // Recycled cells are not resized.
        }
    }

    @Test
    fun autofillXmlConstructorsAndSamDelegateBeforeNotification() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context()
            AutoFillableTextInputEditText(context)
            AutoFillableTextInputEditText(context, null)
            AutoFillableTextInputEditText(context, null, androidx.appcompat.R.attr.editTextStyle)
            val layout = LayoutInflater.from(context).inflate(R.layout.content_authenticate, null, false)
            val field = requireNotNull(layout.findViewById<AutoFillableTextInputEditText>(R.id.passwordEdit))
            assertNotNull(field)
            val callbacks = AtomicInteger()
            field.setAutoFillTextListener { text ->
                assertEquals("autofill-preview", text.toString())
                assertEquals(text.toString(), field.text.toString())
                callbacks.incrementAndGet()
            }
            field.autofill(AutofillValue.forText("autofill-preview"))
            assertEquals(1, callbacks.get())
            field.autofill(AutofillValue.forToggle(true))
            assertEquals(1, callbacks.get())
            field.setAutoFillTextListener(null)
            field.autofill(AutofillValue.forText("replacement"))
            assertEquals("replacement", field.text.toString())
            assertEquals(1, callbacks.get())
        }
    }

    @Test
    fun tagMethodsKeepBoxedSignatures() {
        assertEquals(Boolean::class.java, TagsAdapter::class.java.getMethod("getTagState", String::class.java).returnType)
        TagsAdapter::class.java.getMethod("setTagState", String::class.java, Boolean::class.java)
        assertEquals(HashMap::class.java, TagsAdapter::class.java.getMethod("getTagsWithState").returnType)
    }
}
