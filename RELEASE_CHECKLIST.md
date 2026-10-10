# CraftMind 1.0.0 release checklist

**Status: prepared, not released.** Version metadata, release signing hooks, notes, and a static website are in source, plus the plumbing a phone-only owner needs to build and ship them: `netlify.toml` for hosting, `android-apk-workflow.yml.example` and `ci-workflow.yml.example` for cloud execution, and a download page that enables itself only from a real published release asset. **No signed APK, debug APK, successful Android/Fabric build, runtime test, CI run, or website deployment exists yet** — nothing in the paragraph above has been executed, because this repository has no JDK/Android SDK, no Netlify or cloud credential, and no permission to install a workflow (all three measured again in the final phase and recorded in `README.md` and `docs/android-release-build.md`). Leave this list open until each item is completed with recorded evidence.

## Before publishing

- [ ] Build from a clean checkout with JDK 17 and Android SDK 35: run `./gradlew test lint assembleDebug`, `./gradlew connectedDebugAndroidTest` on a device/emulator, then `cd minecraft-bridge && ../gradlew test build`. Without a PC: install `android-apk-workflow.yml.example` as `.github/workflows/android-apk.yml` and dispatch it — it runs `:app:testDebugUnitTest` and `:bridge-protocol:test` before it builds anything, then verifies the debug APK's size, SHA-256 and badging and uploads it for 30 days. That route yields a **debug** artifact for testing on a device you own; it is not this item's release build and never becomes a Release asset.
- [ ] Inspect the R8/minified release build and verify that bridge protocol Gson serialization, provider flows, plan history, image/video limits, and bridge failure states work on supported Android versions.
- [ ] Create a dedicated release keystore outside the repository. Back it up securely; never commit the keystore, passwords, or a local signing-properties file.
- [ ] Supply `CRAFTMIND_RELEASE_STORE_FILE`, `CRAFTMIND_RELEASE_STORE_PASSWORD`, `CRAFTMIND_RELEASE_KEY_ALIAS`, and `CRAFTMIND_RELEASE_KEY_PASSWORD` through a short-lived local shell or a trusted CI secret store. The Gradle build rejects partial configuration and a keystore inside the checkout.
- [ ] Run `./gradlew :app:assembleRelease`, then `scripts/verify-release-apk.sh app/build/outputs/apk/release/app-release.apk`; archive the APK SHA-256 and signer certificate fingerprint outside Git.
- [ ] Install the signed APK on a clean API 26+ device and a current Android device. Exercise first launch, no-key/no-model state, live Gemini verification, text and supported image/video flows, local history, and the explicit no-bridge state.
- [ ] On a disposable supported Minecraft 1.20.1 Fabric server, verify pairing, preflight, separate confirmation, bridge-reported execution status, cancellation/error paths, and that unsupported server conditions fail closed. Do not test on a valuable world.
- [ ] Publish a GitHub `v1.0.0` release only after the signed APK and checksums are verified. Attach the APK, SHA-256 file, release notes, and compatibility/verification status; do not commit the APK or publish an unsigned artifact.
- [ ] Scan the release commit with the Security Guardian from a clean checkout (`cd backend && node --no-warnings=ExperimentalWarning scripts/security-guardian.mjs --fail-on HIGH --format json --output <release-record>/security-scan.json`), read every open finding at MEDIUM or above, and confirm `status` is not `INCOMPLETE` and `staleSuppressions` is empty before recording the pass. Keep the report with the release record, not in `website/`: it lists internal paths and line numbers even though credentials are masked. This covers 20 source patterns only — it is not dependency scanning, penetration testing, or a security sign-off.
- [ ] Deploy the website to Netlify per `docs/production-operations.md` ("Netlify and the APK download path"): import the repository, accept `netlify.toml`'s empty build command and `website` publish directory, force HTTPS, then open the printed URL on the phone and read the pages by hand — that manual pass is currently the only browser verification the project has (blocker B3). No public URL is claimed here, and `website/github-pages-workflow.yml.example` remains an uninstalled alternative.

## External signing setup (local example)

The Android Gradle configuration reads signing values only from the environment. No signing secret belongs in this repository or in `local.properties`.

With JDK 17 installed, create a dedicated keystore in a protected directory outside the checkout. `keytool` prompts for passwords; do not place passwords on the command line:

```bash
mkdir -p "$HOME/.android"
umask 077
keytool -genkeypair -v \
  -keystore "$HOME/.android/craftmind-release.jks" \
  -storetype JKS \
  -alias craftmind-release \
  -keyalg RSA -keysize 3072 -validity 10000
```

For a one-off local build, load each password without echoing it, export the values only in the current shell, and then run the release task:

```bash
export CRAFTMIND_RELEASE_STORE_FILE="$HOME/.android/craftmind-release.jks"
export CRAFTMIND_RELEASE_KEY_ALIAS="craftmind-release"
read -r -s -p "Keystore password: " CRAFTMIND_RELEASE_STORE_PASSWORD; printf '\n'
export CRAFTMIND_RELEASE_STORE_PASSWORD
read -r -s -p "Key password: " CRAFTMIND_RELEASE_KEY_PASSWORD; printf '\n'
export CRAFTMIND_RELEASE_KEY_PASSWORD
./gradlew :app:assembleRelease
```

The environment variables are `CRAFTMIND_RELEASE_STORE_FILE`, `CRAFTMIND_RELEASE_STORE_PASSWORD`, `CRAFTMIND_RELEASE_KEY_ALIAS`, and `CRAFTMIND_RELEASE_KEY_PASSWORD`. Store only the keystore and credentials in a managed secret store or protected external backup. Do not paste them into chat, source, command-line arguments, logs, or a tracked file. Clear the shell variables after use. Configure the next release with a strictly higher Android `versionCode`; `10000` is the configured code for version `1.0.0`.

The APK inspection helper expects Android SDK Build Tools (`aapt`, `apksigner`) and `sha256sum` on `PATH`. It checks the package/version, verifies an APK signature, prints the public signer-certificate digest, and calculates the artifact hash; it does not establish runtime compatibility or a security audit.

## Also open, and owned by the person with the accounts

- [ ] Install `ci-workflow.yml.example` as `.github/workflows/ci.yml`, then require its checks on `main` via branch protection. Until a run exists, CI is neither green nor red for this project: it does not exist.
- [ ] Install `android-apk-workflow.yml.example` as `.github/workflows/android-apk.yml` and record the first run's outcome — artifact link, commit SHA, variant, and SHA-256 — in the release record. A red first run is a result too, and reporting it is better than deleting the step that caught it.
- [ ] Publish a GitHub Release carrying the verified **signed** APK. `website/download.html` needs no edit: it resolves the newest published release at page load and enables the download control only if that release actually carries an `.apk` asset belonging to this repository, so removing or renaming the release takes the button back to "not available" on the next visit.
- [ ] Decide the license and copyright holder (see `docs/release-readiness.md`): the repository carries no `LICENSE`, and "no legal entity" on the About page is a statement, not a rights holder. Nothing in the codebase may choose this on the owner's behalf.
