/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Activities

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

import org.shadowice.flocke.andotp.R
import org.shadowice.flocke.andotp.Utilities.Settings
import org.shadowice.flocke.andotp.Utilities.Tools

import java.util.Locale

/**
 * Base activity that applies the selected theme and language.
 *
 * Converted from the Java class of the same name. The theme is still applied before
 * `super.onCreate()`, which is what AppCompat expects, and the locale handling is unchanged.
 */
abstract class ThemedActivity : AppCompatActivity() {
    /**
     * The settings of this activity.
     *
     * The Java field was public and assigned in `onCreate`; it stays a public field so the Java
     * activities keep reading it directly. Kotlin cannot declare a public field that is assigned
     * later (`@JvmField` cannot be combined with `lateinit`), so the field is nullable: it is
     * null before `onCreate`, exactly as it was in Java, and the `!!` uses below fail with the
     * same `NullPointerException` the Java field access would have failed with.
     */
    @JvmField
    var settings: Settings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val current = Settings(this)
        settings = current

        setTheme(current.getTheme())
        setLocale()

        // Match the navigation bar to the theme colors
        getWindow().setNavigationBarColor(Tools.getThemeColor(this, R.attr.navigationBarColor))

        super.onCreate(savedInstanceState)
    }

    public override fun onResume() {
        setLocale()

        super.onResume()
    }

    /** Applies the selected language to this activity. */
    public fun setLocale() {
        val locale: Locale = settings!!.getLocale()
        Locale.setDefault(locale)

        val resources: Resources = getBaseContext().resources
        val config: Configuration = resources.configuration
        config.setLocale(locale)

        // TODO: updateConfiguration is marked as deprecated. Replace with android.content.Context.createConfigurationContext
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
