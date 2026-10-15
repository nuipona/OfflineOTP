// Sandbox-only stand-ins for the Android platform surface used by the checked classes.
// They exist so the Kotlin sources can be type-checked without the Android SDK. They are NOT
// the Android platform and prove nothing about SDK behaviour. Real Java getters are modelled as
// Kotlin properties because Kotlin only synthesises properties for Java declarations.
package android.content

import android.content.res.Resources
import android.net.Uri

open class Context {
    val theme: Resources.Theme get() = Resources.Theme()
    val resources: Resources get() = Resources()
    fun getString(resId: Int): String = "stub"
    /** `open` so checks can return a recording service instead of the null default. */
    open fun getSystemService(name: String): Any? = null
    /** The task classes keep only the application context; the checks substitute their own. */
    open fun getApplicationContext(): Context = this
    fun getFilesDir(): java.io.File = java.io.File(".")
    fun getContentResolver(): ContentResolver = ContentResolver()

    companion object {
        const val CLIPBOARD_SERVICE = "clipboard"
        const val INPUT_METHOD_SERVICE = "input_method"
    }
}

/**
 * Field/type-only stand-in for the resolver surface the resolved Java sources mention. It only
 * needs to type-check; the sandbox never executes a document provider through it.
 */
class ContentResolver {
    fun openFileDescriptor(uri: Uri?, mode: String?): android.os.ParcelFileDescriptor? = null
    fun query(uri: Uri?, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): android.database.Cursor? = null
    fun openInputStream(uri: Uri?): java.io.InputStream? = null
    fun openOutputStream(uri: Uri?): java.io.OutputStream? = null
    fun getType(uri: Uri?): String? = null
}

/** Field/type-only stand-in: the Java settings/database sources read and write through this. */
interface SharedPreferences {
    fun getAll(): Map<String, *>?
    fun getString(key: String?, defValue: String?): String?
    fun getInt(key: String?, defValue: Int): Int
    fun getLong(key: String?, defValue: Long): Long
    fun getBoolean(key: String?, defValue: Boolean): Boolean
    fun contains(key: String?): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String?, value: String?): Editor
        fun putStringSet(key: String?, values: Set<String>?): Editor
        fun putInt(key: String?, value: Int): Editor
        fun putLong(key: String?, value: Long): Editor
        fun putBoolean(key: String?, value: Boolean): Editor
        fun remove(key: String?): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }

    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?)
    }
}

class ClipData private constructor() {
    companion object {
        @JvmStatic
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData = ClipData()
    }
}

class ClipboardManager {
    fun setPrimaryClip(clip: ClipData) {}
}

/** Field/type-only stand-in; the checked code never touches Intent internals. */
class Intent

/** The nested listener mirrors the platform nesting; `fun interface` keeps SAM conversion working. */
interface DialogInterface {
    fun interface OnClickListener {
        fun onClick(dialog: DialogInterface, which: Int)
    }

    companion object {
        const val BUTTON_POSITIVE = -1
    }
}
