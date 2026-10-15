// Sandbox-only stand-ins, see ../../../../android/android_content.kt.
package androidx.appcompat.content.res

import android.content.Context
import android.graphics.drawable.Drawable

/** Null-returning stand-in for the drawable lookup the thumbnail rendering resolves against. */
object AppCompatResources {
    @JvmStatic
    fun getDrawable(context: Context?, resId: Int): Drawable? = null
}
