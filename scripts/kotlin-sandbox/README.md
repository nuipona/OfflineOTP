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
| `Tasks/UiBasedBackgroundTask.kt` | The real runner lifecycle on real executors: value/failure/cancelled delivery, one-shot execution, explicit and session-revocation cancellation, owned-result disposal and the legacy fallback adapter, against a queued fake main loop. |
| `Tasks/AuthenticationTask.kt` | Type-checked against the real `Settings`/`EncryptionHelper` Java declarations; constructor and protected-member shapes asserted (its bodies call Java-only classes, so they are not executed here). |
| `Tasks/GenericBackupTask.kt`, `Tasks/GenericRestoreTask.kt` | The abstract backup/restore bases, type-checked against the real `BackupHelper`/`StorageAccessHelper`; abstractness, constructor and (where reflection can see them) member shapes asserted. |
| `Tasks/PGPBackupTask.kt`, `Tasks/PGPRestoreTask.kt` | Constructor shapes and (for the restore task) the protected `doInBackground` override asserted. |
| `Tasks/EncryptedBackupTask.kt` | Both Java constructors (generic erasure makes them reflectable), the `Mode` enum and the class-level extendability asserted; the snapshot/canWrite policy stays source-guarded. |
| `Tasks/EncryptedRestoreTask.kt` | Constructor and protected-member shapes asserted against the real `EncryptedBackupHeader`/`EncryptionHelper` declarations. |
| `Tasks/ChangeEncryptionTask.kt`, `Tasks/ChangeCredentialsTask.kt`, `Tasks/FinishIntroTask.kt` | Class-level shapes and (for the intro result DTO, whose fields are JDK types) both constructors and public final fields asserted; the credential/encryption-change members reference Java-only signature types and are covered by the Python source guards. |
| `Utilities/OtpUriParser.kt`, `Utilities/VaultContracts.kt` | Now compilable here: the stub set gained functional `Base32`/`org.json` stand-ins and the converted `Entry`/`EntryJson` are compiled here too, so the parser's secret rules are executed through the entry batch. |
| `Utilities/EntryPersistence.kt` | Type-checked against the real `DatabaseHelper`/`BackupHelper` declarations, including its Kotlin construction of the converted `EncryptedBackupTask`; not executed here. |
| `Utilities/VaultRestoreOperation.kt` | Type-checked against the real `DatabaseHelper`/`Settings` declarations; its merge/destroy policy stays covered by the Python source guards and the repository's tests. |
| `Utilities/VaultSession.kt` | Compiled with the runner and driven live (generation advances, change listeners, mutation lock); the static facade shapes are asserted. |
| `Database/EntryJsonResult.kt` | The closed parse-error set and the owned one-use seed: `copySecret()` returns copies, `close()` wipes and is idempotent, redacted `toString()`, no generated `data class` members. |
| `Database/EntryJson.kt` | The stored document format, executed against the functional `org.json`/`Base32`/`Hex` stand-ins: legacy field defaults, the malformed-field matrix with its closed errors, the serialized field order, and the Base32/mOTP-hex secret rules. |
| `Database/Entry.kt` | Compiled and shape-checked only: it is non-final, has both legacy adapters, the nested `OTPType`, `COLOR_RED` and `validateSecret`; constructing it or reflecting its fields loads the Java `EntryThumbnail`, which has no class file here, so its preserved behaviour is pinned by the Python source guards instead. |
| `Database/EntryList.kt` | Compiled and shape-checked only: its sorting/filtering API takes `Constants` enums and its entries are `Entry` objects, and neither type has a class file in this sandbox, so its behaviour stays covered by the Python source guards and `ApplicationTest`. |

The task subclasses are compiled **against the real Java declarations** they call: `run.sh` passes
`Settings.java`, `EncryptionHelper.java`, `BackupHelper.java`, `StorageAccessHelper.java`,
`DatabaseHelper.java`, `KeyStoreHelper.java`, `FileHelper.java`, `EntryThumbnail.java` and
`LetterBitmap.java` to `kotlinc` for resolution (the same mechanism already used for
`Constants.java`). `Entry.java` is no longer among them: it is Kotlin now, and the graphical
`EntryThumbnail`/`LetterBitmap` stay Java because their constants are Android resource ids. No class files are produced for them, so their bodies cannot be *executed*
here, and reflection can only see members whose signatures avoid those Java-only types; the
remaining shapes are enforced by the Python source guards in `scripts/tests/`.

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

- `View/EntryActionDialogs.kt`, `View/EntryActionTarget.kt`, `View/EntryListPresentation.kt`:
  Android views/dialogs, or APIs that take the `Entry`/`EntryList` model types.
- Every activity, fragment, adapter, preference, receiver and worker (all still Java).
- **Execution** of the task subclass bodies (and of `EntryPersistence`/`VaultRestoreOperation`):
  the Java resolution sources produce no class files, so `Settings`/`DatabaseHelper`/
  `EncryptionHelper` calls inside them cannot run on the desktop JVM here; only their compile-time
  agreement with those real declarations is checked.
- Java *compilation*: there is no `javac` in this environment, so no Java class file is produced and
  the Java callers' own compilation is never checked. `run.sh` does pass real Java sources
  (`Utilities/Constants.java`, `Settings.java`, `EncryptionHelper.java`, ... — the full list is in
  the script) to `kotlinc` for resolution, which is why the Kotlin code is checked
  against the actual declarations rather than a copy — including the package-private
  `ALGORITHM_ASYMMETRIC` constant, which Kotlin in the same package can read.

## What it proves, and what it does not

Proves, for the listed sources: the Kotlin type-checks under `-jvm-target 1.8`; the described
non-Android logic behaves as the existing tests describe; and the Java-visible member shapes are
what Java callers expect.

Does **not** prove: anything about Android runtime behaviour (the stubs are approximations, not the
platform), resource/manifest wiring, `Looper`/`Handler` timing on a real main thread, `KeyEvent`
delivery, the platform's `org.json`/`commons-codec`/`android.net.Uri` behaviour beyond the replicated
subsets described below, Java-side compilation of the callers (no `javac` here), lint, packaging,
signing or reproducibility.

The entry batch made three more stand-ins functional **replicas** rather than inert types, so the
new checks are only as good as their fidelity. `org.json.JSONObject`/`JSONArray` implement the
constructors (from text and from parsed values), `put`/`get`/`getString`/`getLong`/`getInt`/
`getJSONObject`/`getJSONArray`/`has`/`keys`/`length` and the `opt*` fallbacks the entry code uses;
they keep insertion order for `keys()` and for `toString()`, which emits no whitespace (so a
reserialized document compares byte-for-byte with the stored fixtures), treat `put(name, null)` as a
removal and integral literals as `Long`, and throw `JSONException` for a missing `get`. They do
**not** implement `JSONTokener`, `remove`, `isNull`, `optJSONObject`, duplicate-key rejection or
indenting, and duplicate keys keep the last value. `commons-codec` `Base32` implements the RFC 4648
standard padded uppercase alphabet (`decode`, `encode`, `encodeAsString`; other characters throw) and
`Hex` only the lowercase `encodeHex(byte[])`/`decodeHex(String|CharArray)` pair the model calls.
`android.net.Uri` is a minimal functional parser: a `Builder` with
`scheme`/`authority`/`appendPath`/`appendQueryParameter`/`clearQuery`/`build`, decoded
`getQueryParameter`/`getQueryParameters`, and `parse`/`fromFile` that only echo their input.

Two stubs are *replications* rather than inert types and are called out for that reason:
`TypedValue.applyDimension` reimplements the documented AOSP density formula (so the density-dependent
expectations for `DimensionConverter` are only as good as that formula), and `View.postDelayed` is
`open` so the tap listener's posted callback can be captured (the platform method cannot be
overridden that way, and no platform timing is exercised). Kotlin objects also add an
`INSTANCE` field and static forwarders, and a private constructor produces one synthetic
`public` bridge — both are inherent to Kotlin, not tested here.

The entry checks are the reason the three replicas above exist: they parse the stored document
format, reserialize it and compare the exact string, walk the malformed-field matrix (including the
`MISSING_SECRET` case that the URI parser reports as its own error) and exercise the seed's
one-use/redacted ownership and the secret rules. `Entry` itself is **not** instantiated here, so the
constructors, adapters, enum, constants and `validateSecret` are reflected, and `EntryList` is
compiled but not driven — both stay pinned by the Python source guards and the instrumentation tests.

For the runner checks the `Handler` stand-in queues `post` calls into a process-wide fake main
loop that the checks drain deterministically (the worker threads are real, so delivery order is
polled, not assumed); `Log` is a sink. The fake loop is another approximation: no real looper,
message ordering or ANR semantics exist here.

Three more stub approximations matter for the earlier checks: `ItemTouchHelper.Callback.makeMovementFlags`
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

Verified with Kotlin `2.4.21` and Temurin `25.0.2` on 2026-10-15 (40 checks). Pin your own compiler
when recording results; the check is only as meaningful as the versions you run it with.
