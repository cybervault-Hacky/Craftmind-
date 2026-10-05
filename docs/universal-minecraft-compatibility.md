# Multi-Version Java Edition Compatibility (Phase 10)

## Scope and support policy

Phase 10 extends CraftMind's existing compatibility core and secure construction route. It does not replace the Android/Kotlin/Compose app, provider-backed AI, platform-neutral BuildPlan v2, BYOK storage, pairing, bridge execution, history/refinement, Phase 8 website, or release configuration.

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

No production adapter is registered for other Minecraft or Java versions, Bedrock, Legacy, Forge, NeoForge, Vanilla, snapshots, betas, or experimental targets. Unknown, incomplete, unsupported, and ambiguous identities fail closed. There is no nearest-version fallback, Java-version inference, Fabric-through-another-loader route, BuildPlan substitution, or silent protocol downgrade.

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

## Verification status

The repository contains focused source tests for version and runtime parsing, exact profile registration/resolution, Java and Fabric API mismatch reasons, bridge-reported capability negotiation, BuildPlan/schema/limit rejection, malformed and oversized protocol input, structured block/state validation details, and the paired-but-unsupported execution regression. **Test source is not evidence that tests ran.**

Before claiming verification, check Java/JDK and Android SDK availability and run the focused Gradle tests plus the relevant app/bridge build tasks. Do not claim an Android compile, Fabric compile, APK, bridge test, real Minecraft test, deployment, or compatibility certification unless that exact check succeeds. Verification results belong in the completion summary as compiled, unit-tested, bridge-tested, real-Minecraft-tested, blocked, or unverified.
