# Multi-Edition Minecraft Compatibility Core (Phases 10–14)

## Scope and support policy

Phases 10–11 extend CraftMind's existing compatibility core and secure construction route. They do not replace the Android/Kotlin/Compose app, provider-backed AI, platform-neutral BuildPlan v2, BYOK storage, pairing, bridge execution, history/refinement, Phase 8 website, or release configuration.

Phase 10 targets multiple Java Edition runtime identities. Phase 11 adds **Bedrock Edition as a first-class edition** of the same core — the same resolver, adapter registry, capability negotiation, limits, diagnostics, and execution gate — with a separate Bedrock bridge boundary. Phase 12 adds **legacy, beta, snapshot, and experimental Java targets as explicitly declared, uncertified contracts**: recognition without support, never a nearest-version fallback, and never an authorized execution until a real runtime test is recorded. See [Bedrock edition (Phase 11)](#bedrock-edition-phase-11) and [Legacy, beta, and experimental compatibility (Phase 12)](#legacy-beta-and-experimental-compatibility-phase-12).

The registry contains **one production profile plus two declared, uncertified legacy contracts and one Bedrock contract boundary**. The production profile is unchanged:

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

No production adapter is registered for other Minecraft or Java versions, snapshots, betas, or experimental Java targets. Legacy runtimes that this build explicitly declares (1.7.10/Forge and 1.12.2/Forge, see below) and Bedrock are registered **only as uncertified contract boundaries that can never authorize construction**. Unknown, incomplete, unsupported, and ambiguous identities fail closed. There is no nearest-version fallback, no cross-edition matching, no Java-version inference, no Fabric-through-another-loader route, no BuildPlan substitution, and no silent protocol downgrade.

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
- `BedrockRuntimeProfile` is the version-aware counterpart of the Java profile: edition, bridge protocol/version, supported platforms, `certifiedMinecraftVersions`, contract capabilities, required BuildPlan schema, declared limitations, limits, a block/state support revision, a block/state catalog, a status, and a `MinecraftRuntimeCertification` level (`NOT_PERFORMED`, `UNIT_TESTED`, `BRIDGE_TESTED`, `RUNTIME_TESTED`). Only `RUNTIME_TESTED` authorizes `SUPPORTED`.
- `MinecraftTargetBlockStateCatalog` is the version-aware block/state compatibility layer. A mapping can only prove that the *same* platform-neutral block ID exists with a declared state representation; mappings never change block identity, never substitute a block, and never guess a state value. Unknown block → `UNSUPPORTED_BLOCK`; unlisted property or value → `UNSUPPORTED_BLOCK_STATE`.

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

Legacy/experimental Java targets (Phase 12) reuse the Java column with these differences:

| Concern | Declared legacy Java contracts |
| --- | --- |
| Registry | `java-legacy-forge-1.7.10`, `java-legacy-forge-1.12.2` — declared, `EXPERIMENTAL`, `NOT_PERFORMED` |
| Release channel | explicitly `LEGACY` (never inferred from the version number) |
| Java requirement | Java `8` exactly, per release; Java 17 is rejected rather than assumed |
| Bridge | `CraftMind Legacy Bridge` interface contract `1.0.0-legacy`, protocol `2` — a declared boundary, not a deployed bridge |
| Block/state | app-side mapping with an empty, unverified catalog → every plan fails closed |
| Executable today | **never** (`EXPERIMENTAL`, `RUNTIME_NOT_CERTIFIED`, typed refusal before any bridge call) |

### Bedrock status in this build

**UNSUPPORTED in practice / architecture EXPERIMENTAL.** The Bedrock architecture is implemented and statically verified, but no Bedrock bridge exists in this repository, no Bedrock runtime test was performed, and no Bedrock version is certified. CraftMind therefore reports Bedrock as not currently supported and sends no BuildPlan. Any future claim of Bedrock support requires a real Bedrock runtime test recorded in the registry.

## Legacy, beta, and experimental compatibility (Phase 12)

Phase 12 extends the same core. It adds no second resolver, registry, version model, or execution engine: legacy targets are ordinary `SupportedMinecraftRuntimeDescriptor` profiles resolved by the same `MinecraftCompatibilityResolver`, exposed by the same `MinecraftAdapterRegistry`, and gated by the same execution rules. The core principle is unchanged and enforced in code: **recognizing an old, beta, snapshot, or experimental runtime is not the same as supporting it.** CraftMind prefers an honest `UNSUPPORTED`/`UNKNOWN` result over a fabricated compatibility claim.

### Explicit legacy modeling

- A legacy target exists only because a profile *declares* it. `MinecraftVersion.parse("1.7.10")` returns the `RELEASE` channel — an old release number is never auto-classified as legacy. `SupportedMinecraftRuntimeDescriptor.releaseChannel` is the explicit registry decision, and the registry rejects a declared channel that the identifier cannot carry (`LEGACY` may describe a release token such as `1.7.10`; `SNAPSHOT` may not).
- Two declared contracts ship in `LegacyRuntimeProfileRegistry`: `java-legacy-forge-1.7.10` (Forge `10.13.4.1614`, the final published 1.7.10 build) and `java-legacy-forge-1.12.2` (Forge `14.23.5.2859`, the recommended 1.12.2 build). Both declare Java 8 exactly, `EXPERIMENTAL` status, `NOT_PERFORMED` certification, an explicit limitation set, and an empty, unverified app-side block/state catalog.
- Their declared bridge identity `1.0.0-legacy` is an **interface contract version** that a future legacy bridge would have to report. It is not an assertion that a legacy bridge, a Forge mod, or a runtime integration exists — none does in this repository. A runtime reporting the production bridge `1.2.0`/protocol `2` is therefore not rerouted into a legacy contract; it fails closed with `BRIDGE_VERSION_MISMATCH`.
- Legacy contract identities are separate from the production profile (`java-fabric-1.20.1`), which stays exactly as specified above: 1.20.1, Java 17, Fabric `0.16.10`, Fabric API `0.92.2+1.20.1`, Bridge `1.2.0`, protocol `2`, schema `2`.

### Release channels

`MinecraftVersionChannel` (`RELEASE`, `PRE_RELEASE`, `SNAPSHOT`, `BETA`, `ALPHA`, `LEGACY`, `UNKNOWN`) is the single channel model; `MinecraftVersion` carries `major`/`minor`/`patch`, a pre-release qualifier, and the channel, and preserves the original safe identifier for exact matching. There is no ordering and no range comparison anywhere in resolution.

- A `SNAPSHOT`/`BETA`/`ALPHA`/`PRE_RELEASE` runtime that no registered profile covers resolves to `UNSUPPORTED` with `UNSUPPORTED_RELEASE_CHANNEL`; a classic/beta-era-format identifier (`rd-132211`, `inf-20100618`, `classic/0.0.14a`) resolves to `UNSUPPORTED` with `UNSUPPORTED_LEGACY_VERSION`. Both messages state that the runtime is never matched to a release build.
- Beta/snapshot/alpha runtimes are `EXPERIMENTAL` only when a registered contract matches them exactly, and registry validation forbids such a profile from claiming `SUPPORTED` without a supporting certification rung. No profile with a non-release channel can be `SUPPORTED` merely because it was registered.
- Malformed or unsafe version tokens become `UNKNOWN`, never a nearby release.

### Certification ladder

`MinecraftRuntimeCertification` is ordered and never collapsed: `NOT_PERFORMED`, `STATIC_ONLY`, `UNIT_TESTED`, `BRIDGE_TESTED`, `SIMULATED_E2E_VERIFIED`, `RUNTIME_TESTED`, `CERTIFIED`, ranked by an explicit `evidenceRank` rather than by declaration order. Only `RUNTIME_TESTED` and `CERTIFIED` authorize a `SUPPORTED` claim (`authorizesSupport`); Bedrock additionally requires `RUNTIME_TESTED` (`authorizesBedrockSupport`), so a release-process label can never stand in for a real Bedrock runtime test.

Phase 14 added the single rung `SIMULATED_E2E_VERIFIED` between `BRIDGE_TESTED` and `RUNTIME_TESTED`: a complete production-pipeline run against a controlled **simulated** bridge is genuine evidence about CraftMind's own wiring and is recorded as such. It is capped by `MAXIMUM_SIMULATED_LEVEL`, is `isSimulatedEvidence` and never `isRealRuntimeEvidence`, never authorizes support or Bedrock support, and cannot be upgraded to a runtime rung by any code path — only real-runtime evidence in a policy-declared environment can certify. See [`minecraft-certification-testing.md`](minecraft-certification-testing.md). Registry validation rejects a `SUPPORTED` profile whose certification does not support it, and the legacy adapter re-checks the same rule so a registry bypass cannot surface an uncertified runtime as `SUPPORTED`. Static analysis and unit tests are recorded as exactly what they are — not as runtime verification.

### Resolver behavior and structured reasons

The shared resolver keeps exact identity matching and adds channel-aware diagnostics and explicit legacy reasons:

| Reason code | When it is returned |
| --- | --- |
| `UNKNOWN_MINECRAFT_VERSION` | Version missing/unrecognized (never guessed, never matched by proximity) |
| `UNSUPPORTED_LEGACY_VERSION` | Legacy-format identifier with no registered profile |
| `UNSUPPORTED_RELEASE_CHANNEL` | Snapshot/beta/alpha/pre-release build with no registered profile for that channel |
| `UNSUPPORTED_MINECRAFT_VERSION` | Release identifier with no registered profile (for example `1.20.2` or an undeclared `1.7.10`) |
| `UNSUPPORTED_LOADER` | Loader not registered for that exact edition+version (Forge/NeoForge/Vanilla never borrow the Fabric adapter; old vanilla is never mapped to the Forge contract) |
| `UNSUPPORTED_LOADER_VERSION` | Exact loader version not registered |
| `INCOMPATIBLE_JAVA_RUNTIME` | Bridge-reported Java major outside the profile's declared range (Java 17 is never assumed to fit a Java-8 legacy release) |
| `BRIDGE_PROTOCOL_MISMATCH` / `BRIDGE_VERSION_MISMATCH` | No downgrade and no fallback: the bridge must match the profile exactly |
| `UNSUPPORTED_BUILDPLAN_SCHEMA` | Plan schema not accepted by the runtime/adapter, with no silent conversion |
| `PLAN_LIMIT_EXCEEDED` / `MISSING_CAPABILITY` | Shared limit/capability failures |
| `UNSUPPORTED_BLOCK` / `UNSUPPORTED_BLOCK_STATE` | Requested content has no verified representation on the target |
| `RUNTIME_NOT_CERTIFIED` | The runtime is recognized by a declared contract but has no recorded runtime verification |

`failureReasonCode()` maps these to stable UI/error codes (`UNSUPPORTED_LEGACY_VERSION`, `UNSUPPORTED_RELEASE_CHANNEL`, `RUNTIME_NOT_CERTIFIED`, `BUILD_PLAN_SCHEMA_UNSUPPORTED`, `LIMIT_EXCEEDED`, `UNSUPPORTED_BLOCK`, …) and keeps reporting a concrete plan-level cause before the generic "runtime not certified" fallback.

### Block/state handling

One fail-closed mapping layer serves every target family: `MinecraftTargetBlockStateCatalog` maps platform-neutral block IDs and state values onto a declared target representation and returns `Supported`, `UnsupportedBlock`, or `UnsupportedState`. Mappings must record how they were verified; unverified mappings are rejected at construction, duplicates are rejected, and a requested block or state value that is not declared is reported as unsupported. CraftMind never substitutes a block, a state value, a loader, or a Minecraft version. The production profile declares server-side content validation (`SERVER_SIDE_VALIDATION`) and keeps an empty, unused catalog; declared legacy contracts use `APP_SIDE_MAPPING` with an empty catalog, so every legacy plan fails closed until a verified mapping is recorded.

### Execution restrictions

- An `EXPERIMENTAL` or `UNKNOWN` result, an uncertified profile, missing capabilities, failed limits, or unrepresentable content all keep `MinecraftCompatibilityResult.canExecute == false`.
- The legacy adapter's `preflight`, `execute`, `cancel`, and `status` members throw a typed `MinecraftBridgeFailure("RUNTIME_NOT_CERTIFIED")` before any bridge call, so no BuildPlan can be forwarded to a legacy runtime. (Bedrock does the same with `BEDROCK_RUNTIME_NOT_CERTIFIED`.)
- Any future experimental execution path would still have to pass the unchanged gate: explicit warning, compatibility result, capability check, server preflight, and separate final user confirmation. There is no "try it anyway" route, and `BuildPlanLimits` are never loosened for legacy targets — runtime-reported limits are combined by taking the stricter value, and a missing or unusable reported limit fails closed.

### UI

Settings and Build Review show the bridge-reported edition, Minecraft version, **release channel**, Java/loader/Fabric API (Java) or platform facts (Bedrock), bridge and protocol, the recorded certification level, declared limitations, capabilities and gaps, structured reasons, and the effective limits. The compatibility status, release channel, certification, declared limitations, and the reason a build cannot run are always visible; raw adapter/capability detail is collapsible ("Show advanced compatibility details"). An unavailable build shows `Build unavailable` with the honest reason and "No BuildPlan will be sent for execution", and the confirmation/construction control stays disabled.

### Security

Legacy and experimental paths reuse every Phase 1–11 control: pinned TLS pairing, authenticated sessions, replay protection, bounded parsing of every reported field, capability negotiation from bridge reports only, execution IDs, single active build, server-side BuildPlan validation, cancellation semantics, and fail-closed error handling. A legacy runtime may declare integration limitations (`LEGACY_RUNTIME_NOT_VERIFIED`, `LEGACY_BRIDGE_INTERFACE_UNVERIFIED`, `BLOCK_STATE_MAPPING_NOT_VERIFIED`, no-rollback/no-resume/progress-is-bridge-reported, …) only when it is a Bedrock/Legacy edition, a non-release channel, or an exact declared legacy identity; a production release runtime that claims them is rejected as an invalid descriptor. The Bedrock wire codec accepts only the Bedrock limitation set, so a Bedrock payload cannot borrow legacy declarations. No legacy path introduces a shell, an arbitrary command endpoint, a protocol downgrade, screen scraping, input simulation, injection, patching, or any licensing/anti-cheat bypass.

## Automatic runtime detection and adapter selection (Phase 13)

Phase 13 makes the runtime target *detected* rather than configured, and makes adapter selection a deterministic
function of that detection. It adds no second runtime model, no second adapter registry, no second resolver, no
second version model, and no second block/state layer: the existing `MinecraftRuntimeDescriptor`,
`MinecraftAdapterRegistry`, `MinecraftAdapter` implementations, and `MinecraftCompatibilityResolver` are extended and
reused. The pipeline is strictly ordered:

```text
authenticated bridge → runtime descriptor → runtime detection → descriptor validation → adapter selection
  → compatibility resolution → session-bound eligibility → final pre-execution authorization
```

| Layer | Type | Responsibility |
| --- | --- | --- |
| Authoritative facts | `MinecraftRuntimeDescriptor` (+ `releaseChannel`, `hasReportedLimits`) | What the authenticated bridge reported |
| Detection | `MinecraftRuntimeDetector` → `MinecraftRuntimeDetectionResult` | Typed `DETECTED / INCOMPLETE / UNKNOWN / INVALID` + bounded diagnostics + `MinecraftRuntimeIdentity` |
| Validation | `MinecraftRuntimeDescriptorValidation` | One shared rule set used by both detection and resolution |
| Selection | `MinecraftAdapterSelector` → `CompatibilityAdapterSelection` | `SELECTED / NO_MATCH / AMBIGUOUS / INVALID`, exact matching only |
| Resolution | `MinecraftCompatibilityResolver` | Status, certification, capabilities, limits, content, `canExecute` |
| Binding + gate | `MinecraftRuntimeCompatibilityBinding`, `MinecraftRuntimeCompatibilityGate` | Session/runtime binding, reconnection, TOCTOU, final authorization |

### Detection input: only an authenticated report

The only input detection accepts is `AuthenticatedMinecraftRuntimeReport`, which carries the descriptor *plus* the
session facts that authorize detection (`authenticated`, `sessionId`, `bridgeId`, `identityFingerprint`,
`authenticatedAtEpochMillis`, the `requestedAppVersion` this client signed, the paired identity expectations, and the
measured report size). `BridgeCapabilitiesSnapshot.runtimeReport(...)` and
`BridgeConnectionState.Connected.runtimeReport(...)` build it, so a caller cannot ask for detection without an
authenticated session, and an unauthenticated report is refused with `RUNTIME_DETECTION_UNAUTHORIZED` before any
descriptor field is interpreted. Nothing is read from a launcher name, an executable, an APK setting, a package
name, a filename, a port, a user-typed version, a UI selection, a previous connection, or a saved preference.

### Edition, version, channel, loader, and Java runtime

- **Edition** is `JAVA`, `BEDROCK`, `LEGACY`, or `UNKNOWN` from the bridge report. A legacy Java runtime is reported
  as `JAVA` plus the `LEGACY` release channel — never as a third Minecraft edition. Bedrock is reported as `BEDROCK`
  with the `BEDROCK_NATIVE` runtime and no JVM, loader, or Fabric API facts. Editions never cross-report.
- **Edition/loader coherence** is enforced in both directions: `JAVA` requires `FABRIC`/`FORGE`/`NEOFORGE`/`VANILLA`,
  `BEDROCK` requires `BEDROCK_NATIVE`, and every other combination (`BEDROCK` + `FABRIC`, `JAVA` + `BEDROCK_NATIVE`)
  is `INVALID_RUNTIME_DESCRIPTOR`. CraftMind never auto-corrects an incoherent pair.
- **Version** is the exact reported identifier: `1.20.1`, `1.19.4`, `1.12.2`, `1.7.10`, snapshots (`24w14a`), betas
  (`b1.7.3`), alphas (`a1.2.6`), pre-releases (`1.21-pre1`), legacy tokens (`c0.30_01`), or `unknown`. There is no
  nearest-version fallback — `1.20.2` is never treated as `1.20.1` — and an unrecognized identifier stays `UNKNOWN`.
- **Release channel** is `RELEASE | PRE_RELEASE | SNAPSHOT | BETA | ALPHA | LEGACY | UNKNOWN`, securely derived from
  the authoritative identifier (`MinecraftRuntimeDescriptor.releaseChannel`). A registered profile's explicit
  declaration may reclassify an exact identity as `LEGACY` (that is how `1.7.10` is a legacy target), and the shared
  `releaseChannelCoherent` rule — used by both registration and detection — rejects a declaration that contradicts
  the identifier (a `RELEASE` claim on a snapshot/beta/alpha token). Contradictions invalidate the descriptor; they
  are never silently corrected.
- **Loader and loader version** come from the report. A missing loader is `UNKNOWN`; a missing loader version,
  Fabric API version (for Fabric), or Java runtime keeps the runtime `INCOMPLETE` and blocks execution.
- **Java runtime validation** stays per registered profile (`JavaRuntimeRequirement`): `1.20.1` requires Java 17
  exactly; `1.7.10`/`1.12.2` Forge require Java 8. A mismatch yields `INCOMPATIBLE_JAVA_RUNTIME` and no execution.

### Bridge, protocol, app version, capabilities, and limits

- `bridgeProtocolVersion` must equal `BridgeProtocol.VERSION` (2) and `bridgeVersion` must be a safe token that a
  registered profile/contract declares. A correct Minecraft version with an incompatible bridge still fails
  (`BRIDGE_PROTOCOL_MISMATCH` / `BRIDGE_VERSION_MISMATCH`); there is no protocol downgrade.
- The application-version echo is verified again at detection time: a descriptor whose `appVersion` differs from the
  version this client signed into the request yields `APP_VERSION_MISMATCH` and fails closed. The wire codecs keep
  their Phase 10/11 behaviour and reject the response first with `BRIDGE_RESPONSE_MISMATCH`.
- Capabilities are validated **only** from the authenticated bridge report. A report whose capability set contradicts
  its own runtime facts (`worldAvailable` without `WORLD_ACCESS`, an origin claim without `ORIGIN_RESOLUTION`) or that
  contains a capability this build does not define is rejected as forged (`INVALID_RUNTIME_DESCRIPTOR`). A bridge
  that claims `BUILD_EXECUTION` for a runtime whose matched profile does not authorize execution still gets
  `canExecute = false`, and the gate surfaces that as an explicit capability warning.
- Effective limits remain `min(CraftMind global limit, runtime-reported limit)` through the shared
  `MinecraftCompatibilityLimitsEvaluation`. A missing required limit makes detection `INCOMPLETE` and keeps execution
  blocked; a runtime claiming "unlimited" can never bypass `BuildPlanLimits` or the protocol ceilings.

### Deterministic adapter selection

`MinecraftAdapterSelector` reads the existing registry and returns a typed `CompatibilityAdapterSelection`:

- `SELECTED` — exactly one registered adapter matches this runtime exactly, with `matchKind`
  (`VERSION_KEYED_PROFILE` or `BEDROCK_CONTRACT`), the matched profile/contract, and the candidate list.
- `NO_MATCH` — no adapter matches; identity-level matches that fail only on Java runtime or Fabric API keep their
  specific reason codes, and everything else is diagnosed by the resolver (unsupported version, loader, loader
  version, channel, bridge, or protocol). No nearest adapter is ever chosen.
- `AMBIGUOUS` — two different adapters claim the same exact runtime. Selection is blocked (`AMBIGUOUS_ADAPTER_PROFILE`
  / `AMBIGUOUS_ADAPTER_MATCH`) instead of resolved by registration order; candidates are always ordered by adapter ID.
- `INVALID` — selection may not run at all: the runtime was not `DETECTED`, or it is not bound to an authenticated
  session.

Selection happens only after secure pairing, an authenticated session, protocol validation, descriptor validation,
app-version validation, and capability validation. The registry continues to refuse overlapping registrations at
startup (duplicate adapter IDs, duplicate/overlapping runtime identities, mixed Java/Bedrock families, invalid
profiles), and Phase 13 adds one more rule: a version-keyed profile may not declare the Bedrock edition, because
Bedrock identities are contract-keyed — this removes a cross-family ambiguity source structurally.

**Selection is not execution.** A selected adapter can still resolve to `EXPERIMENTAL`/`UNSUPPORTED`,
`RUNTIME_NOT_CERTIFIED`, missing capabilities, failed limits, or unrepresentable content, all of which keep
`canExecute == false`.

### Session binding, reconnection, runtime change, and TOCTOU

`MinecraftRuntimeIdentity` binds compatibility to the authenticated session (`bridgeId`, `identityFingerprint`,
`sessionId`, `authenticatedAtEpochMillis`) and to the exact runtime (`runtimeKey`: edition, version, channel, loader,
loader version, Java runtime, platform, bridge version, protocol). `MinecraftRuntimeCompatibilityBinding` records
identity + descriptor + detection + selection + compatibility + resolution time, and
`MinecraftRuntimeCompatibilityGate.authorizeExecution(...)` is the only path that authorizes a build:

- It always re-runs detection, validation, selection, and resolution against the report that is authenticated *now*;
  a cached or displayed result authorizes nothing.
- It compares the fresh identity with the binding compatibility was resolved for and aborts on
  `SESSION_IDENTITY_MISMATCH` (session/bridge identity), `RUNTIME_IDENTITY_CHANGED` (Minecraft version, loader, Java
  runtime, bridge, protocol, or selected adapter), or `WORLD_SESSION_CHANGED` (prepared work invalidated).
- Reconnecting re-runs the whole pipeline (`AndroidMinecraftBridgePairingRepository.bindRuntime` on authenticate and
  on every capability refresh), and a changed runtime clears every prepared execution binding, so switching
  `1.20.1 → 1.19.4` (or switching worlds) invalidates prior eligibility instead of reusing it.
- `prepareExecution` records the binding for that execution ID and `startExecution` re-authorizes against it, which
  closes the time-of-check/time-of-use gap between preflight and execution. Bindings are memory-only, are dropped
  when an execution starts or is cancelled, and are cleared with the session.

### Connection and pipeline states

The existing `BridgeConnectionState` (Disconnected / Connecting / Connected / Error) remains the single connection
state machine; Phase 13 only adds the authenticated `sessionId` to `Connected`. The runtime pipeline has its own
explicit phase — `MinecraftRuntimePipelinePhase`: `DISCONNECTED, CONNECTING, AUTHENTICATING, DETECTING_RUNTIME,
VALIDATING_RUNTIME, SELECTING_ADAPTER, RESOLVING_COMPATIBILITY, READY, INCOMPATIBLE, UNKNOWN, ERROR` — which records
where the pipeline stopped and is shown in Settings and Build Review. No duplicate connection state was introduced.

### UI

Minecraft Settings shows a **Minecraft Runtime** section: the detected edition, Minecraft version, release channel,
loader (+ Fabric API) or Bedrock platform, Java runtime, bridge version and protocol, compatibility status,
certification, the selected adapter and selection status, the pipeline phase, declared limitations, structured
reasons, and the session/runtime binding under "Show advanced compatibility details". Build Review shows
**"Target Runtime: Detected automatically"** with the same facts, and an unavailable build shows `Build unavailable`
plus "No BuildPlan will be sent for execution" while the final confirmation stays disabled. There is no control
anywhere for choosing an edition, Minecraft version, loader, or adapter, and no manual execution override: the only
interactive element is the display toggle for technical detail.

### Bridge protocol impact

No wire change was required. Detection uses only fields that protocol 2 already mandates and the codecs already
validate (`clientAppVersion`, `bridgeProtocolVersion`, `bridgeVersion`, `edition`, `minecraftVersion`,
`javaRuntimeMajor`, `loader`, `loaderVersion`, `fabricApiVersion`, `capabilities`, `supportedBuildPlanSchemaVersions`,
the four limits, `worldAccess`, `dimensionId`, `worldSessionId`, Bedrock `platform`/`platformVersion`,
`limitations`). Protocol 2 stays at version 2, the Fabric bridge sources are unchanged, and no downgrade path exists.

### Preserved regressions

- **Bedrock** stays `EXPERIMENTAL` + `NOT_PERFORMED` with an empty certified-version set and an empty block/state
  catalog; detection identifies a Bedrock runtime but never certifies it, and execution stays disabled.
- **Legacy** runtimes are still recognized exactly (`1.7.10` Forge `10.13.4.1614`, `1.12.2` Forge `14.23.5.2859` on
  Java 8 with the legacy bridge contract), resolve to `EXPERIMENTAL` with `RUNTIME_NOT_CERTIFIED`, keep
  `canExecute == false`, and their adapter still throws a typed refusal before any bridge call.
- The **production Java profile** is unchanged: `1.20.1`, Java 17, Fabric Loader `0.16.10`, Fabric API
  `0.92.2+1.20.1`, Bridge `1.2.0`, protocol 2, BuildPlan schema 2.
- Every Phase 1–12 security control remains in force; automatic detection adds checks and never relaxes one.

## Universal testing and certification (Phase 14)

Phase 14 does not change the compatibility core: no protocol field, resolver rule, adapter, registry entry, limit
evaluation, block/state catalog, or execution gate was modified. It adds a certification layer **on top** of Phases
10–13 in `domain/minecraft/certification/`, so that every compatibility claim can be traced to explicit evidence and
nothing can be certified without one.

- **Layers.** Seven verification categories (`STATIC`, `UNIT`, `PROTOCOL`, `SIMULATED_INTEGRATION`,
  `REAL_RUNTIME_INTEGRATION`, `END_TO_END_EXECUTION`, `CERTIFICATION_DECISION`) with six explicit outcomes (`PASSED`,
  `FAILED`, `NOT_RUN`, `NOT_AVAILABLE`, `NOT_PERFORMED`, `BLOCKED`). A skipped check is never a pass.
- **Evidence.** `MinecraftCertificationEvidence` records the full runtime identity, bridge/protocol/schema/catalog
  versions, suite and run IDs, environment, a timestamp only when a run happened, level, mode, per-category outcomes,
  execution evidence, limitations, and whether a real runtime test occurred. `MinecraftCertificationSanitizer` rejects
  (never redacts) secrets, hosts, IPs, keystores, and fingerprints, so certification artifacts are safe to commit.
- **Decisions.** `MinecraftCertificationRuleEngine` is the only decision maker and returns `CERTIFIED`,
  `NOT_CERTIFIED`, `INSUFFICIENT_EVIDENCE`, `BLOCKED_BY_LIMITATION`, or `FAILED` deterministically. `MinecraftCertificationPolicy.DEFAULT`
  declares **no** real-runtime capable environment, so no new certification can be minted in this repository; the
  shipped Java 1.20.1 production record is reported separately, with `evidenceMode = NONE` and
  `source = SHIPPED_PRODUCTION_RECORD`, and is neither downgraded nor extended with invented runtime evidence.
- **Matrix.** `MinecraftCompatibilityCertificationMatrix` holds 35 runtime rows (Java production, Forge 1.7.10 and
  1.12.2, the Bedrock contract, 19 unsupported/invalid cases, 11 security cases) and 15 execution failure cases, each
  declaring the layers that apply. `CertificationMatrixTest` executes every row through production code and asserts
  detection status, selection status, stable reason code, adapter, executability, and certification decision, so a row
  that stops being true fails the build instead of going stale.
- **Simulated end-to-end.** `SimulatedCertificationPipeline` drives a protocol-faithful simulated bridge through
  authentication, capability exchange, app-version echo, detection, descriptor validation, adapter selection,
  compatibility resolution, BuildPlan validation, preflight, execution authorization, execution, progress,
  cancellation, and completion — all production code, no bypassed layer. It proves wiring only; its evidence is capped
  at `SIMULATED_E2E_VERIFIED` and its decision is `NOT_CERTIFIED`.
- **Execution depth.** `REQUEST_ACCEPTED`, `PRECHECK_PASSED`, `EXECUTION_STARTED`, `BLOCKS_WRITTEN`,
  `PROGRESS_REPORTED`, `EXECUTION_COMPLETED`, `EXECUTION_CANCELLED`, `EXECUTION_FAILED`; the world-touching stages are
  marked `requiresRealWorld`, so a simulated read-back is always labelled simulated and a cancelled run writes at most
  a bounded prefix.
- **Failure injection and security.** 15 documented failure cases each assert the documented reason code, the refusing
  stage, and that nothing was written before the validation that refused it; `CertificationSecurityRegressionTest`
  re-asserts pinned HTTPS pairing, mandatory authentication, replay and staleness refusal, session and identity
  binding, bridge-reported capabilities only, protocol/schema downgrade refusal, `min(global, runtime)` limits a client
  can never raise, server-side block/state validation, the absence of any command/shell/credential surface, and the
  absence of secrets or identity material in evidence and reports.
- **Real-runtime hook.** `MinecraftRealRuntimeCertificationHarness` performs 17 steps against a
  `MinecraftRealRuntimeHost`. The shipped `NoRealRuntimeHostProvider` supplies none — no download, no network, no fake
  server called real — so every step is `NOT_PERFORMED`, the outcome is `RUNTIME_TEST_NOT_PERFORMED`, and nothing is
  certified. A host that claims to be real still cannot certify unless the policy declares its environment.
- **Certification BuildPlan.** A deterministic three-placement probe inside 3×1×3 bounds, `PLACE_BLOCK` only, schema 2,
  validated by the same `DefaultBuildPlanValidator`, `MinecraftBlockCatalog`, and bridge contract validator as a user
  build. Production validation was not weakened for it, and the suite asserts that the validator still rejects
  unsupported blocks, invalid states, empty operations, invalid and duplicate coordinates, unsupported schemas, and
  oversized operation lists.
- **UI.** The existing compatibility block gains three lines — certification state, evidence state, and why execution
  is allowed or blocked — plus per-category verification detail in the existing advanced panel. Labels are literal and
  bounded; no combination of inputs can produce "fully compatible", and only a support-authorizing certification can
  produce "Certified production target". There is no redesign and no new screen.

Statuses in this build: **Java 1.20.1** `SUPPORTED` / `CERTIFIED` (shipped production record, no new runtime evidence);
**Bedrock** `EXPERIMENTAL` / `NOT_PERFORMED` / not certified, execution disabled; **legacy Forge 1.7.10 and 1.12.2**
`EXPERIMENTAL` / `NOT_PERFORMED` / not certified, execution disabled; **everything else** unsupported or unavailable
with a typed reason. `REAL_RUNTIME_TESTS = NOT_PERFORMED`.

## Verification status

Phase 14 adds the certification suite (`SimulatedEndToEndCertificationTest`, `CertificationMatrixTest`,
`CertificationFailureInjectionTest`, `CertificationSecurityRegressionTest`, `CertificationRuleEngineTest`,
`CertificationBuildPlanTest`, `RealRuntimeCertificationHarnessTest`) plus certification-wording tests in
`MinecraftRuntimeCompatibilityTextTest`; [`minecraft-certification-testing.md`](minecraft-certification-testing.md)
describes each layer and how to reproduce it.

Phase 13 adds runtime-detection tests (edition/loader coherence, exact version and channel handling, Java-runtime
validation, bridge/protocol and app-version echo, missing limits, unauthenticated/unbound/oversized/forged reports,
determinism, runtime-key change detection), adapter-selection tests (production/legacy/Bedrock selection, no-match
without a nearest adapter, specific Java/Fabric API reasons, blocked selection, ambiguity, registration-order
independence, the registry's version-keyed-Bedrock rule), gate tests (session binding, runtime/session/adapter/world
change invalidation, reconnection, fresh re-checks, TOCTOU, plan-level fail-closed authorization, typed refusals
before any bridge call), and UI-text tests for the detected-runtime wording.

The repository contains focused source tests for version and runtime parsing, exact profile registration/resolution, Java and Fabric API mismatch reasons, bridge-reported capability negotiation, BuildPlan/schema/limit rejection, malformed and oversized protocol input, structured block/state validation details, and the paired-but-unsupported execution regression. Phase 11 added edition/version dispatch, Bedrock version validity/unknown handling, Bedrock adapter registration (duplicate ID, duplicate profile, cross-adapter overlap, ID mismatch, invalid profiles, mixed Java/Bedrock families), Bedrock resolver outcomes, block/state mapping with no silent substitution, Bedrock fail-closed security cases, and edition-aware UI text. Phase 12 adds release-channel and legacy-identifier parsing, explicit legacy contract declarations, channel-aware resolver reasons, the certification ladder and its registry rules, Java-runtime rejection for legacy releases, loader-family separation, legacy block/state fail-closed behavior, BuildPlan schema/limit/content rejection for legacy runtimes, shared-descriptor/capability security cases, the typed legacy refusal before any bridge call, and the legacy UI text.

**Test source is not evidence that tests ran.** Verification below is separated by category, and no category is claimed beyond what was actually executed. The Gradle build still cannot run in this sandbox — there is no Android SDK/AGP/Gradle distribution and no Maven Central/Google egress — so `./gradlew test`, `lint`, `assembleDebug`, `connectedDebugAndroidTest`, and the Fabric mod build were **not** executed. Tools were reconstructed outside Gradle again for Phase 13 (a JDK 17 runtime, JDK 8 `javac` from a packaged `tools.jar` plus a synthetic Java-8 platform jar derived from the JDK 17 runtime image with post-Java-8 class attributes removed, Kotlin `2.1.10` with the matching serialization compiler plugin, kotlinx-serialization `1.8.0` core+json compiled from source with `-Xfragments`, gson `2.10.1` and junit `4.13.2` compiled from source, hamcrest-core `1.3` from the junit checkout). All of that tooling lives outside the repository; nothing generated by it is committed.

- **Static/source level**: `scripts/check_website.py` → PASS; `scripts/check_release_config.py` → PASS (3 checks) plus a NOTE that static checks are not builds.
- **Compilation**: 102 Kotlin sources (60 main + 42 test, including the certification fixtures) compiled with 0 errors → 756 JVM classes (564 main + 192 test); Phase 13 baseline with the same harness was 86 sources → 622 classes; `bridge-protocol` main sources compiled with JDK 8 `javac` → 34 classes. The six edited Android-only files (`AndroidMinecraftBridgePairingRepository`, `BridgePairingViewModel`, `BuildExecutionViewModel`, `PlanReviewScreen`, `MinecraftBridgeSettingsContent`, `MinecraftRuntimeCompatibility`) were reviewed and parse-checked but **not** compiled (no AGP/AndroidX/Compose/OkHttp artifacts in the sandbox).
- **Unit tests (Android-free Kotlin subset)**: **292 tests across 37 classes → 288 passed, 4 failed** (Phase 13 baseline with the same harness: 230 across 30 → 226 passed, same 4 failures; Phase 12 baseline: 185 → same 4 failures). The new certification suite is `OK (57 tests)` across 7 classes, and the compatibility core is green and grew with the wording tests: `OK (113 tests)` across the seven detection/selection/gate/legacy/Bedrock/UI-text classes, including `MinecraftRuntimeCompatibilityTextTest` `OK (16 tests)`. The 4 failures (`AiBuildEngineTest` ×2, `BuildPlanContextSerializerTest`, `BuildDiffTest`) are pre-existing, unrelated to Phases 10–14, and were neither deleted, skipped, nor weakened; one pre-existing ladder test was updated for the single new rung with four assertions added and none removed.
- **Bridge tests (Java, `bridge-protocol`)**: `BridgeCryptoTest`, `BridgeNetworkAddressPolicyTest`, `BridgeProtocolCodecTest`, `BuildPlanContractValidatorTest` → `OK (14 tests)`. `BuildPlanContractValidatorTest` uses a Java text block, which the sandbox's JDK 8 `javac` cannot parse, so that one test source was mechanically converted to an equivalent string concatenation **in a temporary copy** (`/tmp`, repository file untouched) before compiling.
- **Android app build / APK / `connectedAndroidTest` / instrumentation**: not run (no SDK/AGP). The one Android-only file Phase 14 edited (`MinecraftRuntimeCompatibility.kt`) was reviewed and parse-checked against its unmodified original with an identical kotlinc invocation; the error classes are identical (unresolved `androidx`/Compose symbols only), and **no Android compilation is claimed** — no APK, deployment, or release artifact is claimed anywhere.
- **Fabric mod build / real Java runtime test**: not run (no Gradle/Maven egress, no Minecraft runtime in this environment).
- **Bedrock bridge / real Bedrock runtime test**: not performed; no Bedrock bridge implementation exists in this repository.
- **Legacy/experimental bridge or real Minecraft legacy runtime test**: not performed; no legacy bridge, Forge mod, or legacy runtime integration exists in this repository.
- **Certification**: `REAL_RUNTIME_TESTS = NOT_PERFORMED`. No real Java, Bedrock, or legacy Minecraft runtime test happened in Phase 14, and none is claimed. The production Java profile remains recorded as CraftMind's shipped production target (`CERTIFIED`, `evidenceMode = NONE`, `source = SHIPPED_PRODUCTION_RECORD`), explicitly separated from newly established evidence; legacy contracts and Bedrock remain `NOT_PERFORMED` and not certified, with execution disabled. New Phase 14 evidence is simulated only: level `SIMULATED_E2E_VERIFIED`, mode `SIMULATED`, decision `NOT_CERTIFIED`. The shipped certification policy declares no real-runtime capable environment, so nothing in this checkout can mint a new certification. Static analysis, unit tests, simulated pipeline runs, adapter existence, resolver matches, BuildPlan validation, and syntactically valid capability reports are never reported as runtime certification.

Before claiming verification, check Java/JDK and Android SDK availability and run the focused Gradle tests plus the relevant app/bridge build tasks. Do not claim an Android compile, Fabric compile, APK, bridge test, real Minecraft test, deployment, or compatibility certification unless that exact check succeeds. Verification results belong in the completion summary as compiled, unit-tested, bridge-tested, real-Minecraft-tested, blocked, or unverified.
