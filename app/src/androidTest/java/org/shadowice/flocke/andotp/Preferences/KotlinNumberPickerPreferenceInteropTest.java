package org.shadowice.flocke.andotp.Preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.NumberPicker;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.R;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Java subclass hooks and real picker/XML/summary behavior. Persistence is recorded, not disk I/O. */
@RunWith(AndroidJUnit4.class)
public class KotlinNumberPickerPreferenceInteropTest {
    private Context context() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.AppTheme);
    }

    private static class TestPreference extends NumberPickerPreference {
        final ArrayList<Integer> writes = new ArrayList<>();
        Integer restored;
        int persistedFallback;

        TestPreference(Context context, AttributeSet attrs) { super(context, attrs); }
        TestPreference(Context context, AttributeSet attrs, int style) { super(context, attrs, style); }

        @Override protected boolean persistInt(int value) { writes.add(value); return true; }
        @Override protected int getPersistedInt(int fallback) {
            persistedFallback = fallback;
            return restored == null ? fallback : restored;
        }
        View createView() { return onCreateDialogView(); }
        void bind(View view) { onBindDialogView(view); }
        void close(boolean positive) { onDialogClosed(positive); }
        void initialize(boolean restore, Object value) { onSetInitialValue(restore, value); }
        Object defaultValue(TypedArray a, int index) { return onGetDefaultValue(a, index); }
    }

    @Test public void defaultBoundsAndBothJavaConstructorsRemainUsable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            TestPreference pref = new TestPreference(context, null);
            new TestPreference(context, null, android.R.attr.dialogPreferenceStyle);
            assertEquals(0, NumberPickerPreference.DEFAULT_MIN_VALUE);
            assertEquals(100, NumberPickerPreference.DEFAULT_MAX_VALUE);
            assertTrue(NumberPickerPreference.DEFAULT_WRAP_SELECTOR_WHEEL);
            pref.setValue(12);
            FrameLayout root = (FrameLayout) pref.createView();
            pref.bind(root);
            assertEquals(1, root.getChildCount());
            NumberPicker picker = (NumberPicker) root.getChildAt(0);
            assertEquals(0, picker.getMinValue());
            assertEquals(100, picker.getMaxValue());
            assertTrue(picker.getWrapSelectorWheel());
            assertEquals(12, picker.getValue());
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) picker.getLayoutParams();
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.width);
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.height);
            assertEquals(Gravity.CENTER, params.gravity);
            assertEquals("12", pref.getSummary().toString());
        });
    }

    @Test public void actualPreferenceXmlAttributesStillConfigureThePicker() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            XmlResourceParser xml = context.getResources().getXml(R.xml.preferences);
            try {
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.getEventType() == XmlPullParser.START_TAG
                            && xml.getName().equals(NumberPickerPreference.class.getName())) {
                        AttributeSet attrs = Xml.asAttributeSet(xml);
                        TypedArray values = context.obtainStyledAttributes(attrs, R.styleable.NumberPickerPreference);
                        int min, max;
                        boolean wrap;
                        try {
                            min = values.getInteger(R.styleable.NumberPickerPreference_minValue, -1);
                            max = values.getInteger(R.styleable.NumberPickerPreference_maxValue, -1);
                            wrap = values.getBoolean(R.styleable.NumberPickerPreference_wrapSelectorWheel, true);
                        } finally {
                            values.recycle();
                        }
                        TestPreference pref = new TestPreference(context, attrs);
                        pref.setValue(min);
                        FrameLayout root = (FrameLayout) pref.createView();
                        pref.bind(root);
                        NumberPicker picker = (NumberPicker) root.getChildAt(0);
                        assertEquals(min, picker.getMinValue());
                        assertEquals(max, picker.getMaxValue());
                        assertEquals(wrap, picker.getWrapSelectorWheel());
                        return;
                    }
                }
                fail("No NumberPickerPreference found in application XML");
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                xml.close();
            }
        });
    }

    @Test public void cancelAndListenerVetoLeaveValueSummaryAndRecordedPersistenceUnchanged() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            TestPreference pref = new TestPreference(context(), null);
            pref.setValue(5);
            FrameLayout root = (FrameLayout) pref.createView();
            pref.bind(root);
            NumberPicker picker = (NumberPicker) root.getChildAt(0);
            ArrayList<Integer> proposals = new ArrayList<>();
            AtomicBoolean allow = new AtomicBoolean(false);
            pref.setOnPreferenceChangeListener((preference, value) -> {
                proposals.add((Integer) value);
                return allow.get();
            });
            int writesBefore = pref.writes.size();
            picker.setValue(7);
            pref.close(false);
            assertTrue(proposals.isEmpty());
            assertEquals(writesBefore, pref.writes.size());
            assertEquals(5, pref.getValue());
            pref.close(true);
            assertEquals(Integer.valueOf(7), proposals.get(0));
            assertEquals(5, pref.getValue());
            assertEquals("5", pref.getSummary().toString());
            assertEquals(writesBefore, pref.writes.size());
            allow.set(true);
            pref.close(true);
            assertEquals(7, pref.getValue());
            assertEquals("7", pref.getSummary().toString());
            assertEquals(Integer.valueOf(7), pref.writes.get(pref.writes.size() - 1));
        });
    }

    @Test public void restoreAndDefaultPathsKeepTheirProtectedJavaHooks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            TestPreference pref = new TestPreference(context, null);
            // An absent integer attribute still falls back to minValue.
            TypedArray absent = context.obtainStyledAttributes(null, R.styleable.NumberPickerPreference);
            try {
                assertEquals(Integer.valueOf(0), pref.defaultValue(absent, R.styleable.NumberPickerPreference_minValue));
            } finally {
                absent.recycle();
            }
            pref.initialize(false, Integer.valueOf(8));
            assertEquals(8, pref.getValue());
            assertEquals("8", pref.getSummary().toString());
            pref.restored = 19;
            pref.initialize(true, null);
            assertEquals(19, pref.getValue());
            assertEquals(0, pref.persistedFallback);
            pref.restored = null;
            pref.initialize(true, Integer.valueOf(99));
            assertEquals(0, pref.getValue());
            assertEquals("0", pref.getSummary().toString());
        });
    }
}
