// Sandbox-only stand-ins, see android_content.kt.
package android.preference

import android.content.Context
import android.content.SharedPreferences

/** Framework (not androidx) PreferenceManager, as referenced by the resolved Java sources. */
object PreferenceManager {
    @JvmStatic
    fun getDefaultSharedPreferences(context: Context): SharedPreferences {
        throw UnsupportedOperationException("stub")
    }
}

/**
 * Recording superclass for the number preference's control flow only. No PreferenceManager,
 * real persistence, lifecycle, listener delivery or summary rendering is implemented here.
 */
open class DialogPreference(val context: Context, attrs: android.util.AttributeSet?, defStyleAttr: Int) {
    var summary: CharSequence? = null
    open fun callChangeListener(value: Any?): Boolean = true
    protected open fun persistInt(value: Int): Boolean = true
    protected open fun getPersistedInt(defaultValue: Int): Int = defaultValue
    protected open fun onCreateDialogView(): android.view.View = android.view.View()
    protected open fun onBindDialogView(view: android.view.View) {}
    protected open fun onDialogClosed(positiveResult: Boolean) {}
    protected open fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any? = null
    protected open fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {}
}
