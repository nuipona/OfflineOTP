"""Source-level guards for the twentieth batch (the about screens).

The sandbox compiles the converted about activity, the shared about fragment and the fdroid
flavor's fragment against the platform stand-in stubs and executes their row wiring, version
binding, easter egg, fallback and page building. The parts that need a real window, a real package
manager or a real clipboard stay with the Android gates.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
FLAVOR_FDROID = ROOT / "app/src/fdroid/kotlin/org/shadowice/flocke/andotp"
FLAVOR_PLAY = ROOT / "app/src/play/kotlin/org/shadowice/flocke/andotp"
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
ANDROID_TEST = ROOT / "app/src/androidTest/kotlin/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


class KotlinAboutScreensConversionTest(unittest.TestCase):
    def test_the_replacements_keep_their_names_and_are_compiled_by_the_sandbox(self):
        harness = (SANDBOX / "run.sh").read_text()
        for stem in ("Activities/AboutActivity", "Fragments/BaseAboutFragment"):
            self.assertFalse((JAVA / f"{stem}.java").exists(), f"{stem}.java is still there")
            self.assertTrue((KOTLIN / f"{stem}.kt").is_file(), f"{stem}.kt is missing")
            self.assertIn(f"$KOTLIN_APP/{stem}.kt", harness)
        for flavor, root in (("fdroid", FLAVOR_FDROID), ("play", FLAVOR_PLAY)):
            self.assertFalse(
                (ROOT / f"app/src/{flavor}/java/org/shadowice/flocke/andotp/Fragments/AboutFragment.java").exists(),
                f"{flavor} AboutFragment.java is still there")
            self.assertTrue((root / "Fragments/AboutFragment.kt").is_file(),
                            f"{flavor} AboutFragment.kt is missing")
        # The fdroid flavor is the sandbox's representative flavor of the about screens.
        self.assertIn("$ROOT/app/src/fdroid/kotlin/org/shadowice/flocke/andotp/Fragments/AboutFragment.kt",
                      harness)
        for stub in ("androidx/fragment/app/Fragment.kt",
                     "androidx/viewpager/widget/ViewPager.kt",
                     "androidx/appcompat/widget/Toolbar.kt",
                     "com/google/android/material/tabs/TabLayout.kt",
                     "com/mikepenz/aboutlibraries/AboutLibraries.kt"):
            self.assertTrue((SANDBOX / "stubs" / stub).is_file(), f"the {stub} stand-in is missing")

    def test_the_about_activity_keeps_its_two_pages_and_navigation(self):
        source = (KOTLIN / "Activities/AboutActivity.kt").read_text()
        self.assertIn("open class AboutActivity : BaseActivity()", source)
        # The shell wiring is unchanged: title, container, toolbar and the inflated component.
        for wiring in ("setTitle(R.string.about_activity_title)",
                       "setContentView(R.layout.activity_container)",
                       "findViewById<Toolbar>(R.id.container_toolbar)",
                       "findViewById<ViewStub>(R.id.container_stub)",
                       "stub.setLayoutResource(R.layout.component_about)",
                       "findViewById<ViewPager>(R.id.viewPager)",
                       "findViewById<TabLayout>(R.id.tabLayout)",
                       "AboutPageAdapter(getSupportFragmentManager())",
                       "viewPager.setAdapter(aboutPageAdapter)",
                       "tabLayout.setupWithViewPager(viewPager)"):
            self.assertIn(wiring, source)
        # Up-navigation and back both finish the screen.
        self.assertIn("override fun onSupportNavigateUp(): Boolean {\n        finish()\n        return true\n    }",
                      source)
        self.assertIn("override fun onBackPressed() {\n        finish()\n        super.onBackPressed()\n    }",
                      source)
        # The two pages: the about tab and the library list from the generated raw resource.
        self.assertIn("override fun getCount(): Int = 2", source)
        self.assertIn("0 -> AboutFragment()", source)
        self.assertIn(".withLibs(loadLibs())", source)
        self.assertIn(".withLicenseShown(true)", source)
        self.assertIn(".withVersionShown(true)", source)
        self.assertIn(".supportFragment()", source)
        self.assertIn('resources.openRawResource(R.raw.aboutlibraries)', source)
        self.assertIn('Scanner(stream, "UTF-8").useDelimiter("\\\\A")', source)
        self.assertIn('if (scanner.hasNext()) scanner.next() else "{}"', source)
        self.assertIn("0 -> getString(R.string.about_tab_about)", source)
        self.assertIn("1 -> getString(R.string.about_tab_libraries)", source)
        # The documented delta: the unreachable default throws instead of returning null against
        # FragmentPagerAdapter's @NonNull contract.
        self.assertIn('else -> throw IndexOutOfBoundsException("Invalid about page: $pos")', source)

    def test_the_shared_about_fragment_keeps_its_rows_and_fallback(self):
        source = (KOTLIN / "Fragments/BaseAboutFragment.kt").read_text()
        self.assertIn("open class BaseAboutFragment : Fragment()", source)
        # Every documented link keeps its exact target.
        for uri in ('const val GITHUB_URI = "https://github.com/andOTP/andOTP"',
                    'const val CHANGELOG_URI = "$GITHUB_URI/blob/master/CHANGELOG.md"',
                    'const val MIT_URI = "$GITHUB_URI/blob/master/LICENSE.txt"',
                    'const val FAQ_URI = "$GITHUB_URI/wiki/Frequently-Asked-Questions"',
                    'const val AUTHOR1_GITHUB = "https://github.com/flocke"',
                    'const val AUTHOR2_GITHUB = "https://github.com/richyhbm"',
                    'const val AUTHOR_ORIGINAL_GITHUB = "https://github.com/0xbb"',
                    'const val AUTHOR_ORIGINAL_EXTRA = "$AUTHOR_ORIGINAL_GITHUB/otp-authenticator"',
                    'const val CONTRIBUTORS_URI = "$GITHUB_URI/graphs/contributors"',
                    'const val SUPPORT_URI = "$GITHUB_URI/blob/master/README.md#contribute"'):
            self.assertIn(uri, source)
        # The rows are wired to exactly those links.
        for wiring in ("license.setOnClickListener { openURI(MIT_URI) }",
                       "changelog.setOnClickListener { openURI(CHANGELOG_URI) }",
                       "source.setOnClickListener { openURI(GITHUB_URI) }",
                       "faq.setOnClickListener { openURI(FAQ_URI) }",
                       "author1Layout.setOnClickListener { openURI(AUTHOR1_GITHUB) }",
                       "author2Layout.setOnClickListener { openURI(AUTHOR2_GITHUB) }",
                       "authorOrigialLayout.setOnClickListener { openURI(AUTHOR_ORIGINAL_GITHUB) }",
                       "contributors.setOnClickListener { openURI(CONTRIBUTORS_URI) }",
                       "supportLayout.setOnClickListener { openURI(SUPPORT_URI) }"):
            self.assertIn(wiring, source)
        # The original-app row keeps its broad fallback.
        self.assertIn("openURI(AUTHOR_ORIGINAL_EXTRA)", source)
        self.assertIn("catch (ignored: Exception) {\n                copyToClipboard(AUTHOR_ORIGINAL_EXTRA)",
                      source)
        # The public helpers keep their names, their fallback and their overridability.
        self.assertIn("open fun openURI(uri: String)", source)
        self.assertIn("val intent = Intent(Intent.ACTION_VIEW)", source)
        self.assertIn("intent.data = Uri.parse(uri)", source)
        self.assertIn("} catch (e: ActivityNotFoundException) {\n            copyToClipboard(uri)", source)
        self.assertIn("open fun copyToClipboard(uri: String)", source)
        self.assertIn("ClipData.newPlainText(\"andOTP\", uri)", source)
        self.assertIn("getString(R.string.about_toast_copied_to_clipboard)", source)
        # The version binding and the theme tint of the image rows.
        self.assertIn("imgView.getDrawable()!!.setColorFilter(filter)", source)
        self.assertIn("version.setText(versionName)", source)
        self.assertIn("getPackageInfo(getActivity()!!.getPackageName(), 0)", source)
        self.assertIn("catch (e: PackageManager.NameNotFoundException)", source)

    def test_the_easter_egg_keeps_its_counter_and_confirmation(self):
        source = (KOTLIN / "Fragments/BaseAboutFragment.kt").read_text()
        # The shared static state survives a fragment re-creation, and stays Java-callable.
        for field in ("@JvmField\n        var lastTap: Long = 0",
                      "@JvmField\n        var taps: Int = 0",
                      "@JvmField\n        var currentToast: Toast? = null"):
            self.assertIn(field, source)
        # The counter: a quick tap increments, a slow tap resets, the third count onwards toasts.
        body = source[source.index("versionLayout.setOnClickListener"):source.index("val version =")]
        self.assertIn("if (thisTap - lastTap < 500) {", body)
        self.assertIn("taps = taps + 1", body)
        self.assertIn("if (currentToast != null && taps <= 7)", body)
        self.assertIn("if (taps >= 3 && taps <= 7)", body)
        self.assertIn("Toast.makeText(getActivity()!!, taps.toString(), Toast.LENGTH_SHORT)", body)
        self.assertIn("} else {\n                taps = 0\n            }", body)
        self.assertIn("lastTap = thisTap", body)
        # The seventh count asks before enabling anything.
        self.assertIn("if (taps == 7) {", body)
        self.assertIn("if (settings!!.getSpecialFeatures())", body)
        self.assertIn("R.string.about_toast_special_features_enabled", body)
        self.assertIn("else\n                        enableSpecialFeatures()", body)
        confirm = source[source.index("private fun enableSpecialFeatures"):source.index("open fun openURI")]
        self.assertIn("settings!!.setSpecialFeatures(true)", confirm)
        self.assertIn("R.string.about_toast_special_features", confirm)
        self.assertIn("android.R.string.ok", confirm)
        self.assertIn("android.R.string.cancel", confirm)

    def test_the_flavor_fragments_keep_their_extras(self):
        fdroid = (FLAVOR_FDROID / "Fragments/AboutFragment.kt").read_text()
        self.assertIn("open class AboutFragment : BaseAboutFragment()", fdroid)
        self.assertIn('private const val ABOUT_AUTHOR1_EXTRA_LINK = "https://flocke.shadowice.org/donate.html"',
                      fdroid)
        self.assertIn('private const val ABOUT_AUTHOR2_EXTRA_LINK = "https://richyhbm.co.uk/donate"',
                      fdroid)
        self.assertIn("val v = super.onCreateView(inflater, container, savedInstanceState)", fdroid)
        self.assertIn("inflater.inflate(R.layout.part_author_extra, null)", fdroid)
        self.assertIn("author1Content.addView(extra1)", fdroid)
        self.assertIn("author2Content.addView(extra2)", fdroid)
        # The broad fallback of the Java listeners is preserved.
        self.assertEqual(2, fdroid.count("catch (ignored: Exception) {\n                copyToClipboard(ABOUT_AUTHOR"))
        play = (FLAVOR_PLAY / "Fragments/AboutFragment.kt").read_text()
        self.assertIn("open class AboutFragment : BaseAboutFragment()", play)
        # The play flavor adds no rows and overrides nothing.
        self.assertNotIn("onCreateView", play)
        self.assertNotIn("openURI", play)

    def test_the_java_visible_surface_is_pinned(self):
        base = (KOTLIN / "Fragments/BaseAboutFragment.kt").read_text()
        # The Java package-private statics become public fields (documented widening) and the
        # private URI constants stay private.
        self.assertIn("@JvmField\n        val imageResources: IntArray = intArrayOf(", base)
        self.assertIn("private const val GITHUB_URI", base)
        self.assertIn("private fun enableSpecialFeatures()", base)
        activity = (KOTLIN / "Activities/AboutActivity.kt").read_text()
        self.assertIn("private inner class AboutPageAdapter(fm: FragmentManager)", activity)
        self.assertIn("FragmentPagerAdapter(fm, FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT)",
                      activity)
        self.assertIn("private fun loadLibs(): Libs", activity)
        # The call site in MainActivity keeps compiling against the same class name. MainActivity
        # is Kotlin since the twenty-first batch, so the call site moved with it.
        main = (KOTLIN / "Activities/MainActivity.kt").read_text()
        self.assertIn("AboutActivity::class.java", main)

    def test_the_device_gate_keeps_the_kotlin_interop_test(self):
        interop = ANDROID_TEST / "Fragments/KotlinAboutScreensInteropTest.kt"
        self.assertTrue(interop.is_file(), "the Kotlin device test is missing")
        source = interop.read_text()
        self.assertIn("class RecordingFragment : BaseAboutFragment()", source)
        self.assertIn("openURI(", source)
        self.assertIn("copyToClipboard(", source)
        self.assertIn("AboutActivity::class.java", source)


if __name__ == "__main__":
    unittest.main()
