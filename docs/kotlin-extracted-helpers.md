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

`Utilities/EntryThumbnail.java` and `Utilities/LetterBitmap.java` stay Java: their constants are
Android resource ids (`R.drawable`/`R.mipmap`) and their bitmaps are platform graphics. `Entry`
therefore keeps `EntryThumbnail.EntryThumbnails` as its thumbnail type, exactly as the Java did.

Eighth batch (the vault repository — the assessment's second conversion target):

- `Utilities/DatabaseHelper.kt` (replacing `DatabaseHelper.java`): the serialized vault file
  transaction — recovery of a staged write, the authenticated read, the bounded write, the staged
  encryption migration, backup/restore/wipe and the entry-document writer/parser. The typed
  outcomes (`VaultReadResult`, `VaultWriteResult`, `VaultCommitDecision`) and the session already
  existed in `VaultContracts.kt`/`VaultSession.kt`; this batch moves the repository that produces
  them. Every member was `static` in Java, so the conversion is the documented `object` delta with
  `@JvmStatic` forwarders; the Java-visible names, the nested `Status`/`LoadResult`/`VaultException`
  types and both callback interfaces are unchanged.

No activity, list adapter, fragment, preference, receiver or worker is converted; the task runner
and its subclasses are, and so are the entry model and the vault repository above. The repository's
own storage primitives (`Utilities/FileHelper.java`) and the Android file/preference surface it uses
stay Java and are resolved by the sandbox rather than converted. No coroutine, Compose, new
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
  Java's `Status.valueOf(error.name())` did exactly that for `RECOVERY_REQUIRED`). `RECOVERY_REQUIRED`
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
  this file can. No `!!` appears in the file.

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
calculators and the manual-entry secret validator.

### Executed here: partial sandbox compile/run check

`scripts/kotlin-sandbox/` compiles the converted platform-free/low-platform sources against
hand-written platform stubs and runs `Checks.kt` on a desktop JVM; `bootstrap-toolchain.sh` fetches
a JDK and the Kotlin compiler from package mirrors when the machine has neither. Executed on
2026-10-15 with **Kotlin 2.4.21 and Temurin 25.0.2** (`-jvm-target 1.8`), it reports 41 passing
checks:

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
  vault and `entriesToString(null)` stays a caller error.
- The entry document boundary executed against functional `org.json`/`Base32`/`Hex` replicas: the
  stored field order and defaults (including a reserialization that equals the legacy string
  byte-for-byte), the malformed-field matrix with its closed error set, the `ValidatedEntry`
  one-use/redacted ownership, and the Base32/mOTP-hex secret rules.
- The converted `Entry` class **reflected, not instantiated** (its members reference the Java,
  resource-backed `EntryThumbnail`, so no `Entry` object can exist in this sandbox): public and
  non-final class, the six constructors including both `Exception`-declaring adapters, the `OTPType`
  constants, `COLOR_RED`, and `validateSecret` for Base32/mOTP secrets.
- The ten converted task subclasses and the converted repository are **type-checked against the real
  Java declarations** they call: `run.sh` passes `Settings.java`, `EncryptionHelper.java`,
  `BackupHelper.java`, `StorageAccessHelper.java`, `KeyStoreHelper.java`, `FileHelper.java`,
  `EntryThumbnail.java` and `LetterBitmap.java` to `kotlinc` for resolution, so the
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
transaction itself (`FileHelper`/`AtomicFile`, `SharedPreferences`, `EncryptionHelper` and the
staged-promotion choreography all need a device) and the real
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
- `Utilities/EntryThumbnail.java`: it is a resource catalogue — its enum constants are
  `R.drawable`/`R.mipmap` ids and the class returns platform drawables/bitmaps — so it needs the
  Android build regardless of language. Converting it would neither remove platform code nor shrink
  the Java-visible API, so it stays Java and the sandbox resolves it for `Entry`'s compile check.
- `Utilities/LetterBitmap.java`: same package-private policy — the class itself is package-private,
  and a Kotlin conversion would have to publish it to at least `public`/`internal` bytecode.
- `Tasks/EncryptedBackupWorker.java`: it is a WorkManager `Worker` — an Android-scheduled entry
  point like the activities and receivers, not a task subclass over the runner. Component
  conversion is a separate decision from the task layer (see the sixth batch).

`Utilities/DatabaseHelper` is no longer Java either: it is converted (eighth batch), and the
package-private-visibility rule above did not block it because the only package-private members were
two `String` constants and a nested class whose constructor Java never called.

The drag & drop trio, `UIHelper`, `ConfirmedPasswordTransformationHelper` and `BackupTaskResult`
are no longer in this list: they are converted (see the fourth batch above). The task subclasses
and their runner are converted too (fifth and sixth batches); the *workers*, receivers and other
components remain Java.
