/*
 * Copyright (C) 2019-2021 Jakob Nixdorf
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

import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

import org.shadowice.flocke.andotp.Utilities.Settings
import java.util.Locale

/**
 * QR scanner activity that honours the theme, language and screenshot settings.
 *
 * Converted from the Java class of the same name. The theme, the language, the window flags, the
 * secure flag and the hidden system bars are unchanged; the deprecated configuration update is
 * still used, because the platform has no replacement for it in this activity.
 */
open class SecureCaptureActivity : CaptureActivity() {
    /**
     * Overrides [CaptureActivity.initializeContent] to:
     * - block screenshots and non-secure displays (unless enabled in the app settings),
     * - request hardware acceleration,
     * - hide the system bars.
     *
     * Note: the super class calls `android.app.Activity.setContentView(int)` before this method,
     * so the window decor exists by the time [android.view.Window.getInsetsController] is used;
     * calling it earlier would throw an NPE.
     */
    @Suppress("DEPRECATION")
    override fun initializeContent(): DecoratedBarcodeView {
        val settings = Settings(this)

        setTheme(settings.getTheme())
        setLocale(settings)

        // This flag must be set before setting the content view of the activity or dialog.
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // All window flags, that must be set before the window decoration is created, are already set
        // so we are safe to call super here.
        val barcodeScannerView = super.initializeContent()

        if (!settings.getScreenshotsEnabled()) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insetsController = getWindow().getInsetsController()
            if (insetsController != null) {
                insetsController.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                insetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
            }
        } else {
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        return barcodeScannerView
    }

    /** Applies the selected language (this activity does not extend ThemedActivity). */
    @Suppress("DEPRECATION")
    private fun setLocale(settings: Settings) {
        val locale = settings.getLocale()
        Locale.setDefault(locale)

        val resources = getBaseContext().resources
        val config = resources.configuration
        config.setLocale(locale)

        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
