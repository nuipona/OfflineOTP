// Sandbox-only stand-ins, see android_content.kt.
package android.content.res

import android.os.LocaleList

/**
 * Inert resource view. `open` since the settings checks substitute a resource view with distinct,
 * check-owned values (the converted `Settings` class resolves preference keys and defaults through
 * string ids). Nothing here parses resources, and no value is aapt's.
 */
open class Resources {
    fun obtainTypedArray(id: Int): TypedArray = TypedArray()
    fun getValue(id: Int, value: android.util.TypedValue, resolveRefs: Boolean) {}

    /** Resolves to the id itself so checks can tell which dimension was read. */
    fun getDimensionPixelSize(id: Int): Int = id

    class Theme {
        fun obtainStyledAttributes(attrs: IntArray): TypedArray = TypedArray()
    }

    /** Resolves to the id itself, like [getDimensionPixelSize], so checks can tell them apart. */
    open fun getInteger(id: Int): Int = id

    /** Recognisable placeholder; the settings checks override it with their own values. */
    open fun getString(id: Int): String = "res:$id"

    /** Empty by default: no XML array is read by this harness. */
    open fun getStringArray(id: Int): Array<String> = emptyArray()

    /** Inert density metrics; the settings checks supply their own. */
    open val displayMetrics: android.util.DisplayMetrics get() = android.util.DisplayMetrics()

    open val configuration: Configuration get() = Configuration()

    /**
     * Records the configuration update. The platform method is deprecated but the converted base
     * activity still uses it to apply the selected language.
     */
    open fun updateConfiguration(config: Configuration, metrics: android.util.DisplayMetrics) {}

    companion object {
        @JvmStatic
        fun getSystem(): Resources = Resources()
    }
}

class Configuration {
    val locales: LocaleList get() = LocaleList()

    /** The locale that was set; the platform's own `getLocale()` was removed in API 24. */
    var currentLocale: java.util.Locale? = null
        private set

    /** The platform setter, kept because the converted base activity applies the language with it. */
    fun setLocale(locale: java.util.Locale) {
        currentLocale = locale
    }

    /**
     * Runtime field like the platform's: the settings checks drive the `auto` theme branch through
     * it. The bitmask values below are the platform's constants.
     */
    var uiMode: Int = UI_MODE_NIGHT_UNDEFINED

    companion object {
        const val UI_MODE_NIGHT_MASK: Int = 0x30
        const val UI_MODE_NIGHT_NO: Int = 0x10
        const val UI_MODE_NIGHT_YES: Int = 0x20
        const val UI_MODE_NIGHT_UNDEFINED: Int = 0x00
    }
}

/** Default-returning typed array; explicit values in sandbox checks are not real XML resolution. */
open class TypedArray {
    open fun getInteger(index: Int, defValue: Int): Int = defValue
    open fun getBoolean(index: Int, defValue: Boolean): Boolean = defValue
    open fun getInt(index: Int, defValue: Int): Int = defValue
    fun getColor(index: Int, defValue: Int): Int = defValue
    fun getResourceId(index: Int, defValue: Int): Int = defValue
    open fun getString(index: Int): String? = null
    open fun recycle() {}
}
