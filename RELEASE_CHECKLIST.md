# CraftMind 1.0.0 release checklist

**Status: prepared, not released.** Version metadata, release signing hooks, notes, and a static website are in source. No signed APK, successful Android/Fabric build, runtime test, or website deployment exists yet. Leave this list open until each item is completed with recorded evidence.

## Before publishing

- [ ] Build from a clean checkout with JDK 17 and Android SDK 35: run `./gradlew test lint assembleDebug`, `./gradlew connectedDebugAndroidTest` on a device/emulator, then `cd minecraft-bridge && ../gradlew test build`.
- [ ] Inspect the R8/minified release build and verify that bridge protocol Gson serialization, provider flows, plan history, image/video limits, and bridge failure states work on supported Android versions.
- [ ] Create a dedicated release keystore outside the repository. Back it up securely; never commit the keystore, passwords, or a local signing-properties file.
- [ ] Supply `CRAFTMIND_RELEASE_STORE_FILE`, `CRAFTMIND_RELEASE_STORE_PASSWORD`, `CRAFTMIND_RELEASE_KEY_ALIAS`, and `CRAFTMIND_RELEASE_KEY_PASSWORD` through a short-lived local shell or a trusted CI secret store. The Gradle build rejects partial configuration and a keystore inside the checkout.
- [ ] Run `./gradlew :app:assembleRelease`, then `scripts/verify-release-apk.sh app/build/outputs/apk/release/app-release.apk`; archive the APK SHA-256 and signer certificate fingerprint outside Git.
- [ ] Install the signed APK on a clean API 26+ device and a current Android device. Exercise first launch, no-key/no-model state, live Gemini verification, text and supported image/video flows, local history, and the explicit no-bridge state.
- [ ] On a disposable supported Minecraft 1.20.1 Fabric server, verify pairing, preflight, separate confirmation, bridge-reported execution status, cancellation/error paths, and that unsupported server conditions fail closed. Do not test on a valuable world.
- [ ] Publish a GitHub `v1.0.0` release only after the signed APK and checksums are verified. Attach the APK, SHA-256 file, release notes, and compatibility/verification status; do not commit the APK or publish an unsigned artifact.
- [ ] Replace the website's disabled download placeholder only with the exact published, verified APK asset. Run `python3 scripts/check_website.py`, enable GitHub Pages, install `website/github-pages-workflow.yml.example` as `.github/workflows/publish-website.yml` from a checkout with workflow-write permission, run it manually, and verify the generated live URL and download link.

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
