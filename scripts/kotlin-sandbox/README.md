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
| `Utilities/SecretKeyWrapper.kt` | Real RSA wrap/unwrap round trip with a generated `KeyPair`; no platform crypto is stubbed. |
| `Utilities/DimensionConverter.kt` | Parse/unit/rounding cases; the density formula lives in the `TypedValue` stub (see caveats). |
| `View/SimpleDoubleClickListener.kt` | Single/double tap discrimination against a fake `View` that captures posted callbacks. |
| `View/ItemTouchHelper/ItemTouchHelperAdapter.kt` | Drag/move contract implemented by the Java card adapter; compiled and shape-checked. |
| `View/ItemTouchHelper/ItemTouchHelperViewHolder.kt` | Select/clear callbacks for the entry cards; compiled and shape-checked. |
| `View/ItemTouchHelper/SimpleItemTouchHelperCallback.kt` | Movement flags, move forwarding, select/clear wiring and the swipe fade against fake holders (the `makeMovementFlags` composition lives in the stub; see caveats). |
| `Utilities/UIHelper.kt` | Dialog confirm callback and keyboard show/hide wiring against a fake `InputMethodManager`. |
| `Utilities/ConfirmedPasswordTransformationHelper.kt` | Show/hide toggle wiring between password and confirm fields against fake inputs. |
| `Tasks/BackupTaskResult.kt` | Factories, public-field defaults, `ResultType` constants and redacted diagnostics. |

`Checks.kt` reproduces the intent of the repository's own tests — `ApplicationTest` (RFC vectors),
`InputPolicyTest`, `BoundedInputReaderTest`, `EncryptedBackupHeaderTest`,
`ScreenTimerControllerTest`, `EntryActionTargetTest` — for the classes above, and adds reflection
assertions that the Java-visible API shapes survived the conversion (`@JvmStatic` forwarders,
`@JvmField`/`const` fields, checked exceptions, `synchronized` methods, enum constants, abstract
callbacks, a **non-final** class where the Java one was extendable, and the deliberate
`object`-conversion delta for static-only utilities).

`Constants.java` is resolved, not shadowed: `scripts/tests/test_kotlin_sandbox_harness.py` fails if a
stub of the same name as a production class appears, so the sandbox cannot drift from the real
declarations. It also records *why* `Constants` stays Java — its crypto/backup parameters are
package-private and Kotlin has no package-private visibility — while confirming that same-package
Kotlin can read them.

The RSA round trip is real cryptography: `SecretKeyWrapper` is exercised against a JVM-generated
`KeyPair`, including a tampered blob, a wrong private key and wrong-length input, and one case
decrypts the wrapped bytes with an independent `RSA/ECB/PKCS1Padding` cipher to prove the
transformation actually in use.

Still **not** covered, and therefore still dependent on the Gradle/device gates:

- `Utilities/OtpUriParser.kt` and `Utilities/VaultContracts.kt`: the stub set has no `Base32`/`Entry`.
- `Utilities/VaultRestoreOperation.kt`, `Utilities/VaultSession.kt`, `Utilities/EntryPersistence.kt`:
  they pull in `DatabaseHelper`, `Settings` and the task runners.
- `Database/EntryList.kt`, `View/EntryActionDialogs.kt`, `View/EntryActionTarget.kt`,
  `View/EntryListPresentation.kt`: Android views/dialogs or the `Entry` model.
- Every activity, fragment, adapter, preference, receiver and worker (all still Java).
- Java *compilation*: there is no `javac` in this environment, so no Java class file is produced and
  the Java callers' own compilation is never checked. `run.sh` does pass real Java sources
  (`Utilities/Constants.java`) to `kotlinc` for resolution, which is why the Kotlin code is checked
  against the actual declarations rather than a copy — including the package-private
  `ALGORITHM_ASYMMETRIC` constant, which Kotlin in the same package can read.

## What it proves, and what it does not

Proves, for the listed sources: the Kotlin type-checks under `-jvm-target 1.8`; the described
non-Android logic behaves as the existing tests describe; and the Java-visible member shapes are
what Java callers expect.

Does **not** prove: anything about Android runtime behaviour (the stubs are approximations, not the
platform), resource/manifest wiring, `Looper`/`Handler` timing on a real main thread, `KeyEvent`
delivery, `commons-codec` behaviour (the `Hex` stub is a stand-in), Java-side compilation of the
callers (no `javac` here), lint, packaging, signing or reproducibility.

Two stubs are *replications* rather than inert types and are called out for that reason:
`TypedValue.applyDimension` reimplements the documented AOSP density formula (so the density-dependent
expectations for `DimensionConverter` are only as good as that formula), and `View.postDelayed` is
`open` so the tap listener's posted callback can be captured (the platform method cannot be
overridden that way, and no platform timing is exercised). Kotlin objects also add an
`INSTANCE` field and static forwarders, and a private constructor produces one synthetic
`public` bridge — both are inherent to Kotlin, not tested here.

Three more stub approximations matter for the newer checks: `ItemTouchHelper.Callback.makeMovementFlags`
reimplements the documented androidx flag composition (`(swipe|drag) | swipe << 8 | drag << 16`), so
the drag-flag expectations decode those bit fields; the RecyclerView/View stand-ins expose writable
`alpha`/`translationX`/`width`/type/position state that the platform keeps final; and the
`AlertDialog`/`TextInputLayout`/`InputMethodManager` fakes merely record what the helpers wire up.
No real layout, drawing, window or input-method behaviour is exercised.

## Running it

On a machine without a JDK or Kotlin compiler, `bootstrap-toolchain.sh` fetches both from package
mirrors (PyPI `jdk4py`, the npm `kotlin-compiler` distribution), verifies the pinned SHA-256 digests,
unpacks them outside the repository and prints the environment to use:

```sh
bash scripts/kotlin-sandbox/bootstrap-toolchain.sh            # default: ${TMPDIR:-/tmp}/kotlin-sandbox-toolchain
eval "$(bash scripts/kotlin-sandbox/bootstrap-toolchain.sh | grep ^export)"
bash scripts/kotlin-sandbox/run.sh
```

Those digests are trust-on-first-use against the mirrors over TLS — not an independent provenance
check. Prefer a package manager or a verified archive where one is available.

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

Verified with Kotlin `2.4.21` and Temurin `25.0.2` on 2026-10-15 (27 checks). Pin your own compiler
when recording results; the check is only as meaningful as the versions you run it with.
