// Sandbox-only stand-ins, see ../../../../android/android_content.kt.
package androidx.core.content.res

import android.content.res.Resources
import android.graphics.drawable.Drawable

/** The drawable lookup of the manual entry dialog. */
object ResourcesCompat {
    /** The drawables that were looked up, in order. */
    @JvmField
    val lookedUp: MutableList<Int> = ArrayList()

    @JvmStatic
    fun getDrawable(resources: Resources, id: Int, theme: android.content.res.Resources.Theme?): Drawable? {
        lookedUp.add(id)
        return null
    }
}
