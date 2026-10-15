package org.shadowice.flocke.andotp.View;

import android.content.Context;
import android.database.DataSetObserver;
import android.view.LayoutInflater;
import android.view.View;
import android.view.autofill.AutofillValue;
import android.widget.CheckedTextView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Utilities.EntryThumbnail.EntryThumbnails;
import org.shadowice.flocke.andotp.Utilities.Settings;
import org.shadowice.flocke.andotp.View.AutoFillable.AutoFillableTextInputEditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Java callers and real Android adapters/widgets; these checks are not desktop-sandbox tests. */
@RunWith(AndroidJUnit4.class)
public class KotlinSmallAdaptersInteropTest {
    private Context context() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.AppTheme);
    }

    @Test public void tagsKeepSortedRowsBorrowedStateAndObserverNotifications() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            HashMap<String, Boolean> states = new HashMap<>();
            states.put("zeta", false);
            states.put("Alpha", true);
            states.put("alpha", false);
            TagsAdapter adapter = new TagsAdapter(context, states);
            assertEquals(Arrays.asList("Alpha", "alpha", "zeta"), adapter.getTags());
            assertEquals(Arrays.asList("Alpha"), adapter.getActiveTags());
            AtomicInteger changes = new AtomicInteger();
            adapter.registerDataSetObserver(new DataSetObserver() {
                @Override public void onChanged() { changes.incrementAndGet(); }
            });
            adapter.setTagState("unknown", true);
            assertFalse(states.containsKey("unknown"));
            assertEquals(Boolean.FALSE, adapter.getTagState("unknown"));
            assertTrue(changes.get() > 0);
            adapter.setTagState("zeta", true);
            assertEquals(Boolean.TRUE, states.get("zeta"));
            states.put("alpha", true);
            assertTrue(adapter.allTagsActive());
            adapter.getTagsWithState().put("Alpha", false);
            assertEquals(Boolean.TRUE, adapter.getTagState("Alpha"));

            FrameLayout parent = new FrameLayout(context);
            CheckedTextView row = (CheckedTextView) adapter.getView(0, null, parent);
            assertEquals("Alpha", row.getText().toString());
            assertTrue(row.isChecked());
            assertSame(row, adapter.getView(2, row, parent));
            assertEquals("zeta", row.getText().toString());

            HashMap<String, Boolean> replacement = new HashMap<>();
            replacement.put("two", false);
            replacement.put("one", true);
            adapter.setTags(replacement);
            assertEquals(2, adapter.getCount());
            assertEquals("one", adapter.getItem(0));
            assertEquals(Arrays.asList("one", "two"), adapter.getTags());
            adapter.setTagState("two", true);
            assertEquals(Boolean.TRUE, replacement.get("two"));
            adapter.setTagState("one", null); // The public API takes boxed Boolean, not primitive.
            assertNull(adapter.getTagState("one"));
            adapter.setTags(new HashMap<>());
            assertTrue(adapter.allTagsActive());
            assertTrue(adapter.getActiveTags().isEmpty());
        });
    }

    @Test public void staticTagFactoryDeDuplicatesAndUsesRememberedStates() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Settings settings = new Settings(context()) {
                @Override public boolean getTagToggle(String tag) { return !tag.equals("disabled"); }
            };
            Entry first = new Entry();
            Entry second = new Entry();
            try {
                first.setTags(new ArrayList<>(Arrays.asList("shared", "disabled")));
                second.setTags(new ArrayList<>(Arrays.asList("shared", "other")));
                HashMap<String, Boolean> tags = TagsAdapter.createTagsMap(
                        new ArrayList<>(Arrays.asList(first, second)), settings);
                assertEquals(3, tags.size());
                assertEquals(Boolean.TRUE, tags.get("shared"));
                assertEquals(Boolean.TRUE, tags.get("other"));
                assertEquals(Boolean.FALSE, tags.get("disabled"));
            } finally {
                first.destroy();
                second.destroy();
            }
        });
    }

    @Test public void thumbnailFiltersKeepLocaleSemanticsOrdinalsAndIndexFallback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Locale original = Locale.getDefault();
            try {
                ThumbnailSelectionAdapter adapter = new ThumbnailSelectionAdapter(context(), "Example", "Example");
                for (Locale locale : new Locale[] { Locale.US, new Locale("tr", "TR") }) {
                    Locale.setDefault(locale);
                    for (String filter : new String[] { "", "gIt", "I", "no such thumbnail" }) {
                        adapter.filter(filter);
                        ArrayList<EntryThumbnails> expected = new ArrayList<>();
                        for (EntryThumbnails thumbnail : EntryThumbnails.values()) {
                            if (thumbnail.name().toLowerCase(locale).contains(filter.toLowerCase(locale))) {
                                expected.add(thumbnail);
                            }
                        }
                        assertEquals(expected.size(), adapter.getCount());
                        for (int i = 0; i < expected.size(); i++) {
                            assertSame(expected.get(i), adapter.getItem(i));
                            assertEquals(expected.get(i).ordinal(), adapter.getRealIndex(i));
                            assertEquals(expected.get(i).ordinal(), adapter.getItemId(i));
                        }
                        assertSame(EntryThumbnails.Default, adapter.getItem(adapter.getCount()));
                        assertEquals(0, adapter.getRealIndex(Integer.MAX_VALUE));
                        try {
                            adapter.getItem(-1);
                            fail("Negative positions must still be rejected");
                        } catch (IndexOutOfBoundsException expectedFailure) { }
                    }
                }
            } finally {
                Locale.setDefault(original);
            }
        });
    }

    @Test public void thumbnailViewsKeepSizeDescriptionsAndRecycling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            ThumbnailSelectionAdapter adapter = new ThumbnailSelectionAdapter(context, "Example", "Example");
            GridView parent = new GridView(context);
            adapter.filter("GitHub");
            ImageView image = (ImageView) adapter.getView(0, null, parent);
            int size = new Settings(context).getThumbnailSize();
            assertEquals(size, image.getLayoutParams().width);
            assertEquals(size, image.getLayoutParams().height);
            assertEquals("GitHub", image.getContentDescription().toString());
            assertNotNull(image.getDrawable());
            image.setLayoutParams(new GridView.LayoutParams(7, 9));
            adapter.filter("Google");
            assertSame(image, adapter.getView(0, image, parent));
            assertEquals("Google", image.getContentDescription().toString());
            assertEquals(7, image.getLayoutParams().width); // Recycled cells are not resized.
        });
    }

    @Test public void autofillXmlConstructorsAndJavaSamDelegateBeforeNotification() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            new AutoFillableTextInputEditText(context);
            new AutoFillableTextInputEditText(context, null);
            new AutoFillableTextInputEditText(context, null, androidx.appcompat.R.attr.editTextStyle);
            View layout = LayoutInflater.from(context).inflate(R.layout.content_authenticate, null, false);
            AutoFillableTextInputEditText field = layout.findViewById(R.id.passwordEdit);
            assertNotNull(field);
            AtomicInteger callbacks = new AtomicInteger();
            field.setAutoFillTextListener(text -> {
                assertEquals("autofill-preview", text.toString());
                assertEquals(text.toString(), field.getText().toString());
                callbacks.incrementAndGet();
            });
            field.autofill(AutofillValue.forText("autofill-preview"));
            assertEquals(1, callbacks.get());
            field.autofill(AutofillValue.forToggle(true));
            assertEquals(1, callbacks.get());
            field.setAutoFillTextListener(null);
            field.autofill(AutofillValue.forText("replacement"));
            assertEquals("replacement", field.getText().toString());
            assertEquals(1, callbacks.get());
        });
    }

    @Test public void tagMethodsKeepBoxedJavaSignatures() throws Exception {
        assertEquals(Boolean.class, TagsAdapter.class.getMethod("getTagState", String.class).getReturnType());
        TagsAdapter.class.getMethod("setTagState", String.class, Boolean.class);
        assertEquals(HashMap.class, TagsAdapter.class.getMethod("getTagsWithState").getReturnType());
    }
}
