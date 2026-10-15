"""Source guards for the UI migration, not substitutes for Java compilation/device tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp/View"
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/View"


class KotlinSmallAdaptersConversionTest(unittest.TestCase):
    def test_implementations_are_replaced_and_included_in_the_compile_harness(self):
        harness = (ROOT / "scripts/kotlin-sandbox/run.sh").read_text()
        for name in ("TagsAdapter", "ThumbnailSelectionAdapter", "AutoFillable/AutoFillableTextInputEditText"):
            self.assertFalse((JAVA / f"{name}.java").exists())
            self.assertTrue((KOTLIN / f"{name}.kt").is_file())
            self.assertIn(f"$KOTLIN_APP/View/{name}.kt", harness)

    def test_tag_contract_keeps_boxed_states_and_real_java_settings_boundary(self):
        tags = (KOTLIN / "TagsAdapter.kt").read_text()
        self.assertIn("open class TagsAdapter(", tags)
        self.assertIn("open fun getTagState(tag: String?): Boolean?", tags)
        self.assertIn("open fun setTagState(tag: String?, state: Boolean?)", tags)
        self.assertIn("open fun getTagsWithState(): HashMap<String, Boolean?> = HashMap(tagsState)", tags)
        self.assertIn("@JvmStatic\n        fun createTagsMap(entries: ArrayList<Entry>, settings: Settings)", tags)
        self.assertIn("settings.getTagToggle(tag)", tags)
        self.assertNotIn("data class", tags)

    def test_picker_keeps_unmangled_filter_object_return_and_device_locale(self):
        picker = (KOTLIN / "ThumbnailSelectionAdapter.kt").read_text()
        self.assertIn("open class ThumbnailSelectionAdapter internal constructor(", picker)
        self.assertIn("open fun filter(filter: String)", picker)
        self.assertIn("override fun getItem(position: Int): Any", picker)
        self.assertIn("Locale.getDefault()", picker)
        self.assertNotIn("Locale.ROOT", picker)
        self.assertNotIn("internal fun filter", picker)

    def test_autofill_keeps_xml_name_three_super_calls_and_java_listener(self):
        field = (KOTLIN / "AutoFillable/AutoFillableTextInputEditText.kt").read_text()
        self.assertIn("open class AutoFillableTextInputEditText : TextInputEditText", field)
        for call in ("super(context)", "super(context, attrs)", "super(context, attrs, defStyleAttr)"):
            self.assertIn(call, field)
        self.assertLess(field.index("super.autofill(value)"), field.index("callback.onTextAutoFilled("))
        self.assertIn("open fun setAutoFillTextListener(listener: AutoFillTextListener?)", field)
        self.assertIn("fun interface AutoFillTextListener", field)
        self.assertIn("fun onTextAutoFilled(text: CharSequence)", field)
        layout = (ROOT / "app/src/main/res/layout/content_authenticate.xml").read_text()
        self.assertIn("org.shadowice.flocke.andotp.View.AutoFillable.AutoFillableTextInputEditText", layout)
