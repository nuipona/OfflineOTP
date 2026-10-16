// Sandbox-only stand-ins, see android_content.kt.
package android.content.res

import android.os.LocaleList

class Resources {
    fun obtainTypedArray(id: Int): TypedArray = TypedArray()
    fun getValue(id: Int, value: android.util.TypedValue, resolveRefs: Boolean) {}

    /** Resolves to the id itself so checks can tell which dimension was read. */
    fun getDimensionPixelSize(id: Int): Int = id

    class Theme {
        fun obtainStyledAttributes(attrs: IntArray): TypedArray = TypedArray()
    }

    val configuration: Configuration get() = Configuration()

    companion object {
        @JvmStatic
        fun getSystem(): Resources = Resources()
    }
}

class Configuration {
    val locales: LocaleList get() = LocaleList()
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
