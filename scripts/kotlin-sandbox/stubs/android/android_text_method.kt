// Sandbox-only stand-ins, see android_content.kt.
package android.text.method

/** Field/type-only stand-in for the platform interface. */
interface TransformationMethod

class PasswordTransformationMethod private constructor() : TransformationMethod {
    companion object {
        @JvmStatic
        fun getInstance(): PasswordTransformationMethod = PasswordTransformationMethod()
    }
}
