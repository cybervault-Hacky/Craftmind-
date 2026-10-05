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
- **Provider network behavior:** provider calls use the built-in HTTPS endpoint directly with bounded response bodies, connection/read/write/call timeouts, cancellation propagation, safe status mapping, disabled redirects, and no automatic retries. The user may explicitly retry retryable failures.

## Phase 4 secure bridge foundation

Phase 4 adds a separate, server-only Fabric mod (`minecraft-bridge/`), a shared versioned protocol module (`bridge-protocol/`), and a Minecraft pairing/session panel in Android Settings. Provider behavior, encrypted provider-key storage, provider-backed AI generation, on-device plan review, and local history remain separate and unchanged. Minecraft/bridge availability is optional for core app and local-history use; AI generation still requires the selected provider's network service. **The Android client does not send a BuildPlan. Minecraft construction, block placement, fake progress, world access, and undo are disabled.**

### Configured compatibility matrix

| Component | Configured version |
| --- | --- |
| Minecraft | `1.20.1` |
| Yarn mappings | `1.20.1+build.10` |
| Fabric Loader | `0.16.10` |
| Fabric API | `0.92.2+1.20.1` |
| Fabric Loom | `1.9.2` |
| Java bytecode/toolchain | `17` |
| Root Gradle wrapper | `8.11.1` |

These versions are pinned in `minecraft-bridge/build.gradle`, `minecraft-bridge/src/main/resources/fabric.mod.json`, `minecraft-bridge/settings.gradle`, the root Gradle wrapper, and the Android/Java build settings. The matrix is **configured but not verified by a successful build in this checkout**; see verification status below.

### What the bridge implements

- **Typed v1 contracts:** bounded strict JSON envelopes, UUID request/correlation IDs, version checks, typed errors, rate/body/depth/operation limits, BuildPlan v2 transport DTOs, and independent validation of IDs, dimensions, coordinates, components, operation count, vanilla block IDs, and registered block-state properties.
- **Real acknowledgement semantics:** valid future execution requests are independently validated and receive an explicit `execution.rejected` response with `CONSTRUCTION_DISABLED`; malformed or unsupported requests receive typed rejection errors. There is no build queue. Typed future ACK, cancellation, and progress event shapes exist, but this version never reports acceptance, cancellation success, or progress. The cancel route explicitly rejects cancellation as unavailable.
- **Encrypted, pinned transport:** HTTPS only (TLS 1.2/1.3), a server certificate whose private key is stored in password-protected PKCS#12, and Android verification against the SHA-256 certificate fingerprint entered out-of-band from the in-game identity command. Android accepts only strict loopback/RFC1918 IPv4 literals—no DNS, public address, IPv6, redirects, or cleartext fallback. The server binds to one loopback/RFC1918 IPv4 address and accepts remote peers only from loopback, RFC1918, or IPv4 link-local ranges.
- **Explicit device pairing:** an operator opens a one-use, 256-bit pairing code for three minutes. The Android app proves possession of a P-256 key generated and held by Android Keystore. The server persists only the Android public key and display metadata in a bounded, atomically replaced trusted-client file; pairing codes, challenges, and sessions are memory-only.
- **Authenticated sessions and revocation:** expiring one-time challenges, P-256 signed proof-of-possession, request signatures over exact body bytes, route, method, request ID, timestamp, session ID, and monotonically increasing sequence; replayed/stale requests are rejected. Sessions expire after five minutes idle and at 30 minutes maximum age. The app can reconnect by authenticating again, explicitly disconnect a session, remotely revoke its paired key, or forget local state. Operator revocation removes the trusted record and invalidates sessions/challenges.
- **No arbitrary control surface:** only an exact POST route allowlist is exposed. There is no shell, command execution, screen/mouse/keyboard automation, simulated player, block-placement endpoint, or general-purpose LAN API. Minecraft registry lookups validate block IDs/states without reading or modifying a world.

### Pairing and server setup

1. Install the server-only Fabric mod built from `minecraft-bridge/` on the configured compatibility matrix. It creates `config/craftmind-bridge.properties`; the default listener is loopback-only (`127.0.0.1:19872`).
2. For a phone on a private LAN, set `bindAddress` to the server's private IPv4 address and allow the configured port through the local firewall. Do not port-forward or expose the listener to the public Internet. Configure the address **before first startup with the keystore password set**, so the durable TLS certificate is minted with the right IP Subject Alternative Name.
3. Set `CRAFTMIND_BRIDGE_KEYSTORE_PASSWORD` in the server process environment to a strong password of at least 16 characters. It is never written to the properties file. Without it the bridge listener stays disabled; core app functions and local plan history remain available without Minecraft connectivity. The encrypted identity file and trusted-client file are under `config/craftmind-bridge/`.
4. In game, an operator runs `/craftmind identity` and checks the public bridge ID/TLS SHA-256 fingerprint. Then `/craftmind pair open` shows the one-time code privately to that in-game operator; it expires after three minutes. Enter the private IPv4, port, fingerprint, and code in **Settings → Minecraft bridge** on Android. Treat the code as a secret; do not share it. `/craftmind pair close` closes the window, `/craftmind pair list` shows trusted client IDs, and `/craftmind pair revoke <clientId>` removes a device and its active sessions.
5. Android persists only the confirmed endpoint/pin/client metadata in app-private DataStore and keeps the private client key in Android Keystore. A later TLS identity mismatch is rejected; identity changes are never silently trusted. Use the Android **Revoke this device** action while reachable, or revoke the displayed client ID from an operator account. **Forget saved bridge** is local-only and warns that server-side trust remains until explicitly revoked.

The current Android UI reports only a real authenticated session and the bridge's verified v1 capabilities. It has pair/connect/reconnect/disconnect/revoke controls, but no plan-send control. Its capability display says construction is disabled. Session status is cleared locally before the bridge's five-minute idle timeout; a dropped network may still require operator-side revocation.

### Deliberate limitations

- Only one saved bridge profile and a manually entered private IPv4 endpoint are supported; discovery, DNS names, QR pairing, IPv6, internet-facing servers, and automatic identity replacement are not.
- Changing the server bind IP after creating its certificate fails closed with `IDENTITY_ADDRESS_CHANGED_REQUIRES_REPAIR`. The operator must deliberately back up/remove the identity file and generate a new one; this changes the TLS fingerprint and bridge ID. Previously paired clients must be reviewed/revoked; Android requires explicit re-pairing against the new fingerprint.
- There is no Minecraft world read/write, block placement, queued execution, undo, fabricated progress, or supported execution cancellation. The shared protocol's future request/ACK/progress vocabulary is not evidence that those features exist.
- The bridge foundation has not undergone an independent security audit. Configuration and tests are not a substitute for an audit or safe private-network deployment.

## Not implemented

- Gemini vision/image analysis, arbitrary URL fetching, or analysis of restricted/social content.
- Other provider adapters or user-configurable AI endpoints.
- Minecraft BuildPlan transmission from Android, world access, block placement, construction, undo, or execution progress.
- Manual build editing/design tools, templates, fake provider output/progress, backend/cloud sync, subscriptions, ads, payments, credits, or premium tiers.

## Provider verification

A provider is reported as connected only after a real authenticated HTTPS request lists compatible models. Generation and refinement recheck the saved provider/model selection and accept only real provider responses that pass local parsing and validation. This repository contains no provider keys. A live account/key was not available during implementation, so a real Google account connection has not been exercised here.

## Project and verification

- Application ID: `com.craftmind.app`
- Android: Kotlin, Jetpack Compose, Material 3
- Architecture: `presentation/`, `domain/`, `data/`, and `designsystem/` boundaries
- Minimum Android version: API 26; compile/target SDK: 35
- Java toolchain: 17

With JDK 17 and Android SDK 35 installed, run the Android/shared-protocol checks from the repository root:

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```

Build and test the separate Fabric project from its own root using the repository wrapper:

```bash
cd minecraft-bridge
../gradlew test build
```

`test` from the root includes Android unit tests and `bridge-protocol` tests; it does **not** build the separate Fabric project. `connectedDebugAndroidTest` requires a configured Android SDK and attached emulator/device. `build` in `minecraft-bridge/` produces the remapped mod jar when successful.

**Checkout verification status:** At the time of this update, `java`, `javac`, `kotlinc`, `adb`, and `sdkmanager` were not found, `JAVA_HOME`/`ANDROID_HOME` were unset, and the Gradle wrapper could not start because Java is missing. Therefore no Gradle/JUnit tests, Fabric compile/remap, lint, APK assembly, emulator/integration test, or Android runtime pairing test has been verified, and no APK is claimed. The compatibility versions are configuration targets, not a verified matrix. A repository text scan for Google/AWS credential patterns, private-key PEM markers, and literal bridge-keystore-password assignments found no matches; no dedicated SAST or dependency-vulnerability scanner is installed in this checkout. These checks are not a full security audit; the bridge has not undergone an independent security audit.
