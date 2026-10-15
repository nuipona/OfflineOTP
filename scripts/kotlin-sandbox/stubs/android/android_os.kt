// Sandbox-only stand-ins, see android_content.kt.
package android.os

import java.util.Locale

class Handler(private val looper: Looper?) {
    fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean = true
    fun removeCallbacks(runnable: Runnable) {}
}

class Looper private constructor() {
    companion object {
        @JvmStatic
        fun getMainLooper(): Looper = Looper()
    }
}

class SystemClock private constructor() {
    companion object {
        @JvmStatic
        fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
    }
}

class Environment private constructor() {
    companion object {
        const val MEDIA_MOUNTED = "mounted"
        const val MEDIA_MOUNTED_READ_ONLY = "mounted_ro"

        @JvmStatic
        fun getExternalStorageState(): String = MEDIA_MOUNTED
    }
}

class LocaleList {
    operator fun get(index: Int): Locale = Locale.ENGLISH
    val size: Int get() = 1
}
