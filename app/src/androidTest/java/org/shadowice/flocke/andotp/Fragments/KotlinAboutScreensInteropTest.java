package org.shadowice.flocke.andotp.Fragments;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import androidx.viewpager.widget.ViewPager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.Activities.AboutActivity;
import org.shadowice.flocke.andotp.R;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Real-platform characterization of the converted about screens: the about activity, the shared
 * about fragment and the flavor fragment.
 *
 * The desktop sandbox executes the row wiring, the easter egg and the page building against
 * hand-written stand-in widgets, but it has no real window, package manager or clipboard. Here the
 * real screen is started, so the real layouts resolve their children, the real version name is
 * bound and the clipboard fallback of `openURI` runs against the platform. The tap-counter dialog
 * stays with the sandbox checks and the Python guards; driving seven timed taps through a real
 * window would race the emulator.
 *
 * Synthetic URIs only; the fallback URI uses a scheme no app handles, so nothing is launched.
 */
@RunWith(AndroidJUnit4.class)
public class KotlinAboutScreensInteropTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** Waits until the condition holds, so the asynchronous activity is not raced. */
    private static void waitUntil(String what, Condition condition) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.holds()) return;
            Thread.sleep(100);
        }
        fail("the activity did not " + what);
    }

    private interface Condition {
        boolean holds();
    }

    /** The single resumed activity, or null. */
    private Activity resumedActivity() {
        final Activity[] holder = new Activity[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> activities =
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED);
            if (!activities.isEmpty()) holder[0] = activities.iterator().next();
        });
        return holder[0];
    }

    /** Starts the about screen and waits for its real layout. */
    private AboutActivity startAboutScreen() throws Exception {
        context().startActivity(new Intent(context(), AboutActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitUntil("show the about screen", () -> resumedActivity() instanceof AboutActivity);
        AboutActivity activity = (AboutActivity) resumedActivity();
        waitUntil("inflate the about tab", () -> {
            Activity current = resumedActivity();
            return current != null && current.findViewById(R.id.about_layout_version) != null;
        });
        return activity;
    }

    /** A Java subclass of the converted fragment, to prove the class stays open to Java. */
    public static class RecordingFragment extends BaseAboutFragment {
        final List<String> opened = new ArrayList<>();

        @Override
        public void openURI(String uri) {
            opened.add(uri);
        }
    }

    @Test public void javaConstructionSubclassingAndStaticStateStayUnchanged() throws Exception {
        RecordingFragment fragment = new RecordingFragment();
        fragment.openURI("https://example.org/from-java");
        assertEquals(1, fragment.opened.size());
        assertEquals("https://example.org/from-java", fragment.opened.get(0));

        // The Java package-private statics are public fields here (documented widening).
        assertNotNull(BaseAboutFragment.imageResources);
        assertEquals(9, BaseAboutFragment.imageResources.length);
        BaseAboutFragment.taps = 3;
        assertEquals(3, BaseAboutFragment.taps);
        BaseAboutFragment.taps = 0;
        BaseAboutFragment.lastTap = 0L;
        BaseAboutFragment.currentToast = null;

        Class<?> base = BaseAboutFragment.class;
        assertFalse(Modifier.isFinal(base.getModifiers()));
        assertFalse(Modifier.isFinal(AboutFragment.class.getModifiers()));
        assertFalse(Modifier.isFinal(AboutActivity.class.getModifiers()));
        assertTrue(Activity.class.isAssignableFrom(AboutActivity.class));
        assertTrue(base.isAssignableFrom(AboutFragment.class));
        for (String name : new String[] {"openURI", "copyToClipboard"}) {
            Method method = base.getMethod(name, String.class);
            assertTrue(name + " is not public", Modifier.isPublic(method.getModifiers()));
            assertFalse(name + " is final", Modifier.isFinal(method.getModifiers()));
        }
        for (String name : new String[] {"imageResources", "lastTap", "taps", "currentToast"}) {
            Field field = base.getField(name);
            assertTrue(name + " is not static", Modifier.isStatic(field.getModifiers()));
        }
    }

    @Test public void theRealScreenBindsItsVersionAndBuildsBothPages() throws Exception {
        AboutActivity activity = startAboutScreen();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TextView version = activity.findViewById(R.id.about_text_version);
                assertNotNull("the version text is missing", version);
                PackageInfo info;
                try {
                    info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
                String expected = info.versionName == null ? "" : info.versionName;
                assertEquals(expected, version.getText() == null ? null : version.getText().toString());

                // The real layout resolves every documented row and image.
                for (int id : BaseAboutFragment.imageResources) {
                    ImageView image = activity.findViewById(id);
                    assertNotNull("image row " + id + " is missing", image);
                    Drawable drawable = image.getDrawable();
                    assertNotNull("image row " + id + " is untinted", drawable);
                }
                LinearLayout license = activity.findViewById(R.id.about_layout_license);
                assertNotNull(license);
                assertTrue(license.hasOnClickListeners());

                // The pager really has the two tabs of the converted adapter.
                ViewPager pager = activity.findViewById(R.id.viewPager);
                assertNotNull(pager);
                assertNotNull(pager.getAdapter());
                assertEquals(2, pager.getAdapter().getCount());
                assertEquals(activity.getString(R.string.about_tab_about),
                        pager.getAdapter().getPageTitle(0));
                assertEquals(activity.getString(R.string.about_tab_libraries),
                        pager.getAdapter().getPageTitle(1));

                // The about tab is the flavor fragment the Java adapter instantiates.
                boolean found = false;
                for (androidx.fragment.app.Fragment fragment
                        : activity.getSupportFragmentManager().getFragments()) {
                    if (fragment instanceof AboutFragment) found = true;
                }
                assertTrue("the about tab fragment is missing", found);
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
        }
    }

    @Test public void theClipboardFallbackCoversWhatNoAppCanOpen() throws Exception {
        AboutActivity activity = startAboutScreen();
        String direct = "andotp-test-no-handler://interop/direct";
        String uri = "andotp-test-no-handler://interop/copy";
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                BaseAboutFragment fragment = null;
                for (androidx.fragment.app.Fragment candidate
                        : activity.getSupportFragmentManager().getFragments()) {
                    if (candidate instanceof BaseAboutFragment) fragment = (BaseAboutFragment) candidate;
                }
                assertNotNull("the about fragment is missing", fragment);

                // The public helper is callable from Java and copies exactly what it is given.
                fragment.copyToClipboard(direct);
                ClipboardManager clipboard =
                        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = clipboard.getPrimaryClip();
                assertNotNull("nothing was copied", clip);
                assertTrue(clip.getItemCount() > 0);
                assertEquals(direct, clip.getItemAt(0).getText().toString());

                // No installed activity handles this scheme, so openURI falls back to the clipboard.
                fragment.openURI(uri);
                clip = clipboard.getPrimaryClip();
                assertNotNull("nothing was copied", clip);
                assertTrue(clip.getItemCount() > 0);
                assertEquals(uri, clip.getItemAt(0).getText().toString());
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ClipboardManager clipboard =
                        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                activity.finish();
            });
        }
    }
}
