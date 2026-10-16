// Sandbox-only stand-ins for the Android platform surface used by the checked classes.
// They exist so the Kotlin sources can be type-checked without the Android SDK. They are NOT
// the Android platform and prove nothing about SDK behaviour. Real Java getters are modelled as
// Kotlin properties because Kotlin only synthesises properties for Java declarations.
package android.content

import android.content.res.Resources
import android.net.Uri

open class Context {
    /** Open only to let the preference check supply explicit fake attribute values. */
    open fun obtainStyledAttributes(attrs: android.util.AttributeSet?, styleable: IntArray): android.content.res.TypedArray = android.content.res.TypedArray()
    val theme: Resources.Theme get() = Resources.Theme()
    /**
     * `open` so the settings checks can substitute a resource view with distinct, check-owned values;
     * the default returns an inert [Resources].
     */
    open val resources: Resources get() = Resources()
    /**
     * `open` so the settings checks can map a resource id to a distinct key string. The default is a
     * recognisable placeholder, not a real resource lookup.
     */
    open fun getString(resId: Int): String = "stub"

    /** Format overload; the result stays a recognisable placeholder, arguments are not applied. */
    fun getString(resId: Int, vararg formatArgs: Any?): String = "text:$resId"
    /** Resolves to a recognisable placeholder so the checks can tell which resource was read. */
    fun getText(resId: Int): CharSequence = "text:$resId"
    /** `open` so a fake activity can grant or deny; the default denies, like an ungranted app. */
    open fun checkSelfPermission(permission: String): Int = android.content.pm.PackageManager.PERMISSION_DENIED
    /** `open` so checks can return a recording service instead of the null default. */
    open fun getSystemService(name: String): Any? = null
    /** The task classes keep only the application context; the checks substitute their own. */
    open fun getApplicationContext(): Context = this
    fun getFilesDir(): java.io.File = java.io.File(".")
    /** `open` so the storage checks can substitute a recording resolver for the inert default. */
    open fun getContentResolver(): ContentResolver = ContentResolver()
    /** `open` so the storage checks can supply their own private cache directory. */
    open fun getCacheDir(): java.io.File = java.io.File(".")

    companion object {
        const val CLIPBOARD_SERVICE = "clipboard"
        const val INPUT_METHOD_SERVICE = "input_method"
        const val NOTIFICATION_SERVICE = "notification"
    }
}

/**
 * Resolver surface used by the storage helper. The defaults are inert (`null`, like a provider that
 * returns nothing); the storage checks subclass it with a recording fake that serves their own
 * documents. No content provider, permission or URI-grant behaviour of the platform is modelled.
 */
open class ContentResolver {
    open fun openFileDescriptor(uri: Uri?, mode: String?): android.os.ParcelFileDescriptor? = null
    open fun query(uri: Uri?, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): android.database.Cursor? = null
    open fun openInputStream(uri: Uri?): java.io.InputStream? = null
    open fun openOutputStream(uri: Uri?): java.io.OutputStream? = null
    open fun getType(uri: Uri?): String? = null
}

/**
 * Field/type-only stand-in for the preference interface. The converted `Settings` class is
 * *executed* against it through [android.preference.PreferenceManager]'s in-memory implementation;
 * this interface itself models no persistence, listener delivery or type-mismatch behaviour.
 */
interface SharedPreferences {
    fun getAll(): Map<String, *>?
    fun getString(key: String?, defValue: String?): String?
    fun getStringSet(key: String?, defValues: Set<String>?): Set<String>?
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

    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?)
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?)

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
    fun dismiss()

    fun interface OnClickListener {
        fun onClick(dialog: DialogInterface, which: Int)
    }

    companion object {
        const val BUTTON_NEGATIVE = -2
        const val BUTTON_POSITIVE = -1
        const val BUTTON_NEUTRAL = -3
    }
}
