package org.shadowice.flocke.andotp.Utilities;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.R;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Real-platform characterization of the Kotlin ScanQRCodeFromFile/NotificationHelper and their
 * Java call sites: real BitmapFactory + zxing decoding of a generated code, and real notification
 * channels. The desktop sandbox only drives scripted stand-ins. Synthetic data only; run on
 * disposable emulators (channel importance is asserted as created, not as a user may change it).
 */
@RunWith(AndroidJUnit4.class)
public class KotlinNotificationQrInteropTest {
    private static final String OTP_URI = "otpauth://totp/synthetic:fixture?secret=GEZDGNBVGY3TQOJQ&issuer=synthetic";

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    /** Failure paths show toasts, which need a looper thread. */
    private String scanOnMainThread(Uri uri) {
        AtomicReference<String> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> result.set(ScanQRCodeFromFile.scanQRImage(context, uri)));
        return result.get();
    }

    private File writePng(String name, Bitmap bitmap) throws Exception {
        File file = new File(context.getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    @Test public void generatedCodesRoundTripAndUnreadableImagesReturnNull() throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(OTP_URI, BarcodeFormat.QR_CODE, 240, 240);
        Bitmap code = Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
        for (int x = 0; x < matrix.getWidth(); x++)
            for (int y = 0; y < matrix.getHeight(); y++)
                code.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
        File png = writePng("qr-interop-code.png", code);
        File blank = writePng("qr-interop-blank.png", Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888));
        File notAnImage = new File(context.getCacheDir(), "qr-interop-text.png");
        try (FileOutputStream output = new FileOutputStream(notAnImage)) { output.write("not an image".getBytes()); }
        try {
            // The Java call site and the real decode path.
            String contents = scanOnMainThread(Uri.fromFile(png));
            assertEquals(OTP_URI, contents);
            // No code in the image, an undecodable file, a refused location and no URI: null.
            assertNull(scanOnMainThread(Uri.fromFile(blank)));
            assertNull(scanOnMainThread(Uri.fromFile(notAnImage)));
            assertNull(scanOnMainThread(Uri.fromFile(new File(context.getFilesDir(), "qr-interop-code.png"))));
            assertNull(scanOnMainThread(null));
        } finally {
            assertTrue(png.delete() && blank.delete() && notAnImage.delete());
        }
    }

    @Test public void channelsAreCreatedWithTheirNamesAndImportanceAndPostingDoesNotThrow() {
        NotificationHelper.initializeNotificationChannels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        // Ids are derived exactly as the Java did: "andOTP_" + the default-locale lower-cased name.
        NotificationChannel failed = manager.getNotificationChannel(
                "andOTP_" + Constants.NotificationChannel.BACKUP_FAILED.name().toLowerCase());
        NotificationChannel success = manager.getNotificationChannel(
                "andOTP_" + Constants.NotificationChannel.BACKUP_SUCCESS.name().toLowerCase());
        assertNotNull(failed);
        assertNotNull(success);
        assertEquals(context.getString(R.string.notification_channel_name_backup_failed), failed.getName().toString());
        assertEquals(context.getString(R.string.notification_channel_desc_backup_failed), failed.getDescription());
        assertEquals(NotificationManager.IMPORTANCE_HIGH, failed.getImportance());
        assertEquals(context.getString(R.string.notification_channel_name_backup_success), success.getName().toString());
        assertEquals(NotificationManager.IMPORTANCE_LOW, success.getImportance());

        // Both overloads stay callable from Java; without the API 33+ grant the post is dropped
        // by the platform, which must not surface as an exception either.
        try {
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_SUCCESS, R.string.app_name, "synthetic body");
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.app_name, R.string.app_name);
            NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_FAILED, R.string.app_name, (String) null);
        } finally {
            manager.cancel(1);
        }
    }
}
