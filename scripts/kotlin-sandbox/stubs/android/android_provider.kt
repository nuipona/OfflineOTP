// Sandbox-only stand-ins, see android_content.kt.
package android.provider

/** Constant-only stand-in for the OpenableColumns projection the storage helper queries. */
object OpenableColumns {
    const val DISPLAY_NAME: String = "_display_name"
    const val SIZE: String = "_size"
}

/** The global settings the main screen reads. */
object Settings {
    /** The global settings values. */
    object Global {
        /** The values that were read, in order. */
        @JvmField
        val reads: MutableList<String> = ArrayList()

        @JvmStatic
        fun getInt(resolver: android.content.ContentResolver, name: String, def: Int): Int {
            reads.add(name)
            return def
        }

        @JvmStatic
        fun getFloat(resolver: android.content.ContentResolver, name: String, def: Float): Float {
            reads.add(name)
            return def
        }

        const val AUTO_TIME = "auto_time"
        const val ANIMATOR_DURATION_SCALE = "animator_duration_scale"
    }
}

/** The documents contract of the backup location picker. */
object DocumentsContract {
    const val EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI"
}
