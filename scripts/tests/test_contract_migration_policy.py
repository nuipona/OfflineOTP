from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/org/shadowice/flocke/andotp"
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
                     "Preferences/CredentialsPreference.java", "Utilities/EntryPersistence.java"):
            source = (JAVA / name).read_text()
            self.assertIn("setOutcomeCallback", source)
            self.assertNotIn("task.setCallback(", source)
            self.assertNotIn("taskFragment.setCallback(", source)
        activity = (JAVA / "Activities/BackgroundTaskActivity.java").read_text()
        self.assertIn("source != activeTask", activity)
        self.assertIn("source.disposeOutcome(outcome)", activity)

    def test_production_vault_callers_do_not_project_read_failures_to_empty_lists(self):
        forbidden = ("DatabaseHelper.loadDatabase(", "DatabaseHelper.loadDatabaseResult(",
                     "DatabaseHelper.saveDatabase(", "DatabaseHelper.changeEncryption(")
        for file in JAVA.rglob("*.java"):
            if file.name == "DatabaseHelper.java":
                continue
            for old_api in forbidden:
                self.assertNotIn(old_api, file.read_text(), str(file))

    def test_uncertain_commit_and_recovery_remain_explicit(self):
        contract = (KOTLIN / "Utilities/VaultContracts.kt").read_text()
        database = (JAVA / "Utilities/DatabaseHelper.java").read_text()
        self.assertIn("UNKNOWN, COMMITTED", contract)
        self.assertIn("if (success) COMMITTED else UNKNOWN", contract)
        self.assertIn("decision == VaultCommitDecision.CANCELED && !id.equals(marker)", database)
        self.assertIn("recoveryRequired(VaultCommitState.COMMITTED)", database)
        self.assertIn("VaultSession.fail(SessionFailure.RECOVERY_REQUIRED)", database)
        self.assertIn("transactionPoisoned = true", database)
