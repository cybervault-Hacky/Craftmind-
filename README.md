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
- **Diff and review:** candidate review identifies provider/model, dimensions, total placement count, semantic components, added/removed/modified components and placements, and suspiciously broad or unrelated changes. Warnings are advisory and are not hidden. A candidate cannot be constructed: it must first be explicitly accepted as a new immutable local version, then separately preflighted and finally confirmed.
- **Local version history:** accepted refinements are stored as immutable versions in atomic app-private local history. Earlier versions are retained; restore creates a new version. No backend or cloud sync is used.
- **Request references:** JPEG, PNG, and WebP image references and HTTP(S) URL references can be retained locally. They are **not uploaded, fetched, scraped, or analyzed** by this adapter. The selected provider receives a clear notice that such references exist but are unavailable for analysis; raw image URIs and URL values are omitted from refinement context.
- **Provider network behavior:** provider calls use the built-in HTTPS endpoint directly with bounded response bodies, connection/read/write/call timeouts, cancellation propagation, safe status mapping, disabled redirects, and no automatic retries. The user may explicitly retry retryable failures.

## Minecraft bridge: Phase 4 foundation and Phase 5 construction

Phase 4 established the separate, server-only Fabric mod (`minecraft-bridge/`), shared versioned protocol module (`bridge-protocol/`), and secure Android pairing/session flow. Phase 5 adds a narrow, explicit construction route to that existing bridge. Provider behavior, encrypted provider-key storage, provider-backed AI generation, on-device plan review, and immutable local plan history remain separate. Minecraft/bridge availability is optional for offline plan-history use; AI generation still needs the selected provider's network service. Construction is enabled only by an operator's server opt-in and only when Android has an authenticated compatible session reporting `construction.execute = true`.

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

### Phase 5: controlled construction workflow

Construction is an opt-in extension of the existing paired bridge, not a second connection service. The operator must deliberately set `constructionEnabled=true` in `config/craftmind-bridge.properties`; the generated default is `false`. A capability is usable only when the authenticated Android session reports `construction.execute = true`, protocol 1, BuildPlan schema 2, the exact supported Minecraft/Fabric/bridge versions, world access, cancellation, a current dimension and server world-session ID, and limits accepted by the Android client. Otherwise every construction action remains disabled.

1. **Pair using the existing flow.** The operator uses `/craftmind identity`, `/craftmind pair open`, and Android Settings to verify the bridge ID and TLS fingerprint and pair the device. Pairing is explicit; an operator can list/revoke devices. Provider API keys remain on Android and are never included in bridge requests.
2. **Select a safe origin in game.** An operator runs `/craftmind origin set` while standing at the intended anchor. The bridge captures that player's block position and the current dimension; no coordinates can be entered in the app or sent as an arbitrary placement origin. `/craftmind origin status` shows it and `/craftmind origin clear` removes it. The selected origin is memory-only and tied to a fresh world-session ID, so a server restart clears it. Moving or changing the selected origin invalidates prepared/active work before another placement.
3. **Choose an accepted immutable plan version.** Only the latest, locally saved, validated BuildPlan v2 in `READY` state can be prepared. A candidate refinement must be explicitly accepted first. The client sends the exact saved plan/version to the typed prepare route; it cannot edit or choose blocks/coordinates for execution.
4. **Run server preflight.** On the Minecraft server thread, the bridge checks the trusted authenticated client, protocol and schema, UUID execution ID and idempotency binding, operation/request limits, plan/component/semantic order, supported origin/dimension/world session, registered vanilla block IDs and state properties, transforms, loaded chunks, world height/border, unoccupied target cells, non-spectator entity collisions, vanilla spawn protection, and each block state's `canPlaceAt` against the existing world plus earlier operations in plan order. No blocks are placed during preflight. Any failed check rejects the whole preflight with zero writes; chunks are not force-loaded and invalid blocks/states are not skipped or repaired.
5. **Review a resolved preview, then confirm again.** Android displays the server-resolved world, dimension, operator-selected origin, immutable plan title/version, operation count, and preflight expiry. A separate confirmation dialog repeats the summary and warns that failure/cancellation can leave partial world changes. Only a fresh, authenticated final confirmation with the short-lived server token may start placement; start re-runs preflight before queueing.
6. **Observe actual server status.** The single active execution is placed in semantic BuildPlan operation order from bounded END_SERVER_TICK batches. The default is 32 attempted placements per tick, configurable up to 64. The default execution timeout is five minutes, configurable up to 15 minutes. Before each placement, the bridge rechecks the current origin, world bounds, border, loaded chunk, occupancy, entities, block state, and placement support, then uses Minecraft's `ServerWorld.setBlockState` and confirms the target block still occupies that cell; progress is stored only after both checks succeed. Android polls the authenticated status route and shows bridge snapshots; it never estimates progress locally. Cancellation is a best-effort request applied at the next safe tick boundary; the currently executing bounded batch (at most 64 placements) can finish first.
7. **Reconnect and restart safely.** Execution IDs are bound to the trusted client and payload hash; same-ID prepare/start retries are idempotent, conflicting reuse is rejected, and the server permits only one prepared/queued/running build at a time. Android stores execution snapshots in a separate bounded local ledger; it never mutates immutable plan records. On reconnect, Android queries the bridge's saved record; it never auto-starts a `PREPARED` execution. The mod marks prepared/queued/running records `FAILED` with `SERVER_RESTARTED` at shutdown/startup and never resumes. If the process crashes between a world write and its status-file checkpoint—or before Minecraft saves the affected chunk—the last persisted count may differ from the recovered world. The app warns operators to inspect the world in game; CraftMind has no automatic reconciliation, duplicate prevention across a **new** manual execution, rollback, or undo for partially placed blocks. Do not blindly retry an interrupted plan.

### Execution limits and supported world policy

The generated server defaults are `maxOperations=4096`, `maxRequestBytes=1048576` (1 MiB), `operationsPerTick=32`, and `maxExecutionSeconds=300`. The hard ceilings are 4,096 operations, 1 MiB, 64 operations/tick, and 900 seconds; out-of-range config is rejected at startup. A bridge allows one active or prepared execution, preflight tokens expire after 120 seconds, and both the server-side and Android-local execution ledgers are separately bounded to 100 records/256 KiB; neither replaces immutable plan history. Plan dimensions remain capped at 96 × 64 × 96, 64 semantic components, and 4,096 placements. Coordinates are transformed with checked arithmetic; transformed X/Z are bounded to ±30,000,000 and transformed Y to `-2048..2048`, then checked again against the loaded world's actual height and border.

Only the exact configured stack below is supported: Minecraft `1.20.1`, Java `17`, Fabric Loader `0.16.10`, Fabric API `0.92.2+1.20.1`, CraftMind Bridge `1.1.0`, protocol `1`, and BuildPlan schema `2`. In addition to the four base mod IDs `minecraft`, `fabricloader`, `fabric-api`, and `craftmind_bridge`, the mod policy permits only the module IDs bundled by the pinned Fabric API `0.92.2+1.20.1`: `fabric-api-base`, `fabric-api-lookup-api-v1`, `fabric-biome-api-v1`, `fabric-block-api-v1`, `fabric-block-view-api-v2`, `fabric-blockrenderlayer-v1`, `fabric-client-tags-api-v1`, `fabric-command-api-v1`, `fabric-command-api-v2`, `fabric-commands-v0`, `fabric-containers-v0`, `fabric-content-registries-v0`, `fabric-convention-tags-v1`, `fabric-crash-report-info-v1`, `fabric-data-attachment-api-v1`, `fabric-data-generation-api-v1`, `fabric-dimensions-v1`, `fabric-entity-events-v1`, `fabric-events-interaction-v0`, `fabric-events-lifecycle-v0`, `fabric-game-rule-api-v1`, `fabric-item-api-v1`, `fabric-item-group-api-v1`, `fabric-key-binding-api-v1`, `fabric-keybindings-v0`, `fabric-lifecycle-events-v1`, `fabric-loot-api-v2`, `fabric-loot-tables-v1`, `fabric-message-api-v1`, `fabric-mining-level-api-v1`, `fabric-model-loading-api-v1`, `fabric-models-v0`, `fabric-networking-api-v1`, `fabric-networking-v0`, `fabric-object-builder-api-v1`, `fabric-particles-v1`, `fabric-recipe-api-v1`, `fabric-registry-sync-v0`, `fabric-renderer-api-v1`, `fabric-renderer-indigo`, `fabric-renderer-registries-v1`, `fabric-rendering-data-attachment-v1`, `fabric-rendering-fluids-v1`, `fabric-rendering-v0`, `fabric-rendering-v1`, `fabric-resource-conditions-api-v1`, `fabric-resource-loader-v0`, `fabric-screen-api-v1`, `fabric-screen-handler-api-v1`, `fabric-sound-api-v1`, `fabric-transfer-api-v1`, and `fabric-transitive-access-wideners-v1`. Any additional/unknown mod—including claims, region protection, permissions, or other world hooks—disables construction rather than being bypassed; no third-party protection plugin integration is implemented. The bridge enforces the vanilla world border, loaded-chunk requirement, target-air/entity checks, block placement rules, and spawn protection. The vanilla sensitive/dynamic-block denylist includes administrative/structure blocks, barriers/light, spawners, bedrock/reinforced deepslate, portals, TNT, fluids, fire, dragon eggs, sand/gravel/anvils, scaffolding, pointed dripstone, and all concrete-powder colors. Blocks with block entities and any state with a non-empty fluid state are unsupported because plans do not carry safe block-entity data and fluid spread is outside the placement contract. Only registered `minecraft:` blocks and recognized state properties are accepted.

These are configured policy targets, not a successful real-server integration claim. Unsupported Fabric API modules or other mods fail closed. Do not install this bridge into a modded server with claims/protection systems and expect it to construct; the bridge deliberately has not integrated with them.

### What the bridge implements

- **Typed v1 contracts:** bounded strict JSON envelopes, UUID request/correlation IDs, version checks, typed errors, rate/body/depth/operation limits, BuildPlan v2 transport DTOs, and independent validation of IDs, dimensions, coordinates, components, operation count, vanilla block IDs, and registered block-state properties.
- **Typed execution semantics:** the bridge has separate prepare, start, status, and cancel routes. Prepare validates the complete request and server world without writing any blocks. Only a short-lived server-issued token plus a later authenticated start request can queue execution. Status, progress counts, terminal state, cancellation, and errors come from the bridge coordinator; unknown or mismatched IDs never imply success.
- **Encrypted, pinned transport:** HTTPS only (TLS 1.2/1.3), a server certificate whose private key is stored in password-protected PKCS#12, and Android verification against the SHA-256 certificate fingerprint entered out-of-band from the in-game identity command. Android accepts only strict loopback/RFC1918 IPv4 literals—no DNS, public address, IPv6, redirects, or cleartext fallback. The server binds to one loopback/RFC1918 IPv4 address and accepts remote peers only from loopback, RFC1918, or IPv4 link-local ranges.
- **Explicit device pairing:** an operator opens a one-use, 256-bit pairing code for three minutes. The Android app proves possession of a P-256 key generated and held by Android Keystore. The server persists only the Android public key and display metadata in a bounded, atomically replaced trusted-client file; pairing codes, challenges, and sessions are memory-only.
- **Authenticated sessions and revocation:** expiring one-time challenges, P-256 signed proof-of-possession, request signatures over exact body bytes, route, method, request ID, timestamp, session ID, and monotonically increasing sequence; replayed/stale requests are rejected. Sessions expire after five minutes idle and at 30 minutes maximum age. The app can reconnect by authenticating again, explicitly disconnect a session, remotely revoke its paired key, or forget local state. Operator revocation removes the trusted record and invalidates sessions/challenges.
- **No arbitrary control surface:** only an exact authenticated POST route allowlist is exposed. There is no shell, arbitrary command execution, screen/mouse/keyboard automation, fake player, simulated input, remote console, or general-purpose LAN API. The only mutation path is the typed BuildPlan-v2 execution lifecycle, which uses the server's world APIs and independently validates every requested block/state.

### Pairing and server setup

1. Install the server-only Fabric mod built from `minecraft-bridge/` on the configured compatibility matrix and only the supported Fabric API module set above. Any extra/unknown mod disables construction. It creates `config/craftmind-bridge.properties`; the default listener is loopback-only (`127.0.0.1:19872`) and `constructionEnabled` defaults to `false`.
2. For a phone on a private LAN, set `bindAddress` to the server's private IPv4 address and allow the configured port through the local firewall. Do not port-forward or expose the listener to the public Internet. Configure the address **before first startup with the keystore password set**, so the durable TLS certificate is minted with the right IP Subject Alternative Name.
3. Set `CRAFTMIND_BRIDGE_KEYSTORE_PASSWORD` in the server process environment to a strong password of at least 16 characters. It is never written to the properties file. Without it the bridge listener stays disabled; core app functions and local plan history remain available without Minecraft connectivity. To explicitly permit construction, review the safety limits and set `constructionEnabled=true` in the properties file; do not enable it on an unsupported/modded server. The encrypted identity, trusted-client, and separate bounded execution-record files are under `config/craftmind-bridge/`.
4. In game, an operator runs `/craftmind identity` and checks the public bridge ID/TLS SHA-256 fingerprint. Then `/craftmind pair open` shows the one-time code privately to that in-game operator; it expires after three minutes. Enter the private IPv4, port, fingerprint, and code in **Settings → Minecraft bridge** on Android. Treat the code as a secret; do not share it. `/craftmind pair close` closes the window, `/craftmind pair list` shows trusted client IDs, and `/craftmind pair revoke <clientId>` removes a device and its active sessions. Before construction, the same operator selects the in-game origin with `/craftmind origin set`; `/craftmind origin status` reviews it and `/craftmind origin clear` disables it.
5. Android persists only the confirmed endpoint/pin/client metadata in app-private DataStore and keeps the private client key in Android Keystore. A later TLS identity mismatch is rejected; identity changes are never silently trusted. Use the Android **Revoke this device** action while reachable, or revoke the displayed client ID from an operator account. **Forget saved bridge** is local-only and warns that server-side trust remains until explicitly revoked.

The Android UI reports the authenticated session and the bridge's verified capabilities. Construction actions in plan review remain disabled unless the exact compatible authenticated capability is true. Pair/connect/reconnect/disconnect/revoke controls remain in Settings; the construction review displays the resolved world, dimension, operator origin, plan version and operation count before the final confirmation. Session status is cleared locally before the bridge's five-minute idle timeout; a dropped network may still require operator-side revocation.

### Deliberate limitations

- Only one saved bridge profile and a manually entered private IPv4 endpoint are supported; discovery, DNS names, QR pairing, IPv6, internet-facing servers, and automatic identity replacement are not.
- Changing the server bind IP after creating its certificate fails closed with `IDENTITY_ADDRESS_CHANGED_REQUIRES_REPAIR`. The operator must deliberately back up/remove the identity file and generate a new one; this changes the TLS fingerprint and bridge ID. Previously paired clients must be reviewed/revoked; Android requires explicit re-pairing against the new fingerprint.
- No rollback, undo, automatic continuation, or automatic reconciliation of uncertain world state is implemented. A graceful cancellation/failure may leave partial changes. A server restart records `FAILED / SERVER_RESTARTED` and never resumes; the last persisted count may not exactly match the recovered world if a crash races a world write or chunk save. Inspect the world in game before starting another build.
- Claim/region-protection mods, custom permissions, other modded block registries, block-entity data, gravity-sensitive blocks, arbitrary coordinates, structure replacement, and remote commands are unsupported. Any extra mod fails construction closed; no special protection integration or permission bypass is provided.
- The bridge foundation has not undergone an independent security audit. Configuration and tests are not a substitute for an audit or safe private-network deployment.

## Not implemented

- Gemini vision/image analysis, arbitrary URL fetching, or analysis of restricted/social content.
- Other provider adapters or user-configurable AI endpoints.
- Manual build editing/design tools, block palettes, coordinate editors, templates, rollback, undo, or visual/screenshot verification of a constructed world.
- Fake provider output/progress, backend/cloud sync, subscriptions, ads, payments, credits, or premium tiers.

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

**Checkout verification status:** `java`, `javac`, `kotlinc`, `adb`, and `sdkmanager` are unavailable in this environment, and `JAVA_HOME`/`ANDROID_HOME` are unset. Both `./gradlew test` and `cd minecraft-bridge && ../gradlew test build` were attempted; each stopped in the wrapper before Gradle launched with `JAVA_HOME is not set and no 'java' command could be found`. No tests or compilation ran. Android `lint`, APK assembly, connected-device tests, and real Minecraft integration were not run; no APK, placed blocks, or successful integration is claimed. The compatibility versions are configured targets, not a verified matrix. A repository text scan for Google/AWS credential patterns, private-key PEM markers, and literal bridge-keystore-password assignments found no matches; no dedicated SAST or dependency-vulnerability scanner is available here. These checks are not a full security audit; the bridge has not undergone an independent security audit.
