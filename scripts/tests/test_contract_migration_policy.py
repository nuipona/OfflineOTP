from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"


class ContractMigrationSourceTest(unittest.TestCase):
    """Boundary regression guards only: these do not compile or execute the contracts."""

    def test_controllers_are_android_free_and_diagnostics_do_not_hold_secrets(self):
        for name in ("Utilities/SessionContracts.kt", "Tasks/TaskContracts.kt", "Tasks/AuthenticationOutcome.kt"):
            source = (KOTLIN / name).read_text()
            self.assertNotIn("import android.", source)
            self.assertNotIn("Parcelable", source.replace("Parcelable or saved-state payload", ""))
            self.assertNotIn("Serializable", source)
        session = (KOTLIN / "Utilities/SessionContracts.kt").read_text()
        task = (KOTLIN / "Tasks/TaskContracts.kt").read_text()
        self.assertNotIn("data class OwnedSecret", session)
        self.assertNotIn("data class Value", task)
        self.assertIn('"OwnedSecret(redacted)"', session)
        self.assertIn('"TaskOutcome.Value(redacted)"', task)

    def test_production_task_consumers_do_not_use_legacy_fallback_adapter(self):
        for name in ("Activities/BackgroundTaskActivity.java", "Activities/IntroScreenActivity.java",
                     "Preferences/CredentialsPreference.java"):
            source = (JAVA / name).read_text()
        persistence = (KOTLIN / "Utilities/EntryPersistence.kt").read_text()
        self.assertIn("setOutcomeCallback", persistence)
        self.assertNotIn("task.setCallback(", persistence)
        self.assertNotIn("taskFragment.setCallback(", persistence)
        activity = (JAVA / "Activities/BackgroundTaskActivity.java").read_text()
        self.assertIn("source != activeTask", activity)
        self.assertIn("source.disposeOutcome(outcome)", activity)

    def test_production_vault_callers_do_not_project_read_failures_to_empty_lists(self):
        forbidden = ("DatabaseHelper.loadDatabase(", "DatabaseHelper.loadDatabaseResult(",
                     "DatabaseHelper.saveDatabase(", "DatabaseHelper.changeEncryption(")
        # The task/vault conversions live in Kotlin now, so both trees must stay scanned.
        for tree in (JAVA, KOTLIN):
            for file in tree.rglob("*.java" if tree == JAVA else "*.kt"):
                if file.name in ("DatabaseHelper.java", "DatabaseHelper.kt"):
                    continue  # the repository definition itself, not a caller
                for old_api in forbidden:
                    self.assertNotIn(old_api, file.read_text(), str(file))

    def test_uncertain_commit_and_recovery_remain_explicit(self):
        contract = (KOTLIN / "Utilities/VaultContracts.kt").read_text()
        # The repository moved to Kotlin in the eighth batch; the transaction guards below are the
        # same ones the Java held (the `!id.equals(marker)` test is Kotlin's `id != marker`).
        database = (KOTLIN / "Utilities/DatabaseHelper.kt").read_text()
        self.assertIn("UNKNOWN, COMMITTED", contract)
        self.assertIn("if (success) COMMITTED else UNKNOWN", contract)
        self.assertIn("decision == VaultCommitDecision.CANCELED && id != marker", database)
        self.assertIn("decision != VaultCommitDecision.COMMITTED || id != marker", database)
        # Both ambiguous paths (unreadable marker, uncommitted decision) poison the process.
        self.assertEqual(2, database.count("transactionPoisoned = true"), "poison paths changed")
        self.assertEqual(1, database.count("if (transactionPoisoned) return false"), "poison check changed")
        self.assertIn("recoveryRequired(VaultCommitState.COMMITTED)", database)
        self.assertIn("VaultSession.fail(SessionFailure.RECOVERY_REQUIRED)", database)
        # The staged write keeps the 36-byte UUID prefix and its minimum length check.
        self.assertIn("if (staged.size < 36 + 28) return false", database)
        self.assertIn("Arrays.copyOfRange(staged, 36, staged.size)", database)
        # Refuse to encrypt without a key, and keep writes bounded before encryption.
        self.assertIn('if (key == null) throw IllegalArgumentException("Missing vault key")', database)
        self.assertIn("plain.size > BoundedInputReader.MAX_DOCUMENT_BYTES - 64", database)
