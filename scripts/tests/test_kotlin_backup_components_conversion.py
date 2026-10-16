"""Source-level guards for the seventeenth batch (the Android components).

The receivers, the WorkManager worker and the legacy backup agent are compiled by the sandbox and
their Java-visible shapes are asserted there. Their failure paths post a notification through the
Java-only ``Constants.NotificationChannel`` and need the AndroidKeyStore, so the order of the
checks and the messages are pinned here; the real paths belong to the device test
(``KotlinBackupComponentsInteropTest``) and the Android gates.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
SANDBOX = ROOT / "scripts/kotlin-sandbox"
ANDROID_TEST = ROOT / "app/src/androidTest/java/org/shadowice/flocke/andotp"


class KotlinBackupComponentsConversionTest(unittest.TestCase):
    def test_replacements_keep_their_names_and_are_compiled_by_the_sandbox(self):
        harness = (SANDBOX / "run.sh").read_text()
        for name in ("Receivers/BackupBroadcastReceiver", "Receivers/EncryptedBackupBroadcastReceiver",
                     "Tasks/EncryptedBackupWorker", "Utilities/BackupAgent"):
            self.assertFalse((JAVA / f"{name}.java").exists(), f"{name}.java is still there")
            self.assertTrue((KOTLIN / f"{name}.kt").is_file(), f"{name}.kt is missing")
            self.assertIn(f"$KOTLIN_APP/{name}.kt", harness)
        self.assertIn('"$SANDBOX"/stubs/androidx/work/*.kt', harness)

    def test_the_receiver_keeps_the_check_order_and_the_failure_messages(self):
        source = (KOTLIN / "Receivers/EncryptedBackupBroadcastReceiver.kt").read_text()
        body = source[source.index("override fun onReceive("):]
        # The order is the security-relevant part: the action gate, the opt-in, the location, the
        # throttled slot, the password and only then the encryption type and the queued work.
        order = ["if (intent == null || !ACTION.equals(intent.getAction())) return",
                 "if (!settings.isEncryptedBackupBroadcastEnabled()) {",
                 "if (!canSaveBackup(context)) {",
                 "if (!settings.tryAcquireBroadcastBackupSlot()) return",
                 "if (password == null || password.isEmpty()) {",
                 "if (settings.getEncryption() != Constants.EncryptionType.KEYSTORE) {",
                 "WorkManager.getInstance(context).enqueueUniqueWork("]
        positions = [body.index(marker) for marker in order]
        self.assertEqual(sorted(positions), positions, "the receiver checks were reordered")
        # One distinct message per failure, and no message outside the failure branches.
        for message in ("backup_receiver_encrypted_disabled", "backup_receiver_no_location_set",
                        "backup_receiver_no_password_set", "backup_receiver_custom_encryption_failed"):
            self.assertEqual(1, body.count(f"R.string.{message}"), f"{message} changed")
        self.assertEqual(4, body.count("Constants.NotificationChannel.BACKUP_FAILED"),
                         "a failure notification was added or dropped")
        self.assertNotIn("BACKUP_SUCCESS", body, "the receiver must not report a success")
        # The work is queued once, under the documented name and policy.
        self.assertEqual(1, body.count("enqueueUniqueWork("))
        self.assertIn("UNIQUE_WORK_NAME,\n                ExistingWorkPolicy.KEEP,", body)
        self.assertIn("OneTimeWorkRequest.Builder(EncryptedBackupWorker::class.java).build()", body)
        # The constants stay public static final fields for the Java callers.
        self.assertIn("const val UNIQUE_WORK_NAME = \"encrypted_backup\"", source)
        self.assertIn("const val ACTION = \"org.shadowice.flocke.andotp.broadcast.ENCRYPTED_BACKUP\"", source)

    def test_the_base_receiver_keeps_its_protected_helper(self):
        source = (KOTLIN / "Receivers/BackupBroadcastReceiver.kt").read_text()
        self.assertIn("abstract class BackupBroadcastReceiver : BroadcastReceiver() {", source)
        self.assertIn("protected fun canSaveBackup(context: Context): Boolean {", source)
        self.assertIn("return settings.isBackupLocationSet()", source)

    def test_the_worker_keeps_the_check_order_and_the_failure_messages(self):
        source = (KOTLIN / "Tasks/EncryptedBackupWorker.kt").read_text()
        body = source[source.index("override fun doWork():"):]
        order = ["if (isStopped() || !settings.isEncryptedBackupBroadcastEnabled()) return Result.failure()",
                 "if (password == null || password.isEmpty()) {",
                 "if (!settings.isBackupLocationSet()) {",
                 "if (settings.getEncryption() != Constants.EncryptionType.KEYSTORE) {",
                 "synchronized(DatabaseHelper.DatabaseFileLock) {",
                 "if (loaded !is VaultReadResult.Loaded) return fail(context, R.string.backup_receiver_unexpected_error)",
                 "result = BroadcastBackupTask(",
                 "NotificationHelper.notify(context, Constants.NotificationChannel.BACKUP_SUCCESS",
                 "return fail(context, result.messageId)"]
        positions = [body.index(marker) for marker in order]
        self.assertEqual(sorted(positions), positions, "the worker checks were reordered")
        for message in ("backup_receiver_no_password_set", "backup_receiver_no_location_set",
                        "backup_receiver_custom_encryption_failed"):
            self.assertEqual(1, body.count(f"R.string.{message}"), f"{message} changed")
        # The unexpected error is reported twice: for an unreadable vault and for a thrown error.
        self.assertEqual(2, body.count("R.string.backup_receiver_unexpected_error"),
                         "the unexpected-error message changed")
        # The entries are destroyed whatever happens, and the vault read stays under the file lock.
        self.assertIn("} finally {\n                for (entry in entries) entry.destroy()\n            }", body)
        self.assertIn("generation = VaultSession.generation()", body)
        # The backup may only write while the captured generation is still current.
        self.assertIn("BooleanSupplier { !isStopped() && VaultSession.isCurrent(generation) }", source)
        # The Java worker reached the protected task method through the package; Kotlin's
        # `protected` has no package-private part, so the call is made from a private subclass.
        self.assertIn("private class BroadcastBackupTask(", source)
        self.assertIn("fun run(): BackupTaskResult = doInBackground()", source)
        self.assertNotIn("import androidx.annotation.NonNull", source,
                         "the Java nullability annotations are not repeated")

    def test_the_backup_agent_still_refuses_the_platform_restore(self):
        source = (KOTLIN / "Utilities/BackupAgent.kt").read_text()
        self.assertIn("open class BackupAgent : BackupAgentHelper() {", source)
        self.assertIn('@Throws(IOException::class)', source)
        self.assertIn('throw IOException("Platform restore is disabled; use portable encrypted backups")', source)
        # The platform passes the callbacks without annotations, so the refusal must not be
        # preceded by a Kotlin null check.
        self.assertIn("override fun onRestore(data: BackupDataInput?, version: Int, state: ParcelFileDescriptor?) {", source)

    def test_the_java_callers_and_the_manifest_still_use_the_converted_components(self):
        # The panic responder and the settings screen cancel the queued work by its name.
        for name in ("Activities/PanicResponderActivity.java", "Activities/SettingsActivity.java"):
            self.assertIn("EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME", (JAVA / name).read_text(), name)
        self.assertIn("EncryptedBackupBroadcastReceiver.UNIQUE_WORK_NAME",
                      (ANDROID_TEST / "Utilities/IpcAuthorizationSecurityTest.java").read_text())
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:name=".Receivers.EncryptedBackupBroadcastReceiver"', manifest)
        self.assertIn('android:permission="${applicationId}.permission.CONTROL_VAULT"', manifest)

    def test_the_device_test_covers_the_paths_the_sandbox_cannot_reach(self):
        test = ANDROID_TEST / "Utilities/KotlinBackupComponentsInteropTest.java"
        self.assertTrue(test.is_file(), "the device test for the converted components is missing")
        source = test.read_text()
        self.assertIn("EncryptedBackupBroadcastReceiver", source)
        self.assertIn("EncryptedBackupWorker", source)
        self.assertIn("UNIQUE_WORK_NAME", source)


if __name__ == "__main__":
    unittest.main()
