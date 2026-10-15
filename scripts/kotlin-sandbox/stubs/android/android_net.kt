// Sandbox-only stand-ins, see android_content.kt.
package android.net

/**
 * Accessor-level stand-in: the checked sources never parse real URI contents, they only route on
 * scheme/last-segment and bound the string length, so every accessor returns an inert value.
 */
class Uri() {
    override fun toString(): String = "stub:uri"

    fun getScheme(): String? = null
    fun getPath(): String? = null
    fun getLastPathSegment(): String? = null
    fun getQueryParameter(key: String?): String? = null
    fun buildUpon(): Builder = Builder()

    class Builder {
        fun scheme(scheme: String?): Builder = this
        fun opaquePart(part: String?): Builder = this
        fun encodedOpaquePart(part: String?): Builder = this
        fun appendQueryParameter(key: String?, value: String?): Builder = this
        fun clearQuery(): Builder = this
        fun build(): Uri = Uri()
    }

    companion object {
        @JvmStatic
        val EMPTY: Uri = Uri()

        @JvmStatic
        fun parse(uri: String?): Uri = Uri()

        @JvmStatic
        fun fromFile(file: java.io.File?): Uri = Uri()
    }
}
