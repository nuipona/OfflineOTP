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
| `Preferences/PasswordEncryptedPreference.kt` | Compiled and shape-checked against the converted `Settings` class (its accessors are called as methods now that they are Kotlin) and the converted crypto helpers. Construction does not run here — its `DialogPreference` layout path needs the platform — and its encryption path needs `AndroidKeyStore`; the real keystore/layout path has a Kotlin instrumentation test. |
| `Tasks/BackupTaskResult.kt` | Factories, public-field defaults, `ResultType` constants and redacted diagnostics. |
| `Tasks/UiBasedBackgroundTask.kt` | The real runner lifecycle on real executors: value/failure/cancelled delivery, one-shot execution, explicit and session-revocation cancellation, owned-result disposal and the legacy fallback adapter, against a queued fake main loop. |
| `Tasks/AuthenticationTask.kt` | Type-checked against the converted `Settings` and `EncryptionHelper`; constructor and protected-member shapes asserted (its bodies call the Java-only settings/preferences surface, so they are not executed here). |
| `Tasks/GenericBackupTask.kt`, `Tasks/GenericRestoreTask.kt` | The abstract backup/restore bases, compiled with the converted `BackupHelper`/`StorageAccessHelper`; abstractness, constructor and (where reflection can see them) member shapes asserted. |
| `Tasks/PGPBackupTask.kt`, `Tasks/PGPRestoreTask.kt` | Constructor shapes and (for the restore task) the protected `doInBackground` override asserted. |
| `Tasks/EncryptedBackupTask.kt` | Both Java constructors (generic erasure makes them reflectable), the `Mode` enum and the class-level extendability asserted; the snapshot/canWrite policy stays source-guarded. |
| `Tasks/EncryptedRestoreTask.kt` | Constructor and protected-member shapes asserted against the converted `EncryptedBackupHeader`/`EncryptionHelper` declarations. |
| `Tasks/ChangeEncryptionTask.kt`, `Tasks/ChangeCredentialsTask.kt`, `Tasks/FinishIntroTask.kt` | Class-level shapes and (for the intro result DTO, whose fields are JDK types) both constructors and public final fields asserted; the credential/encryption-change members reference Java-only signature types and are covered by the Python source guards. |
| `Utilities/OtpUriParser.kt`, `Utilities/VaultContracts.kt` | Now compilable here: the stub set gained functional `Base32`/`org.json` stand-ins and the converted `Entry`/`EntryJson` are compiled here too, so the parser's secret rules are executed through the entry batch. |
| `Utilities/EntryPersistence.kt` | Compiled with the converted `DatabaseHelper`/`BackupHelper`, including its Kotlin construction of the converted `EncryptedBackupTask`; not executed here. |
| `Utilities/VaultRestoreOperation.kt` | Type-checked against the converted `DatabaseHelper`/`Settings`; its merge/destroy policy stays covered by the Python source guards and the repository's tests. |
| `Receivers/BackupBroadcastReceiver.kt`, `Receivers/EncryptedBackupBroadcastReceiver.kt` | The receiver's **action gate is executed**: a null intent and another action are ignored without reaching the settings, the notifications or the work manager. The failure paths behind it post a notification through the Java-only `Constants.NotificationChannel` (no class file here), so their order and messages are pinned by `test_kotlin_backup_components_conversion.py` and by `KotlinBackupComponentsInteropTest` on a device. The Java-visible shapes (the two public static final constants, the protected `canSaveBackup` helper, the abstract base class) are reflected. |
| `Tasks/EncryptedBackupWorker.kt` | Compiled against the converted task layer, the `androidx.work` stand-ins and the real `Constants` declaration. The **early exits are executed**: stopped or disabled work fails without a notification. The vault read, the backup and the failure notifications are not: they need the Java-only notification channel, the AndroidKeyStore and a real vault file. The constructor and the `ListenableWorker.Result` return type are reflected. |
| `Utilities/BackupAgent.kt` | The refused platform restore is **executed**: the restore throws the documented `IOException`, and the refusal is not preceded by a null check on the platform's (unannotated) arguments. The other callbacks are inert, like the platform's. |
| `Activities/ThemedActivity.kt`, `Activities/BaseActivity.kt` | The **screen-off handling and the obscured-touch rejection are executed** against the new `AppCompatActivity` stand-in: the receiver is registered with `ACTION_SCREEN_OFF`, another action is ignored, the screen-off broadcast notifies the callback, locks the vault and finishes the activity, the lock reaches the activity through the recorded UI-thread delivery, the receiver is unregistered on destroy, and a touch from an obscured (or partially obscured, from Android 10 on) window is refused. The theme, the window color, the locale update, the real broadcast dispatch and the activity lifecycle are not exercised: the stand-in has no window, decor, configuration change or looper. |
| `Activities/PanicResponderActivity.kt`, `Activities/SecureCaptureActivity.kt` | The **panic decision and the window handling are executed**: a foreign action wipes nothing and the panic action without a configured response only removes the task, the "settings" response disables the automatic backups, cancels the pending encrypted backup and locks the vault, and the "accounts" response reaches the wipe of the database. The scanner activity applies the theme and the language, requests the hardware acceleration before the content view is built, blocks the screenshots with `FLAG_SECURE` unless the setting allows them, hides the system bars with the fullscreen flag below Android 11 and with the insets controller (and the requested behaviour) from Android 11 on. The wipe of the database and the keys needs the Java-only `FileHelper`, which has no class file here, so only the steps before it are visible; the real activity start, the real theme, the camera and the real configuration change are not exercised. |
| `Activities/AboutActivity.kt`, `Fragments/BaseAboutFragment.kt`, the fdroid `Fragments/AboutFragment.kt` | The **row wiring, the easter egg and the page building are executed** against scripted layout children, a recording host and an in-memory store: every documented row opens its exact URI as a VIEW intent, the version name binds (including the missing-version and missing-package paths), the nine image rows are tinted, a missing handler (and, in the flavor, any start failure) falls back to the clipboard with the copied-to-clipboard toast, the seven-quick-taps easter egg counts/toasts/cancels, resets on a slow tap and asks before enabling the special features (cancel/confirm split, already-enabled report), and the activity wires its shell and builds the two pages — the flavor about tab and the library list from the scripted raw document (including the empty-document `{}` fallback) — with their titles and the finished up/back navigation. The Java-visible shapes (extendable classes, public `openURI`/`copyToClipboard`, the four public static fields) are reflected. The play flavor differs only by having no extras and is pinned by `test_kotlin_about_screens_conversion.py`. No real window, theme, toast, dialog, clipboard, package manager, AboutLibraries UI or pager layout is involved. |
| `Utilities/VaultSession.kt` | Compiled with the runner and driven live (generation advances, change listeners, mutation lock); the static facade shapes are asserted. |
| `Database/EntryJsonResult.kt` | The closed parse-error set and the owned one-use seed: `copySecret()` returns copies, `close()` wipes and is idempotent, redacted `toString()`, no generated `data class` members. |
| `Database/EntryJson.kt` | The stored document format, executed against the functional `org.json`/`Base32`/`Hex` stand-ins: legacy field defaults, the malformed-field matrix with its closed errors, the serialized field order, and the Base32/mOTP-hex secret rules. |
| `Database/Entry.kt` | Shape-checked: it is non-final, has both legacy adapters, the nested `OTPType`, `COLOR_RED` and `validateSecret`; the thumbnail getter is reflected too now that `EntryThumbnail` is Kotlin. Entries are only *constructed* through the vault document check (document adapter, validation, serialization, destroy); token/URI members are not executed here. |
| `Utilities/EntryThumbnail.kt`, `Utilities/LetterBitmap.kt` | Lookup behavior and JVM signatures only; inert graphics stand-ins do not exercise rendering. |
| `Utilities/DatabaseHelper.kt` | The vault repository's outcome mapping and its fail-closed document boundary are executed (every `VaultReadError` -> `Status`, `LoadResult`/`VaultException` behaviour, `stringToEntries` rejections, `entriesToString` bounds), as are the stored-document round trip over real entries with whole-document rejection of a bad entry, and the poisoned-transaction short-circuits of every entry point (the poison flag is set by reflection; reaching `FileHelper` fails with `UnsupportedOperationException` from the refusing `AtomicFile` stand-in). The live metadata-callback guard is also set by reflection to check that recovery, reads and nested initialization defer, and that constructing `Settings` skips deprecated-setting migration. The file transaction itself is not executed: `AtomicFile` refuses every operation and the ciphertext paths need the Android AES-GCM provider's IV-parameter behavior. Its Java-visible surface is reflected, including both public `status` fields. |
| `Utilities/EncryptionHelper.kt` | **Executed** for the platform-free crypto: the credential derivation cross-checked against an independent `PBKDF2WithHmacSHA1` split plus the RFC 6070 vector, both derivation bound sets (null/over-long password, short salt, iteration range) with their accepted boundaries, the legacy SHA-256 key against known digests, `generateSymmetricKey`, the RSA encrypt/decrypt helpers against a real JVM key pair, the random/iteration/benchmark bounds, and the framed reader's pre-provider refusal of `null` and every frame shorter than nonce plus tag. The AES-GCM provider calls cannot run here (this JDK's SunJCE requires `GCMParameterSpec` while the unchanged Android path passes `IvParameterSpec`), and the class cannot be reflected at all (enumerating its declared methods eagerly loads the Java-only `Constants.EncryptionType`), so its static surface and throws clauses stay pinned by `test_kotlin_crypto_helpers_conversion.py` and by `KotlinCryptoInteropTest` on a device. |
| `Utilities/KeyStoreHelper.kt` | Compiled and reflected only: the five static members keep their parameter and throws clauses (the three that declared no checked exceptions still declare none) and the nullable lookup results are pinned at source level. No `AndroidKeyStore` exists here, so no alias, generation, wrap/unwrap or `wipeKeys` behaviour is executed; `KotlinCryptoInteropTest` covers those paths on a device. |
| `Utilities/Settings.kt` | **Executed** for the platform-free half against an in-memory `SharedPreferences` plus a resource view serving the `settings.xml` defaults: the default matrix, the resource/key resolution of every accessor (recorded), round trips including the salt in Base64 `URL_SAFE`, the string-set tag toggles with the boxed setters' null failure mode, the broadcast-backup throttle (acquire, throttled, stored future timestamp), the credential staging split (real PBKDF2 derivation, 16-byte key half, `URL_SAFE` verifier/salt/iterations, `destroy()` on both paths) and the invariant that `getAndroidBackupServiceEnabled()` stays `false`. `clear`, `commitVaultSettings` and the vault-file half of the staging are only compiled (their `Constants`-enum parameters have no class file and there is no vault transaction), the migration sits behind a poisoned recovery guard, and no theme is rendered. The whole Java-visible surface, including the enum-typed members, is pinned from the compiled class file (reflection cannot describe this class here) by `test_kotlin_settings_conversion.py` and `KotlinSettingsInteropTest`'s callers on a device. |
| `Utilities/StorageAccessHelper.kt` | **Executed** against a recording fake `ContentResolver` over real temporary files: chunked writes with their six permission checks, mid-write revocation, after-open/after-flush refusals, every pre-provider refusal, provider failures as `false`; bounded reads, cache-only `file:` URIs (sibling prefix, `..`, the cache itself), stream closing; display-name metadata, fallbacks, bound-before-strip and ASCII control stripping. The provider is a fake and `ParcelFileDescriptor` replicates only write-truncate, so no platform provider, URI grant or SAF write semantics are exercised. |
| `Utilities/BackupHelper.kt` | Compiled against the converted `Settings`/`EncryptionHelper` and the real `Constants` declaration. Its methods take the Java-only `Constants.BackupType` (no class file here), so they can be neither executed nor reflected; the nested `BackupFile` holder is instantiated and reflected, and the rest is pinned by `test_kotlin_document_io_conversion.py`. |
| `Utilities/ScanQRCodeFromFile.kt` | **Executed** against scripted stand-ins: a `BitmapFactory` hook that reports bounds and returns pixel-holding bitmaps, a zxing `QRCodeReader` that replays results/failures, and recorded toasts. Covers load failures, bounds rejected before the full decode, rejected decoded bitmaps (recycled), pixels handed to the luminance source, the `TRY_HARDER` retry and the last-failure-wins messages. No image is decoded and zxing itself is not run. |
| `Utilities/NotificationHelper.kt` | The Android 13 permission gate is executed with fake fragments/activities and a runtime `SDK_INT`; the channel/notification members take the Java-only `Constants.NotificationChannel` (no class file here) and are compiled only, against recording `NotificationManager`/`NotificationCompat` stand-ins. |
| `Database/EntryList.kt` | Compiled and shape-checked only: its sorting/filtering API takes `Constants` enums and its entries are `Entry` objects, and the `Constants` enums have no class file in this sandbox, so its behaviour stays covered by the Python source guards and `ApplicationTest`. |
| `View/EntryViewHolder.kt` | **Executed** against a scripted card layout: the token is bound and kept unformatted in the view tag for the copy action, the label and the comma separated tags are written, a counter based entry shows its counter instead of the progress bar, and without tap-to-reveal the token layout is shown while the cover is not. The entry has no issuer, so the card does not read the card layout (a Java-only `Constants` enum), and the thumbnail is switched off in the fixture, so no letter bitmap is drawn. The real window, the real theme and the real layout inflation are not exercised. |
| `View/EntriesCardAdapter.kt`, `View/EntryListPresentation.kt` | Compiled and shape-checked only: the adapter's sorting/filtering API takes `Constants` enums and its entries are `Entry` objects, and the `Constants` enums have no class file in this sandbox, so the tag reconciliation stays covered by the Python source guards and by the device test. The class shape, the `Filterable` contract and the read/write `encryptionKey` field are reflected. |
| `Dialogs/ManualEntryDialog.kt` | Compiled and shape-checked only: the static-only Java class became the documented Kotlin `object` with `@JvmStatic` forwarders, and the nested `UpdateCallback` stays a nested interface. The dialog itself needs a real window, a real layout and the main activity, so it is not executed here. |
| `Preferences/CredentialsPreference.kt` | Compiled and shape-checked only: the class name (the preference XML instantiates it by name), the `(Context, AttributeSet)` constructor and the public static `DEFAULT_VALUE` constant are reflected, and the two public setters are asserted. The class initializer reads the Java-only `Constants.AuthMethod`, so the constant's value cannot be read here; it is asserted on the device. |
| `Activities/MainActivity.kt`, `Activities/BackupActivity.kt`, `Activities/SettingsActivity.kt`, `Activities/IntroScreenActivity.kt`, `Activities/AuthenticateActivity.kt`, `Activities/BackgroundTaskActivity.kt` | Compiled and shape-checked only: the class shapes, the base classes, the nested types (`SettingsActivity.SettingsFragment`, the four intro slides, `BackgroundTaskActivity.TaskFragment`) and the members whose signatures avoid the Java-only `Constants` enums are reflected. The activities themselves need the real window, the real theme, the real `Preferences` framework and the vault session, so they are not executed here. |

The task subclasses are compiled **against the real declarations** they call. Since the twenty-second
batch every converted source of the app is Kotlin and `run.sh` compiles all of them, `Utilities/Constants.kt`
and `Utilities/FileHelper.kt` included; no Java file is passed to `kotlinc` any more. The graphical
`EntryThumbnail.kt`/`LetterBitmap.kt` are compiled Kotlin too, using inert graphics stand-ins. Members whose
signatures name a platform class without a stub cannot be reflected, so the remaining shapes are enforced by
the Python source guards in `scripts/tests/`.

`Checks.kt` reproduces the intent of the repository's own tests — `ApplicationTest` (RFC vectors),
`InputPolicyTest`, `BoundedInputReaderTest`, `EncryptedBackupHeaderTest`,
`ScreenTimerControllerTest`, `EntryActionTargetTest` — for the classes above, and adds reflection
assertions that the Java-visible API shapes survived the conversion (`@JvmStatic` forwarders,
`@JvmField`/`const` fields, checked exceptions, `synchronized` methods, enum constants, abstract
callbacks, a **non-final** class where the Java one was extendable, and the deliberate
`object`-conversion delta for static-only utilities). Where a class cannot be reflected at all —
its signatures name a type this sandbox cannot load — `Checks.kt` reads the **compiled class file** instead
(`CompiledClass`): the same names, descriptors and access flags, without resolving a single
referenced type.

`Constants` is compiled, not shadowed: `scripts/tests/test_kotlin_sandbox_harness.py` fails if a stub of
the same name as a production class appears, so the sandbox cannot drift from the real declarations. Its
crypto/backup parameters are `internal const val`s, which same-module Kotlin reads directly.

The RSA round trip is real cryptography: `SecretKeyWrapper` is exercised against a JVM-generated
`KeyPair`, including a tampered blob, a wrong private key and wrong-length input, and one case
decrypts the wrapped bytes with an independent `RSA/ECB/PKCS1Padding` cipher to prove the
transformation actually in use.

Still **not** covered, and therefore still dependent on the Gradle/device gates:

- `View/EntryActionDialogs.kt`, `View/EntryActionTarget.kt`, `View/EntryListPresentation.kt`:
  Android views/dialogs, or APIs that take the `Entry`/`EntryList` model types.
- The **user interface** of the twenty-first batch: the six activities, `Dialogs/ManualEntryDialog`,
  `Preferences/CredentialsPreference` and the entry-card adapter/view holder are compiled and
  shape-checked here, but their behaviour needs the real window, the real `Preferences` framework,
  the vault session and the `Constants` enums, so it belongs to the device test
  (`KotlinConvertedUiInteropTest`) and to the Android gates. The entry card's binding is the
  exception: it is executed above.
- `Utilities/DatabaseHelper.kt` beyond the executed part above: the vault file transaction needs
  `FileHelper` (`AtomicFile`), `SharedPreferences` and the AES-GCM provider, so recovery, promotion,
  backup/restore and wipe are covered by the instrumentation tests
  (`VaultFailureSecurityTest`, `VaultRestoreOperationTest`) rather than here.
- `Utilities/StorageAccessHelper.kt` against a real provider, and `Utilities/BackupHelper.kt`
  beyond its holder: SAF descriptors, URI grants, `DocumentFile` tree creation, settings-driven names
  and the encrypted-backup writer need the platform, `Settings` and `EncryptionHelper`
  (`KotlinDocumentIoInteropTest`, `EncryptedRestoreSecurityTest`).
- Real image decoding and zxing, notification channels/posting and the runtime-permission dialog
  (`KotlinNotificationQrInteropTest`).
- **Execution** of the task subclass bodies (and of `EntryPersistence`/`VaultRestoreOperation`):
  `Settings`/`DatabaseHelper` calls inside
  them cannot run on the desktop JVM here; only their compile-time agreement with those real
  declarations is checked. `tryEncryptionChange`, `loadOrGenerateWrappedKey` and every
  `KeyStoreHelper` path stay in this group even though they are Kotlin now: they need `Settings`, a
  real file transaction or `AndroidKeyStore`.
- The AES-GCM **provider** calls and the AndroidKeyStore alias lifecycle of the fifteenth batch:
  the desktop JDK rejects the unchanged Android nonce type and there is no keystore provider here, so
  the vectors, tamper/tag cases, wrapped-key file round trip, alias generation/wipe and the nullable
  lookup outcomes need `KotlinCryptoInteropTest` (and the existing `EncryptionSecurityTest`,
  `CredentialLifecycleSecurityTest`, `IpcAuthorizationSecurityTest`, `VaultFailureSecurityTest`)
  on a device.
- Java *compilation*: `run.sh` does not invoke `javac` or provide the Android dependency classpath, so
  no Java class file is produced and the Java callers' own compilation is never checked. No Java source
  is passed to `kotlinc`; all 15 Kotlin `Kotlin*InteropTest` suites are now migrated under `app/src/androidTest/kotlin` but
  are deliberately outside this sandbox. The test-source suites also are not part of `run.sh`; the
  Kotlinized tests require Gradle/JUnit/Android acceptance. The sandbox checks Kotlin against the real
  Kotlin declarations; the `ALGORITHM_ASYMMETRIC`/`PBKDF2_*` constants are `internal const val`s, whose
  values the compiler inlines, so the executed crypto checks in the default package still see them.

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

Verified with Kotlin `2.3.20` (the app's pinned version at the time) and `2.4.21` (the bootstrap version), plus
Temurin `25.0.2`, on 2026-10-15 (63 checks). The fifteenth batch added seven checks (70 total) and
was verified with Kotlin `2.4.21`/Temurin `25.0.2` on 2026-10-16. The seventeenth batch added four
(81), the eighteenth two (83) and the nineteenth two (85) checks; the last run reports **85
passing checks** with Kotlin `2.4.21`/Temurin `25.0.2` on 2026-10-15. Pin your own compiler when
recording results; the check is only as meaningful as the versions you run it with.

## Crypto and KeyStore helper follow-up

`Utilities/EncryptionHelper.kt` and `Utilities/KeyStoreHelper.kt` replace the Java helpers of the
same names. Both were static-only, so both are the documented `object` delta with `@JvmStatic`
forwarders; the nested `PBKDF2Credentials`/`EncryptionChangeResult`/`EncryptionChangeCallback` types
keep their JVM names and the checked-exception lists keep their `@Throws`.

What the checks execute is described in the table above. Two limits are worth repeating, because a
green run here does not cover them:

- **AES-GCM cannot run on this JDK.** The app's unchanged code passes an `IvParameterSpec` to
  `AES/GCM/NoPadding` (the Android provider accepts it); the desktop JDK requires
  `GCMParameterSpec` and throws `InvalidAlgorithmParameterException` before any provider work. Only
  the framing guard that runs *before* the provider is executed here. This is a sandbox limitation,
  not a behaviour change: the constants, the transformation name, the nonce length and the
  nonce-prefixed framing are untouched, and `EncryptionSecurityTest`/`ApplicationTest` keep the
  vectors and tamper cases on a device.
- **`EncryptionHelper` reflection (state before the twenty-second batch).** Its declared methods mention
  `Constants.EncryptionType`, which had no class file in that state, and
  `Class.getDeclaredMethods()` resolves parameter types eagerly, so even enumerating the class
  fails. `KeyStoreHelper` (no `Constants` types in its signatures) and the nested
  `PBKDF2Credentials`/`EncryptionChangeResult` types are reflected normally; the rest stays pinned
  by `scripts/tests/test_kotlin_crypto_helpers_conversion.py`.

Deliberate deltas, recorded for review: `PBKDF2Credentials` stays an `open` class with public
mutable `@JvmField byte[]` fields, but the fields are non-null and start as empty arrays instead of
`null`; the two lookups and both key entry points return `KeyPair?`/`SecretKey?`, matching the Java
failure paths that returned `null`; the `EncryptionChangeCallback` parameter is non-null (the
callback is only ever invoked with a key) so `ChangeEncryptionTask`'s override tightened from
`SecretKey?` to `SecretKey` while its `Result` field stayed nullable; and the `encrypt`/`decrypt`
overloads other than the framed reader declare their byte arrays non-null, so a Java caller passing
`null` now fails at the Kotlin boundary instead of inside the JCA provider. The framed
`decrypt(SecretKey, byte[]?)` keeps the Java `IllegalArgumentException` for `null` and every
truncated frame.

One real defect was caught by the new checks and fixed in the same batch: a line break before the
leading `+` in `generateRandomIterations` ended the Kotlin statement after `nextInt(...)`, so raw
`0..20000` values were returned instead of `140000..160000`. The Python guard now pins the completed
expression so the addition cannot be dropped again.

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

The Kotlin instrumentation tests are checked in but have not been compiled or run in this environment.
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
  `backupToFile` still needs `Settings` (a Java resolution source) and the AES-GCM provider, so it
  stays device-only even though `EncryptionHelper` is Kotlin now.

The checked-in Kotlin `KotlinDocumentIoInteropTest` covers the real resolver, `Uri.fromFile`,
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

The checked-in Kotlin `KotlinNotificationQrInteropTest` round-trips a zxing-generated code through the
real decode path and checks the real channels on a device. It has not been compiled or run here.

## Android components follow-up

- `Receivers/BackupBroadcastReceiver.kt` and `Receivers/EncryptedBackupBroadcastReceiver.kt` are
  compiled and the receiver's **action gate is executed**: a null intent and an intent for another
  action are ignored without touching the settings, the notifications or the work manager. The
  failure paths behind that gate are not executed here: they post a notification through
  `Constants.NotificationChannel`, which needs the platform notification manager. Their order and messages are
  pinned by `test_kotlin_backup_components_conversion.py` and covered on a device by
  `KotlinBackupComponentsInteropTest`.
- `Tasks/EncryptedBackupWorker.kt` is compiled against the new `androidx.work` stand-ins
  (`Worker`, `ListenableWorker` with its nested `Result`, `WorkerParameters`, `WorkManager`,
  `OneTimeWorkRequest`, `ExistingWorkPolicy`) and the converted task layer. The **early exits are
  executed**: stopped or disabled work fails without a notification. The worker's stop flag is a
  stand-in property that the checks set; the recorded enqueues in the `WorkManager` stand-in are
  not work, and no scheduler, database or worker thread exists here.
- `Utilities/BackupAgent.kt` is compiled against the new `android.app.backup` stand-ins and the
  refusal is **executed**: the restore throws the documented `IOException` even when the
  platform's unannotated arguments are null, because the Kotlin parameters stay nullable and no
  null check precedes the refusal.
- The `android.content.Intent` stand-in grew a `getAction()` (a method, because the platform class
  is a Java class and the converted receiver calls `intent.getAction()`), and `BroadcastReceiver`
  and `IntentFilter` were added.

The checked-in Kotlin `KotlinBackupComponentsInteropTest` drives the real receiver with a
keystore-backed password, checks that the work is queued under its unique name, that a disabled
broadcast and another action queue nothing, and that the queued work really runs the worker and
reaches a terminal state. It has not been compiled or run here.

## Activity base classes follow-up

- `Activities/ThemedActivity.kt` and `Activities/BaseActivity.kt` are compiled against the new
  `androidx.appcompat.app.AppCompatActivity` stand-in (which records the applied theme, the
  window color, the registered receivers, the posted UI-thread work, the finished state and the
  dispatched touches) and the new `android.view.Window`, `android.view.MotionEvent` and
  `android.os.Bundle` stand-ins.
- The stand-in `Context` hands out a `Resources` whose `configuration` is a **property**, so the
  converted code reads `resources.configuration` and `resources.displayMetrics` instead of the
  Java getters. Both spellings work in the real build, where the platform classes are Java.
- The screen-off handling and the obscured-touch rejection are executed; the theme, the window
  color, the locale update, the real broadcast dispatch and the activity lifecycle are not: there
  is no window, decor, configuration change or looper here, and the `Settings` the activity builds
  needs `FileHelper`, whose `AtomicFile` stand-in refuses here, so the checks poison the vault recovery around it.
- The stand-in's `getString` returns a placeholder, so the checks' activity subclass maps the
  resource ids to the preference keys the in-memory store uses, exactly like the settings checks do.

The converted base activities still need the Android gates: the real theme and window, the real
configuration change, the real screen-off broadcast and the activity lifecycle with rotation.

## Small activities follow-up

- `Activities/PanicResponderActivity.kt` and `Activities/SecureCaptureActivity.kt` are compiled
  against the new `android.app.Activity` stand-in (which records the launch intent and the removed
  tasks), the new `com.journeyapps.barcodescanner.CaptureActivity` stand-in (which hands out an
  inert scanner view) and the extended `android.view.Window` stand-in (which records the flag
  calls and the insets controller).
- The panic responder builds its own `Settings`, so the checks run it with the vault transaction
  poisoned, like the base activity checks: the pending-file recovery needs `FileHelper`, whose `AtomicFile` stand-in refuses here.
- The wipe of the database and of the keys cannot run here. The check asserts that the wipe was
  reached (the vault session was locked with the `RESET` reason) and that the failure is the
  missing `FileHelper` class, so a reordering of the panic steps would fail the check.
- `WindowInsetsController` and `WindowInsets.Type` are stand-ins with the platform's mask and
  behaviour values; they record what was hidden and which behaviour was requested.
- The real activity start, the real theme, the real configuration change and the camera are still
  missing here; the device test starts the panic responder for real and checks the scanner
  activity's shape.

## Converted user interface follow-up

- The twenty-first batch converted the last ten user interface classes: the six activities, the
  manual entry dialog, the credential preference and the two card adapters. At this batch only `Utilities/Constants.java` and `Utilities/FileHelper.java` were still Java, for the documented reasons (the package-private crypto parameters, and the file helper's Java-only API); the twenty-second batch converted them.
- At this batch the Java sources were only **resolved** by `kotlinc`, never compiled, so the Java-only
  `Utilities/Constants` has no class file at runtime here. A class whose members mention one of
  its types therefore cannot be reflected either: enumerating the declared methods or fields of
  `MainActivity`, `BackupActivity`, `SettingsActivity`, `IntroScreenActivity`, `EntryViewHolder` or
  `EntriesCardAdapter` would need the missing class. The checks assert the class shape, the base
  classes, the nested types and the members whose signatures avoid those enums; the remaining
  members are pinned by `test_kotlin_converted_ui_conversion.py` and by
  `KotlinConvertedUiInteropTest` on a device.
- `Checks.kt` reflects the converted classes with `Class.forName(name, false, loader)`, which
  links the class without running its initializer, and it skips a method whose signature cannot be
  resolved instead of failing the whole run.
- The entry card is the one converted user interface class that is **executed** here: the checks
  inflate a scripted card layout, bind an entry and read the token, the label, the tags and the
  counter back. The retained task fragment is executed as well: it starts a task, hands the
  outcome over and cancels a running one.
- Two platform methods that the Java classes had widened to public stay public in Kotlin
  (`BackupActivity.onDestroy()` and `SettingsActivity.SettingsFragment.onCreate()`), and the
  conversion is documented in the class comments and in the source guards.
- The locale semantics of the Java code are kept: Java's bare `toUpperCase()`/`toLowerCase()` use
  the default locale, so the converted calls pass `Locale.getDefault()` explicitly, while the
  credential preference keeps its deliberate `Locale.ENGLISH`.

## Utilities follow-up (twenty-second batch)

- `Utilities/Constants.kt` and `Utilities/FileHelper.kt` replace the last two Java sources of the app, so
  `run.sh` has no `JAVA_SOURCES` list any more. `generate-r-stub.py` and `compile-only.sh` read only the
  `APP_SOURCES` and `STUBS` lists. The R stand-in is unchanged: neither file references a resource id.
- Earlier notes above (the per-batch sections, and the "What it proves" list) that describe `Constants`, `FileHelper`,
  or an enum such as `Constants.BackupType` as Java-only or as having no class file describe the state of their
  batch. Both are compiled now, so the `Constants`
  enums load in the checks.
- `AtomicFile` is a stand-in that **refuses every operation** with `UnsupportedOperationException`. The real
  `FileHelper` therefore runs up to the platform boundary and then fails. This matters because
  `Context.getFilesDir()` is `.` in this sandbox: a stand-in that touched the disk would write or delete
  files in the working directory. The panic-responder check now expects that exception where it used to
  expect `NoClassDefFoundError`, which still proves the wipe was reached and nothing was deleted.
- The `Checks.kt` literal copies of the PBKDF2/backup constants are kept as an independent expectation.
  `test_kotlin_crypto_helpers_conversion.py` keeps them equal to `Constants.kt`.
- Not executed here: Gradle, lint, the instrumentation tests and any device run. The sandbox runner is still
  non-authoritative and compiles no Java/test source. A separate Kotlin 2.4.21 compile-only smoke check of the
  migrated Kotlin test sources with local JUnit/Android stand-ins is also non-authoritative; the Kotlin interop
  suites and all tests still require the Gradle/device gates in [build-and-security-tests.md](../../docs/build-and-security-tests.md).
