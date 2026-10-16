// Sandbox-only stand-ins, see android_content.kt.
package android.view.animation

/** The interpolator the animations use. */
interface Interpolator

/** The linear interpolator of the progress animations. */
open class LinearInterpolator : Interpolator

/** The accelerate/decelerate interpolator of the intro warning flash. */
open class AccelerateDecelerateInterpolator : Interpolator
