package org.shadowice.flocke.andotp.Preferences

import android.content.Context
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.util.AttributeSet
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.NumberPicker
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.R
import org.xmlpull.v1.XmlPullParser
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Subclass hooks and real picker/XML/summary behavior. Persistence is recorded, not disk I/O. */
@RunWith(AndroidJUnit4::class)
class KotlinNumberPickerPreferenceInteropTest {
    private fun context(): Context = ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
        R.style.AppTheme
    )

    private class TestPreference : NumberPickerPreference {
        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
        constructor(context: Context, attrs: AttributeSet?, style: Int) : super(context, attrs, style)

        val writes = ArrayList<Int>()
        var restored: Int? = null
        var persistedFallback = 0

        override fun persistInt(value: Int): Boolean {
            writes.add(value)
            return true
        }

        override fun getPersistedInt(fallback: Int): Int {
            persistedFallback = fallback
            return restored ?: fallback
        }

        fun createView(): View = onCreateDialogView()
        fun bind(view: View) = onBindDialogView(view)
        fun close(positive: Boolean) = onDialogClosed(positive)
        fun initialize(restore: Boolean, value: Any?) = onSetInitialValue(restore, value)
        fun defaultValue(a: TypedArray, index: Int): Any = onGetDefaultValue(a, index)
    }

    @Test
    fun defaultBoundsAndBothConstructorsRemainUsable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context()
            val pref = TestPreference(context, null)
            TestPreference(context, null, android.R.attr.dialogPreferenceStyle)
            assertEquals(0, NumberPickerPreference.DEFAULT_MIN_VALUE)
            assertEquals(100, NumberPickerPreference.DEFAULT_MAX_VALUE)
            assertTrue(NumberPickerPreference.DEFAULT_WRAP_SELECTOR_WHEEL)
            pref.setValue(12)
            val root = pref.createView() as FrameLayout
            pref.bind(root)
            assertEquals(1, root.childCount)
            val picker = root.getChildAt(0) as NumberPicker
            assertEquals(0, picker.minValue)
            assertEquals(100, picker.maxValue)
            assertTrue(picker.wrapSelectorWheel)
            assertEquals(12, picker.value)
            val params = picker.layoutParams as FrameLayout.LayoutParams
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.width)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.height)
            assertEquals(Gravity.CENTER, params.gravity)
            assertEquals("12", pref.summary.toString())
        }
    }

    @Test
    fun actualPreferenceXmlAttributesStillConfigureThePicker() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context()
            val xml: XmlResourceParser = context.resources.getXml(R.xml.preferences)
            try {
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG &&
                        xml.name == NumberPickerPreference::class.java.name
                    ) {
                        val attrs = Xml.asAttributeSet(xml)
                        val values = context.obtainStyledAttributes(attrs, R.styleable.NumberPickerPreference)
                        val min: Int
                        val max: Int
                        val wrap: Boolean
                        try {
                            min = values.getInteger(R.styleable.NumberPickerPreference_minValue, -1)
                            max = values.getInteger(R.styleable.NumberPickerPreference_maxValue, -1)
                            wrap = values.getBoolean(R.styleable.NumberPickerPreference_wrapSelectorWheel, true)
                        } finally {
                            values.recycle()
                        }
                        val pref = TestPreference(context, attrs)
                        pref.setValue(min)
                        val root = pref.createView() as FrameLayout
                        pref.bind(root)
                        val picker = root.getChildAt(0) as NumberPicker
                        assertEquals(min, picker.minValue)
                        assertEquals(max, picker.maxValue)
                        assertEquals(wrap, picker.wrapSelectorWheel)
                        return@runOnMainSync
                    }
                }
                fail("No NumberPickerPreference found in application XML")
            } catch (e: Exception) {
                throw AssertionError(e)
            } finally {
                xml.close()
            }
        }
    }

    @Test
    fun cancelAndListenerVetoLeaveValueSummaryAndRecordedPersistenceUnchanged() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val pref = TestPreference(context(), null)
            pref.setValue(5)
            val root = pref.createView() as FrameLayout
            pref.bind(root)
            val picker = root.getChildAt(0) as NumberPicker
            val proposals = ArrayList<Int>()
            val allow = AtomicBoolean(false)
            pref.onPreferenceChangeListener =
                android.preference.Preference.OnPreferenceChangeListener { _, value ->
                    proposals.add(value as Int)
                    allow.get()
                }
            val writesBefore = pref.writes.size
            picker.value = 7
            pref.close(false)
            assertTrue(proposals.isEmpty())
            assertEquals(writesBefore, pref.writes.size)
            assertEquals(5, pref.getValue())
            pref.close(true)
            assertEquals(7, proposals[0])
            assertEquals(5, pref.getValue())
            assertEquals("5", pref.summary.toString())
            assertEquals(writesBefore, pref.writes.size)
            allow.set(true)
            pref.close(true)
            assertEquals(7, pref.getValue())
            assertEquals("7", pref.summary.toString())
            assertEquals(7, pref.writes.last())
        }
    }

    @Test
    fun restoreAndDefaultPathsKeepTheirProtectedHooks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context()
            val pref = TestPreference(context, null)
            val absent = context.obtainStyledAttributes(null, R.styleable.NumberPickerPreference)
            try {
                assertEquals(0, pref.defaultValue(absent, R.styleable.NumberPickerPreference_minValue))
            } finally {
                absent.recycle()
            }
            pref.initialize(false, 8)
            assertEquals(8, pref.getValue())
            assertEquals("8", pref.summary.toString())
            pref.restored = 19
            pref.initialize(true, null)
            assertEquals(19, pref.getValue())
            assertEquals(0, pref.persistedFallback)
            pref.restored = null
            pref.initialize(true, 99)
            assertEquals(0, pref.getValue())
            assertEquals("0", pref.summary.toString())
        }
    }
}
