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
| `Dialogs/PasswordEntryDialog.kt` | **Executed** against scripted password/layout/button stand-ins: ENTER versus UPDATE visibility, minimum length, matching confirmation, privacy flags, editor actions, nullable callback, delivery and dismissal; its Java constructor/enum/SAM/listener shapes are reflected. No real window, IME or Material layout is involved. |
| `Dialogs/TagsDialog.kt` | **Executed** against recording dialog/list stand-ins: row toggles, adapter replacement after adding a tag, optional callables, selection/cancel/new-tag buttons and its static Java entry point. No real window or list layout is involved. |
| `Preferences/PasswordEncryptedPreference.kt` | Compiled and shape-checked against real `Settings`/`EncryptionHelper`/`KeyStoreHelper` declarations. Construction and encryption cannot run because those Java resolution sources have no class files; the real keystore/layout path has a Java instrumentation test. |
| `Tasks/BackupTaskResult.kt` | Factories, public-field defaults, `ResultType` constants and redacted diagnostics. |
| `Tasks/UiBasedBackgroundTask.kt` | The real runner lifecycle on real executors: value/failure/cancelled delivery, one-shot execution, explicit and session-revocation cancellation, owned-result disposal and the legacy fallback adapter, against a queued fake main loop. |
| `Tasks/AuthenticationTask.kt` | Type-checked against the real `Settings`/`EncryptionHelper` Java declarations; constructor and protected-member shapes asserted (its bodies call Java-only classes, so they are not executed here). |
| `Tasks/GenericBackupTask.kt`, `Tasks/GenericRestoreTask.kt` | The abstract backup/restore bases, compiled with the converted `BackupHelper`/`StorageAccessHelper`; abstractness, constructor and (where reflection can see them) member shapes asserted. |
| `Tasks/PGPBackupTask.kt`, `Tasks/PGPRestoreTask.kt` | Constructor shapes and (for the restore task) the protected `doInBackground` override asserted. |
| `Tasks/EncryptedBackupTask.kt` | Both Java constructors (generic erasure makes them reflectable), the `Mode` enum and the class-level extendability asserted; the snapshot/canWrite policy stays source-guarded. |
| `Tasks/EncryptedRestoreTask.kt` | Constructor and protected-member shapes asserted against the real `EncryptedBackupHeader`/`EncryptionHelper` declarations. |
| `Tasks/ChangeEncryptionTask.kt`, `Tasks/ChangeCredentialsTask.kt`, `Tasks/FinishIntroTask.kt` | Class-level shapes and (for the intro result DTO, whose fields are JDK types) both constructors and public final fields asserted; the credential/encryption-change members reference Java-only signature types and are covered by the Python source guards. |
| `Utilities/OtpUriParser.kt`, `Utilities/VaultContracts.kt` | Now compilable here: the stub set gained functional `Base32`/`org.json` stand-ins and the converted `Entry`/`EntryJson` are compiled here too, so the parser's secret rules are executed through the entry batch. |
| `Utilities/EntryPersistence.kt` | Compiled with the converted `DatabaseHelper`/`BackupHelper`, including its Kotlin construction of the converted `EncryptedBackupTask`; not executed here. |
| `Utilities/VaultRestoreOperation.kt` | Type-checked against the real `DatabaseHelper`/`Settings` declarations; its merge/destroy policy stays covered by the Python source guards and the repository's tests. |
| `Utilities/VaultSession.kt` | Compiled with the runner and driven live (generation advances, change listeners, mutation lock); the static facade shapes are asserted. |
| `Database/EntryJsonResult.kt` | The closed parse-error set and the owned one-use seed: `copySecret()` returns copies, `close()` wipes and is idempotent, redacted `toString()`, no generated `data class` members. |
| `Database/EntryJson.kt` | The stored document format, executed against the functional `org.json`/`Base32`/`Hex` stand-ins: legacy field defaults, the malformed-field matrix with its closed errors, the serialized field order, and the Base32/mOTP-hex secret rules. |
| `Database/Entry.kt` | Shape-checked: it is non-final, has both legacy adapters, the nested `OTPType`, `COLOR_RED` and `validateSecret`; the thumbnail getter is reflected too now that `EntryThumbnail` is Kotlin. Entries are only *constructed* through the vault document check (document adapter, validation, serialization, destroy); token/URI members are not executed here. |
| `Utilities/EntryThumbnail.kt`, `Utilities/LetterBitmap.kt` | Lookup behavior and JVM signatures only; inert graphics stand-ins do not exercise rendering. |
| `Utilities/DatabaseHelper.kt` | The vault repository's outcome mapping and its fail-closed document boundary are executed (every `VaultReadError` -> `Status`, `LoadResult`/`VaultException` behaviour, `stringToEntries` rejections, `entriesToString` bounds), as are the stored-document round trip over real entries with whole-document rejection of a bad entry, and the poisoned-transaction short-circuits of every entry point (the poison flag is set by reflection; reaching `FileHelper` would fail the check). The file transaction itself is not: `FileHelper`/`AtomicFile`, `SharedPreferences` and `EncryptionHelper` have no class files here. Its Java-visible surface is reflected, including both public `status` fields. |
| `Utilities/StorageAccessHelper.kt` | **Executed** against a recording fake `ContentResolver` over real temporary files: chunked writes with their six permission checks, mid-write revocation, after-open/after-flush refusals, every pre-provider refusal, provider failures as `false`; bounded reads, cache-only `file:` URIs (sibling prefix, `..`, the cache itself), stream closing; display-name metadata, fallbacks, bound-before-strip and ASCII control stripping. The provider is a fake and `ParcelFileDescriptor` replicates only write-truncate, so no platform provider, URI grant or SAF write semantics are exercised. |
| `Utilities/BackupHelper.kt` | Compiled against the real `Settings`/`EncryptionHelper`/`Constants` declarations. Its methods take the Java-only `Constants.BackupType` (no class file here), so they can be neither executed nor reflected; the nested `BackupFile` holder is instantiated and reflected, and the rest is pinned by `test_kotlin_document_io_conversion.py`. |
| `Utilities/ScanQRCodeFromFile.kt` | **Executed** against scripted stand-ins: a `BitmapFactory` hook that reports bounds and returns pixel-holding bitmaps, a zxing `QRCodeReader` that replays results/failures, and recorded toasts. Covers load failures, bounds rejected before the full decode, rejected decoded bitmaps (recycled), pixels handed to the luminance source, the `TRY_HARDER` retry and the last-failure-wins messages. No image is decoded and zxing itself is not run. |
| `Utilities/NotificationHelper.kt` | The Android 13 permission gate is executed with fake fragments/activities and a runtime `SDK_INT`; the channel/notification members take the Java-only `Constants.NotificationChannel` (no class file here) and are compiled only, against recording `NotificationManager`/`NotificationCompat` stand-ins. |
| `Database/EntryList.kt` | Compiled and shape-checked only: its sorting/filtering API takes `Constants` enums and its entries are `Entry` objects, and the `Constants` enums have no class file in this sandbox, so its behaviour stays covered by the Python source guards and `ApplicationTest`. |

The task subclasses are compiled **against the real Java declarations** they call: `run.sh` passes
`Settings.java`, `EncryptionHelper.java`, `KeyStoreHelper.java` and `FileHelper.java` to `kotlinc`
for resolution (the same mechanism already used for `Constants.java`). `Entry.java`,
`DatabaseHelper.java`, `StorageAccessHelper.java` and `BackupHelper.java` are no longer among them:
all four are Kotlin now (and the converted repository and backup helper read the same real
`FileHelper`/`Constants`/`EncryptionHelper`/`Settings` declarations), and the graphical
`EntryThumbnail.kt`/`LetterBitmap.kt` are now compiled Kotlin, using inert graphics stand-ins.
No class files are produced for the remaining Java resolution sources, so their bodies cannot be *executed*
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
- Every activity, fragment, credential preference, receiver and worker (all still Java), and the
  entry-card adapter/view holder. `NumberPickerPreference` is covered below. The two small adapters below are the exceptions.
- `Utilities/DatabaseHelper.kt` beyond the executed part above: the vault file transaction needs
  `FileHelper` (`AtomicFile`), `SharedPreferences` and `EncryptionHelper`, so recovery, promotion,
  backup/restore and wipe are covered by the instrumentation tests
  (`VaultFailureSecurityTest`, `VaultRestoreOperationTest`) rather than here.
- `Utilities/StorageAccessHelper.kt` against a real provider, and `Utilities/BackupHelper.kt`
  beyond its holder: SAF descriptors, URI grants, `DocumentFile` tree creation, settings-driven names
  and the encrypted-backup writer need the platform, `Settings` and `EncryptionHelper`
  (`KotlinDocumentIoInteropTest`, `EncryptedRestoreSecurityTest`).
- Real image decoding and zxing, notification channels/posting and the runtime-permission dialog
  (`KotlinNotificationQrInteropTest`).
- **Execution** of the task subclass bodies (and of `EntryPersistence`/`VaultRestoreOperation`):
  the Java resolution sources produce no class files, so `Settings`/`DatabaseHelper`/
  `EncryptionHelper` calls inside them cannot run on the desktop JVM here; only their compile-time
  agreement with those real declarations is checked.
- Java *compilation*: `run.sh` does not invoke `javac` or provide the Android dependency classpath, so
  no Java class file is produced and the Java callers' own compilation is never checked. It does pass real Java sources
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
subsets described below, Java-side compilation of the callers (not part of this harness), lint, packaging,
signing or reproducibility.

The entry batch made three more stand-ins functional **replicas** rather than inert types, so the
new checks are only as good as their fidelity. `org.json.JSONObject`/`JSONArray` implement the
constructors (from text and from parsed values), `put`/`get`/`getString`/`getLong`/`getInt`/
`getJSONObject`/`getJSONArray`/`has`/`keys`/`length` and the `opt*` fallbacks the entry code uses;
they keep insertion order for `keys()` and for `toString()`, which emits no whitespace (so a
reserialized document compares byte-for-byte with the stored fixtures), treat `put(name, null)` as a
removal and integral literals as `Long`, and throw `JSONException` for a missing `get`. They also
carry the subset of `JSONTokener` the vault parser uses (`nextValue()` with values converted to
`JSONObject`/`JSONArray` as the platform does, and `nextClean()`, which consumes the next
non-whitespace character and returns NUL at the end). They do **not** implement the rest of the
tokener (`nextString`, `back`, `skipTo`, `more`, comment skipping — the vault parser rejects comment
syntax before the tokener runs), `remove`, `isNull`, `optJSONObject`, duplicate-key rejection or
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

The vault checks are the reason the tokener subset exists: they execute the repository's outcome
mapping and the document boundary's rejections (null, empty, truncated, object-valued,
trailing-content and single-quoted documents are all `VaultException(CORRUPT)`, `"[]"` is an empty
vault, `entriesToString(null)` is a caller error) without ever touching the file system, which is why
the rest of the transaction stays with the instrumentation tests. The entry checks are the reason the
three replicas above exist: they parse the stored document
format, reserialize it and compare the exact string, walk the malformed-field matrix (including the
`MISSING_SECRET` case that the URI parser reports as its own error) and exercise the seed's
one-use/redacted ownership and the secret rules. `Entry` itself is **not** instantiated here, so the
constructors, adapters, enum, constants, thumbnail getter and `validateSecret` are reflected, and `EntryList` is
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

Verified with Kotlin `2.3.20` (the app's pinned version) and `2.4.21` (the bootstrap version), plus
Temurin `25.0.2`, on 2026-10-15 (63 checks). Pin your own compiler when recording results; the check
is only as meaningful as the versions you run it with.

## Thumbnail conversion follow-up

`Utilities/EntryThumbnail.kt` and `Utilities/LetterBitmap.kt` are compiled now, rather than passed as
Java resolution sources. Checks exercise every enum name with case-insensitive lookup, rejection of
unknown/substring matches, declaration-order fuzzy lookup, and reflection of the static Java APIs,
getters and constructors. The source guard fingerprints all pre-conversion enum/resource mappings.

The graphics additions (`Canvas`, `TextPaint`, `Color`, font constants, `Resources.obtainTypedArray`/
`getValue`, and instantiable `TypedValue`) are **inert** compile-only stand-ins. No pixel, glyph,
resource, typed-array recycling or vector fallback behavior is verified on the desktop. The checked-in
Java `KotlinThumbnailInteropTest` exercises real-platform drawing and Java call sites on a device;
it still requires the Android SDK/emulator gates. `AssetType` is intentionally public now to preserve
its formerly public getter without Kotlin exposed-private-type errors; `LetterBitmap` is internal in
Kotlin but public in bytecode (no package-private Kotlin visibility).

## Small UI adapters and autofill follow-up

- `View/TagsAdapter.kt` compiles and executes against minimal `ArrayAdapter`/`CheckedTextView` fakes:
  sorted names, ordered active tags, unknown toggles and notification forwarding, borrowed backing
  maps, copied snapshots, replacement versus merge, recycled-row rebinding, empty-all-active behavior
  and boxed-null states. `createTagsMap` is type-checked against real `Entry`/`Settings` but not
  executed: Java `Settings` has no class file here. The constructor's inherited `ArrayAdapter` item
  order is deliberately not changed; only rendered rows/getTags are initially sorted, as before.
- `View/ThumbnailSelectionAdapter.kt` is compiled and its JVM constructors/filter/Object-returning
  `getItem`/item IDs are reflected. Its instance methods are not executed here because constructing
  real `Settings` needs the Java/Android runtime. Filtering and recycling have device tests instead.
- `View/AutoFillable/AutoFillableTextInputEditText.kt` is compiled and driven with text/non-text values,
  a cleared listener, and a recording fake superclass to pin delegate-before-notify order. Its three
  constructors and nested Java SAM listener are reflected. Passing null only verifies the subclass
  guard with this fake; it does not claim the real superclass tolerates null.

The adapter, layout inflater, checked text/image/grid views, `AttributeSet`, `AutofillValue`, and Material
constructor/autofill stand-ins are approximations only. No platform adapter observers, actual XML,
Material styling, autofill service, main-thread layout or real text insertion is exercised here.
`KotlinSmallAdaptersInteropTest` provides Java-side and real-platform checks for those boundaries,
including locale-sensitive picker filters, ordinals/index fallback and the remembered-tag factory;
it has not been executed in this environment.

## Number preference and hideable-dialog follow-up

- `Preferences/NumberPickerPreference.kt` compiles and executes with recording fake `DialogPreference`,
  `NumberPicker` and `FrameLayout` surfaces. Checks cover defaults, explicit bounds/wrapping, typed-array
  recycling, layout parameters, binding, summary/persistence forwarding, cancel/veto/accept paths,
  focus-clear-before-listener order, default parsing, restored value/fallback, and protected/open Java
  hook/constructor/static-constant shapes. No real PreferenceManager persistence is exercised.
- `Dialogs/HideableDialog.kt` is compiled against real Java `Settings`, and its constructor, static
  `ShowHidableDialog` spelling, listener interfaces and extendability are reflected. Its body is
  **not executed**: `Settings` has no sandbox class file and the compile-only `AppCompatDialog` has no
  content inflation/window. Source guards pin immediate checkbox persistence and the hide flag guard.

The R generator reads `declare-styleable` in values XML to emit **arrays** and distinct deterministic
attribute slots for `NumberPickerPreference`. Those stand-in indexes are not aapt's resource IDs or
ordering, and the fake typed arrays use explicitly supplied values, not XML/theme/reference resolution.
`Context.obtainStyledAttributes` is open only for those fakes. `DialogPreference`/picker state and
clearFocus are recorders, not platform lifecycle, range enforcement, text-edit commit, focus or UI.

The Java `KotlinNumberPickerPreferenceInteropTest` adds real application-XML/widget/listener checks
with recorded number persistence. `KotlinHideableDialogInteropTest` covers real notice layout listeners,
settings writes and the already-hidden helper branch without showing a window; unsuppressed showing,
window/lifecycle behavior and real preference disk I/O remain device/manual acceptance work. Neither
instrumentation class has been compiled or run here; only the desktop checks are executed.

## Password and tag dialog / encrypted preference follow-up

- `Dialogs/PasswordEntryDialog.kt` is compiled and executed against scripted children returned by the
  fake `AppCompatDialog`: checks cover both modes, warning/OK state, matching confirmation, requested
  accessibility/autofill flags, IME done and enter-up handling, nullable callbacks and dismissal.
  The stubs do not inflate `dialog_password_entry`, dispatch real `TextWatcher` events or provide an
  IME/window; `KotlinPasswordDialogsInteropTest` supplies those real-widget and Java-call-site gates.
- `Dialogs/TagsDialog.kt` is compiled and executed against recording `AlertDialog`, `ListView` and
  editable-field stand-ins. The check drives row toggling and both dialog stages, including optional
  `Callable`s. It does not model AlertDialog's automatic button dismissal or a real adapter/window.
- `Preferences/PasswordEncryptedPreference.kt` is compiled against the real Java keystore/settings/
  encryption declarations and reflected, but not constructed here because those resolution sources
  produce no runtime classes. Its first-attachment null default, AndroidKeyStore round trip, mode
  input types and matching rule are covered by `KotlinPasswordEncryptedPreferenceInteropTest` on a
  device. That test records preference writes; it does not claim PreferenceManager disk acceptance.

The Java instrumentation tests are checked in but have not been compiled or run in this environment.
The fake text/layout/dialog additions are recorders only and do not validate Material styling,
selection, focus, lifecycle, rotation, keyboard behavior, accessibility service behavior or window
privacy.

## Document and backup I/O follow-up

- `Utilities/StorageAccessHelper.kt` is compiled **and executed**. The resolver stand-in became an
  `open class` (inert `null` defaults) and `Context.getContentResolver`/`getCacheDir` became `open`
  so the checks can supply a recording provider and their own cache directory; `Cursor` is
  `Closeable` like the platform interface. `ParcelFileDescriptor.open`/`parseMode` replicate only the
  write-truncate `"wt"` mode over a real temporary file, and its `AutoCloseOutputStream` closes the
  descriptor as the platform does, so the checks can read back the written bytes and assert that
  nothing stays open. Any other mode throws in the stand-in. URIs are built with the replicated
  `Uri.Builder` (the stub's `parse` stays inert); its paths are percent-encoded rather than decoded,
  which the checks avoid by using unreserved characters only.
- `Utilities/BackupHelper.kt` is compiled only: every member signature mentions the Java
  `Constants.BackupType`, which has no class file here, so even reflecting its methods fails. Its
  `BackupFile` holder is instantiated and reflected; names, the MIME mapping, the policy order and
  the encrypted-backup layout are pinned by `scripts/tests/test_kotlin_document_io_conversion.py`.
  `backupToFile` will become executable here once `EncryptionHelper` is Kotlin.

The checked-in Java `KotlinDocumentIoInteropTest` covers the real resolver, `Uri.fromFile`,
`DocumentFile` tree and `Settings` paths and the Java call sites on a device, without a test provider
(so it proves refusals and failure handling, not a successful provider write). It has not been
compiled or run here.

## Notification and QR-image follow-up

- `Utilities/ScanQRCodeFromFile.kt` is compiled **and executed**. New stand-ins: zxing's core types
  under `stubs/com/google/zxing` (formats, hints, luminance source, binarizer, result and the three
  reader failures, which like zxing's capture no stack trace) and a `QRCodeReader` that replays a
  scripted queue and records the bitmap and hints of every call; `BitmapFactory.Options` with its
  public fields plus a `decoder` hook for the options overload; a `Bitmap` that holds a size and
  pixels, copies them row by row and records recycling; and a `Toast` that records the resource id
  of every toast shown. None of this decodes an image.
- `Utilities/NotificationHelper.kt` is compiled; its permission gate is executed. `Build.VERSION.SDK_INT`
  is now a runtime field (as on the platform, where it is not a compile-time constant) so a check
  can drive the API 33 branch and restore it; `VERSION_CODES.TIRAMISU`, `Manifest.permission`,
  `PackageManager`, a recording `NotificationManager`/`NotificationChannel`, the deprecated platform
  `Fragment`/`Activity`, `Context.getText`/`checkSelfPermission`/`NOTIFICATION_SERVICE` and the
  AndroidX `NotificationCompat`/`ContextCompat` (under `stubs/androidx/core`) were added. The
  stand-in builder's channel id is non-null, mirroring the `@NonNull` AndroidX parameter.
- `generate-r-stub.py` now gives scalar resources **distinct** deterministic ids (an aapt-like
  `0x7fTTEEEE` layout from the sorted type and entry names) instead of `0` for all of them, so a
  check can tell which toast or message was used. They are still not aapt's ids; nothing may rely on
  a particular number.

The checked-in Java `KotlinNotificationQrInteropTest` round-trips a zxing-generated code through the
real decode path and checks the real channels on a device. It has not been compiled or run here.
