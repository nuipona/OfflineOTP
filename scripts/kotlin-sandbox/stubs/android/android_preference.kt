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
