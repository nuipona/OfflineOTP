// Sandbox-only stand-ins, see android_content.kt.
package android.preference

import android.content.Context
import android.content.SharedPreferences

/**
 * Framework (not androidx) PreferenceManager, as referenced by the resolved Java sources and by the
 * converted `Settings` class.
 *
 * [getDefaultSharedPreferences] returns an in-memory [FakeSharedPreferences] rather than an inert
 * value, because the settings checks *execute* `Settings`: preference keys, string sets, typed
 * defaults, the credential transaction's single commit and the audit of which keys were written are
 * observed through it. This is not the platform's `SharedPreferencesImpl`: no file backing, no
 * asynchronous `apply`, no multi-process or type-mismatch behaviour, and the recorded
 * [setDefaultValues] call does not inflate the XML preference resources.
 */
object PreferenceManager {
    private var store: SharedPreferences = FakeSharedPreferences()

    /** Resource ids that [setDefaultValues] was called with, in call order. */
    val defaultValuesCalls: MutableList<Int> = ArrayList()

    @JvmStatic
    fun getDefaultSharedPreferences(context: Context): SharedPreferences = store

    /** Recorded only; no XML resource is read and no default is written. */
    @JvmStatic
    fun setDefaultValues(context: Context, resId: Int, readAgain: Boolean) {
        defaultValuesCalls.add(resId)
    }

    /** Discards the shared store and the recorded calls so a check starts from a clean state. */
    fun reset() {
        store = FakeSharedPreferences()
        defaultValuesCalls.clear()
    }
}

/**
 * Functional in-memory [SharedPreferences] for the executed settings checks: typed values, string
 * sets copied on write and on read (the platform acknowledges mutations in place), a synchronous
 * `apply`, a `commit` that always succeeds, and a listener registry that records registrations
 * without delivering callbacks. Nothing is persisted, cleared on process death or observed by the
 * platform.
 */
class FakeSharedPreferences : SharedPreferences {
    private val values = LinkedHashMap<String?, Any?>()

    /** Registrations recorded by the converted code; no callback is ever delivered. */
    val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    var editCount = 0
    var commitCount = 0
    var applyCount = 0

    /** A copy, non-null-keyed like the interface's return type (the platform rejects null keys). */
    override fun getAll(): Map<String, *> = values.mapKeys { it.key ?: "" }

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    /** Stored sets are copied out, so a caller cannot mutate the stored value in place. */
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? {
        val stored = values[key] as? Set<String> ?: return defValues
        return HashSet(stored)
    }

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor {
        editCount += 1
        return FakeEditor()
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listeners.remove(listener)
    }

    /** The value as the platform would hand it back: sets are copied, not shared. */
    @Suppress("UNCHECKED_CAST")
    fun stringSet(key: String): Set<String> = HashSet(values[key] as Set<String>)

    inner class FakeEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String?, Any?>()
        private val removals = HashSet<String?>()
        private var cleared = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (value == null) removals.add(key) else pending[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (values == null) removals.add(key) else pending[key] = HashSet(values)
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = putPending(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = putPending(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = putPending(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            removals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            cleared = true
            return this
        }

        override fun commit(): Boolean {
            commitCount += 1
            applyToStore()
            return true
        }

        override fun apply() {
            applyCount += 1
            applyToStore()
        }

        private fun putPending(key: String?, value: Any?): SharedPreferences.Editor {
            if (value == null) removals.add(key) else pending[key] = value
            return this
        }

        private fun applyToStore() {
            if (cleared) values.clear()
            for (key in removals) values.remove(key)
            values.putAll(pending)
        }
    }
}

/**
 * Recording superclass for the preference/dialog control flow only. No PreferenceManager,
 * real persistence, lifecycle, listener delivery or summary rendering is implemented here.
 */
open class DialogPreference(val context: Context, attrs: android.util.AttributeSet?, defStyleAttr: Int) {
    constructor(context: Context, attrs: android.util.AttributeSet?) : this(context, attrs, 0)

    var summary: CharSequence? = null
    open fun callChangeListener(value: Any?): Boolean = true
    protected open fun persistInt(value: Int): Boolean = true
    protected open fun getPersistedInt(defaultValue: Int): Int = defaultValue
    protected open fun persistString(value: String): Boolean = true
    protected open fun getPersistedString(defaultValue: String): String = defaultValue
    open fun setDialogLayoutResource(resource: Int) {}
    open fun getDialog(): android.app.AlertDialog? = null
    protected open fun onCreateDialogView(): android.view.View = android.view.View()
    protected open fun onBindDialogView(view: android.view.View) {}
    protected open fun onDialogClosed(positiveResult: Boolean) {}
    protected open fun onPrepareDialogBuilder(builder: android.app.AlertDialog.Builder) {}
    protected open fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any? = null
    protected open fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {}
}
