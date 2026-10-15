"""Source-level guards for the thirteenth batch (notification and QR-image helpers).

The sandbox executes ScanQRCodeFromFile against scripted zxing/BitmapFactory stand-ins and the
permission gate of NotificationHelper; the channel/notification members take the Java-only
Constants.NotificationChannel, so they only compile there. Real decoding, channels and posting
need the Android gates.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


class KotlinNotificationQrConversionTest(unittest.TestCase):
    def test_replacements_keep_their_names_and_are_compiled_by_the_sandbox(self):
        harness = (SANDBOX / "run.sh").read_text()
        for name in ("Utilities/NotificationHelper", "Utilities/ScanQRCodeFromFile"):
            self.assertFalse((JAVA / f"{name}.java").exists())
            self.assertTrue((KOTLIN / f"{name}.kt").is_file())
            self.assertIn(f"$KOTLIN_APP/{name}.kt", harness)
        for stubs in ("androidx/core/app", "androidx/core/content", "com/google/zxing",
                      "com/google/zxing/common", "com/google/zxing/qrcode"):
            self.assertIn(f'"$SANDBOX"/stubs/{stubs}/*.kt', harness)
        checks = (SANDBOX / "Checks.kt").read_text()
        self.assertIn("ScanQRCodeFromFile.scanQRImage(context, uri)", checks)
        self.assertIn("NotificationHelper.requestNotificationPermission(", checks)

    def test_qr_helper_keeps_bounds_recycling_two_passes_and_failure_messages(self):
        source = (KOTLIN / "Utilities/ScanQRCodeFromFile.kt").read_text()
        self.assertIn("object ScanQRCodeFromFile {", source)
        self.assertIn("@JvmStatic\n    fun scanQRImage(context: Context, uri: Uri?): String? {", source)
        self.assertEqual(1, source.count("    @JvmStatic\n"))
        # Hints: QR only, then a copy that adds TRY_HARDER (never the same map).
        self.assertIn("EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)", source)
        self.assertIn("put(DecodeHintType.POSSIBLE_FORMATS, barcodeFormats)", source)
        self.assertIn("EnumMap<DecodeHintType, Any>(HINTS).apply {", source)
        self.assertIn("put(DecodeHintType.TRY_HARDER, java.lang.Boolean.TRUE)", source)
        body = source[source.index("fun scanQRImage("):]
        # Load failures of either kind are the same toast; nothing else is swallowed there.
        self.assertIn("imageInBytes = StorageAccessHelper.loadFile(context, uri)", body)
        self.assertIn("} catch (e: IOException) {", body)
        self.assertIn("} catch (e: RuntimeException) {", body)
        # Bounds first, then the full ARGB_8888 decode, then the decoded size again.
        order = ["options.inJustDecodeBounds = true",
                 "InputPolicy.validImage(options.outWidth, options.outHeight)",
                 "options.inJustDecodeBounds = false",
                 "options.inPreferredConfig = Bitmap.Config.ARGB_8888",
                 "InputPolicy.validImage(bMap.getWidth(), bMap.getHeight())",
                 "IntArray(width * height)",
                 "reader.decode(bitmap, HINTS)",
                 "reader.decode(bitmap, HINTS_HARDER)"]
        positions = [body.index(marker) for marker in order]
        self.assertEqual(sorted(positions), positions, "the decode pipeline was reordered")
        self.assertIn("bMap?.recycle()", body)
        self.assertIn("} finally {\n            bMap.recycle()\n        }", body)
        # Two load failures, rejected bounds and a rejected decoded bitmap: four load-error toasts.
        self.assertEqual(4, body.count("R.string.toast_file_load_error"), "a load-error toast disappeared")
        # Every decode failure is recorded, so the last one decides the message.
        self.assertEqual(2, body.count("} catch (re: ReaderException) {\n"))
        self.assertEqual(2, body.count("savedException = re\n"))
        self.assertIn("val failure: ReaderException = savedException ?: NotFoundException.getNotFoundInstance()", body)
        self.assertIn("failure.printStackTrace()", body)
        self.assertLess(body.index("is ChecksumException -> R.string.toast_qr_checksum_exception"),
                        body.index("is FormatException -> R.string.toast_qr_format_error"))
        self.assertIn("else -> R.string.toast_qr_error", body)
        self.assertNotIn("!!", source)

    def test_notification_helper_keeps_channels_gate_and_single_notification_id(self):
        source = (KOTLIN / "Utilities/NotificationHelper.kt").read_text()
        self.assertIn("object NotificationHelper {", source)
        self.assertEqual(4, source.count("@JvmStatic\n    fun "), "a former static member lost @JvmStatic")
        self.assertIn("private const val POST_NOTIFICATIONS_PERMISSION = Manifest.permission.POST_NOTIFICATIONS", source)
        self.assertIn("private const val POST_NOTIFICATIONS_REQUEST_CODE = 1", source)
        # The same early-return gate lint's NewApi/InlinedApi analysis sees in the Java.
        self.assertIn("if (context == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {\n"
                      "            return\n        }", source)
        self.assertIn("ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION) != PackageManager.PERMISSION_GRANTED",
                      source)
        self.assertIn("fragment.requestPermissions(arrayOf(POST_NOTIFICATIONS_PERMISSION), POST_NOTIFICATIONS_REQUEST_CODE)", source)
        # Channel ids keep the Java default-locale lower-casing (not Kotlin's root-locale lowercase()).
        self.assertIn('"andOTP_" + channel.name.lowercase(Locale.getDefault())', source)
        self.assertNotIn(".lowercase()", source)
        for name, importance in (("backup_failed", "IMPORTANCE_HIGH"), ("backup_success", "IMPORTANCE_LOW")):
            self.assertIn(f"R.string.notification_channel_name_{name}", source)
            self.assertIn(f"R.string.notification_channel_desc_{name}", source)
            self.assertIn(f"NotificationManager.{importance}", source)
        self.assertIn("NotificationManager.IMPORTANCE_DEFAULT)", source)
        self.assertIn("for (channel in Constants.NotificationChannel.values())", source)
        post = source[source.index("resBody: String?) {"):]
        self.assertIn("NotificationCompat.Builder(context, channelId(channel))", post)
        self.assertLess(post.index("createNotificationChannel(context, channel)"), post.index("builder.build()"))
        self.assertIn("val notificationId = 1", post)
        self.assertIn(".setSmallIcon(R.mipmap.ic_launcher)", post)
        self.assertIn(".bigText(resBody))", post)
        self.assertIn("notify(context, channel, resIdTitle, context.getText(resIdBody).toString())", source)
        self.assertNotIn("!!", source)

    def test_resource_stand_ins_are_distinct(self):
        # Checks compare which toast/message was used, so scalar ids must not collapse to one value.
        r_stub = (SANDBOX / "stubs/org/shadowice/flocke/andotp/R.kt").read_text()
        ids = [line.split("=")[1].strip() for line in r_stub.splitlines() if line.strip().startswith("const val")
               and "0x7f" in line]
        self.assertGreater(len(ids), 100)
        self.assertEqual(len(ids), len(set(ids)), "duplicate resource stand-in ids")


if __name__ == "__main__":
    unittest.main()
