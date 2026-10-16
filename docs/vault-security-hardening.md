# Vault security hardening

The subsequent [Kotlin OTP parser pilot](kotlin-otp-parser-pilot.md) owns URI validation through the existing Java Entry adapter, with explicit success/failure results and additional encoding checks; the rest of these lifecycle changes remain Java.

This documents the current implementation and its compatibility changes, **not a completed security audit or a claim that Android tests have passed**. Minimum Android remains API 28, target API 35; both existing flavors remain supported by the build configuration.

## IPC authorization

The panic activity and encrypted-backup receiver require the app-owned
`${applicationId}.permission.CONTROL_VAULT` permission, declared with **signature** protection. Android checks it before delivering an activity launch or broadcast. Exported-component allowlisting alone is not authorization.

A companion application must use the same signing identity and request this permission. An explicit package/component or a correct action string does not grant access. Independently signed Tasker/PanicKit installations and ordinary `adb shell am` commands are intentionally no longer authorized. There is no caller-name, Intent-extra, or user-toggle bypass. Introducing independently signed automation again requires a separately designed, revocable authorization mechanism.

The launcher/import activity remains public for normal account import. Incoming OTP accounts require confirmation after vault authentication; invalid input does not silently add entries. Internal credential/settings/backup activities remain unexported. Obscured touch events are rejected by the base vault activities (partially obscured events additionally on API 29+).

## Bounded input and fail-closed results

| Input | Policy |
| --- | --- |
| Documents/provider output | 16 MiB; byte counts enforced independently of provider metadata |
| JSON nesting | At most 32 levels, checked before recursive platform parsing; single-quoted/comment JSON extensions rejected |
| Accounts | At most 2,000 per vault/import |
| OTP URI | At most 16,384 characters; duplicate singleton parameters rejected |
| Label/issuer/password | At most 1,024 characters |
| Tags | At most 32, each at most 128 characters |
| Seed | At most 2,048 decoded bytes, validated encoding |
| Period/digits/counter | Period 1–86,400 seconds; digits 1–9 (Steam fixed to its format); nonnegative counter below `Long.MAX_VALUE` |
| Decoded QR image | Positive dimensions, each at most 8,192, at most 4,000,000 pixels; validated before allocating pixel buffers |
| Untrusted encrypted-backup KDF | 1–1,000,000 iterations, validated before derivation |
| Stored login KDF | 1–20,000,000 iterations accepted; newly benchmarked credentials capped at 2,000,000 |

These limits deliberately reject formerly accepted oversized or malformed data. The existing backup writer's 140,000–160,000 iteration range is unchanged. Normal historical backup framing and custom 1–9 digit OTPs remain supported within the limits. Database payloads leave 64 bytes of headroom for the staged transaction framing.

JSON imports are all-or-nothing: malformed entries cannot produce a successful prefix. Missing files, missing keys, authentication failures, corrupt data and I/O failures have distinct load outcomes; none is treated as a legitimate empty database. Ordinary saves must authenticate the existing vault first. Only explicit setup/reset can initialize a missing vault. Crypto failures no longer manufacture a zero OTP.

Storage Access Framework output uses `content:` URIs; arbitrary external `file:` reads are rejected (private cache fixture files remain supported). Display-name metadata is optional, length-limited and sanitized. Null streams, provider exceptions and oversized PGP output fail rather than becoming successful partial exports. Byte limits do **not** impose a wall-clock timeout on a blocking provider.

## Session and credentials

Vault keys are process-memory state, not Activity Intent/Bundle extras. Authentication/setup key handoffs are one-use and generation-bound. Process death requires reopening/authentication; it does not restore a serialized key.

Locking advances the generation, clears the handoff, invalidates asynchronous results and clears adapter entry/token state. Background/screen-off locking follows the existing relock settings. The device-credential challenge is exempted from background relocking while pending because no vault key has been installed yet. UI task delivery and vault mutations are serialized against revocation. Password/seed widgets do not automatically save their text into Android view state. Byte-array cleanup is best-effort: Java Strings, framework copies, and provider buffers cannot be reliably erased.

KeyStore lookup never silently invents a replacement key for an existing vault. Key creation is confined to explicit setup/reset or an authenticated encryption migration. An invalidated or missing key needs a portable recovery backup; generating another key cannot decrypt the old database.

Credential changes derive new credentials without changing the active verifier first. The transaction is:

1. Authenticate/load the existing ciphertext and retain an encrypted recovery copy.
2. Write staged ciphertext with a unique transaction UUID.
3. Synchronously commit the matching encryption/auth settings and UUID.
4. Promote the ciphertext through `AtomicFile` and verify a bounded read-back.

Startup performs journal recovery **before selecting keys**. Uncommitted staged data is discarded in favor of the original vault; committed staged data is promoted. A failed preferences commit blocks further process-local vault writes, because Android's in-memory preferences may already have changed. Restart or an explicit destructive wipe is needed. Cancellation before metadata commit leaves the old credentials authoritative. Cancellation after a completed commit cannot undo that commit; the next authentication must use the newly committed credentials.

The recovery copy contains old ciphertext, not a plaintext export or a raw key. It can require the previous password/key; it is not a portable backup or an automatic credential rollback. AtomicFile/read-back is not a guarantee against every filesystem, power-loss or hardware failure. Legacy SHA-256 verifier upgrades are auth-only, restricted to a successfully authenticated KeyStore-mode vault; they cannot rewrite a password vault's credentials without re-encryption.

## Backup lifecycle

Platform cloud backup and device transfer are disabled: `allowBackup=false`, no manifest backup agent, and explicit exclusion resources for cloud/device-transfer/full-backup storage domains. The retained legacy agent registers no helpers and refuses restore callbacks. Old cloud restore behavior is intentionally not supported. Maintain and test portable password-encrypted or OpenPGP backups before upgrading or changing devices/signing identities.

Automatic backups require a configured location, explicit opt-in, and a nonempty encrypted backup password. Broadcast backups additionally require KeyStore mode and the signature permission. Duplicate broadcasts are coalesced through unique WorkManager work and a one-minute enqueue throttle. Workers recheck configuration at execution time; export checks also cover cancellation, changed location/password/opt-in, and session revocation during an in-flight operation.

Automatic exports create new UUID-named documents, even if the old date/time naming option is off. A failed export does not truncate the previous backup. Failed new documents are deleted best-effort. Storage providers are not atomic or inherently trustworthy: a failed user-selected export may leave partial data, and a hostile provider can ignore delete requests or retain bytes already received. Panic disables automatic backups, cancels pending unique work, revokes the session and performs the requested wipe under mutation locks; it cannot recall bytes already exported.

## Regression coverage and remaining verification

The Python suite checks the signature-bound manifest policy, backup-disable policy, source exclusion rules, build-input provenance, fixtures and independent-build comparison logic. Kotlin host tests cover input/header limits and process-session handoffs; ordinary Android security tests are Kotlin too. All 15 `Kotlin*InteropTest` suites are now Kotlin and verify the same Java-callable APIs. Android tests additionally cover:

- A **different UID and certificate**, verified before an independent caller attempts panic and backup IPC; denial, unchanged vault/settings and absence of queued backup work are asserted even with backup/panic configured.
- Wrong-key save/migration rejection, missing-versus-empty handling, missing-key non-regeneration, all-or-nothing JSON, journal recovery and failed metadata commits.
- Credential-change failure preserving ciphertext/preferences, successful matching ciphertext/verifier/salt, and canceled encryption changes creating no replacement key.
- URI/JSON numeric and size bounds; late-result cancellation/revocation and unexpected task exceptions.

The caller is the test-only `security-test-client` module. It generates an independent local test signing key under its excluded build directory and is installed before connected tests. **Do not distribute it or run destructive tests on real account installations.** See [build/test instructions](build-and-security-tests.md).

Local validation still cannot run the Gradle/JUnit/Android gates in this sandbox: no complete pinned JDK/SDK or dependency resolution is available. The Kotlin sandbox and a compile-only smoke check with hand-written Android/JUnit stand-ins are not validation against the Gradle plugin, real Android APIs or a device; the Java interoperability suites have not been compiled here. Both-flavor builds, lint, JVM unit tests, packaged manifest/resource inspection, API 28/36 emulator runs, and actual reproducibility comparisons still need a suitable environment/CI. OEM restore behavior, provider failure injection and UI/process-death/rotation flows need further device testing.

This work does not defend against a rooted device, compromised signing key, malicious user-selected OpenPGP/storage provider, privileged accessibility tooling, clipboard/token sharing chosen by the user, or arbitrary same-signer code. The production app must continue to have no `INTERNET` permission; external providers can have their own network access. Reviewed dependency locks/checksums and an archived/digest-pinned release toolchain remain separate supply-chain follow-up work.
