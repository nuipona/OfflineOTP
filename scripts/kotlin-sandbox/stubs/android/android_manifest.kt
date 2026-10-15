// Sandbox-only stand-ins, see android_content.kt.
package android

/** Constant-only stand-in for the permission name the notification helper requests. */
object Manifest {
    object permission {
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}
