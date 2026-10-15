// Sandbox-only value stand-in; no autofill service or platform text insertion is exercised.
package android.view.autofill

class AutofillValue private constructor(val textValue: CharSequence?, val isText: Boolean) {
    companion object {
        @JvmStatic fun forText(text: CharSequence): AutofillValue = AutofillValue(text, true)
        @JvmStatic fun forToggle(value: Boolean): AutofillValue = AutofillValue(null, false)
    }
}
