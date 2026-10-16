// Sandbox-only stand-ins, see android_content.kt.
package android.os

/**
 * Stand-in for the platform bundle.
 *
 * It is a field/type-only placeholder: the converted base activities only pass it through to
 * `super.onCreate()`. No parceling, no saved state and no configuration change is modelled.
 */
open class Bundle
