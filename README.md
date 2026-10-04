# CraftMind

**AI Minecraft Build Planner** · *Describe it. Review it. Refine it.*

CraftMind is an Android app for turning a written request into an AI-designed, validated Minecraft build plan. The selected provider/model must return every plan and refinement; CraftMind has no manual block-layout editor, picker, drag-and-drop designer, or built-in structure templates.

## Phase 3 plan pipeline

**Written request → explicit provider/model selection → direct provider HTTPS request → strict versioned parsing and centralized validation → local review**

**Natural-language refinement → bounded saved-plan context → selected provider/model text + structured-JSON capability checks → strict edit-patch parsing → immutable candidate + domain diff → explicit acceptance → new local version**

The app does not silently change providers, models, capabilities, or schemas. A candidate is kept separate from its saved base until the user accepts it. A provider failure, rejected response, cancellation, or failed local save leaves the previous valid version intact. Retry is user initiated. Restoring an earlier plan appends another local version; it is not Minecraft undo.

## Supported today

- **Provider adapter:** one built-in Google Gemini REST adapter. It lists current provider models and supports text generation with JSON MIME output. Semantic refinement requires text generation and JSON MIME output from both the selected adapter and model; the model's advertised context limit is also enforced. Model availability is rechecked; unsupported or stale selections fail with a typed error and are never replaced with a fallback.
- **Provider setup:** enter, replace, remove, and test a Google-owned API key in Settings; select a model only after a live authenticated model-list request succeeds.
- **Credential privacy:** provider keys are AES-GCM encrypted in app-private no-backup storage, with the AES key held by Android Keystore. Keys are not placed in preferences, logs, URLs, analytics, crash reports, build records, a CraftMind backend, or Minecraft. Stored values are never prefilled into the UI.
- **BuildPlan schemas:** new provider-generated plans use semantic BuildPlan v2, including structured intent and typed, bounded components with parent/construction-order relationships. Existing Phase 2 v1 local records remain readable; a successful AI refinement upgrades a legacy plan only when the AI supplies complete valid semantic metadata. The app does not silently repair incomplete plans.
- **Central validation:** strict JSON parsing and shared validation reject unknown/malformed schemas, unsafe block IDs or states, invalid coordinates/dimensions/bounds, broken component relationships, construction-order errors, duplicate/conflicting positions, unsupported operations, missing component placements, and oversized output. Core limits are 96 × 64 × 96 blocks, 64 components, 4,096 placements, a 4 MiB provider response, and 800 characters per refinement request.
- **Bounded refinement context:** context omits raw image/URL references and includes at most 768 complete placement operations (plus bounded samples when safe), within 96 KiB and the selected model's advertised context limit. Component replacement is rejected unless that component's complete placements were included. Overly broad refinements are refused when safe context cannot be provided.
- **Diff and review:** candidate review identifies provider/model, dimensions, total placement count, semantic components, added/removed/modified components and placements, and suspiciously broad or unrelated changes. Warnings are advisory and are not hidden. No plan or candidate is executed in Minecraft.
- **Local version history:** accepted refinements are stored as immutable versions in atomic app-private local history. Earlier versions are retained; restore creates a new version. No backend or cloud sync is used.
- **Request references:** JPEG, PNG, and WebP image references and HTTP(S) URL references can be retained locally. They are **not uploaded, fetched, scraped, or analyzed** by this adapter. The selected provider receives a clear notice that such references exist but are unavailable for analysis; raw image URIs and URL values are omitted from refinement context.
- **Network behavior:** provider calls use the built-in HTTPS endpoint directly with bounded response bodies, connection/read/write/call timeouts, cancellation propagation, safe status mapping, disabled redirects, and no automatic retries. The user may explicitly retry retryable failures.

## Not implemented

- Gemini vision/image analysis, arbitrary URL fetching, or analysis of restricted/social content.
- Other provider adapters or user-configurable endpoints.
- Minecraft pairing, world connection, block placement, execution progress, or Minecraft undo.
- Manual build editing/design tools, templates, fake provider output/progress, backend/cloud sync, subscriptions, ads, payments, credits, or premium tiers.

## Provider verification

A provider is reported as connected only after a real authenticated HTTPS request lists compatible models. Generation and refinement recheck the saved provider/model selection and accept only real provider responses that pass local parsing and validation. This repository contains no provider keys. A live account/key was not available during implementation, so a real Google account connection has not been exercised here.

## Project and verification

- Application ID: `com.craftmind.app`
- Android: Kotlin, Jetpack Compose, Material 3
- Architecture: `presentation/`, `domain/`, `data/`, and `designsystem/` boundaries
- Minimum Android version: API 26; compile/target SDK: 35
- Java toolchain: 17

With JDK 17 and Android SDK 35 installed, run:

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```

**Checkout verification status:** Java/Android tooling was unavailable in the implementation environment (`java`, `javac`, and `adb` were not found, and `JAVA_HOME` was empty). Gradle tests, lint, APK assembly, and connected Android tests therefore have not been verified here. Do not infer a passing build or a generated APK from the presence of the wrapper or test sources.
