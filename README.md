# CraftMind

CraftMind is the foundation of a professional Android product for turning natural-language ideas and visual references into Minecraft build plans, then eventually executing validated plans through a supported Minecraft-side integration.

> **Phase 1 status:** the Android shell, local builder inputs, URL syntax validation, navigation, appearance settings, and future integration contracts are implemented. **AI planning, reference analysis, cloud requests, and Minecraft connectivity or block execution are not implemented.** Pressing **BUILD** with valid input displays an explicit not-connected state. It does not create a plan, contact a provider, or claim that a build was generated.

## Product experience

- **Home:** a responsive builder workspace with a multiline prompt, character limit, system photo picker, local image preview/removal/replacement, and an inline URL reference editor.
- **Builds:** an honest empty state. No sample or fabricated builds are shown.
- **Settings:** provider and credential setup placeholders, persisted system/light/dark appearance selection, a disabled notifications placeholder, privacy/about information, and app version.
- **Input behavior:** prompt-only, image-only, URL-only, and combined input are validated as future requests. A valid submission currently ends at the explicit “AI builder isn’t connected yet” state.
- **Adaptive UI:** compact layouts use bottom navigation; wider layouts use a navigation rail and a two-column builder where space allows. Reusable color, type, spacing, shape, sizing, and motion tokens support both themes. Focus, attachment, screen, and pointer-hover feedback are restrained; Compose animations follow Android's animator-duration accessibility setting.

Images are selected with Android's photo picker and are not uploaded. Phase 1 accepts JPEG, PNG, or WebP files smaller than 15 MiB, checks their metadata, and creates a downsampled local preview. URL validation is local syntax validation for HTTP(S); links are not opened, downloaded, or scraped. Validation in the Android client is not a security boundary for any future network service.

## Architecture

The app uses a lightweight clean-architecture layout with a small application composition root and constructor-injected repositories/ViewModels. Compose screens render immutable `StateFlow` state and dispatch user actions; they do not call AI, Minecraft, or networking code.

```text
app/src/main/java/com/craftmind/app/
  ai/                 AiProvider, BuildPlanner, credential-store contracts
  core/
    designsystem/     color schemes, typography, spacing, shapes, motion, brand mark
    navigation/      top-level destinations and route resolution
  data/
    media/           ContentResolver photo metadata and bounded thumbnail loading
    settings/        DataStore-backed appearance preference
  domain/
    media/            image validation and media repository contract
    model/            BuildRequest, BuildPlan, block operations, progress, errors
    settings/         appearance and settings repository contracts
    validation/       builder input and URL validation
  minecraft/          MinecraftBridge contract; no implementation in Phase 1
  presentation/       Compose screens, navigation, UI state and ViewModels
  reference/          ReferenceProcessor contract; no scraper or downloader
```

Future AI integrations implement `AiProvider` and `BuildPlanner`; secure credential persistence is represented only by a port and has no Phase 1 implementation. `MinecraftBridge` defines connection, world inspection, plan validation, execution, cancellation, and progress boundaries. Build models already represent coordinates, materials, structures, dimensions, dependencies, ordering, progress, results, and typed errors. Builder presentation state can represent streamed progress, completion, failure, and cancellation for later phases, but Phase 1 never emits those states. None of these contracts report a live connection or generated plan today.

Appearance is stored with Preferences DataStore. Prompt, selected media, and reference URL state remain in the builder ViewModel for the current app session. The app intentionally has no `INTERNET`, broad storage, or media-read permission in its manifest; selecting an image relies on the system picker grant.

## Technology

- Kotlin and Jetpack Compose with Material 3
- Android Gradle Plugin **9.2.1**, Gradle wrapper **9.4.1**, Compose compiler plugin **2.3.10**
- Compose BOM **2026.09.00**, Navigation Compose, Lifecycle ViewModel/Flow, Coroutines, Preferences DataStore
- Android `minSdk 26`, `compileSdk 36`, `targetSdk 36`
- Application ID / package: `com.craftmind.app`

The Gradle wrapper distribution and dependencies need network access on first build. Use a JDK 17 installation and an Android SDK with API 36 and matching build tools. Android Studio's bundled SDK/JDK are suitable when configured to use this project's Gradle wrapper.

## Development

1. Open the repository in Android Studio, or install JDK 17 and the Android SDK.
2. If needed, create an **untracked** `local.properties` file with your local SDK path, for example:

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

3. Build and verify from the repository root:

   ```bash
   ./gradlew test
   ./gradlew lint
   ./gradlew assembleDebug
   ```

4. Run the Android instrumentation/UI tests on an emulator or connected device:

   ```bash
   ./gradlew connectedDebugAndroidTest
   ```

The unit suite exercises input combinations, URL normalization/rejection, image validation, builder ViewModel state transitions, route resolution, and appearance behavior. Instrumentation tests render the app, navigate between the main destinations, and verify the explicit no-AI submission state.

## Security and privacy foundation

- There are no API keys, signing credentials, or secrets in source control.
- No API-key entry or secret persistence is active in Phase 1; settings explain that provider credentials are not available yet.
- No image, prompt, or URL is sent to a server. The URL editor performs no fetching.
- URL inputs must use HTTP(S), include a host, and cannot embed username/password credentials. Future services must repeat validation and enforce their own network/SSRF policy.
- `usesCleartextTraffic` is disabled, backup is disabled, and the manifest requests no broad media/storage permission.
- Do not add provider keys to source files, logs, `local.properties`, or version control. A future credential implementation must use platform-backed encrypted storage and must not expose raw secret values to UI state.

## Roadmap

1. **Phase 1 — Android foundation (this repository):** production-oriented navigation, visual system, local inputs and validation, accessibility basics, state models, and integration contracts.
2. **Future provider and reference phase:** secure provider configuration, supported AI adapters, and compliant processing for explicitly supported references.
3. **Future planning phase:** structured plan generation/streaming, validation, persistence, and real build history.
4. **Future Minecraft integration phase:** supported bridge discovery, explicit connection lifecycle, execution, progress, cancellation, and failure recovery.

No roadmap item above Phase 1 is represented as working functionality in this release.

## License

MIT. See [LICENSE](LICENSE).
