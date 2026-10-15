# Kotlin conversion: extracted timer and delayed-action identity

## Scope and status

The first post-decomposition conversion replaces two Java implementations with Kotlin, at the same package/class names:

- `Utilities/ScreenTimerController.kt`: main-thread scheduling/lifecycle, without Android views, session authority or I/O.
- `View/EntryActionTarget.kt`: borrowed entry identity plus the originating generation, without persistence, key ownership or UI rendering.

The original `.java` implementations have been removed, not retained as duplicate logic or wrappers. Existing Java production callers, `HandlerTimerScheduler`, and Java characterization tests continue to call the same constructors and methods. No activity/adapter, dialog renderer, persistence implementation or task runner is converted in this step. No coroutine, Compose, new dependency, SDK/bytecode target, storage format or crypto change is included.

**Source conversion is implemented; build/runtime acceptance is pending.** The sandbox unit-test command failed before Gradle could run because `JAVA_HOME` and a `java` executable are absent. Python guards and grammar parsing are not Kotlin/JVM compilation, bytecode/API validation or Android acceptance. Do not expand to large UI classes or approve release based on these checks.

## Preserved boundaries

### Timer

The constructor remains `(Scheduler, Runnable, Runnable)`. The nested `Scheduler` interface keeps `postDelayed(Runnable, long)`, `remove(Runnable)` and `elapsedRealtime(): long`; public control methods retain their Java names and void/long signatures. Kotlin function types and default arguments are not introduced into the Java boundary.

Pending tick/expiry objects are compared by reference (`===`/`!==`), preserving the Java identity guards. Repeated starts remain idempotent; cancellation invalidates dequeued callbacks; elapsed realtime still governs inactivity, including after sleep. Reentrant stop/close from a callback must prevent rescheduling. `close()` permanently drops the nullable callback references; safe calls avoid forced non-null assertions. The controller remains main-thread confined, not a new thread-safe scheduler or session authority.

`HandlerTimerScheduler` remains the Java/Android bridge. `MainActivity` still chooses when scheduling is allowed and what inactivity means; it retains rendering, lifecycle wiring and authentication effects.

### Delayed action target

The constructor remains `(Entry, long)`, and `resolve(EntryList, long)` returns an Entry or null. Kotlin makes that nullability explicit. Resolution requires the captured generation, original object identity (`candidate === expected`, not Entry.equals) and stable ID. Equal entries or reused IDs must not redirect a delayed confirmation to a replacement object.

This is deliberately not a data class: no generated entry-based equality, copy, destructuring or secret diagnostics. `toString()` remains redacted. It borrows an Entry rather than cloning or destroying its seed. The Java adapter must still check the current key under the mutation lock before calling resolve and acting; the handle is not an authorization lease or a serialized navigation value.

## Tests and acceptance

Existing Java characterization cases remain in place: seven original timer cases and four original delayed-target cases. Added for this conversion:

- Three Java host timer cases covering the Java constructor/interface/method shapes, close during a callback, and large long-valued inactivity delays.
- One Java host target case covering its Java signature, nullable resolution and absence of generated value methods.
- One Java Android instrumentation case exercising the real Java Handler/Scheduler -> Kotlin controller -> Java callback bridge on the main looper, with cleanup in finally.
- A Python source guard requiring a single Kotlin implementation of each helper and the intended API/identity shapes; the existing architecture guard now reads the moved timer source.

The host and instrumentation tests above are **written, not executed here**. They must pass through the existing both-flavor host/build/lint gates and API 28/36 instrumentation matrix, alongside packaged-manifest and independent-release/dependency checks. See [build commands](build-and-security-tests.md). Runtime dialog teardown, rotation/process recreation and provider behavior remain outstanding acceptance work described in the [decomposition plan](ui-decomposition-plan.md).
