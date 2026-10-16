// Compile-only stand-ins for the AboutLibraries entry points the converted about screen calls.
// No library metadata is parsed and no UI is built: the checks read back the JSON the builder
// received and the visibility/version flags the entry point was configured with.
package com.mikepenz.aboutlibraries

import androidx.fragment.app.Fragment

/** The parsed library list holder; the platform class keeps the parsed metadata. */
class Libs private constructor(val json: String) {
    class Builder {
        private var json: String = "{}"

        fun withJson(json: String): Builder {
            this.json = json
            return this
        }

        fun build(): Libs = Libs(json)
    }
}

/** The fragment entry point of the library; the flags are recorded for the checks. */
class LibsFragment(val libs: Libs?, val licenseShown: Boolean, val versionShown: Boolean) : Fragment()

/** Builder stand-in mirroring the fluent calls of the about screen. */
class LibsBuilder {
    private var libs: Libs? = null
    private var licenseShown = false
    private var versionShown = false

    fun withLibs(libs: Libs): LibsBuilder {
        this.libs = libs
        return this
    }

    fun withLicenseShown(shown: Boolean): LibsBuilder {
        licenseShown = shown
        return this
    }

    fun withVersionShown(shown: Boolean): LibsBuilder {
        versionShown = shown
        return this
    }

    fun supportFragment(): Fragment = LibsFragment(libs, licenseShown, versionShown)
}
