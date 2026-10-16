# Kotlin conversion: extracted helpers, bounded I/O and OTP generation

## Scope and status

Conversions replace Java implementations with Kotlin at the same package/class names. The original
`.java` files are removed, not kept as duplicate logic or wrappers.

First batch (decomposition helpers):

- `Utilities/ScreenTimerController.kt`: main-thread scheduling/lifecycle, without Android views, session authority or I/O.
- `View/EntryActionTarget.kt`: borrowed entry identity plus the originating generation, without persistence, key ownership or UI rendering.

Second batch (leaf/utility components called from Java owners):

- `Utilities/BoundedInputReader.kt`: bounded reads of untrusted documents.
- `Utilities/BoundedOutput.kt`: bounded plaintext/ciphertext returned by a selected OpenPGP provider.
- `Utilities/EncryptedBackupHeader.kt`: backup framing validated before any password derivation.
- `Utilities/TokenCalculator.kt`: TOTP/HOTP (RFC 6238/4226), Steam Guard and mOTP generation.
- `Utilities/EditorActionHelper.kt`: IME "done"/enter action normalization.
- `Utilities/HandlerTimerScheduler.kt`: the Android main-loop bridge behind `ScreenTimerController`.

Third batch (leaf components reachable from the Java owners):

- `Utilities/SecretKeyWrapper.kt`: RSA wrapping of the symmetric vault key, called by
  `EncryptionHelper` and `KeyStoreHelper`.
- `Utilities/DimensionConverter.kt`: preference dimension strings ("12dp") to pixels, called by `Settings`.
- `View/SimpleDoubleClickListener.kt`: single/double tap discrimination for the entry cards, subclassed
  in Java by `EntryViewHolder`.

Fourth batch (drag & drop boundary, dialog/keyboard helpers, task result DTO):

- `View/ItemTouchHelper/ItemTouchHelperAdapter.kt`, `ItemTouchHelperViewHolder.kt` and
  `SimpleItemTouchHelperCallback.kt`: the RecyclerView drag & drop plumbing, converted as one unit
  (implemented in Java by `EntriesCardAdapter`/`EntryViewHolder`, wired by `MainActivity`). This is
  gesture plumbing and forwarding only — the list adapter, its ordering/persistence and the entry
  view holder stay Java.
- `Utilities/UIHelper.kt`: generic dialog/soft-keyboard helpers, called from activities,
  preferences and `EntryActionDialogs.kt`.
- `Utilities/ConfirmedPasswordTransformationHelper.kt`: show-password toggle wiring between the
  password and confirm fields, called from the password dialogs/preferences.
- `Tasks/BackupTaskResult.kt`: the result DTO of the backup/restore tasks, read field-by-field by
  `BackupActivity` and the task subclasses.

Fifth batch (task runner):

- `Tasks/UiBasedBackgroundTask.kt`: the scheduling adapter over the Kotlin task contracts, used by
  every backup/restore/authentication/credentials task and the retained task fragment. The state
  machine, outcome types and session generation stay in `TaskContracts.kt`/`VaultSession.kt`; the
  runner keeps only the executor/main-loop posting and the owned-result disposal hooks. Java task
  subclasses (`AuthenticationTask`, `EncryptedBackupTask`, `FinishIntroTask`, ...) and
  `BackgroundTaskActivity` compile unchanged against it.

Sixth batch (the task subclasses over that runner):

- `Tasks/AuthenticationTask.kt`, `GenericBackupTask.kt`, `GenericRestoreTask.kt`,
  `PGPBackupTask.kt`, `PGPRestoreTask.kt`, `EncryptedBackupTask.kt`, `EncryptedRestoreTask.kt`,
  `ChangeEncryptionTask.kt`, `ChangeCredentialsTask.kt` and `FinishIntroTask.kt`: every
  `UiBasedBackgroundTask` subclass, converted as one layer. Their owners stay Java:
  `BackgroundTaskActivity`, `AuthenticateActivity`, `BackupActivity`, `SettingsActivity`,
  `IntroScreenActivity`, `CredentialsPreference`, `EncryptedBackupWorker` and the instrumentation
  tests keep compiling against the same constructors, enums and nested result DTOs. The runner fix
  that this batch exposed is described under [task runner and `isCanceled()`](#task-runner-and-iscanceled).
- `Utilities/OtpUriParser.kt`, `Utilities/VaultContracts.kt`, `Utilities/EntryPersistence.kt` and
  `Utilities/VaultRestoreOperation.kt` joined the sandbox compile set unchanged: with the stub set
  grown (`Base32`/JSON stand-ins, resolver/keystore/graphics surface) and the then-Java `Entry` a
  resolution source, they now type-check against the real declarations instead of being excluded.
  `EntryPersistence`'s Kotlin construction of the converted `EncryptedBackupTask` is verified by
  that compile; none of them is *executed* in the sandbox (their bodies call the Java-only
  helpers, which produce no class files there).

Seventh batch (the validated entry model — the assessment's first conversion target):

- `Database/EntryJsonResult.kt`: the closed parse-error set and the one-use `ValidatedEntry` value
  that owns its seed (`copySecret()`/`close()`, redacted diagnostics, no generated `data class`
  members).
- `Database/EntryJson.kt`: the stored-document codec — `parse(JSONObject)` returning that result and
  `serialize(...)` writing the same fields in the same order as the Java serializer.
- `Database/Entry.kt` (replacing `Entry.java`): the mutable token/display metadata plus the two Java
  adapters, delegating parsing to `OtpUriParser`/`EntryJson` instead of parsing itself.
- `Database/EntryList.kt` predates this batch as Kotlin and is unchanged; it is compiled with it.

Eighth batch (entry thumbnail leaf renderers):

- `Utilities/EntryThumbnail.kt` and `Utilities/LetterBitmap.kt` replace the Java thumbnail helpers.
  `Entry` keeps the same `EntryThumbnail.EntryThumbnails` type. All 346 thumbnail enum names,
  declaration order, resource mappings and vector defaults are unchanged (source fingerprint guard).
  Static lookup/render entry points and the enum resource/asset getters remain callable from Java.
- Exact lookup still ignores case but not whitespace; fuzzy lookup still uses Java regex whole-word
  boundaries and declaration order, not textual position or a longest-match algorithm.
- Drawing preserves issuer-over-label selection, first UTF-16 char/uppercase/placeholder rules,
  string-hash color selection, integer centering, vector intrinsic dimensions and app-icon fallback.
  Nullable bitmap decode results are now explicit. No new caching, resizing or resource policy is added.
- Kotlin cannot publicly return a private nested type: `AssetType` is now public so that the existing
  `getAssetType()` getter keeps its name and return descriptor without compiler-error suppression.
  `LetterBitmap` is Kotlin `internal` (public JVM bytecode, since Kotlin has no package-private).
  Both classes remain extendable; the static-only `EntryThumbnail` still has its public constructor.
  `LetterBitmap` remains per-request: this conversion deliberately retains its existing typed-array
  recycling in `pickColor` rather than changing its lifetime or making the renderer reusable.
- The sandbox now compiles these real implementations against inert graphics/resource stand-ins;
  it executes enum lookup checks and reflects the Java-facing signatures, **not** rendering.
  `KotlinThumbnailInteropTest` supplies real-platform pixel comparisons against the pre-conversion
  drawing recipe and Java call sites for the device gates; it has not been executed here.

Ninth batch (small UI adapters and autofill field):

- `View/TagsAdapter.kt`, `View/ThumbnailSelectionAdapter.kt` and
  `View/AutoFillable/AutoFillableTextInputEditText.kt` replace their Java implementations. MainActivity,
  EntriesCardAdapter, ManualEntryDialog and AuthenticateActivity remain Java, with the same call
  sites (`TagsDialog` joined Kotlin in the fourteenth batch); the autofill widget keeps its XML class
  name and all three distinct super calls.
- Tags retain natural sorted display order, borrowed maps, copied state snapshots, replacement
  semantics, unknown-tag no-op plus notification, and empty-map all-active behavior. The public
  `Boolean` getter/setter stay boxed (including nullable states), not primitive `boolean`; rendering
  still rejects null states via unboxing rather than silently treating them as unchecked. The
  factory still restores `Settings.getTagToggle` and de-duplicates across entries. Initial inherited
  ArrayAdapter item order is unchanged (the custom rows/getTags, not super.getItem, are sorted).
- The picker retains enum declaration order, device-default-locale substring matching, ordinal IDs,
  high-position Default fallback, negative-position failure, intrinsic graphic rendering, recycled
  views and accessibility descriptions. The raw Java list is now typed internally. The constructor
  is Kotlin internal/public bytecode; formerly package-private `filter` is public and unmangled for
  callers. There is no cache, ROOT-locale behavior change, filtering algorithm change or new policy.
- Autofill still delegates to the platform first, then notifies only text values when a listener is
  registered. The nullable setter and nested SAM interface keep the Java lambda boundary.
- The sandbox compiles all three; it executes tag-state logic and fake autofill wiring, but only
  reflects the picker (real `Settings` is unavailable at runtime). Added Java instrumentation tests
  cover real rows/observers, tag factory, picker locale/index/recycling and widget XML/text insertion.
  They are **not yet executed**; Java compilation, real rendering and platform behavior remain gates.

Tenth batch (number preference and hideable notice):

- `Preferences/NumberPickerPreference.kt` and `Dialogs/HideableDialog.kt` replace the Java classes
  under their existing package/class names; no preference XML or MainActivity call site changes.
- The number preference preserves both XML constructors and the framework dialog style, all three
  public static default constants, protected/overridable lifecycle hooks, min/max/wrapping attributes,
  centered wrap-content picker layout, current-value binding and summary/persistence calls. Positive
  close clears picker focus before consulting `callChangeListener`; cancel and veto do not update
  the value or summary. Restore uses the persisted integer with the minimum as its fallback, not the
  default object; initial setup still takes a boxed integer default. No range clamp or AndroidX
  preference migration is introduced.
- The notice still resolves `dialogTheme`, inflates the same layout, persists checkbox changes
  immediately for positive setting IDs only, and dismisses on OK. The exact misspelled/capitalized
  static `ShowHidableDialog` entry point is retained, including the saved-hide flag guard. It is not
  converted into a singleton, deferred save, or lifecycle owner. Required layout children are now
  checked explicitly rather than relying on disabled Java assertions.
- The sandbox compiles both against real Java `Settings` declarations. It executes the number
  preference against recording fake widgets/persistence/listeners and reflects the dialog API only;
  it cannot instantiate `HideableDialog` because `Settings` has no runtime class file there.
  Sandbox resource generation now emits styleable arrays and distinct deterministic attribute slots
  from `declare-styleable` XML, not scalar stand-ins for arrays. (Since the thirteenth batch the
  scalar ids are distinct deterministic stand-ins too, so checks can tell resources apart.) These slots are **not aapt IDs or
  ordering**, and fake typed arrays do not resolve real XML, references or themes.
- Added Java instrumentation tests cover real picker configuration from application XML, summary
  updates, cancel/veto/accept listeners, default/restore hooks, notice layout listeners and immediate
  setting writes, invalid hide IDs and the saved-hide suppression path. The tests record number
  persistence calls rather than claim real PreferenceManager disk I/O, and do not show an unsuppressed
  dialog window. They still need compilation/execution on the Android gates.

Eleventh batch (the vault repository — the assessment's second conversion target):

- `Utilities/DatabaseHelper.kt` (replacing `DatabaseHelper.java`): the serialized vault file
  transaction — recovery of a staged write, the authenticated read, the bounded write, the staged
  encryption migration, backup/restore/wipe and the entry-document writer/parser. The typed
  outcomes (`VaultReadResult`, `VaultWriteResult`, `VaultCommitDecision`) and the session already
  existed in `VaultContracts.kt`/`VaultSession.kt`; this batch moves the repository that produces
  them. Every member was `static` in Java, so the conversion is the documented `object` delta with
  `@JvmStatic` forwarders; the Java-visible names, the nested `Status`/`LoadResult`/`VaultException`
  types (including both public `status` fields) and both callback interfaces are unchanged.
- This batch was first prepared on top of the seventh batch (as PR #9) and is rebased here onto the
  thumbnail/adapter/preference batches; the repository source it converts is the same
  `DatabaseHelper.java` those batches left untouched.

Twelfth batch (document and backup I/O helpers):

- `Utilities/StorageAccessHelper.kt` and `Utilities/BackupHelper.kt` replace the Java helpers that the
  backup/restore tasks, `EntryPersistence`, `BackupActivity`, `SettingsActivity` and
  `ScanQRCodeFromFile` call: Storage Access Framework reads/writes, document display names, backup
  file naming/creation, the automatic-backup policy and the encrypted-backup writer. Both were
  static-only, so both are the documented `object` delta with `@JvmStatic` forwarders; the nested
  `BackupHelper.BackupFile` holder stays an extendable class with public mutable fields.
- The sandbox now **executes** the storage helper against a recording fake provider (see below);
  `BackupHelper` compiles against the real `Settings`/`EncryptionHelper`/`Constants` declarations
  but its members take the Java-only `Constants.BackupType`, so only its holder runs here.

Thirteenth batch (notification and QR-image helpers):

- `Utilities/NotificationHelper.kt` and `Utilities/ScanQRCodeFromFile.kt` replace the Java helpers that
  `MainActivity`, `SettingsActivity`, the encrypted-backup receiver and the backup worker call: the
  Android 13 notification-permission request, the two backup channels and their notifications, and
  QR decoding from a user-chosen image. Both were static-only, so both are the documented `object`
  delta; the receiver and worker that post the notifications stay Java (components).
- The sandbox **executes** the whole QR pipeline against scripted zxing/`BitmapFactory` stand-ins
  and the permission gate against fake fragments; the channel/notification members take the
  Java-only `Constants.NotificationChannel`, so they only compile here.

Fourteenth batch (small password/tag dialogs and encrypted-password preference):

- `Dialogs/PasswordEntryDialog.kt`, `Dialogs/TagsDialog.kt` and
  `Preferences/PasswordEncryptedPreference.kt` replace their Java implementations. `BackupActivity`,
  `ManualEntryDialog`, `SettingsActivity` and preference XML retain the same Java/XML call sites.
- The sandbox executes the password and tag dialog control flow against recording widgets/dialogs
  and reflects all three Java surfaces. The encrypted preference compiles against the real Java
  `Settings` declaration and the converted crypto helpers but is not constructed there because that
  resolution source has no runtime class; a checked-in Java instrumentation test covers its real
  AndroidKeyStore/layout path.

Fifteenth batch (crypto and KeyStore helpers):

- `Utilities/EncryptionHelper.kt` and `Utilities/KeyStoreHelper.kt` replace the Java helpers of the
  same names. `Settings`, `SettingsActivity`, `PanicResponderActivity`, `CredentialsPreference`,
  `BackupActivity`, `DatabaseHelper`, `BackupHelper` and the five task subclasses keep their existing
  Java/Kotlin call sites and signatures. Both were static-only, so both are the documented `object`
  delta with `@JvmStatic` forwarders; the nested `PBKDF2Credentials`, `EncryptionChangeResult` and
  `EncryptionChangeCallback` types keep their JVM names, and every checked exception keeps its
  `@Throws` declaration.
- The sandbox **executes** the platform-free half of `EncryptionHelper` (PBKDF2 vectors and bounds,
  the legacy SHA-256 key, symmetric key generation, the RSA helpers, the iteration/benchmark bounds
  and the GCM framing guard) and reflects the whole `KeyStoreHelper` surface. The AES-GCM provider
  calls and every AndroidKeyStore path need a device, so a new instrumentation test
  (`KotlinCryptoInteropTest`) plus the existing security suites carry them.

No activity, entry-card list adapter/view holder, fragment, `CredentialsPreference`, receiver, worker
or large manual-entry dialog is converted; the task runner and its subclasses are, and so are the entry
model, the vault repository, the document/backup, notification, QR-image and three small UI pieces
above, plus the crypto and KeyStore helpers — the four Java files that remain under `Utilities/` are
`Constants.java`, `FileHelper.java`, `Settings.java` and `BackupAgent.java`. The repository's own
storage primitives (`Utilities/FileHelper.java`) and the Android file/preference surface it uses stay
Java and are resolved by the sandbox rather than converted. No coroutine, Compose, new
dependency, SDK/bytecode target, storage format or crypto change is included.

**Source conversion is implemented; Gradle and device acceptance is still pending.** What *has* been
executed here is a partial sandbox compile/run check (`scripts/kotlin-sandbox/`), described under
[tests and acceptance](#tests-and-acceptance). Python source guards, a Kotlin parse and that sandbox
check are not the both-flavor Gradle build, lint, packaged-manifest policy, API 28/36 instrumentation
or Java-side compilation. Do not expand to large UI classes or approve release based on them.

## Preserved boundaries

### Timer and its Android bridge

The constructor remains `(Scheduler, Runnable, Runnable)`. The nested `Scheduler` interface keeps
`postDelayed(Runnable, long)`, `remove(Runnable)` and `elapsedRealtime(): long`; public control
methods retain their Java names and void/long signatures. Kotlin function types and default arguments
are not introduced into the Java boundary.

Pending tick/expiry objects are compared by reference (`===`/`!==`), preserving the Java identity
guards. Repeated starts remain idempotent; cancellation invalidates dequeued callbacks; elapsed
realtime still governs inactivity, including after sleep. Reentrant stop/close from a callback must
prevent rescheduling. `close()` permanently drops the nullable callback references; safe calls avoid
forced non-null assertions. The controller remains main-thread confined, not a new thread-safe
scheduler or session authority.

`HandlerTimerScheduler` is now Kotlin too and remains only the main-loop bridge
(`Looper.getMainLooper()`, `SystemClock.elapsedRealtime()`, `postDelayed`/`removeCallbacks`). It holds
no policy: `MainActivity` still chooses when scheduling is allowed and what inactivity means, and
retains rendering, lifecycle wiring and authentication effects.

### Delayed action target

The constructor remains `(Entry, long)`, and `resolve(EntryList, long)` returns an Entry or null.
Kotlin makes that nullability explicit. Resolution requires the captured generation, original object
identity (`candidate === expected`, not Entry.equals) and stable ID. Equal entries or reused IDs must
not redirect a delayed confirmation to a replacement object.

This is deliberately not a data class: no generated entry-based equality, copy, destructuring or
secret diagnostics. `toString()` remains redacted. It borrows an Entry rather than cloning or
destroying its seed. The Java adapter must still check the current key under the mutation lock before
calling resolve and acting; the handle is not an authorization lease or a serialized navigation value.

### Bounded I/O

`BoundedInputReader.read(InputStream, int)` keeps its Java shape: a nullable stream (a null provider
stream is still an `IOException`, not a `NullPointerException`), the same invalid-limit
`IllegalArgumentException` cases, the `@Throws(IOException::class)` declaration the Java callers
compile against, and the guarantee of progress on zero-length reads. It reads at most `limit + 1`
bytes, never trusts provider-reported sizes, and never closes the caller's stream.

`BoundedOutput` keeps extending `ByteArrayOutputStream`, keeps its `synchronized` monitor on the
three public methods, and keeps `overflowed()`. Only the *length* argument is checked locally (a
negative length marks the output as overflowed and throws); a negative *offset* is still validated by
the superclass, exactly as before. A rejected write never yields partial data.

### Backup framing and OTP generation

`EncryptedBackupHeader.parse` keeps its private constructor, static factory, `MIN_BACKUP_BYTES`,
`MAX_ITERATIONS`, big-endian iteration field and copied (non-aliasing) salt/ciphertext getters. The
order of the checks is unchanged: truncation and size before iteration policy, all before any key
derivation.

`TokenCalculator` keeps its constants (`TOTP_DEFAULT_PERIOD`, `TOTP_DEFAULT_DIGITS`,
`HOTP_INITIAL_COUNTER`, `STEAM_DEFAULT_DIGITS`), the nested `HashAlgorithm` enum, the
`DEFAULT_ALGORITHM` *field*, all four public overloads (`TOTP_RFC6238` × 3, `TOTP_Steam`, `HOTP`,
`MOTP`) and the `IllegalStateException("OTP calculation failed")` wrapper. Dynamic truncation, the
`Math.pow(10, digits)` modulus, the Steam alphabet mapping and the mOTP MD5 prefix keep the original
arithmetic. Parameter validation still goes through `InputPolicy.tokenParameters`.

### Key wrapping, dimensions and tap discrimination

`SecretKeyWrapper` keeps the same constructor `(KeyPair)`, the same two checked exceptions
(`GeneralSecurityException`, `IOException`) and the same `wrap`/`unwrap` declarations, so Java
callers keep compiling unchanged. It stays a **non-final** class (`open` in Kotlin): Kotlin classes
are final by default, and a plain `class` would have silently removed the Java class's
extendability. The `@SuppressLint("GetInstance")` on the constructor is kept where the Java had it
(`RSA/ECB/PKCS1Padding` is the transformation JCA names; the ECB lint warning is a false positive).
The algorithm string still comes from `Constants.ALGORITHM_ASYMMETRIC`; wrapping and unwrapping are
unchanged.

`SimpleDoubleClickListener` stays `abstract`, keeps a public no-argument constructor and the two
abstract callbacks Java subclasses override, and keeps the 300 ms delta. The posted callback still
re-reads `firstTap` when it runs, so a second tap suppresses the single click. Two Kotlin-specific
effects are worth knowing: the posted `Runnable` is a SAM-converted lambda (the compiler adds a
private static `onClick$lambda$0` helper, the same mechanism the Java sources' lambdas already use),
and the callbacks' parameters are now non-null `View`, so Kotlin inserts a null check where Java
would have thrown an NPE later.

`DimensionConverter` becomes a Kotlin `object`. Everything Java callers touch is preserved:
`@JvmStatic` static methods, the `dimensionConstantLookup` public static field with its
`Collections.unmodifiableMap` wrapper, the same regex, the same `NumberFormatException` cases and the
same rounding (`(f + 0.5f)` truncation, zero stays zero, a positive sub-pixel value becomes 1). Two
deltas are deliberate and documented rather than incidental: the `object` removes the public
constructor and makes the class final (the same delta as the earlier `Tools` and `InputPolicy`
conversions; every call site uses only the static members), and the unit suffix is still lower-cased
with `Locale.getDefault()`, preserving the Java implementation's locale dependence instead of
silently changing which unit strings are accepted.

### Editor actions

`EditorActionHelper` keeps two static methods with the same names and signatures, but the `KeyEvent`
parameter is explicitly nullable. Android reports IME-triggered actions with a null event, and the
previous Java code returned `true` for `IME_ACTION_DONE` without touching that event. The Kotlin
version keeps exactly that short circuit and still fails (now with a clear `NullPointerException`
instead of a dereference) when a non-"done" action arrives without an event. Making it silently
return `false` would be a behavior change and is left for a separate, reviewable hardening step.

### Drag & drop, dialog helpers and the backup task result

The `ItemTouchHelper` trio is converted as one unit because the two interfaces only have meaning
together with the callback that dispatches to them. `SimpleItemTouchHelperCallback` stays a
**non-final** `open` class with the same public `(ItemTouchHelperAdapter)` constructor and the
`ALPHA_FULL` constant field; `setDragEnabled(boolean)`, `isLongPressDragEnabled()`,
`isItemViewSwipeEnabled()`, `onMove`, `onSwiped`, `getMovementFlags`, `onChildDraw`,
`onSelectedChanged` and `clearView` keep their names, parameter shapes and behavior: swiping stays
disabled (`onSwiped` remains a no-op), cross-view-type moves are rejected before forwarding,
`onSelectedChanged` accepts the platform's nullable view holder, the swipe fade keeps its
`ALPHA_FULL - |dX| / width` arithmetic, and the adapter is still notified with the holders'
adapter positions. The Java implementors (`EntriesCardAdapter`, `EntryViewHolder`) compile
unchanged.

`UIHelper` becomes an `object` with `@JvmStatic` methods (the documented static-only delta: no
public constructor, final class; every call site uses static members only). The `view` parameters
are now explicitly nullable — the Java code null-checked them instead of dereferencing — and
`showKeyboard` keeps the Java **boxed** `Boolean` parameter: a null flag was never passed by any
caller, and it still fails (now with a clear `NullPointerException`) instead of silently meaning
"not forced", the same choice as `EditorActionHelper`. The generic dialog still runs `onOk` only
on the positive button and tolerates a missing handler.

`ConfirmedPasswordTransformationHelper` becomes an `object` with `@JvmStatic setup(...)`. The
end-icon click still dispatches to both fields, starts with both fields masked, keeps the cursor
position when toggling (`setSelection` after the transformation change, only for a non-negative
selection) and refreshes the layout state.

`BackupTaskResult` stays a plain **non-final** class — deliberately *not* a data class, because
`payload` can hold the entire parsed restore database and generated `equals`/`toString`/`copy`
would broadcast it. All fields stay public with the same names and mutability (`resultType`,
`success`, `payload`, `messageId` final; `isPGP`, `decryptIntent`, `uri` writable) via `@JvmField`,
so the Java readers are unchanged. Both constructors, the nested `ResultType` enum constants and
the static `success`/`failure` factories survive with identical Java signatures.

### Task runner

`UiBasedBackgroundTask` keeps its exact Java surface: a non-final generic abstract class with the
same single constructor (`failedResult`), `protected final taskGeneration()`, `getState()`, the
`setCallback` legacy adapter and `setOutcomeCallback`, `execute()`, `isCanceled()`, `cancel()`,
`disposeOutcome(...)`, the protected `doInBackground` (still `throws Exception` in bytecode),
`onCancellation()`/`discardResult(...)` override hooks, `wasCanceled()`, and the nested
`OutcomeCallback`/`UiCallback` functional interfaces (now `fun interface`, so Java lambdas keep
working). The semantics are unchanged: delivery is posted to the main loop and captured by task,
not by result or Activity; delivery claims are one-shot; explicit cancellation and session
revocation dispose owned values even while callbacks are detached; the legacy adapter maps Failure
to the supplied fallback and suppresses Cancelled; `execute()` still throws
`IllegalStateException("Task already submitted")` and still accepts a CANCELED start exactly once.
The androidx threading annotations are dropped as separate symbols: `execute`/`cancel`/
`isCanceled` remain any-thread callable and the delivery remains a main-loop post, which the
Kotlin nullability/types plus the retained comments express.

`Utilities/VaultSession.kt` (already Kotlin) joined the sandbox compile set with this batch: it is
Android-free, so the runner checks drive the real session facade — generation advance, change
listeners and the shared mutation lock — instead of a copy.

`LetterBitmap` was considered for this batch and deliberately stays Java: it is a
package-private class, and Kotlin has no package-private visibility, so converting it would widen
the Java-visible API — the same policy as `Constants` and `FileHelper`.

### Task subclasses

All ten `UiBasedBackgroundTask` subclasses are converted as one layer, because they share the same
owner chain (the runner, the retained task fragment, the activities) and the same review questions.
The Java-visible surface each caller relies on is preserved:

- The constructors keep their exact parameter lists: `AuthenticationTask(Context, boolean, String,
  String)`, `GenericBackupTask`/`GenericRestoreTask(Context, Uri)`, `PGPBackupTask(Context, String,
  Uri)`, `PGPRestoreTask(Context, Uri, Intent)`, `EncryptedBackupTask(Context, ArrayList, String,
  Uri)` plus the six-argument broadcast/auto form, `EncryptedRestoreTask(Context, Uri, String,
  boolean)`, `ChangeEncryptionTask(Context, SecretKey, EncryptionType, byte[])`,
  `ChangeCredentialsTask(Context, EncryptionType, SecretKey, AuthMethod, String)` and
  `FinishIntroTask(Context, EncryptionType, AuthMethod, String, boolean)`.
- `EncryptedBackupTask.Mode` stays a nested enum with `USER`, `AUTO_EDIT`, `BROADCAST`; the nested
  `Result` DTOs of `ChangeEncryptionTask`, `ChangeCredentialsTask` and `FinishIntroTask` keep their
  public final fields (`@JvmField`) and constructors, including `FinishIntroTask.Result`'s
  two-argument form that defaults the generation to `VaultSession.generation()`.
- Every Java class was non-final, so each conversion is an `open class` (or `abstract class` for the
  two generic bases) rather than silently final — the same policy as `SecretKeyWrapper` and
  `BackupTaskResult`. `doInBackground` stays `protected` (bytecode-protected), which is what the
  same-package Java callers (`EncryptedBackupWorker`, the instrumentation tests) rely on: Java
  permits same-package access to protected members, and Kotlin-compiled `protected` keeps that
  bytecode modifier.
- `GenericBackupTask.canWrite()` is `protected open`, because `EncryptedBackupTask` replaces the
  write policy (password presence, configured location equality, broadcast opt-in, keystore-only
  encryption) rather than only the base's session-key check.

Semantic details the conversion keeps deliberately:

- The session/key identity checks stay **reference** comparisons: `VaultSession.getKey() !==
  oldEncryptionKey` in `ChangeCredentialsTask`, exactly the Java `!=` (a structural `!=` would
  accept an equal-but-replaced key).
- `PGPRestoreTask`'s Java multi-catch (`IOException | RuntimeException`) becomes two catch clauses
  rather than one broadened `catch (Exception)`, so non-IO checked exceptions still escape to the
  runner's failure mapping. `EncryptedRestoreTask`'s deliberately broad `catch (Exception)`
  (decrypt/format failures are user-visible import failures) stays broad.
- `AuthenticationTask.confirmAuthentication` keeps the four distinct catch clauses and wipes both
  halves of the derived credentials plus the decoded verifier in `finally`; `upgradeAuthentication`
  returns the key through the one-use `AuthenticationOutcome.verified(...)` factory and wipes the
  original array afterwards, as the Java did.
- Key material owned by `ChangeEncryptionTask` is cloned on construction and wiped in
  `onCancellation()` and after `doInBackground()`; `EncryptedBackupTask` owns a serialized snapshot
  (`stringToEntries(entriesToString(entries))`), destroys it on cancellation, on result disposal and
  after the background work, and synchronizes on it for the export read.
- Nullability follows the Java contracts: parameters the Java code explicitly null-checks
  (`plainPassword`, `password`, `oldEncryptionKey`, `newKey`) stay nullable; parameters it
  dereferenced without checks (`existingAuthCredentials`, the PGP payload/decrypt intent) become
  required non-null parameters, so a null now fails fast at construction with a clear exception —
  the same documented choice as `EditorActionHelper`'s boxed flag. No `!!` appears in any converted
  task file.

`EncryptedBackupWorker` deliberately stays Java: it is a WorkManager `Worker` (an Android-scheduled
entry point, not a task subclass over the runner), and converting components — activities,
receivers, workers — is a separate decision from the task layer.

### Task runner and `isCanceled()`

Converting `EncryptedBackupTask` exposed a latent defect in the fifth batch: the Java
`UiBasedBackgroundTask.isCanceled()` was public and non-final (`EncryptedBackupTask` overrides it so
a broadcast backup ignores session generation changes and stops only on explicit cancellation), but
the Kotlin runner declared a plain `fun isCanceled()` — final by default. No Java or Kotlin subclass
could override it: once any compiler saw both files, the `@Override` would have been rejected. It is
now `open fun isCanceled()` with the same `public boolean isCanceled()` bytecode signature, and the
sandbox check asserts the method stays overridable. This is exactly the kind of error the pending
Gradle gate would have caught; the sandbox now compiles the subclass against the real runner and
would fail again if the override were dropped or the base were re-finalized.

### Entry model and document codec

Parsing is no longer `Entry`'s job. The URI path stays in `OtpUriParser` and the document path is the
new `EntryJson`; both return a closed result instead of throwing from inside the model, and both
enforce `InputPolicy` on the same parameters. `Entry(String)` and `Entry(JSONObject)` remain as thin
adapters: they delegate, throw `IllegalArgumentException` carrying only the error name (never the
rejected input, which may be a secret), and close the parsed value on every path — including the
`destroy()` of an entry that fails later in construction. Java callers see the same constructors,
the same exception type and the same messages' shape.

- The stored format is byte-compatible with the Java serializer: the same field names in the same
  order (`secret, issuer, label, digits, type, algorithm, thumbnail, last_used, used_frequency`, then
  `period` for TOTP or `counter` for HOTP, then `tags`), the secret re-encoded as padded Base32, and
  `period`/`counter` omitted when the entry does not use them. `ApplicationTest.testEntry` compares
  that string exactly, so an ordering or naming change fails there.
- Document numbers must be integral and in range (`InputPolicy.tokenParameters`: `period` 1…86400,
  `digits` 1…9, counter non-negative and not `Long.MAX_VALUE`); a decimal, a string too large for a
  long, or a wrong type is rejected rather than coerced. Steam keeps its own digit default and the
  TOTP/HOTP defaults (`30`/`6`, `HOTP_INITIAL_COUNTER`) come from `TokenCalculator`.
- A document with no `secret` key is reported as `MISSING_SECRET`, matching the URI parser's error for
  a URI without a secret; a non-string secret is `INVALID_TEXT`, an empty or non-canonical Base32
  secret `INVALID_SECRET`. mOTP still requires even-length hex. Rejections are values, not exceptions,
  so `DatabaseHelper.entriesToString` keeps mapping any failure to `VaultException(CORRUPT)`.
- `ValidatedEntry` owns its seed: `copySecret()` returns a copy, `close()` wipes the stored array and
  is idempotent, use after close throws `IllegalStateException`, and no `toString()`, `equals`,
  `hashCode`, `copy` or destructuring is generated from it. `Entry.getSecret()` still returns a copy,
  `destroy()` still wipes and empties the entry, and `ownedSecret()` after `destroy()` still throws
  `NullPointerException` as the Java did.
- Display metadata stays best-effort and unvalidated: an unknown or oversized thumbnail name and an
  unreadable `last_used`/`used_frequency` are tolerated (defaults) rather than rejecting an otherwise
  valid entry, because they are not security-relevant and a rejection would lose the entry.
- The Java-visible surface is unchanged: `public enum OTPType`, `public static final int COLOR_RED`,
  the public `tags` list (field, not a Kotlin `MutableList` property), `@JvmStatic validateSecret`,
  both 8-argument constructors, `getSecretEncoded()` (raw hex for mOTP, Base32 otherwise),
  `toUri()` (including the Java switch's HOTP fall-through that also writes `period`), and
  value-based `equals`/`hashCode`. `hashCode` keeps the Java array-identity byte (`java.util.Objects`
  over the seed array), so equal entries stay equal and the vault's `ArrayList.contains` dedupe is
  unchanged; `EntryList.addEntry` depends on exactly that.
- Nullability follows the Java contracts where they were real: `getSecret()` returns null for a
  destroyed entry, `getCurrentOTP()`/`getPrevOTP()`/`getHideTask()` stay nullable, and the two legacy
  adapters still declare `throws Exception`. A null tag list, `OTPType` or `HashAlgorithm` argument
  now fails fast at construction (Kotlin's parameter check) instead of surfacing later as
  `IllegalArgumentException` — the same documented trade-off as the task batch. A null *issuer* or
  *label* argument, which the Java stored as null and then omitted from the serialized document, is
  normalized to the empty string that every other path (URI parser, document parser, dialogs)
  already produces.
- `Entry` is `open`, not final: it was extendable Java, and `EntryList`/the views hold it as a model.

### Vault repository

The repository is the only owner of the vault file transaction, and it stays exactly that. Only the
language changed: every method, the nested types and the callback interfaces keep their Java
signatures, and the ordering below is the ordering the hardened Java had.

- **Reads distinguish absence from failure**, in this precedence: a poisoned/unrecoverable process
  (`RECOVERY_REQUIRED`) before the file check, `MISSING` before `MISSING_KEY` (a missing vault is
  first-run, not a key problem), then `AUTHENTICATION_FAILED` for a `BadPaddingException` caught
  *before* the broader `IOException` and `Exception` arms that map to `IO_FAILURE` and `CORRUPT`. A
  reordering of those catches would silently relabel a decryption failure, so the source guard pins
  the order. Decrypted plaintext is wiped in the `finally` on every path, including the success one.
- **`LoadResult` keeps the legacy mapping** `VaultReadError -> Status` with one arm per error, so a
  future error constant cannot turn into an `IllegalArgumentException` inside a constructor (the
  Java's `Status.valueOf(error.name())` would do exactly that for any constant without a same-named
  `Status`; it special-cased `RECOVERY_REQUIRED` before reaching it). `RECOVERY_REQUIRED`
  still maps to `IO_FAILURE`: the legacy status set has no recovery value, and
  `VaultFailureSecurityTest.failedPreferenceCommitBlocksWritesUntilRestartOrExplicitWipe` asserts
  that status after wiping a failed preference commit. `LoadResult` stays a one-use view over the
  typed read result — `requireEntries()` transfers entry ownership once and throws `VaultException`
  otherwise, `close()` destroys only untransferred entries.
- **The staged migration is unchanged**: ciphertext is staged with a 36-byte UUID prefix, the
  metadata commit is asked for a decision, the marker preference is read back, `CANCELED` is only
  accepted *before* any metadata mutation, and an `UNKNOWN`/uncommitted marker poisons the process
  (`transactionPoisoned`) so every later recovery and write fails closed until an explicit wipe or
  restart. A committed-but-unpromoted stage is reported as `RecoveryRequired(COMMITTED)`, never as a
  rollback. All entry ownership held by the transaction (the failed-backup branch and the `finally`)
  is destroyed.
- **Bounded and fail-closed documents**: `entriesToString` refuses null/oversized input as a caller
  error (`IllegalArgumentException`) and reports a corrupt vault for per-entry failures; the parser
  rejects a document over `MAX_DOCUMENT_BYTES`, runs `InputPolicy.jsonDepth` before the platform
  tokener, requires the whole value to be a JSON array with nothing but whitespace after it, and
  destroys every partially parsed entry before reporting `CORRUPT`. A malformed document is never a
  short or empty list.
- **Visibility of the two constants**: `TRANSACTION_ID` and `PENDING_FILE` were package-private
  fields in Java (`static final String` with no modifier). Kotlin has no package-private visibility,
  so they are `const val` — same names, same values, read by the same-package `Settings` and by the
  instrumentation tests, but now also visible to out-of-package Java as two non-secret strings. The
  anti-pattern that kept `Constants`/`FileHelper` Java does not apply here: these are `String`
  constants, not a class whose *type* would have to be published.
- **Constructors that stay non-public**: `LoadResult` and `VaultException` had package-private /
  private constructors in Java. Both are `private constructor` plus a `@JvmSynthetic internal`
  factory (`LoadResult.create`, `VaultException.create`), so Java still cannot construct them while
  this file can. The companions add a static `Companion` field with no Java-callable member, and
  the private constructors get Kotlin's synthetic accessor; neither is usable from Java source.
  No `!!` appears in the file.
- **Fields stay fields**: `LoadResult.status` and `VaultException.status` were `public final`
  fields in Java and are `@JvmField` here, so Java keeps reading `result.status`/`error.status`
  rather than needing a `getStatus()` accessor. (PR #9's first draft had dropped `@JvmField` on the
  exception; it was restored when the batch was rebased.)
- **Nullability follows the Java checks**: `key`/`oldKey`/`newKey` stay nullable (missing keys are
  explicit `MISSING_KEY` outcomes), and so does the entry list on the write path — the Java reached
  `entriesToString`'s null check and reported a null list as `Rejected(INVALID_INPUT)` after the
  read, so `saveVault`/`saveDatabase` keep accepting null instead of failing Kotlin's parameter
  check. Parameters the Java dereferenced without a check are non-null and now **fail fast** at the
  call: a null `context` throws before the lock is taken (the Java threw the same
  `NullPointerException` at its first file lookup, or returned the fail-closed result unchanged in a
  poisoned process), and a null `commit` callback for `changeEncryption`/
  `changeVaultEncryption` is rejected before anything is staged. In the Java that null was only
  dereferenced *inside* the staged transaction, where the catch turned it into `UNKNOWN` and
  poisoned the process; failing before staging is the documented trade-off of this repository's
  conversions. Every in-repository caller passes a non-null context and callback.

### Document and backup I/O

These helpers are the app's boundary to user-chosen documents, so the conversion keeps every bound
and refusal exactly where the Java had it.

- **Writes are `content:`-only, bounded and permission-gated**: a null/oversized (`MAX_URI_CHARS`)
  URI, a non-`content` scheme, null data, data over `MAX_DOCUMENT_BYTES` or a refused permission all
  return `false` before the provider is touched. The document is opened with the truncating `"wt"`
  mode, and the caller's `BooleanSupplier` is asked again after opening, before **every** 8 KiB chunk
  and once more after the flush, so a revoked session stops a write mid-document and is reported as
  a failure even when every byte was written (the backup task then deletes the new document).
  `IOException`/`RuntimeException` from the provider stay `false`; provider writes are still not
  atomic, which is why automatic backups always create a new document.
- **Reads are bounded and file URIs stay confined**: `loadFile` refuses null/oversized URIs, accepts
  `content:` documents, and accepts `file:` URIs only when the canonical path is strictly inside the
  private cache directory — the `+ File.separator` suffix keeps a sibling such as `cache-evil/`
  out, and canonicalization defeats `..`. The stream is read through `BoundedInputReader` with the
  document limit regardless of provider metadata, and `loadFile` still declares `IOException`
  (`@Throws`) because the Java `ScanQRCodeFromFile` catches it. `loadFileString` stays the
  never-throwing `""` fallback.
- **Display names are advisory**: provider metadata is used only when the cursor has a non-null
  `DISPLAY_NAME`; any query failure, missing row/column or null value falls back to the last path
  segment, and a null/oversized URI or name yields `"backup"`. The `MAX_TEXT_CHARS` bound is applied
  *before* control characters are stripped, and the stripping regex is the same `[\p{Cntrl}]`, i.e.
  the ASCII control set of `java.util.regex` — C1 controls such as U+0085 are kept, as before.
- **Backup naming and the encrypted layout are unchanged**: automatic backups get a fresh
  `otp_accounts_<UUID>.json.aes`/`.json.gpg` name so the last recoverable backup is never truncated;
  the MIME mapping keeps its `application/octet-stream` fallback; `backupFilename` still constructs
  `Settings` before inspecting the type and falls back to the encrypted name; `autoBackupType` still
  requires a location before it consults the backup password. `backupToFile` refuses a missing/empty
  password or a refused permission before deriving a key, then writes the iteration count
  (`INT_LENGTH` bytes), the salt (`ENCRYPTION_IV_LENGTH` bytes) and the AES-GCM output in that order,
  and still maps every exception to `false` without logging.
- **Nullability follows the Java checks**: the document URI, the data array and the backup password
  stay nullable (the Java null-checked them). The context, the permission supplier, the backup
  location, the backup type, the `String` payload of `saveFile` and the backup `plain` text are
  non-null and now **fail fast** at the call. For the context, the permission supplier and `plain`,
  the Java only dereferenced them inside its catch-all blocks, so a null there used to come back as
  `false`/`""`/the fallback name instead of an exception — the documented trade-off of these
  conversions. Every in-repository caller passes non-null values (the Kotlin task callers are
  compile-time checked).
- **Shape deltas**: both classes are now final with a private constructor and an `INSTANCE` field
  (the `object` delta; neither was ever instantiated or subclassed). `BackupFile` keeps its public
  no-argument constructor, stays non-final, and its `file`/`errorMessage` remain public mutable
  fields (`@JvmField`), not accessors.

### Notifications and QR images

- **The permission gate keeps its shape**: no request without an attached activity or before API 33
  (`TIRAMISU`), then a request for `POST_NOTIFICATIONS` with request code 1 only when the permission
  is not granted. The early-return `SDK_INT` check is kept in the same form, because lint's `NewApi`/
  `InlinedApi` gates (warnings are errors in this build) analyse exactly that flow; the permission
  name stays a class-level constant as in the Java.
- **Channels are unchanged**: ids are `"andOTP_"` plus the enum name lower-cased with the **default
  locale**, as the Java `toLowerCase()` did — Kotlin's `lowercase()` would use the root locale and
  silently rename the channel under a Turkish locale. That pre-existing locale dependence
  (`andOTP_backup_faıled` there) is documented, not fixed: a fix needs a channel migration. Names,
  descriptions, `IMPORTANCE_HIGH`/`IMPORTANCE_LOW` and the default importance for the initial
  constructor are the same, and every notification still reuses id 1.
- **One documented API delta**: the Java built `NotificationCompat.Builder(context, null)` and set the
  channel id after creating the channel. The AndroidX constructor's channel id is `@NonNull` for
  Kotlin, so the converted code passes the same id to the constructor; the builder only stores the
  id, so the posted notification is identical. The `String` body stays nullable (`bigText` accepts
  null and the Java never dereferenced it).
- **QR decoding keeps its pipeline and messages**: a bounded `StorageAccessHelper.loadFile` (either
  failure kind → the load-error toast), a bounds-only decode checked by `InputPolicy.validImage`
  before any pixel allocation, the full `ARGB_8888` decode checked again (and recycled when
  rejected), the pixel copy followed by recycling in `finally`, then a QR-only decode and a retry
  with `TRY_HARDER` on the same binary bitmap. The Java threw the saved failure and caught it by
  type; the Kotlin picks the message with a `when` over the same types in the same order, so the
  **last** decode failure still decides between the checksum, format and generic toasts, a missing
  failure still means "not found", and the stack trace is still printed. The hint maps are built
  once as `EnumMap`s, the retry map being a copy.
- **Nullability**: the image URI stays nullable (a missing intent URI becomes the load-error toast),
  the decoded contents stay a nullable return; `context`, `fragment` and the notification channel
  fail fast when null — the Java dereferenced each of them without a check.

### Password/tag dialogs and encrypted password preference

These three are small Android-facing leaves, not orchestration owners. Their Activity/manual-dialog
callers, the backup policy and the settings screen remain Java.

- **Password entry**: `PasswordEntryDialog` stays open with the same five-argument Java constructor,
  nested `Mode` enum and SAM `PasswordEnteredCallback`. ENTER still hides confirmation, listens on
  the first field and enables OK at `AUTH_MIN_PASSWORD_LENGTH`; UPDATE shows/listens on both fields
  and additionally requires `TextUtils.equals`. The short warning, privacy flags, shared password
  transformation helper, IME-done/hardware-enter handling, enter-up suppression, callback-before-
  dismissal ordering and nullable callback are unchanged. Required inflated children now fail with
  explicit `requireNotNull` rather than a later dereference.
- **Tag picker**: `TagsDialog` was static-only in use and becomes the documented Kotlin `object`
  delta (`INSTANCE`, private constructor/final class) with an `@JvmStatic show(Context, TagsAdapter,
  Callable, Callable)` forwarder. It keeps the original margins, adapter instance, checked-row
  toggle, copied map/replacement semantics, new tags initially active, nullable callables, caught/
  printed callback exceptions, cancel dismissal and nested new-tag dialog. It does not trim, reject
  or de-duplicate empty/new names beyond the adapter map's existing replacement behavior.
- **Encrypted preference**: `PasswordEncryptedPreference` stays open with its XML `(Context,
  AttributeSet)` constructor, nested `Mode`, open `setMode`, protected framework hooks and listener
  interfaces. It keeps the same AndroidKeyStore alias, RSA helper calls, UTF-8/Base64 URL-safe stored
  format, password/PIN input flags, show-password wiring, matching-only save rule and caught/printed
  crypto failures. `key` is explicitly nullable after generation failure and is required only inside
  the existing catch blocks. One null edge is intentionally hardened: this preference XML has no
  `android:defaultValue`, so first attachment can supply a null default. The Java stored null, caught
  a failed encryption and later crashed when the dialog called `value.isEmpty()`; Kotlin now retains
  the existing empty in-memory default and performs no write. Non-null explicit defaults and restored
  ciphertext follow the original paths.

### Crypto and KeyStore helpers

Both helpers are pure crypto/keystore code with no UI or lifecycle ownership, so their conversion is
about preserving the exact byte formats, guard order and checked-exception surface. The boundaries
that matter:

- **Formats and algorithms are untouched.** AES-GCM with the same transformation, 12-byte nonce,
  nonce-prefixed framing and 128-bit tag; RSA/ECB/PKCS1Padding wrapping through `SecretKeyWrapper`;
  PBKDF2WithHmacSHA1 with the same constants (30,000 minimum and 50,000 benchmark iterations for
  authentication, 140,000–160,000 randomly chosen for each new backup) and the same plain SHA-256
  derivation for the legacy backup format; the same URL-safe Base64 storage strings. Nothing in
  these formats changed with the conversion.
- **Guard order in the staged re-encryption is load-bearing and preserved:** permission and live
  session key (`VaultSession.getKey() !== oldKey`, reference identity) are checked before the vault
  is read, and again with the captured generation inside the commit callback, which returns
  `CANCELED` instead of committing when the session was revoked mid-write. A failed read still maps
  to `DECRYPTION_FAILED`, a missing/oversized new key to `MISSING_NEW_KEY`, a failed write to
  `CHANGE_FAILURE`, and `SUCCESS` is only reported after the staged file-plus-metadata commit has
  been recovered.
- **Null safety, not behavior changes**, is the documented delta: the `PBKDF2Credentials` holder
  stays `open` with public mutable `@JvmField byte[]` fields for `password` and `key`, but the fields
  are non-null and start as empty arrays instead of `null`; `oldKey`, `newKey`, `callback`, both
  derived-credential passwords/salts and the framed ciphertext stay nullable exactly where the Java
  accepted `null`; `createEncryptionKey`, the two lookups and the `EncryptionChangeCallback`
  parameter are `SecretKey?`/`KeyPair?` only because the Java returned `null` on failure. The
  callback parameter itself is non-null (it is only ever invoked with a key), so the Java
  `ChangeEncryptionTask` override tightened from `SecretKey?` to `SecretKey` while its `Result` field
  stayed nullable, and the non-framed byte-array parameters of the `encrypt`/`decrypt` overloads are
  non-null so a Java `null` fails at the Kotlin boundary instead of inside the provider. The framed
  `decrypt(SecretKey, byte[]?)` keeps the Java `IllegalArgumentException` for `null` and every frame
  shorter than nonce plus tag.
- **`KeyStoreHelper` keeps the same alias, creation and failure policy**: the RSA wrapping key is
  created under the unchanged alias with `PURPOSE_ENCRYPT`/`PURPOSE_DECRYPT`,
  `ENCRYPTION_PADDING_RSA_PKCS1`, the `CN=<alias>` subject, serial `1` and a 100-year validity
  period, and with no `setUserAuthenticationRequired` policy (still tracked as an open item in the
  security assessment); a missing alias reports `null` instead of generating a replacement in the
  read-only lookup; `loadEncryptionKeyFromKeyStore`/`createEncryptionKey` keep their file-lock
  synchronization and their `null`-on-`GeneralSecurityException`/`IOException`/`RuntimeException`
  policy; `wipeKeys` deletes the wrapped-key file and both aliases inside `VaultSession.lock(RESET)`
  and the database file lock; and the unused `context` parameter of
  `loadOrGenerateAsymmetricKeyPair` (and the unused `failSilent` flag) is retained so call sites do
  not change.
- **One real defect was found and fixed during the batch**, by the new sandbox checks: a line break
  before a leading `+` ended a Kotlin statement early in `generateRandomIterations`, so raw
  `0..20000` values could be returned instead of `140000..160000` back up iterations. A Python guard
  now pins the completed expression.

## Tests and acceptance

Existing Java/Kotlin characterization tests remain in place and unchanged: the RFC vector tests in
`ApplicationTest`, `BoundedInputReaderTest`, `EncryptedBackupHeaderTest`, `InputPolicyTest`,
`ScreenTimerControllerTest`, `EntryActionTargetTest`, `VaultSessionTest`, `TaskContractsTest`,
`SessionContractsTest`, `OtpUriParserTest`, the Kotlin interop tests and the security suites; the backup/restore result now also has a host test of its own (`BackupTaskResultTest`). New
reflection/source guards were added where a conversion could silently change a Java-visible shape:
`scripts/tests/test_ui_decomposition_policy.py` now requires the converted stems, the
`@JvmStatic`/`@JvmField`/`const`/`@Throws`/`@Synchronized` shapes and the editor-action short-circuit,
and the existing architecture guard reads the moved `HandlerTimerScheduler.kt`. With the task batch
it also pins the task subclasses' constructors, `Mode`/`Result` DTOs, the reference-identity session
checks, the wipe paths and the runner's `open isCanceled()`; the contract-migration guard now scans
the Kotlin tree for legacy vault APIs as well, since the tasks moved there; and the sandbox harness
test verifies the `R` stand-in still matches what `run.sh` compiles. With the entry batch it adds
`Entry`, `EntryJson` and `EntryJsonResult` to the converted-stem list and pins the model's shapes
(non-final class, nested `OTPType`, `@JvmField` tags with its getter/setter pair, `COLOR_RED`,
`@JvmStatic validateSecret`, both `@Throws(Exception::class)` adapters), the single definition of the
document field names and their order, the closed error set, the one-use/redacted value, and the
semantics a mechanical conversion could drop (the HOTP/TOTP branch in `ownedSecret`, the value-based
`hashCode`, the `MISSING_SECRET` check, `InputPolicy.tokenParameters`, thumbnail resolution,
`updateOTP`'s `Boolean`, the mOTP PIN placeholder). A new instrumentation test,
`app/src/androidTest/.../Database/EntryJsonTest.java`, characterizes the boundary on a device:
stored-format equality for a round-tripped document, the malformed-field matrix, the seed ownership
rules, the `Entry` adapter's round trip and failure messages, the URI parameter set, the token
calculators and the manual-entry secret validator. With the vault-repository batch the converted-stem
list gains `Utilities/DatabaseHelper`, and a dedicated guard
(`test_converted_vault_repository_keeps_the_transaction_and_its_java_surface`) pins the `object`
shape with its fourteen `@JvmStatic` members, the lock/constant fields, both `@JvmField` status
fields, the nullable write-path entry list, the read precedence and catch order, the plaintext wipe,
the poison flag and recovery ordering, the session-locking wipe and the three entry-destroy paths;
the contract-migration guard now reads the Kotlin repository for the staged-commit, poison, UUID
prefix and write-bound invariants. The existing instrumentation suites that drive the repository
(`VaultFailureSecurityTest`, `VaultRestoreOperationTest`, `CredentialLifecycleSecurityTest`,
`IpcAuthorizationSecurityTest`, `ApplicationTest`) are unchanged and still need a device run. With
the document/backup batch the stem list gains `Utilities/StorageAccessHelper` and
`Utilities/BackupHelper`, and `scripts/tests/test_kotlin_document_io_conversion.py` pins their
static surfaces, nullability, the refusal order before the provider, the four permission checks
around the chunked write, the cache-confinement expression, the bound-before-strip display-name
rule, the unique backup names, the MIME mapping and the encrypted-backup byte layout.
With the crypto/KeyStore batch the stem list gains `Utilities/EncryptionHelper` and
`Utilities/KeyStoreHelper`, and `scripts/tests/test_kotlin_crypto_helpers_conversion.py` pins the two
`object` shapes (`@JvmStatic` counts, no `INSTANCE`-style member access from Java, the retained
`Constants.EncryptionType` parameters), the ten `@Throws` declarations, the `EncryptionChangeCallback`
SAM shape, the guard order of the staged re-encryption, the derivation bounds and the completed
`+ PBKDF2_MIN_BACKUP_ITERATIONS` continuation, the lock/wipe policy, the Java and Kotlin call sites,
the literal equality between `Constants.java` and the mirrored sandbox constants, and the presence of
`KotlinCryptoInteropTest`. That instrumentation test
(`app/src/androidTest/.../Utilities/KotlinCryptoInteropTest.java`) covers what the desktop JDK cannot:
a real `AndroidKeyStore` alias create/load/wipe, the RSA wrap/unwrap and wrapped-key file round trip,
the AES-GCM framing round trip for empty, Unicode and ordinary payloads, wrong-key and tag-tamper
rejection, fresh nonces, the explicit-nonce overload, the derivation bounds and PBKDF2 fields through
the Java-visible surface, and the Kotlin SAM callback implemented and invoked from Java. `scripts/tests/test_documentation_links.py` now fails on any relative documentation link to a file
that no longer exists (several evidence links still pointed at converted `.java` files). A new Java
instrumentation test, `app/src/androidTest/.../Utilities/KotlinDocumentIoInteropTest.java`,
characterizes the real `ContentResolver`/`Uri`/`DocumentFile` paths and the Java call sites: cache
fixture reads and every refused URI (outside, sibling prefix, `..`, the cache itself, `https`, a
missing provider), content-only writes, missing-provider failures and refused permissions,
display-name fallback and control-character stripping, the missing-tree creation message, the
settings-driven file names and the encrypted-backup refusals. It has **not been compiled or run**
here; no test provider is registered, so a successful provider write is not covered by it either.
With the notification/QR batch the stem list gains `Utilities/NotificationHelper` and
`Utilities/ScanQRCodeFromFile`, and `scripts/tests/test_kotlin_notification_qr_conversion.py` pins the
permission gate's exact form, the default-locale channel ids, names and importances, the single
notification id, the constructor-time channel id, the QR pipeline order, both recycling points, the
four load-error toasts, the last-failure-wins bookkeeping, the type order of the failure messages,
the hint maps and the distinct resource stand-ins. `KotlinNotificationQrInteropTest` (Java,
instrumentation) round-trips a QR code generated with zxing's own writer through the real
`BitmapFactory`/zxing path, checks blank, undecodable, refused and missing images, the real channels'
names and importances, and that both `notify` overloads are callable from Java. It has **not been
compiled or run** here. With the password/tag/preference batch,
`scripts/tests/test_kotlin_dialog_preferences_conversion.py` pins the three replacements, retained
Java/XML call sites, enum/SAM/static/override shapes, dialog state rules, privacy flags, callable
handling, exact keystore/Base64 path and the null-default guard. `KotlinPasswordDialogsInteropTest`
uses real inflated password widgets from Java for both modes, privacy flags, watchers, callback and
editor-action behavior; `KotlinPasswordEncryptedPreferenceInteropTest` records preference writes
while exercising the real AndroidKeyStore round trip, first-attachment null default, restored field,
input modes and matching rule. Both are checked in but have **not been compiled or run** here.

### Executed here: partial sandbox compile/run check

`scripts/kotlin-sandbox/` compiles the converted platform-free/low-platform sources against
hand-written platform stubs and runs `Checks.kt` on a desktop JVM; `bootstrap-toolchain.sh` fetches
a JDK and the Kotlin compiler from package mirrors when the machine has neither. Executed on
2026-10-15 with **Kotlin 2.3.20 and 2.4.21 plus Temurin 25.0.2** (`-jvm-target 1.8`), it reports 63 passing
checks after the fourteenth batch:

- RFC 6238 TOTP vectors (SHA-1/256/512, six timestamps, eight digits) and RFC 4226 HOTP vectors.
- TOTP/HOTP agreement with an independent HMAC implementation, including offsets and 1/30/60/86400 s periods.
- Leading-zero token formatting, Steam alphabet mapping, mOTP MD5 prefix.
- `InputPolicy` bounds, JSON nesting scan, `BoundedInputReader`/`BoundedOutput`/`EncryptedBackupHeader` edge cases.
- Timer single-loop start, pause/close, stale callback invalidation and elapsed-time expiry after sleep.
- Session/task contracts: generation revocation, failed-session rejection, one-use `OwnedSecret`, one-shot task lifecycle, redacted diagnostics.
- `SecretKeyWrapper` against a real JVM-generated RSA key pair: round trip of AES-128/AES-256 keys, modulus-sized output, tampered blob, wrong private key, wrong-length input, and an independent `RSA/ECB/PKCS1Padding` decryption proving which transformation is in use.
- `SimpleDoubleClickListener` tap sequences against a fake `View` (delayed single click, suppressed single click, no second double click, fresh single click after the delta).
- `DimensionConverter` parse/unit/rounding cases, the unmodifiable lookup table and the sub-pixel rounding branch.
- Drag & drop forwarding: same-type moves forwarded with adapter positions, cross-type rejection,
  grid/list movement flags (decoded through the stub's replicated `makeMovementFlags`), drag-enable
  toggling, the select/clear lifecycle with its non-idle guard and nullable holder, the swipe-fade
  arithmetic, and the disabled-swipe no-op.
- `UIHelper` dialog confirmation (the handler runs on the positive button, a missing handler is
  tolerated) and keyboard wiring (request versus forced toggle, the null-flag `NullPointerException`,
  missing-view no-ops).
- `ConfirmedPasswordTransformationHelper` toggle sequence: masked start, both fields
  unmasked/re-masked together, the cursor restored, the layout refreshed.
- `PasswordEntryDialog` against scripted children: both modes, minimum length/matching confirmation,
  warning/OK state, privacy flags, IME done/enter-up, callback/dismissal and nullable callback;
  `TagsDialog` row toggling and both dialog stages, including optional callables. Reflection pins
  both Java APIs plus `PasswordEncryptedPreference`'s open class/setter, XML constructor and modes;
  the preference's real crypto body is not executed here.
- `BackupTaskResult` factories, field defaults, writable PGP fields and a payload-free `toString`.
- The background runner on real executor threads with a queued fake main loop: single owned-value
  delivery and one-shot execution, null/exception mapping to typed failures with the legacy
  fallback, pre-start cancellation and idempotent cleanup, explicit cancellation disposing late
  results without legacy callbacks, session-revocation cancellation disposing in-flight values,
  and a stale captured generation being cancelled before work begins.
- `VaultSession` facade shapes (mutation lock field, static generation/listener forwarders) read
  live by the runner.
- The vault outcome mapping and the fail-closed document boundary executed in the sandbox: every
  `VaultReadError` maps to the same `Status` (including `RECOVERY_REQUIRED -> IO_FAILURE`),
  `LoadResult` reports an empty vault as success and an unavailable one as `VaultException` with the
  status-only message, and `stringToEntries` returns `CORRUPT` (never an empty list) for null, empty,
  truncated, object-valued, trailing-content and single-quoted input while `"[]"` stays an empty
  vault and `entriesToString(null)` stays a caller error. Both legacy `status` members are asserted
  to be public final fields (no `getStatus()` accessor) and neither nested type has a Java-callable
  constructor.
- The repository's document path over **real entries** (possible since `Entry` and its thumbnail
  catalogue are both Kotlin): the stored document parses into an entry and is written back
  byte-for-byte, a valid entry followed by an empty, seedless, non-object or truncated one rejects the
  whole document as `CORRUPT`, a destroyed entry cannot be written (`CORRUPT`) and the entry-count
  bound stays a caller error.
- The poisoned-transaction quiescence: with the private poison flag set by reflection (the ambiguous
  commit that sets it needs storage), recovery, reads, the legacy load, both write adapters
  (including a null entry list), both encryption-change adapters, initialization and restore all
  return their fail-closed results **before** reaching `FileHelper` — which has no class file here,
  so a dropped guard fails the check — a poisoned write fails the session, and no metadata commit
  callback runs. The check clears the flag and re-locks the session afterwards.
- `StorageAccessHelper` **executed** against a recording fake provider over real temporary files
  (through a replicated write-truncate `ParcelFileDescriptor`): a 20,000-byte write in three chunks
  with exactly six permission checks and a closed descriptor; revocation before the third chunk
  (16 KiB written, no further checks, descriptor closed), refusal right after opening and after the
  flush; empty and UTF-8 `String` writes; the exact document bound accepted and every pre-open
  refusal (null URI/data, `file:` scheme, oversized URI/data, refused permission) proven not to
  reach the provider; missing descriptors and provider exceptions as `false`. Reads: content and
  cache-fixture documents (stream closed), refusal of null/oversized/`https`/outside/sibling-prefix/
  `..`/cache-directory/empty-path URIs before the provider, the byte limit on a lazily oversized
  stream, a null stream, and provider exceptions propagating from `loadFile` but becoming `""` in
  `loadFileString`. Display names: control-character stripping (C1 kept), cursor closing and
  projection, every metadata fallback, the bound applied before stripping, the placeholder cases and
  no query for `file:` URIs.
- The static Java surfaces of both helpers: `StorageAccessHelper`'s six `@JvmStatic` signatures with
  `IOException` declared on `loadFile` only, and `BackupHelper.BackupFile` as a non-final static
  nested class with public mutable fields and its defaults. `BackupHelper`'s own methods take the
  Java-only `Constants.BackupType`, so they are pinned by the Python guards instead.
- `ScanQRCodeFromFile` **executed** against scripted stand-ins (a recording `BitmapFactory` hook, a
  pixel-holding `Bitmap`, a replaying zxing `QRCodeReader`, recorded toasts): both load-failure
  kinds, rejected bounds (undecodable, empty, oversized, too many pixels) never reaching the full
  decode, a failed or disagreeing full decode (recycled), a first-pass success with the bitmap's
  pixels handed to the luminance source and the bitmap recycled, the `TRY_HARDER` retry on the same
  binary bitmap after a failure or a textless result, the last-failure-wins message matrix, and the
  still-printed stack traces. The hint maps are reflected (separate `EnumMap`s, a `Vector` of
  `QR_CODE`, `TRY_HARDER` only in the copy).
- `NotificationHelper`'s permission gate executed with a runtime `SDK_INT` stand-in and fake
  fragments/activities (below API 33, missing and granted permission, detached fragment); its
  channel/notification members take the Java-only `Constants.NotificationChannel` and are pinned by
  the Python guards.
- The converted crypto helpers: PBKDF2 derivation cross-checked against an independent
  `PBKDF2WithHmacSHA1` split and the RFC 6070 vector, both derivation bound sets with their accepted
  boundaries, the legacy SHA-256 key against known digests, `generateSymmetricKey`, the RSA
  encrypt/decrypt helpers against a real JVM key pair, the random/iteration/benchmark bounds, and the
  framed reader's pre-provider refusal of `null` and every frame shorter than nonce plus tag. The
  AES-GCM provider calls cannot run on this JDK (it requires `GCMParameterSpec` while the unchanged
  Android path passes `IvParameterSpec`), and `EncryptionHelper` cannot be reflected here at all
  because its declared methods mention the Java-only `Constants.EncryptionType`; the static surface,
  the throws clauses and the `KeyStoreHelper` member shapes are asserted where reflection works, and
  the rest stays with the Python guard and the device test.
- The entry document boundary executed against functional `org.json`/`Base32`/`Hex` replicas: the
  stored field order and defaults (including a reserialization that equals the legacy string
  byte-for-byte), the malformed-field matrix with its closed error set, the `ValidatedEntry`
  one-use/redacted ownership, and the Base32/mOTP-hex secret rules.
- The converted `Entry` class reflected: public and non-final class, the six constructors including
  both `Exception`-declaring adapters, the `OTPType` constants, `COLOR_RED`, thumbnail getter and
  `validateSecret` for Base32/mOTP secrets. It is instantiated only through the vault document check
  above (document adapter, validation, serialization, destroy); its token/URI members are not run.
- Thumbnail enum lookups and Java-visible rendering/lookup/getter signatures. The 346 enum/resource
  mappings are also fingerprinted by a Python source guard; actual pixels are not sandbox-tested.
- The ten converted task subclasses, the converted repository, the backup helper and the converted
  crypto helpers are **type-checked against the real Java declarations** they call: `run.sh` passes
  `Settings.java` and `FileHelper.java` (plus `Constants.java`) to `kotlinc` for resolution, so the
  Kotlin calls are verified against the actual parameter/return types instead of stub copies. Their
  bodies are not executed (the Java sources produce no class files on the desktop JVM), and
  reflection covers the members whose signatures avoid those Java-only types: constructor parameter
  lists (`EncryptedBackupTask`'s are visible because generic erasure drops the `Entry` element
  type), the `Mode` enum, protected `doInBackground`/`discardResult` overrides, and
  `FinishIntroTask.Result`'s two constructors and public final fields. `ChangeEncryptionTask`
  implements the Java-only `EncryptionHelper.EncryptionChangeCallback`, so even loading its
  `Class` object resolves that interface; its shapes are pinned by the Python source guards, and the
  same guards cover the members the sandbox cannot reflect.
- The converted sources are compiled with the real `Utilities/Constants.java` on the resolution
  path, so `SecretKeyWrapper` is checked against the actual package-private constant rather than a copy.
- Reflection assertions on the Java-visible shapes (static forwarders, constant fields, checked exceptions, synchronized methods, enum constants, private constructor, non-final class where the Java was extendable, abstract callbacks, and the deliberate `object` delta for static-only utilities).
- The runner's `isCanceled()` is asserted **overridable** — the regression that converting
  `EncryptedBackupTask` exposed (see [task runner and `isCanceled()`](#task-runner-and-iscanceled))
  cannot silently return.

That check is explicitly **non-authoritative**: its stubs are approximations of the Android platform,
so it says nothing about Android runtime behaviour, resources, manifest or packaging. See
[its README](../scripts/kotlin-sandbox/README.md) for the covered sources and the exclusions.

### Still required before release

Both flavors must compile and pass host tests, focused lint and packaged-manifest policy, and the API
28/36 instrumentation matrix, plus the independent release/reproducibility and dependency
comparisons. Java-side compilation of the callers, `Handler`/`Looper` timing on a real main thread,
`KeyEvent` delivery, `TypedValue.applyDimension` (the stub replicates the documented density formula),
the real `View.postDelayed` scheduling, `Entry`'s thumbnail-dependent members, the vault file
transaction itself (`FileHelper`/`AtomicFile`, `SharedPreferences` and the
staged-promotion choreography all need a device), the AES-GCM provider calls and every
AndroidKeyStore path of the converted crypto helpers (alias creation/loading/wipe, wrapping and
unwrapping, the credential ciphertexts) which the desktop JDK and this sandbox cannot run at all, real Storage Access Framework providers (the
platform `ContentResolver`/`ParcelFileDescriptor`, `DocumentFile` tree creation, URI grants and
provider write semantics), the encrypted-backup writer and settings-driven backup names (which need
`EncryptionHelper`/`Settings`), real image decoding, zxing, notification channels/posting and the
runtime-permission flow, and the real
`org.json`/`commons-codec`/`android.net.Uri` behaviour beyond the documented replicated subsets are
not covered by the sandbox check. See
[build commands](build-and-security-tests.md). Remaining decomposition and UI conversion work is
tracked in the [decomposition plan](ui-decomposition-plan.md).

## Deliberately not converted

- `Utilities/Constants.java` and `Utilities/FileHelper.java`: their crypto/backup parameters and the
  helper class are package-private (`final static`, package-private class). Kotlin has no
  package-private visibility, so converting them would widen the Java-visible API. This is an API
  concern, not an access one: compiling `SecretKeyWrapper.kt` with the real `Constants.java` as a
  resolution source (see the sandbox check) confirms that same-package Kotlin reads the
  package-private `ALGORITHM_ASYMMETRIC` constant, and the harness now resolves that Java source
  instead of mirroring it.
- `Tasks/EncryptedBackupWorker.java`: it is a WorkManager `Worker` — an Android-scheduled entry
  point like the activities and receivers, not a task subclass over the runner. Component
  conversion is a separate decision from the task layer (see the sixth batch).

`Utilities/DatabaseHelper` is no longer Java either: it is converted (eleventh batch), and the
package-private-visibility rule above did not block it because the only package-private members were
two `String` constants and a nested class whose constructor Java never called.

The drag & drop trio, `UIHelper`, `ConfirmedPasswordTransformationHelper` and `BackupTaskResult`
are no longer in this list: they are converted (see the fourth batch above). The task subclasses
and their runner are converted too (fifth and sixth batches); the *workers*, receivers and other
components remain Java.

The thumbnail pair has also left this list (eighth batch). The small visibility widenings are
explicitly recorded above; these leaf renderers still need Android acceptance despite their sandbox
compile. Latest local check after the fourteenth batch: 82 Python guards and 63 sandbox checks passed. The both-flavor Gradle
host/lint/instrumentation-APK build was attempted but stopped at the Gradle 8.13 distribution download
(`SSLHandshakeException` from `services.gradle.org`); no Android acceptance result is claimed.
