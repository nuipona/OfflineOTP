// Sandbox-only stand-ins, see android_content.kt.
package android.content.res

import android.os.LocaleList

class Resources {
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

class TypedArray {
    fun getColor(index: Int, defValue: Int): Int = defValue
    fun getResourceId(index: Int, defValue: Int): Int = defValue
    fun recycle() {}
}
