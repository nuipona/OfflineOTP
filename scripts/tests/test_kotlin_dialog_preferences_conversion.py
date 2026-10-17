"""Source-level migration guards; Android layout/lifecycle acceptance requires device tests."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


class KotlinDialogPreferencesConversionTest(unittest.TestCase):
    def test_replacements_keep_the_original_names_and_are_in_the_compile_set(self):
        harness = (SANDBOX / "run.sh").read_text()
        for name in ("Dialogs/PasswordEntryDialog", "Dialogs/TagsDialog",
                     "Preferences/PasswordEncryptedPreference"):
            self.assertFalse((JAVA / f"{name}.java").exists())
            self.assertTrue((KOTLIN / f"{name}.kt").is_file())
            self.assertIn(f"$KOTLIN_APP/{name}.kt", harness)
        xml = (ROOT / "app/src/main/res/xml/preferences.xml").read_text()
        self.assertIn("org.shadowice.flocke.andotp.Preferences.PasswordEncryptedPreference", xml)
        # The callers keep their exact call sites. The backup activity and the manual entry
        # dialog are Kotlin since the twenty-first batch, so the call sites moved with them.
        backup = (KOTLIN / "Activities/BackupActivity.kt").read_text()
        self.assertIn("PasswordEntryDialog.Mode.ENTER", backup)
        self.assertIn("PasswordEntryDialog.Mode.UPDATE", backup)
        manual = (KOTLIN / "Dialogs/ManualEntryDialog.kt").read_text()
        self.assertIn("TagsDialog.show(callingActivity, tagsAdapter, tagsCallable, tagsCallable)", manual)
        for test in ("Dialogs/KotlinPasswordDialogsInteropTest.kt",
                     "Preferences/KotlinPasswordEncryptedPreferenceInteropTest.kt"):
            self.assertTrue((ROOT / "app/src/androidTest/kotlin/org/shadowice/flocke/andotp" / test).is_file())

    def test_password_dialog_keeps_the_confirm_rule_editor_actions_and_callback(self):
        source = (KOTLIN / "Dialogs/PasswordEntryDialog.kt").read_text()
        self.assertIn("open class PasswordEntryDialog(", source)
        self.assertIn("enum class Mode { ENTER, UPDATE }", source)
        self.assertIn("fun interface PasswordEnteredCallback", source)
        self.assertIn("fun onPasswordEntered(newPassword: String)", source)
        self.assertIn("Constants.AUTH_MIN_PASSWORD_LENGTH", source)
        self.assertIn("TextUtils.equals(passwordInput.editableText, passwordConfirm.editableText)", source)
        self.assertIn("dialogMode == Mode.ENTER ||", source)
        # The done/enter action runs the enabled OK; the enter-key release is swallowed after it.
        self.assertLess(source.index("EditorActionHelper.isActionDoneOrKeyboardEnter(actionId, event)"),
                        source.index("EditorActionHelper.isActionUpKeyboardEnter(event)"))
        self.assertIn("if (okButton.isEnabled) okButton.performClick()", source)
        click = source[source.index("// View.OnClickListener callback"):]
        self.assertLess(click.index("callback?.onPasswordEntered(passwordInput.text.toString())"),
                        click.index("dismiss()"))
        self.assertIn("finally", click)
        self.assertIn("override fun dismiss()", source)
        self.assertIn("if (view.id == R.id.buttonOk)", source)
        self.assertIn("passwordInput.isSaveEnabled = false", source)
        self.assertIn("passwordConfirm.isSaveEnabled = false", source)
        self.assertIn("override fun dismiss()", source)
        self.assertIn("passwordInput.setText(\"\")", source)
        # The mode wiring the Java constructor had, in the same branches.
        self.assertIn("passwordConfirm.visibility = View.VISIBLE", source)
        self.assertIn("passwordConfirm.visibility = View.GONE", source)
        self.assertIn("passwordConfirm.setOnEditorActionListener(this)", source)
        self.assertIn("passwordInput.setOnEditorActionListener(this)", source)
        self.assertIn("ConfirmedPasswordTransformationHelper.setup(passwordLayout, passwordInput, passwordConfirm)", source)
        self.assertIn("passwordLayout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)", source)
        self.assertIn("passwordLayout.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)", source)
        self.assertNotIn("!!", source)

    def test_tag_dialog_keeps_the_static_surface_and_adapter_wiring(self):
        source = (KOTLIN / "Dialogs/TagsDialog.kt").read_text()
        # Static-only Java class: the documented object delta with the same Java call shape.
        self.assertIn("object TagsDialog", source)
        self.assertIn("@JvmStatic\n    fun show(", source)
        self.assertIn("newTagCallable: Callable<*>?", source)
        self.assertIn("selectedTagsCallable: Callable<*>?", source)
        self.assertIn("allTags[newTag] = true", source)
        self.assertIn("tagsAdapter.setTags(allTags)", source)
        self.assertIn("if (newTagCallable != null)", source)
        self.assertIn("newTagCallable.call()", source)
        self.assertIn("e.printStackTrace()", source)
        self.assertIn("val checkedTextView = view as CheckedTextView", source)
        self.assertIn("checkedTextView.isChecked = !checkedTextView.isChecked", source)
        self.assertIn("tagsAdapter.setTagState(checkedTextView.text.toString(), checkedTextView.isChecked)", source)
        self.assertIn("tagsSelectionView.adapter = tagsAdapter", source)
        self.assertIn("dialogInterface.dismiss()", source)
        self.assertIn("newTagBuilder.create().show()", source)
        self.assertNotIn("!!", source)

    def test_encrypted_preference_keeps_the_keystore_boundary_and_dialog_flow(self):
        source = (KOTLIN / "Preferences/PasswordEncryptedPreference.kt").read_text()
        self.assertIn("open class PasswordEncryptedPreference(context: Context, attrs: AttributeSet?)", source)
        self.assertIn("DialogPreference(context, attrs)", source)
        self.assertIn("enum class Mode { PASSWORD, PIN }", source)
        self.assertIn("open fun setMode(mode: Mode)", source)
        self.assertIn("KeyStoreHelper.loadOrGenerateAsymmetricKeyPair(context, Constants.KEYSTORE_ALIAS_PASSWORD)",
                      source)
        self.assertIn("EncryptionHelper.encrypt(pair.getPublic(), value.toByteArray(StandardCharsets.UTF_8))", source)
        self.assertIn("if (persistString(encoded)) encryptedValue = encoded", source)
        self.assertIn("private fun restoreAndDecrypt(encValue: String?): String", source)
        self.assertIn("if (encValue.isNullOrEmpty()) return DEFAULT_VALUE", source)
        self.assertIn("Base64.decode(encValue, Base64.URL_SAFE)", source)
        self.assertIn("EncryptionHelper.decrypt(pair.getPrivate(), encBytes)", source)
        self.assertIn("if (restorePersistedValue) {", source)
        self.assertIn("encryptedValue = getPersistedString(DEFAULT_VALUE)", source)
        self.assertIn("val initialValue = defaultValue as? String ?: run", source)
        self.assertIn("encryptedValue = null\n                return", source)
        self.assertIn("encryptAndPersist(initialValue)", source)
        self.assertIn("passwordInput.setText(restoreAndDecrypt(encryptedValue))", source)
        self.assertNotIn("private var value: String", source)
        self.assertIn("passwordInput.isSaveEnabled = false", source)
        self.assertIn("passwordConfirm.isSaveEnabled = false", source)
        self.assertIn("if (passwordConfirm.editableText.toString() == passwordInput.editableText.toString())", source)
        self.assertIn("btnSave.isEnabled = true", source)
        self.assertIn("R.id.btnCancel -> requireNotNull(getDialog()).dismiss()", source)
        self.assertIn("encryptAndPersist(passwordInput.text.toString())", source)
        self.assertIn("override fun onDialogClosed(positiveResult: Boolean)", source)
        self.assertIn("passwordInput.setText(\"\")", source)
        self.assertIn("passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD",
                      source)
        self.assertIn("passwordInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD",
                      source)
        # `Settings` is a Kotlin class since the sixteenth batch, so its getters are called as methods.
        self.assertIn("settings.getBlockAccessibility()", source)
        self.assertIn("settings.getBlockAutofill()", source)
        self.assertIn("builder.setPositiveButton(null as CharSequence?, null)", source)
        self.assertNotIn("!!", source)

    def test_sensitive_password_and_manual_secret_views_cannot_save_instance_state(self):
        android = "{http://schemas.android.com/apk/res/android}"
        layouts = {
            "component_password.xml": ("passwordEdit", "passwordConfirm"),
            "component_authentication.xml": ("passwordEdit", "passwordConfirm"),
            "component_intro_authentication.xml": ("introPasswordEdit", "introPasswordConfirm"),
            "content_authenticate.xml": ("passwordEdit",),
            "dialog_password_entry.xml": ("passwordInput", "passwordConfirm"),
            "dialog_manual_entry.xml": ("manual_secret", "manual_secret_view"),
        }
        for layout, ids in layouts.items():
            with self.subTest(layout=layout):
                root = ET.parse(ROOT / "app/src/main/res/layout" / layout).getroot()
                widgets = {
                    element.get(android + "id", "").split("/")[-1]: element
                    for element in root.iter()
                }
                for view_id in ids:
                    self.assertIn(view_id, widgets, f"{layout} is missing {view_id}")
                    self.assertEqual(
                        "false", widgets[view_id].get(android + "saveEnabled"),
                        f"{layout}/{view_id} can save secret instance state",
                    )

        preference = (KOTLIN / "Preferences/PasswordEncryptedPreference.kt").read_text()
        password_dialog = (KOTLIN / "Dialogs/PasswordEntryDialog.kt").read_text()
        manual_dialog = (KOTLIN / "Dialogs/ManualEntryDialog.kt").read_text()
        self.assertIn("passwordInput.isSaveEnabled = false", preference)
        self.assertIn("passwordConfirm.isSaveEnabled = false", preference)
        self.assertIn("passwordInput.isSaveEnabled = false", password_dialog)
        self.assertIn("passwordConfirm.isSaveEnabled = false", password_dialog)
        self.assertIn("secretInput.isSaveEnabled = false", manual_dialog)
        self.assertIn("secretView.isSaveEnabled = false", manual_dialog)
        self.assertIn("dialog.setOnDismissListener {", manual_dialog)
        self.assertIn("secretInput.setText(\"\")", manual_dialog)
        self.assertNotIn("private var value: String", preference)
