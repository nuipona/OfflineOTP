package org.shadowice.flocke.andotp.Fragments

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.viewpager.widget.ViewPager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.mikepenz.aboutlibraries.R as AboutLibrariesR
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.Activities.AboutActivity
import org.shadowice.flocke.andotp.R
import java.lang.reflect.Modifier

/** Real-platform characterization of the converted about activity and flavor fragment. */
@RunWith(AndroidJUnit4::class)
class KotlinAboutScreensInteropTest {
    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("the activity did not $what")
    }

    /** The single resumed activity, or null. */
    private fun resumedActivity(): Activity? {
        var result: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activities = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
            if (!activities.isEmpty()) result = activities.iterator().next()
        }
        return result
    }

    private fun startAboutScreen(): AboutActivity {
        context().startActivity(
            Intent(context(), AboutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitUntil("show the about screen") { resumedActivity() is AboutActivity }
        val activity = resumedActivity() as? AboutActivity
            ?: throw AssertionError("the about screen was not resumed")
        waitUntil("inflate the about tab") {
            resumedActivity()?.findViewById<View>(R.id.about_layout_version) != null
        }
        return activity
    }

    /** A subclass of the converted fragment, proving that its extension surface remains open. */
    class RecordingFragment : BaseAboutFragment() {
        val opened = ArrayList<String>()

        override fun openURI(uri: String) {
            opened.add(uri)
        }
    }

    @Test
    fun constructionSubclassingAndStaticStateStayUnchanged() {
        val fragment = RecordingFragment()
        fragment.openURI("https://example.org/from-kotlin")
        assertEquals(1, fragment.opened.size)
        assertEquals("https://example.org/from-kotlin", fragment.opened[0])

        assertNotNull(BaseAboutFragment.imageResources)
        assertEquals(9, BaseAboutFragment.imageResources.size)
        BaseAboutFragment.taps = 3
        assertEquals(3, BaseAboutFragment.taps)
        BaseAboutFragment.taps = 0
        BaseAboutFragment.lastTap = 0L
        BaseAboutFragment.currentToast = null

        val base = BaseAboutFragment::class.java
        assertFalse(Modifier.isFinal(base.modifiers))
        assertFalse(Modifier.isFinal(AboutFragment::class.java.modifiers))
        assertFalse(Modifier.isFinal(AboutActivity::class.java.modifiers))
        assertTrue(Activity::class.java.isAssignableFrom(AboutActivity::class.java))
        assertTrue(base.isAssignableFrom(AboutFragment::class.java))
        for (name in arrayOf("openURI", "copyToClipboard")) {
            val method = base.getMethod(name, String::class.java)
            assertTrue("$name is not public", Modifier.isPublic(method.modifiers))
            assertFalse("$name is final", Modifier.isFinal(method.modifiers))
        }
        for (name in arrayOf("imageResources", "lastTap", "taps", "currentToast")) {
            val field = base.getField(name)
            assertTrue("$name is not static", Modifier.isStatic(field.modifiers))
        }
    }

    @Test
    fun libraryCardInflatesUnderEveryAppThemeHook() {
        // Inflate the dependency's real row, not an app-owned imitation or just the pager shell.
        // API 28/36 CI also covers the values-v27 overrides; both flavors run this same test.
        val themes = intArrayOf(
            R.style.AppTheme, R.style.AppTheme_NoActionBar,
            R.style.AppTheme_Dark, R.style.AppTheme_Dark_NoActionBar,
            R.style.AppTheme_Black, R.style.AppTheme_Black_NoActionBar
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (theme in themes) {
                val base = context()
                // Start with an empty theme so the application's default cannot supply missing
                // Material attributes and mask a broken dark/black/NoActionBar parent.
                val isolatedTheme = base.resources.newTheme().apply { applyStyle(theme, true) }
                val themed = ContextThemeWrapper(base, isolatedTheme)
                val row = LayoutInflater.from(themed).inflate(
                    AboutLibrariesR.layout.listitem_opensource, FrameLayout(themed), false
                )
                assertTrue("${themed.resources.getResourceName(theme)} must support MaterialCardView",
                    row is MaterialCardView)
                assertNotNull(row.findViewById<TextView>(AboutLibrariesR.id.libraryName))
            }
        }
    }

    @Test
    fun realScreenBindsItsVersionAndBuildsBothPages() {
        val activity = startAboutScreen()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val version = requireNotNull(activity.findViewById<TextView>(R.id.about_text_version))
                assertNotNull("the version text is missing", version)
                val info: PackageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val expected = info.versionName ?: ""
                assertEquals(expected, version.text?.toString())

                for (id in BaseAboutFragment.imageResources) {
                    val image = requireNotNull(activity.findViewById<ImageView>(id))
                    assertNotNull("image row $id is missing", image)
                    val drawable: Drawable? = image.drawable
                    assertNotNull("image row $id is untinted", drawable)
                }
                val license = requireNotNull(activity.findViewById<LinearLayout>(R.id.about_layout_license))
                assertNotNull(license)
                assertTrue(license.hasOnClickListeners())

                val pager = requireNotNull(activity.findViewById<ViewPager>(R.id.viewPager))
                assertNotNull(pager)
                val adapter = requireNotNull(pager.adapter)
                assertEquals(2, adapter.count)
                assertEquals(activity.getString(R.string.about_tab_about), adapter.getPageTitle(0))
                assertEquals(activity.getString(R.string.about_tab_libraries), adapter.getPageTitle(1))

                val found = activity.supportFragmentManager.fragments.any { it is AboutFragment }
                assertTrue("the about tab fragment is missing", found)

                pager.setCurrentItem(1, false)
                assertEquals(1, pager.currentItem)
            }
            // AboutLibraries loads asynchronously. Merely switching pages and immediately
            // finishing the activity can pass without ever constructing the crashing card.
            waitUntil("render a library card") {
                var rendered = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val list = activity.findViewById<RecyclerView>(AboutLibrariesR.id.cardListView)
                    rendered = list != null && (list.adapter?.itemCount ?: 0) > 0 &&
                        (0 until list.childCount).any { list.getChildAt(it) is MaterialCardView }
                }
                rendered
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                requireNotNull(activity.findViewById<ViewPager>(R.id.viewPager)).setCurrentItem(0, false)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun clipboardFallbackCoversWhatNoAppCanOpen() {
        val activity = startAboutScreen()
        val direct = "andotp-test-no-handler://interop/direct"
        val uri = "andotp-test-no-handler://interop/copy"
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val fragment = activity.supportFragmentManager.fragments
                    .filterIsInstance<BaseAboutFragment>()
                    .firstOrNull()
                assertNotNull("the about fragment is missing", fragment)
                val current = requireNotNull(fragment)

                current.copyToClipboard(direct)
                val clipboard = requireNotNull(
                    activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                )
                var clip = requireNotNull(clipboard.primaryClip)
                assertTrue(clip.itemCount > 0)
                assertEquals(direct, clip.getItemAt(0).text.toString())

                current.openURI(uri)
                clip = requireNotNull(clipboard.primaryClip)
                assertTrue(clip.itemCount > 0)
                assertEquals(uri, clip.getItemAt(0).text.toString())
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                activity.finish()
            }
        }
    }
}
