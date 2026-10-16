// Sandbox-only stand-ins, see android_content.kt.
package android.content.pm

/**
 * Constant-only stand-in for the permission-check results, plus the package metadata lookup the
 * about screen performs. [packageInfo] is settable so the checks can serve a version name and a
 * [NameNotFoundException] instead of a real package query.
 */
object PackageManager {
    const val PERMISSION_GRANTED = 0
    const val PERMISSION_DENIED = -1

    /** The metadata [getPackageInfo] serves; the platform resolves it from the installed packages. */
    var packageInfo: PackageInfo = PackageInfo()

    /** A package name that reports [NameNotFoundException] instead of [packageInfo]. */
    var missingPackage: String? = null

    fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
        if (packageName == missingPackage) throw NameNotFoundException(packageName)
        return packageInfo
    }

    class NameNotFoundException(name: String) : Exception(name)
}

/** The version metadata of one package; the platform also fills the requested flags' fields. */
class PackageInfo {
    @JvmField
    var versionName: String? = null
}
