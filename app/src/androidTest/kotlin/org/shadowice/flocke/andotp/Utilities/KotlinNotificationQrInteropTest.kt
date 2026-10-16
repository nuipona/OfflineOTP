package org.shadowice.flocke.andotp.Utilities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/** Real BitmapFactory/zxing decoding and notification channels for the converted helpers. */
@RunWith(AndroidJUnit4::class)
class KotlinNotificationQrInteropTest {
    companion object {
        private const val OTP_URI =
            "otpauth://totp/synthetic:fixture?secret=GEZDGNBVGY3TQOJQ&issuer=synthetic"
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Failure paths show toasts, which need a looper thread. */
    private fun scanOnMainThread(uri: Uri?): String? {
        val result = AtomicReference<String?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result.set(ScanQRCodeFromFile.scanQRImage(context, uri))
        }
        return result.get()
    }

    private fun writePng(name: String, bitmap: Bitmap): File {
        val file = File(context.cacheDir, name)
        try {
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    @Test
    fun generatedCodesRoundTripAndUnreadableImagesReturnNull() {
        val matrix: BitMatrix = QRCodeWriter().encode(OTP_URI, BarcodeFormat.QR_CODE, 240, 240)
        val code = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                code.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        val png = writePng("qr-interop-code.png", code)
        val blank = writePng(
            "qr-interop-blank.png",
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        )
        val notAnImage = File(context.cacheDir, "qr-interop-text.png")
        FileOutputStream(notAnImage).use { output -> output.write("not an image".toByteArray()) }
        try {
            val contents = scanOnMainThread(Uri.fromFile(png))
            assertEquals(OTP_URI, contents)
            assertNull(scanOnMainThread(Uri.fromFile(blank)))
            assertNull(scanOnMainThread(Uri.fromFile(notAnImage)))
            assertNull(scanOnMainThread(Uri.fromFile(File(context.filesDir, "qr-interop-code.png"))))
            assertNull(scanOnMainThread(null))
        } finally {
            assertTrue(png.delete() && blank.delete() && notAnImage.delete())
        }
    }

    @Test
    fun channelsAreCreatedWithTheirNamesAndImportanceAndPostingDoesNotThrow() {
        NotificationHelper.initializeNotificationChannels(context)
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val failed = requireNotNull(manager.getNotificationChannel(
            "andOTP_" + Constants.NotificationChannel.BACKUP_FAILED.name.lowercase()
        ))
        val success = requireNotNull(manager.getNotificationChannel(
            "andOTP_" + Constants.NotificationChannel.BACKUP_SUCCESS.name.lowercase()
        ))
        assertNotNull(failed)
        assertNotNull(success)
        assertEquals(
            context.getString(R.string.notification_channel_name_backup_failed),
            failed.name.toString()
        )
        assertEquals(context.getString(R.string.notification_channel_desc_backup_failed), failed.description)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, failed.importance)
        assertEquals(
            context.getString(R.string.notification_channel_name_backup_success),
            success.name.toString()
        )
        assertEquals(NotificationManager.IMPORTANCE_LOW, success.importance)

        try {
            NotificationHelper.notify(
                context, Constants.NotificationChannel.BACKUP_SUCCESS, R.string.app_name, "synthetic body"
            )
            NotificationHelper.notify(
                context, Constants.NotificationChannel.BACKUP_FAILED, R.string.app_name, R.string.app_name
            )
            NotificationHelper.notify(
                context, Constants.NotificationChannel.BACKUP_FAILED, R.string.app_name, null as String?
            )
        } finally {
            manager.cancel(1)
        }
    }
}
