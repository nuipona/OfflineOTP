// Sandbox-only stand-ins, see android_content.kt.
package android.graphics.drawable

import android.graphics.Canvas

/** Bounds/draw-only stand-in for the vector thumbnail rendering the resolved Java sources do. */
abstract class Drawable {
    open fun draw(canvas: Canvas?) {}
    open fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {}
    open fun getMinimumWidth(): Int = 1
    open fun getMinimumHeight(): Int = 1
    open fun setAlpha(alpha: Int) {}
    open fun setColorFilter(filter: android.graphics.ColorFilter?) {}
}
