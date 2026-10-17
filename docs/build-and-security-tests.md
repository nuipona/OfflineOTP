# Reproducible builds and security regression tests

## What is enforced

- Gradle **8.13**, with the official wrapper JAR/scripts and the published **all-distribution SHA-256**. `scripts/check_build_inputs.py` detects wrapper substitution or a missing/changed checksum. The independent GitHub wrapper-validation action also runs.
- CI uses Temurin **17.0.14+7**, Android command-line tools **12266719**, platform **android-36**, build tools **36.0.0**, Ubuntu **24.04**, UTC and UTF-8. The app's Java bytecode target remains 8; minimum Android remains API 28 and target remains API 35.
- The narrow Kotlin parser pilot pins Kotlin Gradle plugin/stdlib **2.4.21** and aligns Kotlin/Java bytecode at **1.8**. Compiler/build-tools/plugin classpaths join the dependency inventory; actual Kotlin/Android compilation remains a required gate.
- GitHub Actions are pinned to full commit SHAs, have read-only repository permissions and do not retain checkout credentials. No signing credentials are needed.
- Dynamic/changing Gradle dependencies are rejected. JitPack is exclusive to the two existing JitPack groups; it cannot supply arbitrary Maven Central/Google coordinates.
- Both flavors get host unit tests, a focused fatal lint gate, debug builds, unsigned release builds, and packaged-manifest inspection.
- The reproducibility script builds from **two distinct source directories and Gradle user homes**, without build/configuration caches, and compares both unsigned APKs byte for byte. It also compares resolved dependency graphs and external artifact SHA-256s, including the root buildscript, both flavors' compile/runtime and debug unit-test/androidTest classpaths, the security client, and Kotlin tooling (including test compiler-plugin classpaths). A different graph or artifact byte sequence fails even if the APKs happen to match.
- Instrumentation runs on disposable API **28 and 36** emulators, for **both** flavors, including the existing RFC OTP/AES-GCM tests and the new adversarial crypto/restore tests.

**Scope:** a successful workflow demonstrates repeatability of the unsigned APKs for the source/tooling inputs of that run. It does not by itself prove supply-chain authenticity, cross-OS reproducibility, identical signed APKs, or safety of every application feature. SDK platform/system-image packages and hosted runners can receive revisions; their names are selected explicitly but their revisions are not frozen by sdkmanager. Release reproducibility across time needs the reviewed dependency baseline below plus an archived toolchain/SDK snapshot or a digest-pinned build image.

## Local prerequisites

Install Temurin 17.0.14+7 and the Android command-line tools. Set `JAVA_HOME`, `ANDROID_HOME`, and put `java`, `sdkmanager`, `apkanalyzer` and `adb` on PATH. Accept SDK licenses interactively, then install:

```sh
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
```

The build needs access to Gradle distributions, Google Maven, Maven Central, JitPack and the Android SDK repositories. Python policy tooling needs Python 3.11+ and uses only its standard library. The workflow does not use local Python virtual environments or require `cryptography`.

## Run the checks

From the repository root:

```sh
python3 scripts/check_build_inputs.py
python3 -m unittest discover -s scripts/tests -v

# Provision the wrapper, then test inventory artifact selection without Android or Maven downloads.
./gradlew --no-daemon --version
GRADLE_EXECUTABLE="$PWD/gradlew" python3 scripts/tests/test_dependency_inventory.py -v

./gradlew --no-daemon \
  :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest \
  :app:lintFdroidRelease :app:lintPlayRelease \
  :app:assembleFdroidDebug :app:assemblePlayDebug

bash scripts/verify_reproducible_build.sh
```

Optional, **not** part of CI and not an acceptance gate: where only a JDK and a Kotlin compiler are
available (no Android SDK, no dependency graph), `bash scripts/kotlin-sandbox/bootstrap-toolchain.sh`
fetches both from package mirrors (SHA-256 pinned, unpacked outside the repository) and
`bash scripts/kotlin-sandbox/run.sh` compiles the converted platform-free/low-platform Kotlin sources
against hand-written platform stubs and runs behavior/API-shape checks on a desktop JVM. Read
[its README](../scripts/kotlin-sandbox/README.md) first: a green run there is not the Gradle, lint,
packaging or device result, and the stub Android surface is not the platform. For the vault
repository it executes only the outcome mapping, the document boundary (including a stored-document
round trip over real entries), the poisoned-transaction short-circuits and the live metadata-callback
recovery/migration guard (flags set by reflection); the file transaction,
promotion and backup/wipe paths stay with `VaultFailureSecurityTest` and
`VaultRestoreOperationTest` below.

With a disposable API 28 or 36 emulator already running:

```sh
./gradlew --no-daemon :security-test-client:installDebug
./gradlew --no-daemon :app:connectedFdroidDebugAndroidTest
./gradlew --no-daemon :app:connectedPlayDebugAndroidTest
```

**Never run instrumentation against an installation containing real accounts.** Database/KeyStore, IPC and credential lifecycle tests deliberately wipe the disposable target vault, preferences and KeyStore aliases. CI uses fresh debug-suffixed applications and disposable emulators. Restore-format tests separately use private cache fixture files.

The test-only client is a Kotlin Activity signed with an independently generated key, not the target debug key; the language conversion does not change its application ID, UID or signing identity. The IPC test verifies different UIDs/certificates and denial of `CONTROL_VAULT` before launching that caller. Debug builds alone declare package visibility for this client; it is not a release component. If you delete its generated key and rebuild on an existing emulator, uninstall the old **test client** before installing the newly signed one. Never distribute this client.

Reports live under `app/build/reports/` and `reports/reproducibility/`, and are uploaded on CI failure as well as success. Each independent build preserves unsigned APKs, text manifests, build logs, dependency inventory and SHA256SUMS. A `result.txt` success marker is written only after all comparisons pass. None of these generated reports/APKs should be committed.

## Dependency inventory coverage

`writeDependencyInventory` records a sorted `configuration` marker, the **complete resolved graph**
(including local project edges), and SHA-256s of the published external artifacts for each covered
configuration. It does not hash local project build outputs: the unsigned APK comparison covers the
packaged app, and source is copied into each independent build.

Android test configurations depend on the tested `:app` component, which exposes several artifact
variants (classes, resources, metadata, etc.). The graph resolves correctly; requesting all artifacts
without an artifact type is ambiguous. The inventory uses a strict `incoming.artifactView` with a
`ModuleComponentIdentifier` filter **before artifact selection** to hash external dependencies
without asking Gradle to choose or build a local app artifact. It does not remove project dependencies
from the graph, pick an arbitrary app variant, or use lenient resolution. Missing test configurations,
unresolved edges and missing external artifacts remain fatal, with the configuration name in the error.
Test-specific Kotlin compiler/build-tools/plugin configurations are no longer excluded.

The offline Gradle fixture in `scripts/tests/test_dependency_inventory.py` first reproduces the
unfiltered artifact ambiguity, then checks test dependency hashes/project edges, test tooling coverage,
sorted/path-independent reports, and failure on missing test inputs. Python-only runs skip its two
Gradle tests explicitly; the CI build job runs them with the pinned wrapper. The real Android inventory
is still checked by both independent release builds.

## Establish the reviewed dependency baseline

**Not yet generated in this sandbox:** lockfiles and full dependency verification metadata require successful resolution from Google Maven/Maven Central/JitPack. Do not fabricate checksums or accept generated trust metadata automatically.

Dependency locking is enabled in Gradle's default mode so the first resolution can bootstrap. When reviewed lockfiles are committed, normal builds use them; normal CI does **not** pass `--write-locks` or regenerate verification metadata. This initial state is not a completely frozen transitive dependency graph.

Use the manual **Generate dependency baseline for review** workflow on this branch, or run the same command in a clean, trusted environment:

```sh
./gradlew --no-daemon --no-build-cache --write-locks \
  --write-verification-metadata sha256 \
  :app:assembleFdroidRelease :app:assemblePlayRelease \
  :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest \
  :app:assembleFdroidDebugAndroidTest :app:assemblePlayDebugAndroidTest \
  :app:lintFdroidRelease :app:lintPlayRelease writeDependencyInventory
```

Review the generated `gradle.lockfile`, `buildscript-gradle.lockfile`, `app/gradle.lockfile` (and any app buildscript lockfile) and `gradle/verification-metadata.xml` before committing them. Compare provenance/checksums with trusted upstream releases or an independent trusted resolver. JitPack tags can be mutable: review the source commit as well as the downloaded binary. Generation is trust-on-first-use, not an independent authenticity check. Do not add broad trusted-artifact exemptions to make verification pass.

After committing the baseline, rerun all CI checks in clean environments. Consider strict lock mode once every used configuration is captured; do not enable it with missing lockfiles. Archive the SDK packages/revisions and the exact toolchain used for release. The artifact inventory is evidence and a drift detector, not a substitute for reviewed lockfiles/verification metadata.

## Security coverage

### Host-side JVM tests (Kotlin)

`BoundedInputReaderTest`:

- Empty/exact-limit documents accepted; limit-plus-one rejected.
- Null streams and invalid limits rejected.
- An unending provider is stopped after at most limit + 1 consumed bytes.
- Short reads, zero-byte reads, read failures and stream ownership handled.

`EncryptedBackupHeaderTest`:

- Big-endian current framing and valid historical/current iteration counts preserved.
- Null input and every frame shorter than the mandatory header/nonce/tag rejected.
- Negative, zero and excessive iteration counts rejected **before** key derivation.
- Oversized backups rejected; returned arrays do not alias provider-supplied input.

`VaultSessionTest` covers key/generation revocation, one-use non-aliasing handoffs and listener removal. `InputPolicyTest` covers image arithmetic, OTP bounds, JSON nesting and bounded provider output.

`OtpUriParserTest` exercises the production Kotlin URI parser, explicit failure categories, immutable validated metadata/owned seeds, strict decoding, all supported variants and a deterministic malformed corpus without Android mocks. `KotlinOtpInteropTest` checks the generated API and Java-visible surface from Kotlin, Entry round trips, Android decoding and an RFC leading-zero vector. See [Kotlin OTP parser pilot](kotlin-otp-parser-pilot.md) for compatibility changes and ownership rules.

These exercise production helpers without Android mocks or Robolectric. The helpers are used by actual document reads and restore tasks, rather than being test-only copies.

### Android/provider tests

The converted `ApplicationTest.kt` keeps the RFC 6238/4226 OTP vectors, entry parsing/URI checks, the temporary-database settings test and the AES-GCM provider vectors. `EntryJsonTest.kt` characterizes the stored document boundary on Android: field order/defaults, malformed-field errors, seed ownership, adapter round trips, URI parameters, token calculators, manual-entry validation and the mOTP PIN round trip with its older-document default.

`EncryptionSecurityTest.kt` verifies empty/Unicode round trips, wrong-key rejection, tampered nonce/ciphertext/tag rejection, short-frame rejection, and fresh nonces. `KotlinCryptoInteropTest.kt` exercises the converted Kotlin crypto/key-store helpers on a real device: a dedicated `AndroidKeyStore` alias create/load/wipe, the RSA wrap/unwrap and wrapped-key file round trip, the AES-GCM framing round trip with wrong-key and tag-tamper rejection, the explicit-nonce overload and the derivation bounds, plus the `EncryptionChangeCallback` SAM from Kotlin. The synthetic alias and key file live in the app's private directory and are deleted afterwards, but the test also drives the production key entry points and `wipeKeys`, so it clears the app's stored vault key and keystore aliases: run it only on disposable emulators. It never opens the vault.

`EncryptedRestoreSecurityTest.kt` calls the real restore task on temporary file URIs and verifies independently generated current/legacy encrypted fixtures, Unicode preservation, wrong-password/tamper failure with no plaintext payload, truncated framing, and hostile KDF counts. The timeout is a supplementary regression signal; validation is the actual resource defense.

`KotlinPasswordDialogsInteropTest` drives the converted password dialog through real inflated widgets
(both modes, privacy flags, watchers, IME action, callback and dismissal) and reflects the
static tag-dialog boundary without opening an unowned window. `KotlinPasswordEncryptedPreferenceInteropTest`
records preference writes while exercising first attachment with no XML default, a real AndroidKeyStore
encrypt/decrypt round trip, restored layout text, PASSWORD/PIN input classes and matching-field state.
These still require the API 28/36 instrumentation matrix; the desktop sandbox uses recording widgets
and cannot validate real window, IME, Material or keystore behavior.

`KotlinBackupComponentsInteropTest` drives the converted backup components on a real device: the
encrypted backup broadcast receiver with a keystore-backed password queues the WorkManager job
under its unique name, a disabled broadcast and a broadcast for another action queue nothing, and
the queued work really runs the worker and reaches a terminal state. It stores a synthetic
password in the app's `AndroidKeyStore` and clears the vault and the keystore afterwards, so run
it only on disposable emulators. The desktop sandbox can only check the receiver's action gate and
the worker's early exits; the failure notifications need the notification channels and the happy
path needs the keystore.

`KotlinSmallActivitiesInteropTest` drives the converted small activities on a real device: the
panic responder is started for real with the PanicKit action, so the destructive path is observed
on the platform — a foreign action wipes nothing, the "accounts" response deletes the vault file,
disables the automatic backups and leaves no pending encrypted backup, and the "settings" response
clears the settings. The QR scanner activity is only checked for its shape (it still extends the
scanner library's activity, its content hook is still protected and declared in the converted
class, and the manifest still declares it as not exported), because starting it needs a camera the
emulators do not have. The test wipes the vault of the device it runs on, so run it only on
disposable emulators. The desktop sandbox executes the panic decision and the window handling
against stand-ins, but it cannot start an activity, cannot apply a real theme and has no camera.

`KotlinAboutScreensInteropTest` drives the converted about screens on a real device: the about
activity is started for real, so the real layouts resolve their rows and image tint, the real
version name is bound and both pager pages are built (the flavor about tab and the AboutLibraries
list). **Theme decision:** keep AboutLibraries 14.2.1 and the app-wide
`Theme.MaterialComponents.Bridge` parent, which supplies Material attributes while retaining
AppCompat widget defaults and the Dark Forest palette. All light/dark/black and NoActionBar hooks,
including the API-specific overrides, inherit it. The test inflates the dependency's actual
`listitem_opensource` (`MaterialCardView`) under all six hooks and waits for a real card in the
asynchronously populated Libraries tab; merely creating a pager is not enough to catch this crash.
The theme migration also retains the old dimension names used by the About/entry/drawer layouts;
`tile_letter_font_size_scale` is a float (`0.67`), not the invalid float text `67%`.
Java construction, subclassing and the public static state of the shared fragment are
checked directly, and the clipboard fallback of `copyToClipboard`/`openURI` is exercised with a
scheme no installed app handles, so nothing is launched. The seven-tap easter egg stays with the
desktop sandbox checks and the Python guards; driving seven timed taps through a real window would
race the emulator. The test replaces the clipboard content and finishes the screen afterwards; it
does not touch the vault. The desktop sandbox executes the row wiring, the easter egg and the page
building against stand-ins, but it has no real window, package manager or clipboard.

The new guards impose a **16 MiB document limit** and a **1,000,000-iteration backup-read ceiling**. The current writer's 140,000–160,000 range is unchanged; valid historical counts below that range remain readable. Backups above either bound now fail closed. These are explicit compatibility/security policy limits; review them rather than silently increasing them after a failing test. Decoded QR images now have dimension/pixel bounds checked before pixel-buffer allocation; account/URI/JSON limits are documented in the hardening notes.

Fixtures are synthetic, contain no user accounts, and were generated with Python `cryptography==44.0.3`, not the app's crypto implementation. Fixed salt/nonces are intentionally confined to fixtures. Optional regeneration:

```sh
python3 -m venv .venv
.venv/bin/pip install 'cryptography==44.0.3'
.venv/bin/python scripts/generate_backup_fixtures.py
```

The committed fixture hashes are also checked by the Python suite. Do not regenerate fixture expectations merely to accommodate a broken reader.

### Packaged manifest policy

For each unsigned release APK:

```sh
apkanalyzer manifest print path/to/app-release-unsigned.apk > manifest.xml
python3 scripts/check_manifest.py manifest.xml
```

The checker inspects the **packaged** manifest, so transitive library permissions/components are included. It rejects:

- `INTERNET` and all unreviewed permissions, including SDK-limited permission declarations.
- Unreviewed exported activities, aliases, receivers, services and providers; implicit exported defaults are handled.
- Removal/change of required caller permissions on reviewed system-only components.
- Changed exported actions/URI data filters, unresolved exported resource values, changed application ID/minimum/target SDK, and debug/test/explicit-cleartext release flags.
- Weakened app-owned dynamic-receiver or `CONTROL_VAULT` signature permission, or removal of caller permission from panic/backup IPC.
- Reenabled platform backup, a manifest backup agent, or removal/change of exclusion resource references. Source-resource tests separately enforce all-storage exclusions for full backup, cloud backup and device transfer; the manifest checker does not decode compiled resource contents.

The stdlib Python suite mutates synthetic manifests to prove that these checks fail closed. The allowlist in `scripts/manifest-policy.json` is intentionally small. Unexpected components must trigger review, not an automatic baseline rewrite.

### Vault and independently signed IPC regression tests

`VaultFailureSecurityTest`, `CredentialLifecycleSecurityTest`, `EntryInputSecurityTest`, `EntryJsonTest`, `BackgroundTaskCancellationSecurityTest` and `IpcAuthorizationSecurityTest` exercise strict vault failures, crash-recovery markers, credentials/ciphertext consistency, malformed inputs, the stored entry-document format with its owned one-use seed, cancellation and a different-UID/different-certificate attacker. The IPC assertion checks denied panic, unchanged vault/settings and no backup work queued with automation enabled. These are Android tests prepared for CI, **not locally executed results**.

`ApplicationTest.testSettingsHelper` deliberately constructs `Settings` inside the initialization
commit callback. This used to run startup recovery under a reentrant lock, delete the not-yet-committed
journal, and then report success without creating a vault. Live callbacks now defer recovery/migration;
the guard is cleared in `finally`, and acknowledged commits must promote their own journal UUID.
`VaultFailureSecurityTest` additionally covers construction before/after the marker commit, nonempty
key rotation and its backup, cancellation/exception cleanup, and missing/replaced journals that must
report `RecoveryRequired(COMMITTED)`, never `Saved`. The missing-vault and failed-authentication
write refusals remain intact.

See [Vault security hardening](vault-security-hardening.md) for the intentional IPC/platform-backup compatibility breaks and implementation details. Further runtime review is needed for UI/rotation/process death, authorized same-signer integrations, OEM restore behavior and storage-provider failure injection. The manifest gate checks caller-permission configuration; only execution of the separately signed client exercises the Android authorization boundary.

## Lint policy

This change enables a focused error/warning gate for `NewApi`, `InlinedApi`, `TrustAllX509TrustManager`, `BadHostnameVerifier`, and `SslCertificateSocketFactoryCreateInsecure`. It does not claim a clean full-project lint baseline. Expand coverage after reviewing existing findings; do not generate a blanket baseline that hides security errors.

## Validation performed here

The wrapper hashes/checksum/action pins, Python regression suite, shell syntax, fixture regeneration and workflow YAML structure can be checked here. The Kotlin 2.4.21 toolchain was provisioned outside the repository and the non-authoritative desktop sandbox compiled the converted app sources and passed its 93 checks. A local test-source probe with hand-written Android/JUnit stand-ins is not a Gradle result, and no JUnit test was executed. Android/Gradle execution cannot run here: the wrapper download fails with `SSLHandshakeException` from `services.gradle.org`, and no Android SDK is installed. The pinned full JDK and Google dependencies are unavailable. No successful project Gradle Java/Kotlin compilation, APK build, byte-identical APK comparison, host JUnit execution, or emulator test is claimed until CI runs.
