"""Source-level migration guards; Android layout/lifecycle acceptance requires device tests."""
from pathlib import Path
import unittest

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
        # The Java callers keep their exact call sites.
        backup = (JAVA / "Activities/BackupActivity.java").read_text()
        self.assertIn("PasswordEntryDialog.Mode.ENTER", backup)
        self.assertIn("PasswordEntryDialog.Mode.UPDATE", backup)
        manual = (JAVA / "Dialogs/ManualEntryDialog.java").read_text()
        self.assertIn("TagsDialog.show(callingActivity, tagsAdapter, tagsCallable, tagsCallable)", manual)
        for test in ("Dialogs/KotlinPasswordDialogsInteropTest.java",
                     "Preferences/KotlinPasswordEncryptedPreferenceInteropTest.java"):
            self.assertTrue((ROOT / "app/src/androidTest/java/org/shadowice/flocke/andotp" / test).is_file())

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
        self.assertLess(source.index("callback?.onPasswordEntered(passwordInput.text.toString())"),
                        source.index("dismiss()"))
        self.assertIn("if (view.id == R.id.buttonOk)", source)
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
        self.assertIn("persistString(Base64.encodeToString(encBytes, Base64.URL_SAFE))", source)
        self.assertIn("Base64.decode(encValue, Base64.URL_SAFE)", source)
        self.assertIn("EncryptionHelper.decrypt(pair.getPrivate(), encBytes)", source)
        self.assertIn("if (restorePersistedValue) {", source)
        self.assertIn("restoreAndDecrypt(getPersistedString(DEFAULT_VALUE))", source)
        self.assertIn("val initialValue = defaultValue as? String ?: run", source)
        self.assertIn("value = DEFAULT_VALUE\n                return", source)
        self.assertIn("value = initialValue", source)
        self.assertIn("if (value.isNotEmpty())", source)
        self.assertIn("if (passwordConfirm.editableText.toString() == passwordInput.editableText.toString())", source)
        self.assertIn("btnSave.isEnabled = true", source)
        self.assertIn("R.id.btnCancel -> requireNotNull(getDialog()).dismiss()", source)
        self.assertIn("value = passwordInput.text.toString()", source)
        self.assertIn("passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD",
                      source)
        self.assertIn("passwordInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD",
                      source)
        self.assertIn("settings.blockAccessibility", source)
        self.assertIn("settings.blockAutofill", source)
        self.assertIn("builder.setPositiveButton(null as CharSequence?, null)", source)
        self.assertNotIn("!!", source)
