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

## 7. Blocker status after this phase

| Blocker | Status here |
| --- | --- |
| B1 signed APK/AAB | **BLOCKED** — no toolchain, no production keystore (correctly, none here); no artifact exists |
| B2 Android compile + unit/lint/instrumented tests | **BLOCKED** — 563 + 28 Kotlin tests and 13 Java test files remain unexecuted |
| B6 Minecraft/Fabric runtime | **BLOCKED** — separate Gradle build, Loom/Mojang hosts unreachable, no server |
| Device/emulator tests | **BLOCKED** — no `/dev/kvm`, no `adb`, no device |
| Repository static gates incl. the release-config checker | **PASS** (four checkers, `npm test` 476/476) |

CraftMind's Android release path is therefore **unverified, not verified-and-green**. It stays that way until step 1 of
§4 runs on a provisioned machine; §4 exists so that run is a sequence of exact commands rather than a guess.
