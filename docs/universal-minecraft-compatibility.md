# Universal Minecraft compatibility core

## Scope and current status

The compatibility core is a platform-independent domain layer used by the existing Android/bridge flow. It does not replace the AI BuildPlan pipeline, the secure bridge protocol, or the server-side construction coordinator. It chooses no runtime by proximity and performs no plan translation between editions or loaders.

The production registry currently contains one adapter only:

| Runtime field | Exact registered profile |
| --- | --- |
| Edition | Java Edition (inferred from the known Fabric loader family; protocol v1 has no edition field) |
| Minecraft | `1.20.1` |
| Loader | Fabric `0.16.10` |
| Fabric API | `0.92.2+1.20.1` (existing mod dependency) |
| CraftMind Bridge | `1.1.0` |
| Bridge protocol | `1` |
| BuildPlan schema | `2` |
| Java | Java 17 compile/toolchain target; protocol v1 does **not** report the running server JVM |

These are the registered/configured target values. `SUPPORTED` means the authenticated descriptor exactly matches a production adapter profile and the requested capabilities/limits are satisfied; it is not a claim that a successful Minecraft runtime or release build was exercised. Other Java versions, Bedrock, Legacy, Forge, NeoForge, Vanilla, snapshots, betas, and experimental profiles have no production adapter. The app may still pair with a bridge whose safe runtime metadata does not match, so it can report `UNSUPPORTED` or `UNKNOWN` without treating pairing as construction permission.

## Domain types

- `MinecraftEdition`: Java, Bedrock, Legacy, or Unknown.
- `MinecraftVersion`: safe bounded identifier plus optional numeric components, qualifier, and channel (`RELEASE`, `PRE_RELEASE`, `SNAPSHOT`, `BETA`, `ALPHA`, `LEGACY`, `UNKNOWN`). It has no ordering operation. An adapter profile uses exact identifier equality; `1.20.2` is not treated as a compatible fallback for `1.20.1`.
- `MinecraftLoader`: Fabric, Forge, NeoForge, Vanilla, Bedrock Native, or Unknown. Protocol-v1 edition inference recognizes only known JVM loader families; an unknown loader remains Unknown.
- `MinecraftRuntimeDescriptor`: typed runtime/bridge fields, runtime-reported capability flags, schema versions, limits, and world/origin availability. It carries no provider credentials, pairing codes, private keys, or execution tokens.
- `MinecraftCapability`: explicit capabilities such as build execution, block placement/state support, world validation/access, operator-origin resolution, bounded batching, progress/status, cancellation, BuildPlan v2, large builds, and multiple worlds. Only abilities implemented and substantiated by the adapter or authenticated bridge are made available; the current adapter does not claim large-build or multi-world support.
- `BuildPlanRequirements`: derived from an existing BuildPlan in memory; schema and persisted BuildPlan shapes are unchanged. Every construction requires the base safe execution capabilities, and validated block-state support is additionally required only when the plan uses block-state properties. Operation count, dimensions, schema, and serialized byte limits are checked independently.

Unknown enum/version inputs deserialize to safe `UNKNOWN` values. Bridge report parsing retains the existing exact protocol-v1 key set and identity checks, validates bounded identifiers and numeric limits, and no longer rejects a validly structured report merely because its runtime profile is not supported. A malformed identity, report structure, TLS pin, envelope, or authenticated session still fails closed.

## Resolver and registry contract

`MinecraftAdapterRegistry` rejects duplicate adapter IDs and overlapping exact runtime profiles. `MinecraftCompatibilityResolver` evaluates only registered `supportedRuntimeDescriptors`; there is no default adapter, first-match fallback, semantic-version range, downgrade, or conversion.

A compatibility result contains:

- `SUPPORTED`, `EXPERIMENTAL`, `UNSUPPORTED`, or `UNKNOWN`;
- the selected adapter ID, when exactly one adapter profile matches;
- available and missing capabilities;
- reasons and warnings;
- effective operation/request/dimension limits and Java toolchain metadata;
- whether this plan is within the selected limits and whether it can proceed.

A missing authenticated runtime capability can leave the runtime status `SUPPORTED` while making `canExecute` false. An experimental result can never authorize construction. A known Bedrock/Legacy/other Java runtime with no registered adapter is unsupported; incomplete or unrecognized metadata is unknown. Multiple adapter claims are treated as ambiguous and blocked.

## Adapter boundary and secure execution

`MinecraftAdapter` defines adapter identity, exact supported runtime descriptors, adapter capabilities, a runtime compatibility check, preflight, execute, cancel, and status operations. `JavaFabric1201Adapter` is the only production implementation. Its four operations delegate to `MinecraftBridgePairingRepository`; it adds no network route, protocol message, command execution, placement implementation, or alternate transport.

The existing bridge remains responsible for TLS pin verification, one-time pairing, Keystore-held P-256 proof-of-possession, signed/replay-protected sessions, strict private IPv4 policy, server-issued preflight tokens, independent BuildPlan validation, world checks, bounded placement, cancellation, and bridge-reported status. Protocol and BuildPlan schema numbers remain unchanged. Provider keys stay in their existing Android Keystore-backed path and are excluded from descriptors and bridge payloads.

Construction requires all of the following, in order:

1. A trusted, pinned bridge session authenticates successfully.
2. The resolver returns `SUPPORTED` for the exact runtime and saved immutable BuildPlan; all capability and limit checks pass.
3. The existing authenticated server preflight accepts the request and returns a current preview/token without placing blocks.
4. The user explicitly confirms the preview. The app never starts placement automatically.

Status reads remain read-only. If a runtime no longer matches an adapter, the app may use the existing authenticated status endpoint to observe bridge truth, but that does not select an adapter or authorize construction.

## Extension rules

A future adapter must be real code for a specific runtime and must be registered only after its runtime profile, actual capabilities, BuildPlan requirements, secure transport mapping, preflight/execute/cancel/status behavior, and focused tests are present. Do not register placeholder Bedrock/Legacy/Forge/NeoForge adapters, route another edition through Fabric, or silently convert plans. The AI continues to produce the same platform-neutral BuildPlan; no manual block/coordinate editor or arbitrary Minecraft command surface is part of this boundary.

## Verification boundary

Focused source tests cover safe version and enum parsing/serialization, protocol-v1 descriptor mapping, requirements, duplicate registry handling, exact-match and unsupported/unknown/experimental resolution, capability/limit gaps, and the paired-but-unsupported execution regression. Test source is not evidence that these checks ran. This checkout has no Java/JDK or Android SDK, so Kotlin/Android tests, builds, lint, Fabric compilation, and live Minecraft behavior remain unverified until the documented Gradle commands can run in a suitable environment.
