// Sandbox-only stand-ins, see android_content.kt.
package android.text.style

/** The style span the card label uses for the bold issuer. */
class StyleSpan(private val style: Int) {
    fun getStyle(): Int = style
}
