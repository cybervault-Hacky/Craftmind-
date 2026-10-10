# Android release build and artifact verification — environment readiness

**Phase 37B, measured 2026-10-10 on `e40936b`.** Verdict: **BLOCKED — no Android or JVM toolchain exists in this
environment and none can be installed here.** No build was run, no artifact was produced, no signing key was created,
and static inspection was not passed off as a compile. Everything below is either a measurement from this machine or a
value read out of the repository's own build files; no version was inferred, upgraded, or invented to make anything
appear possible.

## 1. What was executed here

| Command / probe | Result |
| --- | --- |
| `./gradlew --version` and `./gradlew :app:testDebugUnitTest` | **FAIL — BLOCKED at the first step**: `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.` (the wrapper script's own message) |
| PATH probe for `java javac keytool jarsigner gradle sdkmanager avdmanager emulator adb apksigner zipalign aapt2 d8 kotlinc` | **all MISSING**; `/usr/lib/jvm` absent; no `~/.gradle`; `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_USER_HOME`, `GRADLE_USER_HOME` all unset |
| `find / -maxdepth 4 -name libjvm.so -o -name java -type f` | nothing — there is no JVM anywhere on this machine |
| `ls -l /dev/kvm` | `No such file or directory` → an emulator is impossible even in principle, and no device is attached |
| Can a JDK be installed? | **No.** `id -u` = 1001 (not root), `/var/lib/dpkg` is not writable, `apt-get -s install openjdk-17-jdk-headless` → `E: Unable to locate package`, and `apt-get update` cannot write `/var/lib/apt/lists` |
| Egress to the repositories this build needs | **blocked at TLS**: `services.gradle.org`, `repo1.maven.org`, `dl.google.com`, `maven.fabricmc.net`, `libraries.minecraft.net`, `deb.debian.org` all return `SSL_ERROR_SYSCALL` / HTTP `000`, while `github.com`, `api.github.com`, `registry.npmjs.org` succeed — the sandbox allowlist is exactly as documented |
| `bash scripts/verify-release-apk.sh` (no artifact) | exit **1**, `APK not found: app/build/outputs/apk/release/app-release.apk` ✓ fails closed |
| `bash scripts/verify-release-apk.sh /tmp/…/fake-release.apk` (bogus file) | exit **1**, `Required tool not found on PATH: aapt` ✓ it does not "pass" a non-APK |
| `bash -n scripts/verify-release-apk.sh` | clean |
| `python3 scripts/check_release_config.py` / `check_api_contracts.py` / `check_website.py` / `check_deployment_readiness.py` | **PASS / PASS / PASS / PASS** — these are the only release gates this machine can genuinely run, and they are static checks by their own admission |
| `find` for `*.apk *.aab *.jks *.keystore *signing*.properties`; `ls -d app/build bridge-protocol/build minecraft-bridge/build` | none exist — **the project has never been built in this checkout** |

**Consequence:** B1 (signed artifact), B2 (compile + 563 unit / 28 instrumented Kotlin tests, 4 + 9 bridge test
files), and B6 (real Minecraft runtime) stay open. They cannot be advanced from this environment by any means that
would count as honest verification.

## 2. Toolchain the project actually requires — read from its own files

| Requirement | Value | Declared in |
| --- | --- | --- |
| Gradle | **8.11.1**, wrapper-pinned (`distributionUrl`), `validateDistributionUrl=true` | `gradle/wrapper/gradle-wrapper.properties` |
| JDK / bytecode | **Java 17** (`sourceCompatibility`/`targetCompatibility` `VERSION_17`, `jvmTarget = "17"`, bridge-protocol toolchain `JavaLanguageVersion.of(17)`) | `app/build.gradle.kts`, `bridge-protocol/build.gradle.kts` |
| Android Gradle Plugin | **8.9.1** | `gradle/libs.versions.toml` (`agp`) |
| Kotlin | **2.1.10**; Compose BOM **2025.02.00** | `gradle/libs.versions.toml` |
| SDK levels | `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26` | `app/build.gradle.kts` |
| Release identity | `applicationId`/`namespace` `com.craftmind.app`, `versionCode = 10000`, `versionName = "1.0.0"` | `app/build.gradle.kts` (asserted by `check_release_config.py` and pinned in `download.html` by `check_api_contracts.py`) |
| Modules | root build = `:app`, `:bridge-protocol`; **`minecraft-bridge` is a separate Gradle build** (`minecraft-bridge/settings.gradle`) | `settings.gradle.kts`, `minecraft-bridge/settings.gradle` |
| Fabric build | loom **1.9.2**, `com.mojang:minecraft:1.20.1`, yarn `1.20.1+build.10`, loader **0.16.10**, fabric-api **0.92.2+1.20.1**, mod version `1.2.0` | `minecraft-bridge/build.gradle` |
| Maven repositories needed | `google()`, `mavenCentral()`, `gradlePluginPortal()`; Fabric adds `maven.fabricmc.net` + Mojang libraries | `settings.gradle.kts`, `minecraft-bridge/settings.gradle` |
| Instrumented-test setup | `AndroidJUnitRunner`, Espresso 3.6.1, Compose UI test; needs a device or emulator | `app/build.gradle.kts`, `app/src/androidTest/**` (10 files / 28 `@Test`) |

## 3. Fail-closed properties in the signing path (inspected; behaviourally untested here)

`app/build.gradle.kts` refuses, in this order: **partial** signing input (`GradleException: "Release signing is
incomplete…"`) → **missing** keystore file (`"must point to an existing release keystore outside the repository."`) →
keystore **inside** the checkout (`"The release keystore must be stored outside the repository checkout."`) → and,
in `tasks.configureEach`, an unsigned release artifact: running `packageRelease` or `signReleaseBundle` without the
four variables throws `"A release artifact must be signed…"` rather than emitting an unsigned APK. Values are read only
through `providers.environmentVariable(...)`, never from a file in the tree. `.gitignore` excludes `*.jks`,
`*.keystore`, `*.pem`, `*.apk`, `*.aab`, `keystore.properties` and `.env*`. `scripts/verify-release-apk.sh` requires
`aapt`, `apksigner` and `sha256sum`, asserts the exact badging string
`package: name='com.craftmind.app' versionCode='10000' versionName='1.0.0'`, then prints the certificate and the
artifact's SHA-256 — it does not print key material.

Because no Gradle could run, these are **verified by reading and by the static checker, not by execution**. The
negative cases in §4 are the owner's first task, precisely so that the guards become measured facts rather than code
review.

## 4. Owner-side sequence, in the order that fails fastest

Run from the **repository root** — there is no `android/` directory, and `./gradlew` lives at the root.

```bash
# 0. Prerequisites: JDK 17 on PATH or JAVA_HOME; Android SDK with platforms;android-35 and the
#    build-tools AGP 8.9.1 selects; ANDROID_HOME set; licences accepted; network to dl.google.com,
#    repo1.maven.org, plugins.gradle.org, maven.fabricmc.net.
./gradlew --version                                  # must report Gradle 8.11.1

# 1. Pure-JVM first: this module needs no Android SDK, so it isolates toolchain problems.
./gradlew :bridge-protocol:test                       # 4 test files
./gradlew :app:testDebugUnitTest                       # 64 test files / 563 @Test
./gradlew :app:lintDebug                               # abortOnError is AGP's default (true)
./gradlew test lint assembleDebug                      # the RELEASE_CHECKLIST item-1 sequence, from root
cd minecraft-bridge && ../gradlew test build           # separate Fabric build (loom 1.9.2)

# 2. Prove the signing guards fire, before any real key exists. These use no key material:
./gradlew :app:assembleRelease                         # expect: "A release artifact must be signed…"
CRAFTMIND_RELEASE_STORE_FILE=/tmp/absent.jks ./gradlew :app:assembleRelease
                                                       # expect: "Release signing is incomplete…"
# and, with all four names set but the store file pointing inside the checkout (an empty placeholder file is enough,
# the guard is path-based and runs before anything is read): expect "must be stored outside the repository checkout."

# 3. Real release build — only with the owner's production keystore held outside the repository.
#    Supply the passwords in the shell session only (a secret manager or `read -rs`), never in a committed file,
#    a Gradle properties file, or a logged command line.
export CRAFTMIND_RELEASE_STORE_FILE=/secure/outside/checkout/craftmind-release.jks
read -rs CRAFTMIND_RELEASE_STORE_PASSWORD && export CRAFTMIND_RELEASE_STORE_PASSWORD
read -rs CRAFTMIND_RELEASE_KEY_PASSWORD && export CRAFTMIND_RELEASE_KEY_PASSWORD
export CRAFTMIND_RELEASE_KEY_ALIAS=<owner-provided alias>
./gradlew :app:assembleRelease      # -> app/build/outputs/apk/release/app-release.apk
./gradlew :app:bundleRelease        # -> app/build/outputs/bundle/release/app-release.aab
scripts/verify-release-apk.sh app/build/outputs/apk/release/app-release.apk
python3 scripts/check_release_config.py
python3 scripts/check_api_contracts.py

# 4. Device tests (needs a real device or a KVM-capable emulator; impossible here — no /dev/kvm, no adb)
./gradlew :app:connectedDebugAndroidTest              # 10 files / 28 @Test
```

Two honest caveats for whoever runs step 1: `AccountHttpIntegrationTest` (1 test) and two tests in
`CertificationSecurityRegressionTest` are gated by JUnit **assumptions**, so they report as *skipped*, not passed,
unless `CRAFTMIND_LOCAL_AUTH_INTEGRATION=1` is exported on a host with Node 22.5+ — that flag exists so the suite does
not require Node on an ordinary Android builder, and a green run without it is not proof the round-trip through SQLite
was exercised.

## 5. What must not happen in the name of a green build

Not in scope, and not to be done by a later agent either: creating or committing a keystore or any password;
signing a release with a debug key; disabling lint rules, deleting or `@Ignore`-ing Kotlin tests, or changing
`minSdk`/`targetSdk`/`compileSdk` to make a task pass; adding `distributionSha256Sum`-free third-party binaries to
"help" the sandbox; enabling the website download button or linking any URL before a published, verified artifact
exists; committing the APK/AAB (`.gitignore` excludes them, and the release process publishes them outside Git);
merging Phases 30–37 onto `main`; touching payments, memberships-as-purchase, Trusted Seller, or Live AI Build Mode.

## 6. One recommended hardening, deliberately not applied here

`gradle-wrapper.properties` pins Gradle **8.11.1** by URL but sets no `distributionSha256Sum`, so the wrapper trusts
whatever bytes `services.gradle.org` returns. The fix is a one-line addition of the vendor-published checksum
(`curl -s https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256`), ideally with the wrapper jar
itself compared: the jar in this checkout is 43 583 bytes, sha256
`2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`. Adding a checksum here would have meant
transcribing a value this machine cannot fetch or verify, which is precisely how a supply-chain pin becomes fiction —
so it is recorded as a recommendation with the command, and left for an owner with network access.

## 7. Blocker status after Phase 37B (this phase's own table is at the end of §8)

| Blocker | Status here |
| --- | --- |
| B1 signed APK/AAB | **BLOCKED** — no toolchain, no production keystore (correctly, none here); no artifact exists |
| B2 Android compile + unit/lint/instrumented tests | **BLOCKED** — 563 + 28 Kotlin tests and 13 Java test files remain unexecuted |
| B6 Minecraft/Fabric runtime | **BLOCKED** — separate Gradle build, Loom/Mojang hosts unreachable, no server |
| Device/emulator tests | **BLOCKED** — no `/dev/kvm`, no `adb`, no device |
| Repository static gates incl. the release-config checker | **PASS** (four checkers, `npm test` 476/476) |

CraftMind's Android release path is therefore **unverified, not verified-and-green**. It stays that way until step 1 of
§4 runs on a provisioned machine; §4 exists so that run is a sequence of exact commands rather than a guess.

## 8. The cloud route, for an owner with only a phone (final phase)

**Re-measured here on 2026-10-11, not inherited:** `java -version` and `javac -version` → exit **127** (not found);
`./gradlew --version` → exit **1**, `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH`;
`aapt2`, `apksigner`, `keytool`, `adb`, `kotlinc` absent; no `/dev/kvm`. So §1's verdict stands and **no APK was
produced in this environment by any means**. Every number below is either read out of the repository or waiting for a
run that has not happened.

The owner's constraint — one Android phone, no PC, no Android Studio — changes what "runnable" means, so the Gradle
sequence in §4 now has a cloud front door: **`android-apk-workflow.yml.example`**. It is install-ready and not
installed, for the measured reason recorded in `README.md` (GitHub refuses every workflow write from this
workspace's App token: `git push` → `refusing to allow a GitHub App to create or update workflow .github/workflows/ci.yml
without workflows permission`; `PUT /repos/…/contents/.github/workflows/…` → `403 Resource not accessible by
integration`). Its steps, in order:

| Step | Command on the runner | Why it is there |
| --- | --- | --- |
| Toolchain | `actions/setup-java@v4` with Temurin **17**, `cache: gradle` | `sourceCompatibility`/`jvmTarget`/`toolchain` all say 17; the runner ships the Android SDK, so nothing is fetched from an unverified place |
| SDK floor | `sdkmanager "platforms;android-35" "build-tools;35.0.0"` only if missing | `compileSdk = 35`; a no-op when the image already carries it, a visible failure if the runner cannot provide it |
| Tests first | `./gradlew --no-daemon :app:testDebugUnitTest :bridge-protocol:test` | 563 `@Test` methods (64 Kotlin files) and 14 Java ones run before any artifact exists, so a downloadable file is never built from a red suite |
| Build | `./gradlew --no-daemon :app:assembleDebug` | debug only: no `CRAFTMIND_RELEASE_*` values exist on a runner, and `packageRelease` refuses to run without them |
| Verify | size > 1 MB, `sha256sum`, `aapt2 dump badging` | an APK that exists but is broken, empty, or has the wrong package is a failure, not an upload |
| Publish | `actions/upload-artifact@v4`, retention 30 days | the artifact is for phone testing; the workflow creates no Release and deploys nothing |

Two omissions are deliberate and are not gaps to fill quietly: no `:app:lintDebug` (its findings have never been seen
anywhere, and a first lint run would produce a wall of unread output instead of an APK) and no instrumented tests
(`:app:connectedDebugAndroidTest` needs a device or KVM, which a runner is not).

### Installing and running it, from the phone

1. github.com → `cybervault-Hacky/Craftmind-` → switch to the branch holding the work → open
   `android-apk-workflow.yml.example` → **Raw** → select all → copy.
2. Back in the tree view: **Add file → Create new file**, name it exactly `.github/workflows/android-apk.yml`, paste,
   **Commit changes**. GitHub installs the pipeline from the committed file; the repository has to have Actions
   enabled, which it does by default for a public repository.
3. **Actions** tab → **Build CraftMind Android APK (debug)** → **Run workflow** → choose the branch → run.
4. Read the run. The first run is the experiment §4 has always called for: it either produces an artifact or it
   reports the real AGP/SDK/Kotlin failure in its log. Both outcomes are information; a red first run is not a reason
   to delete a step.
5. On a successful job, scroll to **Artifacts** → `craftmind-debug-apk` → download. GitHub serves artifacts as a ZIP,
   so open it with the **Files** app and extract; the file inside is `app-debug.apk`.
6. Compare its SHA-256 with the value in the job log (`sha256: …`) before installing:
   `sha256sum` is unavailable on a phone, so use an offline checker you already trust, or read the size and name from
   the log and treat the checksum as the release-time control (it is recorded with the artifact, not enforced by
   Android).
7. Tapping the APK starts Android's package installer, which will say the source is unknown and offer **Save to
   device**/**Allow from this source** for the Files app. That prompt is real: allow it only for the app you used to
   open a file you just fetched from your own repository, and turn it off afterwards. Never disable verification
   globally, and never install an APK that arrived from anywhere else.
8. Delete the artifact when the testing is done (Artifacts → delete) if you do not want a debug build sitting on a
   public repository for its retention window; and never attach a debug build to a Release or link it from the site.

### What has to be true before the site's download button points at anything

The site no longer needs an edit (see `docs/production-operations.md`, "Where the APK actually lives"):
`download.html` asks the repository for its newest **published release** and enables the control only if that release
really carries an `.apk`. So the order is fixed: a **signed** release build from §3/§4 (never the debug artifact from
step 5), verified with `scripts/verify-release-apk.sh`, attached to a GitHub Release the owner created deliberately.
Until that exists, the button stays disabled — and that is the correct state, not a placeholder to remove.

### Smoke test for the owner's device

Automated evidence exists for none of this: no test below has been executed on any device, so this list is the
procedure, not a result. Order matters — each step is a place a first cloud build could fail.

1. Installer accepts the APK and completes (a badging/ABI/`minSdk 26` problem shows up here, not later).
2. Launch: the home screen appears without a crash, and navigation reaches every existing section.
3. First-run state with no API key and no model selected: the app must say what is missing and must not invent a
   provider, a plan, or a progress bar.
4. Settings persistence: save a value, kill the app from Recents, reopen — the setting survived and the API key field
   is still not readable in full.
5. Gemini verification: a valid key reports the models it actually returned; an invalid key shows the provider's own
   failure, and nothing is stored as if it worked.
6. One text prompt → BuildPlan: the plan renders with its block counts, and re-opening history shows the same plan
   rather than a regenerated one.
7. One image prompt (a JPEG and a PNG, one deliberately mislabelled file): the declared-vs-actual MIME rejection
   behaves as documented.
8. Bridge pairing, if a LAN server is reachable: pairing over the pinned HTTPS transport, the capabilities screen
   reporting the real server version, and an unsupported version **refused** rather than negotiated down.
9. Errors: airplane mode mid-request, a wrong port, a bridge that is not there. Each must be a readable message, no
   stack trace, and no secret in it.
10. Only after all ten: a small real build on a **disposable** world, with the preflight and the separate
    confirmation both observed. Nothing in this list marks Minecraft integration "verified" — that requires step 10
    to have been watched by a person on a device.

| Blocker | Status after this phase |
| --- | --- |
| B1 signed APK/AAB | **BLOCKED, and correctly so** — needs the owner's signing identity; no artifact exists here or in CI |
| B2 Android compile + 563 unit + 14 bridge-protocol tests | **BLOCKED here; a one-click cloud run exists** — `android-apk-workflow.yml.example` is the shortest route to a first real result, and no result has been recorded yet |
| B6 Minecraft/Fabric runtime (29 `@Test` + a live server) | **BLOCKED** — separate Gradle build, unreachable Maven hosts, no server; still implemented-but-unverified |
| Device/emulator tests (28 instrumented `@Test`) | **BLOCKED** — no `/dev/kvm`, no `adb`, no device; and a cloud runner is not a device |
