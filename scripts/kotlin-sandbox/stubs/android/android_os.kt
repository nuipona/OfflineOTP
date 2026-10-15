// Sandbox-only stand-ins, see android_content.kt.
package android.os

import java.util.Locale

/** Field/type-only stand-in for the platform window-token binder. */
interface IBinder

/**
 * `postDelayed` stays inert; `post` queues what the task runner delivers so the checks can drain
 * the "main thread" deterministically. No looper is created and no platform timing is exercised.
 */
open class Handler(private val looper: Looper?) {
    fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean = true
    fun removeCallbacks(runnable: Runnable) {}

    open fun post(runnable: Runnable): Boolean {
        posted.offer(runnable)
        return true
    }

    companion object {
        /** Deliveries waiting for the fake main loop; drained by Checks.kt. */
        @JvmField
        val posted: java.util.Queue<Runnable> = java.util.concurrent.ConcurrentLinkedQueue()
    }
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

/** Field/constant-only stand-in for the version gate the Java sources branch on. */
object Build {
    object VERSION {
        const val SDK_INT: Int = 28
    }
}

/** Field/type-only stand-in for the descriptor the storage helper opens documents through. */
class ParcelFileDescriptor private constructor() {
    class AutoCloseOutputStream(pfd: ParcelFileDescriptor?) : java.io.FileOutputStream(java.io.FileDescriptor())
}
