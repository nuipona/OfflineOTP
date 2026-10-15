// Compile-only dialog surface. No layout inflation, window or preference behavior is simulated.
package androidx.appcompat.app

import android.content.Context
import android.view.View

open class AppCompatDialog(context: Context, theme: Int) {
    fun setTitle(resource: Int) {}
    fun setContentView(resource: Int) {}
    fun <T : View> findViewById(id: Int): T? = null
    open fun dismiss() {}
    fun show() {}
}
