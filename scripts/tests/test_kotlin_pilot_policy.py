from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class KotlinPilotConfigurationTest(unittest.TestCase):
    """Source configuration guards, not evidence that Kotlin or APK compilation passed."""

    def test_plugin_and_runtime_have_aligned_explicit_pins(self):
        root_build = (ROOT / "build.gradle").read_text()
        app_build = (ROOT / "app/build.gradle").read_text()
        self.assertEqual(["2.4.21"], re.findall(r"org.jetbrains.kotlin:kotlin-gradle-plugin:([^'\s]+)", root_build))
        self.assertEqual(["2.4.21"], re.findall(r"org.jetbrains.kotlin:kotlin-stdlib:([^'\s]+)", app_build))
        self.assertIn("version { strictly '2.4.21' }", root_build)
        self.assertIn("version { strictly '2.4.21' }", app_build)
        self.assertIn("apply plugin: 'org.jetbrains.kotlin.android'", app_build)

    def test_jvm_target_remains_aligned_with_java_eight(self):
        app_build = (ROOT / "app/build.gradle").read_text()
        self.assertIn("jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)", app_build)
        self.assertIn("sourceCompatibility JavaVersion.VERSION_1_8", app_build)
        self.assertIn("targetCompatibility JavaVersion.VERSION_1_8", app_build)
        self.assertIn("minSdkVersion 28", app_build)
        self.assertIn("targetSdkVersion 35", app_build)

    def test_compiler_inventory_and_android_free_boundary_remain_explicit(self):
        inventory = (ROOT / "gradle/dependency-inventory.gradle").read_text()
        self.assertIn("configuration.name.startsWith('kotlinCompiler')", inventory)
        self.assertIn("configuration.name.startsWith('kotlinBuildTools')", inventory)
        self.assertIn("Missing Kotlin compiler configuration", inventory)
        source = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities"
        for name in ("OtpUriParser.kt", "OtpParseResult.kt"):
            self.assertNotIn("import android.", (source / name).read_text())
