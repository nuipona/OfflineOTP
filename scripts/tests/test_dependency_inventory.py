"""Inventory coverage guards and an offline Gradle artifact-selection regression.

The policy checks need only Python. To run the Gradle fixture (no Android SDK or
Maven downloads), set GRADLE_EXECUTABLE to a Gradle 8.13 executable or the checked-in
wrapper. CI does this in the build job. This models AGP's multiple outgoing artifact
variants; the full Android inventory/reproducibility job remains the acceptance gate.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / "gradle/dependency-inventory.gradle"
APP_VARIANTS = (
    "fdroidDebug", "fdroidRelease", "playDebug", "playRelease",
    "fdroidDebugUnitTest", "playDebugUnitTest",
    "fdroidDebugAndroidTest", "playDebugAndroidTest",
)
SUFFIXES = ("CompileClasspath", "RuntimeClasspath")
TOOLING = (
    "kotlinCompilerClasspath", "kotlinBuildToolsApiClasspath",
    "kotlinCompilerPluginClasspathFdroidDebugUnitTest",
    "kotlinCompilerPluginClasspathFdroidDebugAndroidTest",
)
GRADLE = os.environ.get("GRADLE_EXECUTABLE")


class DependencyInventoryPolicyTest(unittest.TestCase):
    def test_all_build_and_test_classpaths_are_required(self):
        source = INVENTORY.read_text()
        for variant in APP_VARIANTS:
            self.assertIn(f"'{variant}'", source)
        for suffix in SUFFIXES:
            self.assertIn(f"'{suffix}'", source)
        self.assertIn("app.configurations.getByName(name)", source)
        self.assertNotIn("UnitTest|AndroidTest", source)

    def test_graph_is_complete_and_external_artifacts_are_strict(self):
        source = INVENTORY.read_text()
        self.assertIn("configuration.incoming.resolutionResult.allDependencies", source)
        self.assertIn("UnresolvedDependencyResult", source)
        self.assertIn("configuration.incoming.artifactView", source)
        self.assertIn("componentFilter { id -> id instanceof ModuleComponentIdentifier }", source)
        self.assertNotIn("resolvedConfiguration.resolvedArtifacts", source)
        self.assertNotIn("lenient = true", source)
        self.assertNotIn("lenient(true)", source)
        self.assertIn("new TreeSet<String>()", source)
        self.assertIn("MessageDigest.getInstance('SHA-256')", source)
        self.assertIn("configuration ${label}", source)


@unittest.skipUnless(GRADLE, "Set GRADLE_EXECUTABLE to run the offline Gradle fixture")
class DependencyInventoryGradleTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / "source-1"
        self.root.mkdir()
        # Reuse only the provisioned Gradle distribution/cache. Every Maven repository, project
        # output and project-local cache is temporary; the fixture has no network repositories.
        self.env = dict(os.environ)
        self.artifacts = {}
        for module in ("tool", "runtime", "test-kit", "broken"):
            folder = self.root / "repo/inventory/fixture" / module / "1.0"
            folder.mkdir(parents=True)
            (folder / f"{module}-1.0.pom").write_text(
                f'<project><modelVersion>4.0.0</modelVersion><groupId>inventory.fixture</groupId>'
                f'<artifactId>{module}</artifactId><version>1.0</version></project>')
            if module != "broken":
                jar = folder / f"{module}-1.0.jar"
                with zipfile.ZipFile(jar, "w") as archive:
                    # Fixed metadata: independent fixture copies have byte-identical inputs.
                    archive.writestr(zipfile.ZipInfo("fixture.txt", (2020, 1, 1, 0, 0, 0)), module)
                self.artifacts[module] = hashlib.sha256(jar.read_bytes()).hexdigest()
        (self.root / "settings.gradle").write_text(
            'rootProject.name = "inventory-fixture"\ninclude ":app", ":security-test-client"\n')
        for project in ("app", "security-test-client"):
            (self.root / project).mkdir()
        shutil.copyfile(INVENTORY, self.root / "dependency-inventory.gradle")
        app_names = [v + s for v in APP_VARIANTS for s in SUFFIXES]
        client_names = ["debug" + s for s in SUFFIXES]
        (self.root / "build.gradle").write_text('''
import org.gradle.api.attributes.Attribute

buildscript {
    repositories { maven { url uri('repo') } }
    dependencies { classpath 'inventory.fixture:tool:1.0' }
}
allprojects {
    repositories { maven { url rootProject.uri('repo') } }
}
subprojects {
    def names = project.name == 'app' ? APP_NAMES : CLIENT_NAMES
    names += TOOLING_NAMES
    names.each { configName ->
        configurations.create(configName) {
            canBeConsumed = false
            canBeResolved = true
        }
        dependencies.add(configName, 'inventory.fixture:runtime:1.0')
    }
}
project(':app') {
    // One graph variant, but multiple incompatible artifact variants, just like the tested app.
    configurations.create('testedAppElements') {
        canBeResolved = false
        canBeConsumed = true
        ['jar', 'android-app-symbol-for-data-binding'].each { kind ->
            outgoing.variants.create(kind) {
                attributes.attribute(Attribute.of('artifactType', String), kind)
                artifact(file("build/not-built.${kind}"))
            }
        }
    }
    dependencies.add('testedAppElements', 'inventory.fixture:runtime:1.0')
    configurations.matching { it.name.contains('UnitTest') || it.name.contains('AndroidTest') }.all { config ->
        dependencies.add(config.name, dependencies.project(path: ':app'))
        dependencies.add(config.name, 'inventory.fixture:test-kit:1.0')
    }
    if (rootProject.hasProperty('missingDependency')) {
        dependencies.add('fdroidDebugUnitTestCompileClasspath', 'inventory.fixture:absent:1.0')
    }
    if (rootProject.hasProperty('missingArtifact')) {
        dependencies.add('fdroidDebugAndroidTestRuntimeClasspath', 'inventory.fixture:broken:1.0')
    }
    if (rootProject.hasProperty('missingConfiguration')) {
        configurations.remove(configurations.getByName('playDebugAndroidTestRuntimeClasspath'))
    }
}
tasks.register('resolveUnfilteredTestArtifacts') {
    doLast {
        project(':app').configurations.fdroidDebugUnitTestCompileClasspath
            .resolvedConfiguration.resolvedArtifacts.each { println it.file }
    }
}
apply from: 'dependency-inventory.gradle'
'''.replace('APP_NAMES', json.dumps(app_names)).replace('CLIENT_NAMES', json.dumps(client_names))
            .replace('TOOLING_NAMES', json.dumps(TOOLING)))

    def run_gradle(self, *arguments, root=None):
        return subprocess.run(
            [GRADLE, "--offline", "--no-daemon", "--console=plain", "--no-configuration-cache",
             "-p", str(root or self.root), *arguments],
            env=self.env, capture_output=True, text=True, timeout=180)

    def test_ambiguous_project_artifacts_do_not_hide_test_inputs(self):
        unfiltered = self.run_gradle("resolveUnfilteredTestArtifacts")
        self.assertNotEqual(0, unfiltered.returncode, unfiltered.stdout + unfiltered.stderr)
        self.assertIn("android-app-symbol-for-data-binding", unfiltered.stdout + unfiltered.stderr)

        result = self.run_gradle("writeDependencyInventory")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        report = (self.root / "build/reports/dependency-inventory.txt").read_text()
        for variant in APP_VARIANTS:
            for suffix in SUFFIXES:
                label = variant + suffix
                self.assertIn(f"configuration {label}\n", report)
                self.assertIn(f"artifact {label} inventory.fixture:runtime:1.0 runtime-1.0.jar "
                              f"{self.artifacts['runtime']}\n", report)
                if variant.endswith(("UnitTest", "AndroidTest")):
                    self.assertIn(f"artifact {label} inventory.fixture:test-kit:1.0 test-kit-1.0.jar "
                                  f"{self.artifacts['test-kit']}\n", report)
                    self.assertIn(f"graph {label} project :app -> project :app\n", report)
        for config in TOOLING:
            self.assertIn(f"configuration kotlin-tooling:app:{config}\n", report)
        self.assertNotIn("not-built", report)
        self.assertNotIn(str(self.root), report)
        self.assertEqual(sorted(set(report.splitlines())), report.splitlines())

        second = Path(self.directory.name) / "source-2"
        shutil.copytree(self.root, second, ignore=shutil.ignore_patterns("build", ".gradle"))
        result = self.run_gradle("writeDependencyInventory", root=second)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(report, (second / "build/reports/dependency-inventory.txt").read_text())

    def test_missing_test_inputs_fail_with_the_configuration_name(self):
        for option, label in (
            ("missingDependency", "fdroidDebugUnitTestCompileClasspath"),
            ("missingArtifact", "fdroidDebugAndroidTestRuntimeClasspath"),
            ("missingConfiguration", "playDebugAndroidTestRuntimeClasspath"),
        ):
            with self.subTest(option=option):
                result = self.run_gradle("writeDependencyInventory", f"-P{option}")
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn(label, result.stdout + result.stderr)
                self.assertFalse((self.root / "build/reports/dependency-inventory.txt").exists())


if __name__ == "__main__":
    unittest.main()
