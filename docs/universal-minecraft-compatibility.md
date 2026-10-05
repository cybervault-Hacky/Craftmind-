# Multi-Edition Minecraft Compatibility Core (Phases 10–11)

## Scope and support policy

Phases 10–11 extend CraftMind's existing compatibility core and secure construction route. They do not replace the Android/Kotlin/Compose app, provider-backed AI, platform-neutral BuildPlan v2, BYOK storage, pairing, bridge execution, history/refinement, Phase 8 website, or release configuration.

Phase 10 targets multiple Java Edition runtime identities. Phase 11 adds **Bedrock Edition as a first-class edition** of the same core — the same resolver, adapter registry, capability negotiation, limits, diagnostics, and execution gate — with a separate Bedrock bridge boundary. See [Bedrock edition (Phase 11)](#bedrock-edition-phase-11).

The central runtime-profile registry and adapter registry currently contain **one production profile only**:

| Field | Exact registered profile |
| --- | --- |
| Edition | Java Edition |
| Minecraft | `1.20.1` |
| Java runtime | Java `17` only (`17` required; supported range `17..17`) |
| Loader | Fabric `0.16.10` |
| Fabric API | `0.92.2+1.20.1` |
| CraftMind Bridge | `1.2.0` |
| Bridge protocol | `2` |
| BuildPlan schema | `2` |
| Shared limits | BuildPlan bounds plus the authenticated server's reported operation, request-byte, per-tick, and time limits |

The profile is a configured source-level target, not a claim of successful compilation, live server integration, Minecraft testing, or certification. The `SUPPORTED` resolver status means the bridge-reported identity exactly matches the registered profile; construction additionally requires all capabilities and plan/runtime limits, a successful independent live-server preflight, and separate explicit confirmation.

No production adapter is registered for other Minecraft or Java versions, Legacy, Forge, NeoForge, Vanilla, snapshots, betas, or experimental Java targets. Bedrock is registered only as an uncertified contract boundary that cannot authorize construction (see below). Unknown, incomplete, unsupported, and ambiguous identities fail closed. There is no nearest-version fallback, Java-version inference, Fabric-through-another-loader route, BuildPlan substitution, or silent protocol downgrade.

## Runtime report and existing Bridge 1.1.0 installations

Authenticated protocol v2 distinguishes the CraftMind app version, bridge mod version, wire-protocol version, edition, Minecraft version, actual server Java feature version, loader/version, loaded Fabric API version, supported BuildPlan schemas, implemented capabilities, current world/origin availability, and bounded execution limits. The Android app sends its `BuildConfig.VERSION_NAME` in the signed capabilities request and requires the bridge to echo that exact value; this app-supplied value is not represented as a server runtime measurement. The Android app validates the exact bounded wire shape and internal consistency before resolution. It does not infer Java from Gradle toolchains or infer Fabric API from dependency declarations.

A paired **CraftMind Bridge 1.1.0 / protocol 1** installation must be upgraded together with the Android app to the matching app plus Bridge `1.2.0` / protocol `2` before use. The Android client rejects the old envelope with an update-required error; it does not parse missing Java/Fabric API fields as defaults or attempt a v1 downgrade. The saved TLS pin, pairing record, device Keystore identity, and server trusted-client record are retained. After updating the app and existing server mod, reconnect using the same saved pairing; no new identity or competing bridge is required. Pairing and runtime compatibility remain separate concepts.

## Central types and resolver

- `MinecraftVersion` parses safe bounded release, prerelease, snapshot, beta, alpha, and legacy identifiers. It is not used for range ordering; profiles match exact version strings.
- `MinecraftRuntimeDescriptor` carries only bridge-reported runtime and capability data. Missing or malformed Java/Fabric API/runtime fields remain unknown or invalid; no profile selection is allowed from incomplete data.
- `JavaRuntimeRequirement` makes the profile's required and supported Java majors explicit.
- `MinecraftRuntimeProfileRegistry` is the central list of production profile identities. `MinecraftAdapterRegistry` binds each profile to one adapter ID and rejects empty, invalid, mismatched, duplicate, or overlapping registrations. The resolver blocks ambiguous matches rather than choosing by registration order.
- `MinecraftCompatibilityReasonCode` provides stable diagnostic categories for unsupported Minecraft/loader/bridge profiles, Java/API mismatch, missing capability, unsupported schema, limits, malformed/incomplete metadata, and ambiguous profiles. Human-readable reasons accompany those codes.
- `BuildPlanRequirements` is derived from the saved BuildPlan. BuildPlan v2 remains platform-neutral; block-state support is additionally required only when requested states are present.

Capability negotiation uses **only capabilities reported by the authenticated bridge**. The adapter contributes no unreported dynamic capabilities. A bridge report that omits a required capability can still identify a known runtime profile, but the compatibility result cannot authorize construction. The server independently validates all requests against its current configuration and live Minecraft registries.

## Authoritative block and state validation

The existing server-side `BuildPlanContractValidator.BlockSupport` remains the extension seam for block/state support, and production preflight continues to use the live server registry. Syntactically malformed IDs, state objects, values, or oversized lists remain invalid-plan errors. Well-formed but unavailable/disallowed block IDs return `UNSUPPORTED_BLOCK`; well-formed but unavailable/disallowed state selections return `UNSUPPORTED_BLOCK_STATE`.

When the validator can identify a failing placement, the authenticated rejection includes the zero-based operation index, exact block ID, and up to the shared state-property bound of property names. The app validates those details and presents a one-based operation number and an actionable explanation. The extension can report unsupported property names, but it cannot substitute a block or state, edit the accepted BuildPlan, or bypass live server validation. Unsupported content rejects preflight before placement; no block is skipped or repaired.

Malformed/oversized request bodies, JSON token/depth bounds, operation/state-property bounds, strict exact-key payloads, TLS identity checks, authenticated sessions, replay protection, BuildPlan limits, and existing execution confirmation remain enforced. Unsupported details are diagnostic only and do not weaken validation.

## UI and execution gate

Settings and Build Review show the bridge-reported edition, Minecraft, Java, loader, Fabric API, bridge, and protocol versions; compatibility status; adapter selection; actual capabilities and gaps; structured reason codes and explanations; and effective execution limits. Missing or unsupported runtime facts are shown as missing/incompatible, never synthesized.

Construction remains gated on all of the following:

1. A saved, pinned bridge authenticates successfully.
2. Exactly one production adapter profile matches and status is `SUPPORTED`.
3. All bridge-reported capability and plan/runtime limit checks pass.
4. Independent server-side BuildPlan/world preflight succeeds and returns a current short-lived token.
5. The user separately confirms the immutable plan preview.

Pairing alone does not imply compatibility. A failed capability check, unsupported block/state, stale preview, or failed server preflight cannot be turned into an automatic start. Status is read-only; there is no automatic retry with a modified plan.

## Bedrock edition (Phase 11)

### Target architecture

`Platform-neutral AI → BuildPlan v2 → compatibility resolver → {Java adapter → existing Fabric bridge | Bedrock adapter → Bedrock bridge} → Minecraft world`

The Bedrock path reuses the platform-neutral pieces — protocol concepts, pairing/authentication, BuildPlan validation, capability negotiation, execution identifiers, status, cancellation, limits, and the error model — and adds only an edition-specific adapter and runtime descriptor model. There is no second compatibility system, no Bedrock-specific BuildPlan, and no Bedrock-specific AI prompt.

### Edition and runtime model

- `MinecraftEdition` is `JAVA`, `BEDROCK`, `LEGACY`, or `UNKNOWN`. `MinecraftLoader` keeps the loader families disjoint: `FABRIC`/`FORGE`/`NEOFORGE`/`VANILLA` belong to Java, `BEDROCK_NATIVE` belongs to Bedrock. A mismatch between reported edition and loader is an invalid descriptor.
- `MinecraftRuntimeDescriptor` carries Bedrock-owned facts in addition to the edition: `platform` (`DEDICATED_SERVER`, `CLIENT_HOSTED_WORLD`, `REALMS`, `UNKNOWN`), `platformVersion`, bridge version, protocol version, reported capabilities, limits, `status`, and declared `limitations`. Bedrock never reports a Java runtime, a loader version, or a Fabric API version; if a Bedrock report contains them the descriptor is invalid and resolution fails closed. A Java runtime never reports a Bedrock platform or Bedrock limitations.
- `BedrockRuntimeProfile` is the version-aware counterpart of the Java profile: edition, bridge protocol/version, supported platforms, `certifiedMinecraftVersions`, contract capabilities, required BuildPlan schema, declared limitations, limits, a block/state support revision, a block/state catalog, a status, and a `BedrockRuntimeCertification` level (`NOT_PERFORMED`, `UNIT_TESTED`, `BRIDGE_TESTED`, `RUNTIME_TESTED`). Only `RUNTIME_TESTED` authorizes `SUPPORTED`.
- `BedrockBlockStateCatalog` is the version-aware block/state compatibility layer. A mapping can only prove that the *same* platform-neutral block ID exists with a declared state representation; mappings never change block identity, never substitute a block, and never guess a state value. Unknown block → `UNSUPPORTED_BLOCK`; unlisted property or value → `UNSUPPORTED_BLOCK_STATE`.

### Version handling (no cross-edition matching)

Bedrock versions are modeled independently of Java versions. `Java 1.20.1` and `Bedrock 1.20.1` are unrelated identifiers; nothing in the resolver compares, normalizes, or converts across editions, and no nearest-version or range fallback exists. Each registered Bedrock contract lists the exact Bedrock versions that were runtime-verified, and the resolver requires an exact match.

### Central registry (deliberately small)

`BedrockRuntimeProfileRegistry` ships **exactly one** contract: the `bedrock-bridge-contract` (`CraftMind Bedrock Bridge`, interface version `1.0.0`, protocol `2`) declaring the platforms and capabilities a future Bedrock bridge may report. Its `certifiedMinecraftVersions` is **empty** and its certification level is **`NOT_PERFORMED`**, its block/state catalog is **`EMPTY`**, and its status is `EXPERIMENTAL`. Consequently:

- a Bedrock runtime that matches the contract resolves to `EXPERIMENTAL` (not `SUPPORTED`) and `canExecute` stays `false`;
- the adapter's `preflight`, `execute`, `cancel`, and `status` members throw a typed `MinecraftBridgeFailure` (`BEDROCK_RUNTIME_NOT_CERTIFIED`) instead of forwarding a BuildPlan;
- with the `EMPTY` catalog every requested block resolves as unsupported, so no Bedrock build can be presented as executable.

A version is added to that registry only together with recorded runtime verification. Do not add speculative Bedrock versions.

### Bridge model

The Bedrock integration boundary is a **Bedrock Dedicated Server + CraftMind Bedrock bridge** (or another documented, explicitly supported mechanism). The Bedrock bridge must speak the same authenticated protocol-v2 envelope; the app exposes the interface contract version, not an assertion that such a bridge is deployed. Bedrock worlds cannot load the Java Fabric mod: the Fabric mod reports `edition="java"` and can never serve a Bedrock report, and no compatibility shim, screen scraping, input simulation, injection, patching, or anti-cheat/DRM bypass is used anywhere.

Runtime detection is bridge-authoritative: the app reads `edition` from the authenticated report and dispatches to the Bedrock codec only for the exact Bedrock wire value; everything else goes to the Java codec and fails closed. Launcher names, user-selected versions, filenames, and heuristics are never used.

### Capability negotiation

The Bedrock report reuses the existing protocol structures and is parsed with strict exact-key validation, bounded token/length limits, protocol and bridge-version equality, identity-fingerprint and bridge-ID checks, and capability↔flag coherence. The contract exposes only genuinely implemented capabilities — the protocol's `BUILD_EXECUTION`, `BLOCK_PLACEMENT`, and `BLOCK_STATE_SUPPORT`, the operator-origin requirement (`ORIGIN_RESOLUTION`), progress and status reporting (`PROGRESS_REPORTING`, `BUILD_STATUS`), and cancellation (`CANCELLATION`), plus the `WORLD_ACCESS`, `WORLD_VALIDATION`, `STRUCTURE_BATCHING`, and `BUILD_PLAN_V2` protocol-level set — and each is granted only because the authenticated bridge reported it. Capabilities are never granted automatically, never inferred, and never widened: a capability outside the declared contract makes the result `UNKNOWN`. `javaRuntimeMajor = null` and `loaderName = BEDROCK_NATIVE` are emitted by the codec.

### BuildPlan v2 compatibility and server-side validation

BuildPlan v2 remains platform-neutral. The AI never silently changes a plan for Bedrock, and the app-side resolver is never the authority. The Bedrock bridge must independently validate schema, byte size, width/height/depth, operation count, block/state validity, coordinate bounds, execution ID, protocol version, capability requirements, runtime compatibility, and auth/session validity — reusing the shared `BuildPlanLimits` (schema `2`, 96 × 64 × 96, 64 components, 4096 operations, 8 state properties, 32-character values) and the effective limit = min(bridge-reported, ceiling). Any failure rejects with `PLAN_LIMIT_EXCEEDED`/`UNSUPPORTED_BLOCK_STATE` and the typed error model; limits are never bypassed for Bedrock.

### Structured diagnostics and UI

Failures carry a stable `MinecraftCompatibilityReasonCode`, a bounded human-readable reason, and bounded structured diagnostics (`reasonCode`, affected component/block/state properties, required vs available) that are safe for the Android UI, logs, tests, and AI refinement, and contain no secrets or runtime internals. Settings and Build Review show edition, Minecraft and platform version, bridge/protocol, status, certification level, reported capabilities, missing capabilities, declared limitations, and limit/content checks. A Bedrock runtime that is not `SUPPORTED` shows an explicit state: *"Bedrock Edition: not currently supported. CraftMind cannot safely execute builds on this Bedrock runtime yet. No BuildPlan will be sent for execution."* Final confirmation is disabled while incompatible, and pairing still never implies compatibility.

### Execution, cancellation, and recovery semantics

The Bedrock path reuses `executionId`, status, progress, cancellation, `completed`, and `failed` semantics: no fabricated progress, no premature `completed`, no auto-resume after a restart, and `FAILED`/`RECOVERY_REQUIRED` when world state cannot be proven. Declared Bedrock limitations — cancellation only at batch boundaries, no rollback, no automatic resume, operator-selected origin, bridge-reported progress, single active execution, no block-entity data, no transactional placement — are surfaced rather than hidden; if cancellation cannot be guaranteed, the limitation is shown instead of a false success.

### Security

Every Phase 1–10 control remains in force and is not weakened: TLS pinning, one-time pairing with device identity and proof of possession, authenticated sessions with replay protection, bounded strict parsing, exact-key payloads, fail-closed capability/protocol handling, fail-closed app/bridge version echo (`UNSUPPORTED_BRIDGE_VERSION`, `BRIDGE_MALFORMED_RESPONSE`), preserved saved pairing identity, independent server-side validation, and the existing deny-lists. Provider API keys continue to live only in Android secure storage and are never sent to a Java bridge, a Bedrock bridge, or Minecraft. Bedrock support adds no monetization, no licensing/DRM logic, and no unsafe automation.

### Java vs Bedrock summary

| Concern | Java Edition | Bedrock Edition |
| --- | --- | --- |
| Loader representation | `FABRIC` (also `FORGE`/`NEOFORGE`/`VANILLA` modeled) | `BEDROCK_NATIVE` only |
| Runtime facts | JVM major, loader version, Fabric API version | platform, platform version, bridge version, limitations |
| Bridge | Fabric mod, Bridge `1.2.0`, protocol `2` | CraftMind Bedrock bridge boundary, interface contract `1.0.0`, protocol `2` |
| Registry | one certified production profile (`java-fabric-1.20.1`) | one uncertified contract (`bedrock-bridge-contract`) |
| Version matching | exact Minecraft + exact Java + exact Fabric API | exact certified Bedrock version; no cross-edition matching |
| Block/state | live server registry, `UNSUPPORTED_BLOCK`/`UNSUPPORTED_BLOCK_STATE` | declared verified catalog, same reason codes, no substitution |
| Executable today | only when all checks pass | **never** in this build (`EXPERIMENTAL`, no certified version, empty catalog) |

### Bedrock status in this build

**UNSUPPORTED in practice / architecture EXPERIMENTAL.** The Bedrock architecture is implemented and statically verified, but no Bedrock bridge exists in this repository, no Bedrock runtime test was performed, and no Bedrock version is certified. CraftMind therefore reports Bedrock as not currently supported and sends no BuildPlan. Any future claim of Bedrock support requires a real Bedrock runtime test recorded in the registry.

## Verification status

The repository contains focused source tests for version and runtime parsing, exact profile registration/resolution, Java and Fabric API mismatch reasons, bridge-reported capability negotiation, BuildPlan/schema/limit rejection, malformed and oversized protocol input, structured block/state validation details, and the paired-but-unsupported execution regression. Phase 11 adds tests for edition/version dispatch, Bedrock version validity/unknown handling, Bedrock adapter registration (duplicate ID, duplicate profile, cross-adapter overlap, ID mismatch, invalid profiles, mixed Java/Bedrock families), resolver outcomes (`SUPPORTED`/`UNSUPPORTED`/`UNKNOWN`, protocol/bridge/platform mismatch, missing capability, BuildPlan incompatibility), block/state mapping with no silent substitution, Bedrock fail-closed security cases (malformed/oversized descriptor, unknown protocol, invalid capability, invalid BuildPlan, invalid execution ID, stale session), the edition-aware UI text, and the Java regression profile.

**Test source is not evidence that tests ran.** In this sandbox (no Android SDK/AGP/Gradle and no Maven/Google egress) verification was performed outside the Gradle build with locally reconstructed tooling. Recorded results at the time of this phase:

- **Kotlin (JVM, Android-free subset) compiled**: yes — 75 sources (app domain/data/compatibility + focused tests) compiled with Kotlin `2.1.10`, kotlinx-serialization `1.8.0` core+json and okio compiled from source, using the matching serialization compiler plugin.
- **Unit tests executed**: yes for that subset — 156 tests across 24 test classes: 152 passed, 4 failed. The compatibility core and Bedrock paths are green (`OK (58 tests)`: Bedrock wire codec, Bedrock compatibility/resolver, Java compatibility regression, bridge capabilities codec, edition-aware UI text). The 4 failures are in `AiBuildEngineTest` (2), `BuildPlanContextSerializerTest`, and `BuildDiffTest`, are unrelated to Phase 11, and reproduce identically on the `main` tree.
- **Java (JDK 8 `javac`) compiled**: `bridge-protocol` main sources → 34 classes.
- **Android app build / APK / `connectedAndroidTest`**: not run (no SDK/AGP).
- **Fabric mod build**: not run (no Gradle/Maven egress).
- **Bedrock bridge / real Bedrock runtime test**: not performed; no Bedrock bridge implementation exists in this repository.
- **Certification**: none. Bedrock certification level is `NOT_PERFORMED`.

Before claiming verification, check Java/JDK and Android SDK availability and run the focused Gradle tests plus the relevant app/bridge build tasks. Do not claim an Android compile, Fabric compile, APK, bridge test, real Minecraft test, deployment, or compatibility certification unless that exact check succeeds. Verification results belong in the completion summary as compiled, unit-tested, bridge-tested, real-Minecraft-tested, blocked, or unverified.
