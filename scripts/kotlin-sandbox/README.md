# Kotlin sandbox check (non-authoritative)

`run.sh` compiles a **subset** of the converted Kotlin sources against hand-written platform stubs
and runs `Checks.kt` on a desktop JVM. It exists because an environment with only a JDK and a Kotlin
compiler (no Android SDK, no AndroidX/Google Maven artifacts, no Gradle dependency graph) cannot run
the real gates.

**It is not a substitute for `./gradlew :app:testFdroidDebugUnitTest`, `lint*Release`,
`assemble*`, the packaged-manifest policy or the API 28/36 instrumentation runs described in
[build-and-security-tests.md](../../docs/build-and-security-tests.md).** A green run here is not
build, lint, device or release acceptance, and it does not compile any Java source, resource or
manifest.

## What it checks

| Converted source | Why it is here |
| --- | --- |
| `Utilities/InputPolicy.kt` | Platform-free bounds used by every entry/import path. |
| `Utilities/Tools.kt` | Only `formatTokenString` is exercised; the rest is Android theming/clipboard. |
| `Utilities/ScreenTimerController.kt` | Platform-free timer policy, driven by a fake `Scheduler`. |
| `Utilities/TokenCalculator.kt` | RFC 6238/4226 vectors, independent HMAC cross-check, Steam/mOTP mapping. |
| `Utilities/BoundedInputReader.kt` | Bounded reads of untrusted documents. |
| `Utilities/BoundedOutput.kt` | Bounded provider output for OpenPGP results. |
| `Utilities/EncryptedBackupHeader.kt` | Backup framing validated before password derivation. |
| `Utilities/EditorActionHelper.kt` | Only compiled and shape-checked (`KeyEvent` is a stub). |
| `Utilities/HandlerTimerScheduler.kt` | Only compiled and shape-checked (`Handler`/`Looper` are stubs). |

`Checks.kt` reproduces the intent of the repository's own tests — `ApplicationTest` (RFC vectors),
`InputPolicyTest`, `BoundedInputReaderTest`, `EncryptedBackupHeaderTest`,
`ScreenTimerControllerTest`, `EntryActionTargetTest` — for the classes above, and adds reflection
assertions that the Java-visible API shapes survived the conversion (`@JvmStatic` forwarders,
`@JvmField`/`const` fields, checked exceptions, `synchronized` methods, enum constants).

Still **not** covered, and therefore still dependent on the Gradle/device gates:

- `Utilities/OtpUriParser.kt` and `Utilities/VaultContracts.kt`: the stub set has no `Base32`/`Entry`.
- `Utilities/VaultRestoreOperation.kt`, `Utilities/VaultSession.kt`, `Utilities/EntryPersistence.kt`:
  they pull in `DatabaseHelper`, `Settings` and the task runners.
- `Database/EntryList.kt`, `View/EntryActionDialogs.kt`, `View/EntryActionTarget.kt`,
  `View/EntryListPresentation.kt`: Android views/dialogs or the `Entry` model.
- Every activity, fragment, adapter, preference, receiver and worker (all still Java).

## What it proves, and what it does not

Proves, for the listed sources: the Kotlin type-checks under `-jvm-target 1.8`; the described
non-Android logic behaves as the existing tests describe; and the Java-visible member shapes are
what Java callers expect.

Does **not** prove: anything about Android runtime behaviour (the stubs are approximations, not the
platform), resource/manifest wiring, `Looper`/`Handler` timing on a real main thread, `KeyEvent`
delivery, `commons-codec` behaviour (the `Hex` stub is a stand-in), Java-side compilation of the
callers (no `javac` here), lint, packaging, signing or reproducibility. Kotlin objects also add an
`INSTANCE` field and static forwarders, and a private constructor produces one synthetic
`public` bridge — both are inherent to Kotlin, not tested here.

## Running it

```sh
KOTLINC=/path/to/kotlinc-jvm JAVA_BIN=/path/to/java bash scripts/kotlin-sandbox/run.sh
```

`KOTLIN_STDLIB` can point at a specific `kotlin-stdlib.jar`; output defaults to the ignored
`reports/kotlin-sandbox/` directory.

On a machine without a JDK or Kotlin compiler (for example this project's sandbox), both can be
obtained from package mirrors reachable without the Android SDK:

```sh
python3 -m pip download --no-deps -d /tmp/toolchain jdk4py   # JDK 25 wheel, extract, chmod +x bin/*
curl -sS -o /tmp/kt.tgz https://registry.npmjs.org/kotlin-compiler/-/kotlin-compiler-2.4.21.tgz
tar xzf /tmp/kt.tgz -C /tmp && chmod +x /tmp/package/bin/kotlinc*
```

Verified with Kotlin `2.4.21` and Temurin `25.0.2` on 2026-10-15. Pin your own compiler when
recording results; the check is only as meaningful as the versions you run it with.
