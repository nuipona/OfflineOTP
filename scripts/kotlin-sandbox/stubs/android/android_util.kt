// Sandbox-only stand-ins, see android_content.kt.
package android.util

/** Public density/xdpi fields are read by the converted code; the values are set by the checks. */
class DisplayMetrics {
    @JvmField var density: Float = 0f
    @JvmField var scaledDensity: Float = 0f
    @JvmField var xdpi: Float = 0f
    @JvmField var ydpi: Float = 0f
}

/**
 * Contains the platform's dimension constants and a *replication* of the documented
 * `applyDimension` formula (AOSP `TypedValue.applyDimension`). The replication is what makes the
 * density-dependent expectations in Checks.kt meaningful, so it is listed as an approximation in
 * the sandbox README rather than as verified platform behavior.
 */
object TypedValue {
    const val COMPLEX_UNIT_PX = 0
    const val COMPLEX_UNIT_DIP = 1
    const val COMPLEX_UNIT_SP = 2
    const val COMPLEX_UNIT_PT = 3
    const val COMPLEX_UNIT_IN = 4
    const val COMPLEX_UNIT_MM = 5

    @JvmStatic
    fun applyDimension(unit: Int, value: Float, metrics: DisplayMetrics): Float = when (unit) {
        COMPLEX_UNIT_PX -> value
        COMPLEX_UNIT_DIP -> value * metrics.density
        COMPLEX_UNIT_SP -> value * metrics.scaledDensity
        COMPLEX_UNIT_PT -> value * metrics.xdpi * (1.0f / 72)
        COMPLEX_UNIT_IN -> value * metrics.xdpi
        COMPLEX_UNIT_MM -> value * metrics.xdpi * (1.0f / 25.4f)
        else -> 0f
    }
}
