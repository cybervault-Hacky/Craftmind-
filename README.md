# CraftMind

CraftMind is an Android app for turning Minecraft build ideas into structured, reviewable block-placement plans. Minecraft-side discovery, validation, and execution are a separate future integration; Phase 2 never places blocks in a world.

> **Phase 2 status:** text-only AI planning is implemented through the configured OpenAI HTTPS API, with Android Keystore-backed credential encryption, strict versioned JSON parsing, semantic plan validation, cancellation, and explicit UI outcomes. Only OpenAI is currently implemented. Image and URL references remain represented and locally visible, but are not sent to a provider or analyzed. Plans are held in memory for the current app session only.

## Product experience

- **Home:** responsive builder with a bounded text prompt, system photo picker, local image preview/removal/replacement, and an HTTP(S) URL reference editor. Starting a text-only request sends the text to the configured provider. If image or URL references are attached, the request is rejected as unsupported rather than silently omitting or uploading them.
- **Generation states:** request validation, provider generation, plan validation, success, typed failure, and cancellation are real states. Progress is indeterminate; the app does not invent percentages. Retry is user-triggered and offered only for retryable errors.
- **Builds:** displays only a plan that the provider actually returned and the domain validator accepted. It is session-only; there are no sample/fabricated plans or persistent history yet.
- **Settings:** lets the user select the implemented provider, enter a model identifier, save/replace/remove an API key, and test model access with a non-generative lookup. Only safe configured/missing metadata is displayed; a saved key cannot be viewed again.
- **Appearance and navigation:** system/light/dark appearance, compact bottom navigation, wide navigation rail, two-column builder, accessibility semantics, and light/dark Material 3 themes.

Reference URLs are validated locally, never opened, downloaded, scraped, or proxied. Selected image metadata and thumbnails are processed locally. Neither reference type is sent to OpenAI in Phase 2.

## Architecture

The app uses a small clean-architecture layout with constructor-injected repositories, use cases, adapters, and ViewModels. Compose renders immutable state and dispatches actions; provider SDK/network calls, credential access, request validation, parsing, and plan validation stay outside UI code.

```text
app/src/main/java/com/craftmind/app/
  core/
    designsystem/     CraftMind themes, reusable visual tokens, brand mark
    navigation/      top-level destinations and route resolution
  data/
    ai/               production provider registry
      openai/         fixed-host HTTPS adapter, request/schema, response parsing
    credentials/      Android Keystore AES-GCM and ciphertext-only DataStore records
    media/            ContentResolver image metadata and bounded local thumbnails
    settings/         DataStore appearance and provider/model configuration
  domain/
    ai/               provider, credential, configuration, and generation contracts
    media/            image validation and media repository contract
    model/            immutable requests, plan drafts/results, operations, typed errors, shared safety limits
    planning/         strict versioned JSON parser and semantic plan validator
    settings/         appearance and settings repository contracts
    validation/       request, builder input, and URL validation
  minecraft/          future Minecraft bridge boundary; no Phase 2 executor
  presentation/       Compose screens, UI state, and ViewModels
  reference/          reference-processing boundary; no scraper/downloader
```

`BuildRequest` can represent text, image, and URL inputs together. The Phase 2 generation use case validates the complete request and explicitly rejects image/URL processing until a provider adapter truly supports it. No Minecraft operation is executed by the app.

The provider requests a strict JSON Schema response for plan schema version 1. A bounded parser rejects malformed, wrapped, unknown-field, and unsupported-version output. A separate validator checks dimensions and volume, origin and coordinates, material allowlists and totals, operation count/order, duplicate/conflicting positions, steps, dependencies, block properties, and other configured limits before a `BuildResult` can be emitted. The plan is still untrusted data for any future Minecraft integration.

## Provider and network scope

The current production registry contains **OpenAI only**. The adapter calls the OpenAI Chat Completions endpoint over HTTPS using a user-configured model ID and strict structured output. The Settings connection test uses the model-retrieval endpoint; it does not generate a completion or submit a build prompt.

The HTTP client is restricted in code to `https://api.openai.com`, disables redirects and automatic connection retries, uses bounded request/response bodies and connect/read/write/call timeouts, propagates coroutine cancellation to OkHttp, and has no logging interceptor. Provider failures are reduced to safe typed error codes; raw provider bodies, prompts, and credentials are not placed in UI errors or logs. Automatic retry is disabled.

## Credential handling and privacy

- The API key is never hardcoded or checked into the repository. Settings accepts it as a masked, ephemeral field and clears it from UI state after save.
- Credentials are encrypted with AES-GCM using a non-exportable Android Keystore key **before** ciphertext is written to Preferences DataStore. The preference record key uses a SHA-256 digest of the provider alias. Provider and model identifiers are stored separately; they are not credentials.
- `allowBackup` is disabled. The app uses HTTPS only and disallows cleartext traffic.
- When a user starts a text-only request, the prompt and authorization credential are sent to OpenAI over HTTPS. A connection test sends only a model lookup. OpenAI's terms, privacy, and retention policies apply; this app does not provide a server-side proxy.
- Image and URL references are not sent. URL validation is local only. Do not add scraping, access-control bypass, unrestricted proxying, or Minecraft block execution to Phase 2.
- Plans are kept in memory for the app session; they are not written to a database or files. No plan is executed in Minecraft.

As with any client-side secret, Android Keystore protects the saved credential at rest but cannot protect it from a compromised device or a fully compromised app process while it is being used.

## Technology

- Kotlin and Jetpack Compose with Material 3
- Android Gradle Plugin **9.2.1**, Gradle wrapper **9.4.1**, Compose compiler plugin **2.3.10**
- Compose BOM **2026.09.00**, Navigation Compose, Lifecycle ViewModel/Flow, Coroutines, Preferences DataStore, kotlinx.serialization JSON, OkHttp
- Android `minSdk 26`, `compileSdk 36`, `targetSdk 36`
- Application ID / package: `com.craftmind.app`

The Gradle wrapper and dependencies need network access on first build. Use JDK 17 and an Android SDK with API 36 and matching build tools. Android Studio's bundled SDK/JDK are suitable when configured to use this project's Gradle wrapper.

## Development and verification

1. Open the repository in Android Studio, or install JDK 17 and the Android SDK.
2. If needed, create an **untracked** `local.properties` file with your local SDK path, for example:

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

3. From the repository root, run:

   ```bash
   ./gradlew test
   ./gradlew lint
   ./gradlew assembleDebug
   ./gradlew connectedDebugAndroidTest
   ```

Unit tests cover request preservation and rejection behavior, plan parsing/validation, credential-store behavior with a test-only cipher, provider request/error mapping, cancellation, retry policy, and UI-state behavior. Instrumentation tests cover navigation, provider settings, and honest missing-configuration states. Tests use no production API key and do not call OpenAI.

## Roadmap

1. **Phase 1 — Android foundation:** responsive UI, local input handling, appearance settings, and integration boundaries.
2. **Phase 2 — AI build engine (current):** provider configuration, secure credential storage, text-only OpenAI planning, strict plan validation, and real UI states/results.
3. **Phase 3 — Minecraft integration:** supported bridge discovery, explicit connection lifecycle, plan compatibility checks, and only then execution with real progress, cancellation, and failure recovery.

## License

MIT. See [LICENSE](LICENSE).
