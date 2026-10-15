// Sandbox-only stand-in, see android_content.kt.
package android.annotation

@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION,
        AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.CONSTRUCTOR,
        AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class SuppressLint(vararg val value: String)
