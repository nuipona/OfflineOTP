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

No activity, adapter, fragment, preference, receiver, worker, dialog renderer, persistence
implementation or task runner is converted. No coroutine, Compose, new dependency, SDK/bytecode
target, storage format or crypto change is included.

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

### Editor actions

`EditorActionHelper` keeps two static methods with the same names and signatures, but the `KeyEvent`
parameter is explicitly nullable. Android reports IME-triggered actions with a null event, and the
previous Java code returned `true` for `IME_ACTION_DONE` without touching that event. The Kotlin
version keeps exactly that short circuit and still fails (now with a clear `NullPointerException`
instead of a dereference) when a non-"done" action arrives without an event. Making it silently
return `false` would be a behavior change and is left for a separate, reviewable hardening step.

## Tests and acceptance

Existing Java/Kotlin characterization tests remain in place and unchanged: the RFC vector tests in
`ApplicationTest`, `BoundedInputReaderTest`, `EncryptedBackupHeaderTest`, `InputPolicyTest`,
`ScreenTimerControllerTest`, `EntryActionTargetTest`, `VaultSessionTest`, `TaskContractsTest`,
`SessionContractsTest`, `OtpUriParserTest`, the Kotlin interop tests and the security suites. New
reflection/source guards were added where a conversion could silently change a Java-visible shape:
`scripts/tests/test_ui_decomposition_policy.py` now requires the converted stems, the
`@JvmStatic`/`@JvmField`/`const`/`@Throws`/`@Synchronized` shapes and the editor-action short-circuit,
and the existing architecture guard reads the moved `HandlerTimerScheduler.kt`.

### Executed here: partial sandbox compile/run check

`scripts/kotlin-sandbox/` compiles the converted platform-free/low-platform sources against
hand-written platform stubs and runs `Checks.kt` on a desktop JVM. Executed on 2026-10-15 with
**Kotlin 2.4.21 and Temurin 25.0.2** (`-jvm-target 1.8`), it reports 18 passing checks:

- RFC 6238 TOTP vectors (SHA-1/256/512, six timestamps, eight digits) and RFC 4226 HOTP vectors.
- TOTP/HOTP agreement with an independent HMAC implementation, including offsets and 1/30/60/86400 s periods.
- Leading-zero token formatting, Steam alphabet mapping, mOTP MD5 prefix.
- `InputPolicy` bounds, JSON nesting scan, `BoundedInputReader`/`BoundedOutput`/`EncryptedBackupHeader` edge cases.
- Timer single-loop start, pause/close, stale callback invalidation and elapsed-time expiry after sleep.
- Session/task contracts: generation revocation, failed-session rejection, one-use `OwnedSecret`, one-shot task lifecycle, redacted diagnostics.
- Reflection assertions on the Java-visible shapes (static forwarders, constant fields, checked exception, synchronized methods, enum constants, private constructor).

That check is explicitly **non-authoritative**: its stubs are approximations of the Android platform,
so it says nothing about Android runtime behaviour, resources, manifest or packaging. See
[its README](../scripts/kotlin-sandbox/README.md) for the covered sources and the exclusions.

### Still required before release

Both flavors must compile and pass host tests, focused lint and packaged-manifest policy, and the API
28/36 instrumentation matrix, plus the independent release/reproducibility and dependency
comparisons. Java-side compilation of the callers, `Handler`/`Looper` timing on a real main thread,
`KeyEvent` delivery and `commons-codec` behavior are not covered by the sandbox check. See
[build commands](build-and-security-tests.md). Remaining decomposition and UI conversion work is
tracked in the [decomposition plan](ui-decomposition-plan.md).
