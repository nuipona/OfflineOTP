// Sandbox-only stand-in for the framework resource class (see android_content.kt).
package android

object R {
    object attr {
        const val dialogPreferenceStyle = 16842897
    }
    object layout {
        const val simple_list_item_multiple_choice = 17367056
    }
    object string {
        /** The real public framework value of android.R.string.ok. */
        const val ok = 16908301
        /** Distinct stand-in for android.R.string.cancel; checks must not depend on the value. */
        const val cancel = 16908302
    }
}
