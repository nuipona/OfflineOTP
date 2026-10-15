"""Source-level migration guards; Android layout/lifecycle acceptance requires device tests."""
from pathlib import Path
import importlib.util
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


class KotlinSmallDialogsConversionTest(unittest.TestCase):
    def test_replacements_keep_the_original_names_and_are_in_the_compile_set(self):
        harness = (SANDBOX / "run.sh").read_text()
        for name in ("Preferences/NumberPickerPreference", "Dialogs/HideableDialog"):
            self.assertFalse((JAVA / f"{name}.java").exists())
            self.assertTrue((KOTLIN / f"{name}.kt").is_file())
            self.assertIn(f"$KOTLIN_APP/{name}.kt", harness)
        xml = (ROOT / "app/src/main/res/xml/preferences.xml").read_text()
        self.assertIn("org.shadowice.flocke.andotp.Preferences.NumberPickerPreference", xml)

    def test_picker_keeps_protected_hooks_and_commit_before_veto_dispatch(self):
        source = (KOTLIN / "Preferences/NumberPickerPreference.kt").read_text()
        self.assertIn("open class NumberPickerPreference : DialogPreference", source)
        self.assertIn("this(context, attrs, android.R.attr.dialogPreferenceStyle)", source)
        for hook in ("onCreateDialogView", "onBindDialogView", "onDialogClosed", "onGetDefaultValue", "onSetInitialValue"):
            self.assertIn(f"protected override fun {hook}(", source)
        self.assertIn("open fun setValue(value: Int)", source)
        self.assertIn("open fun getValue(): Int", source)
        self.assertLess(source.index("picker.clearFocus()"), source.index("callChangeListener(newValue)"))
        self.assertIn("if (callChangeListener(newValue)) setValue(newValue)", source)
        self.assertIn("if (restorePersistedValue) getPersistedInt(minValue) else defaultValue as Int", source)
        self.assertIn("attributes.recycle()", source)

    def test_hide_choice_is_immediate_guarded_and_static_helper_is_not_renamed(self):
        source = (KOTLIN / "Dialogs/HideableDialog.kt").read_text()
        self.assertIn("open class HideableDialog(", source)
        self.assertIn("if (hideSettingId > 0) settings.setBoolean(hideSettingId, isChecked)", source)
        self.assertIn("dontShowAgain.setOnCheckedChangeListener(this)", source)
        self.assertIn("buttonOk.setOnClickListener(this)", source)
        self.assertIn("@JvmStatic\n        fun ShowHidableDialog(", source)
        self.assertIn("if (!settings.getBoolean(hideSettingId, false))", source)
        self.assertIn("Tools.getThemeResource(context, R.attr.dialogTheme)", source)
        for forbidden in ("EncryptionHelper", "VaultSession", "CoroutineScope"):
            self.assertNotIn(forbidden, source)

    def test_styleable_stand_in_has_an_array_and_distinct_attribute_slots(self):
        spec = importlib.util.spec_from_file_location("sandbox_r_generator", SANDBOX / "generate-r-stub.py")
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        attrs = generator.styleable_declarations()["NumberPickerPreference"]
        self.assertEqual(["maxValue", "minValue", "wrapSelectorWheel"], attrs)
        rendered = generator.render(generator.collect())
        self.assertIn("@JvmField val NumberPickerPreference = intArrayOf(0, 1, 2)", rendered)
        slots = dict(re.findall(r"const val NumberPickerPreference_(\w+) = (\d+)", rendered))
        self.assertEqual({name: str(i) for i, name in enumerate(attrs)}, slots)
        # The checked-in stand-in must come only from the generator, never hand-edited resource ids.
        self.assertEqual(rendered, (SANDBOX / "stubs/org/shadowice/flocke/andotp/R.kt").read_text())
