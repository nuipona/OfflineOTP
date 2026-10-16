// Compile-only dialog surface. No layout inflation, window or preference behavior is simulated.
// The recording fields and the scripted `viewProvider` let the checks drive the converted dialogs'
// constructor wiring against fake widgets instead of inflated layouts.
package androidx.appcompat.app

import android.content.Context
import android.view.View

open class AppCompatDialog(val context: Context, theme: Int) {
    var titleResource: Int = 0
        private set
    var contentResource: Int = 0
        private set
    var dismissals: Int = 0
        private set

    fun setTitle(resource: Int) {
        titleResource = resource
    }

    fun setContentView(resource: Int) {
        contentResource = resource
    }

    fun <T : View> findViewById(id: Int): T? = viewProvider?.invoke(id) as T?

    open fun dismiss() {
        dismissals++
    }

    fun show() {}

    companion object {
        /**
         * Scripted child lookup for the checks; the platform resolves inflated children instead.
         * Nothing in production sets this.
         */
        var viewProvider: ((Int) -> View?)? = null
    }
}
