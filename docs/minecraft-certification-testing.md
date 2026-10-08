# Minecraft Certification and Testing (Phase 14)

This document is the authoritative description of **what CraftMind claims about a Minecraft runtime, what evidence
backs each claim, and how that evidence is produced and reproduced**. It exists because "compatible" and "certified"
are different statements, and because a test suite that cannot tell them apart eventually certifies nothing.

Related documents: [`universal-minecraft-compatibility.md`](universal-minecraft-compatibility.md) (the compatibility
core, Phases 10–13) and the repository [`README.md`](../README.md) (release and verification status).

---

## 1. Supported versus certified

| Statement | Meaning | What it requires |
| --- | --- | --- |
| **Recognized** | An authenticated bridge reported facts CraftMind can parse into a runtime descriptor. | Nothing but a well-formed report. |
| **Supported** | A registered profile exactly matches the detected runtime, so compatibility resolves to `SUPPORTED`. | Exact identity match, capabilities, limits, schema, content validation. |
| **Certified** | A real Minecraft runtime was tested end-to-end and the result was recorded, or the project carries an explicit shipped certification record for that exact target. | Real-runtime evidence **or** an explicit shipped record, evaluated by the certification engine. |
| **Not certified** | Something is missing, failed, or was never performed. | — |

Selection is not executability, support is not certification, and a passing test is not a runtime test. Nothing in
CraftMind infers certification from the existence of an adapter, a resolver match, a unit test, a fake or mock bridge, a
validated BuildPlan, a syntactically valid capability report, or theoretical version compatibility.

## 2. The seven verification layers

Every claim in this repository is attributed to exactly one layer. `MinecraftVerificationCategory` is the single model:

| Layer | Enum | What it proves | Executed here? |
| --- | --- | --- | --- |
| Static | `STATIC` | Source-level review, structural constraints, no forbidden surface. | Yes (source review + `scripts/check_*.py`). |
| Unit | `UNIT` | JVM unit tests of domain logic. | Yes — 292 tests, 37 classes. |
| Protocol | `PROTOCOL` | Envelope, wire codecs, contract validator, bounded sizes, exact key sets. | Yes — including 14 `bridge-protocol` Java tests. |
| Simulated integration | `SIMULATED_INTEGRATION` | The whole production pipeline against a controlled **simulated** bridge. | Yes — `SimulatedCertificationPipeline`. |
| Real-runtime integration | `REAL_RUNTIME_INTEGRATION` | The same pipeline against a **real** Minecraft runtime. | **Not performed** — no runtime, no network, no download. |
| End-to-end execution | `END_TO_END_EXECUTION` | Actual placement, progress, cancellation, completion, reconnect. | Simulated only; labelled `SIMULATED`. |
| Certification decision | `CERTIFICATION_DECISION` | The centralized engine's decision and its reasons. | Yes — `MinecraftCertificationRuleEngine`. |

Outcomes are kept apart explicitly (`MinecraftVerificationOutcome`): `PASSED`, `FAILED`, `NOT_RUN`, `NOT_AVAILABLE`,
`NOT_PERFORMED`, `BLOCKED`. A skipped check is never recorded as a pass.

### Evidence ladder

`MinecraftRuntimeCertification` is the single ordered ladder (no competing model exists):

```
NOT_PERFORMED (0) → STATIC_ONLY (1) → UNIT_TESTED (2) → BRIDGE_TESTED (3)
  → SIMULATED_E2E_VERIFIED (4) → RUNTIME_TESTED (5) → CERTIFIED (6)
```

Phase 14 added exactly one rung, `SIMULATED_E2E_VERIFIED`, between `BRIDGE_TESTED` and `RUNTIME_TESTED`: a complete
pipeline run against a simulated bridge is genuine evidence about CraftMind's own wiring and deserves to be recorded as
such. It is capped by `MAXIMUM_SIMULATED_LEVEL`, never `authorizesSupport`, never `authorizesBedrockSupport`, and can
never be upgraded to `RUNTIME_TESTED` or `CERTIFIED`. Ordering is by explicit `evidenceRank`, not declaration accident,
and a higher rung never auto-certifies: only the decision engine certifies.

## 3. Structured evidence record

`MinecraftCertificationEvidence` is the only evidence type. One record per profile per run, containing: profile ID,
edition, Minecraft version, release channel, loader and loader version, server Java runtime major, Fabric API version,
bridge version, bridge protocol version, BuildPlan schema version, block/state catalog revision, test suite ID, test run
ID, execution environment label, recorded timestamp (**only when the run actually happened** — `null` otherwise, never
invented), evidence level, evidence mode, per-category outcomes, execution verification evidence, known limitations,
whether a real runtime test occurred, the certification source, and bounded notes.

Rules enforced by the type itself:

- `realRuntimeTested` must agree with `evidenceMode`; a simulated run cannot claim a real runtime.
- `evidenceMode = NONE` requires level `NOT_PERFORMED`.
- Simulated evidence can never record `RUNTIME_TESTED` or `CERTIFIED`.
- A `CERTIFIED` level requires a recorded certification decision.
- Identifiers are bounded (96 characters) and every free-text field passes `MinecraftCertificationSanitizer`.

The sanitizer **rejects rather than redacts**: private-key material, credential assignments (`api_key=`, `password:`,
`keystorePassword=`), bearer tokens, Google API keys (`AIza…`), vendor tokens (`ghp_…`, `sk-…`, `xox…`), IPv4
addresses, host names, keystore/`.pem`/`.p12` references, and TLS fingerprint shapes all cause an
`IllegalArgumentException`, so a secret can never be committed inside an evidence record or a report.

## 4. The centralized decision engine

`MinecraftCertificationRuleEngine` is the only place a decision is made. It is deterministic: identical evidence always
produces an identical `MinecraftCertificationEvaluation` (decision, level, mode, source, maximum claimable status,
authorization flag, reason codes, ordered reasons).

Decisions: `CERTIFIED`, `NOT_CERTIFIED`, `INSUFFICIENT_EVIDENCE`, `BLOCKED_BY_LIMITATION`, `FAILED`.

Check order (first match wins):

1. No evidence at all → `NOT_CERTIFIED` + `NO_EVIDENCE`, `RUNTIME_TEST_NOT_PERFORMED`.
2. Evidence that could carry a secret → `FAILED` + `EVIDENCE_NOT_SANITIZED`.
3. Any failed category → `FAILED` + `CATEGORY_FAILED` (a failure is never averaged away).
4. Execution failed → `EXECUTION_FAILED` recorded.
5. **Nothing was performed at all** → `NOT_CERTIFIED` + `RUNTIME_TEST_NOT_PERFORMED` (kept distinct from "insufficient",
   so the UI can say *not tested* instead of implying a partial pass).
6. A declared limitation that states the runtime is unverified → `BLOCKED_BY_LIMITATION` + `BLOCKING_LIMITATION`.
7. `REAL_RUNTIME` evidence in an environment the policy does not declare real-runtime capable → `NOT_CERTIFIED` +
   `ENVIRONMENT_NOT_DECLARED_FOR_REAL_RUNTIME`; the evidence is then treated as simulated and capped.
8. Missing required categories → `INSUFFICIENT_EVIDENCE` naming each missing category
   (`MISSING_STATIC_VERIFICATION`, `MISSING_UNIT_VERIFICATION`, `MISSING_PROTOCOL_VERIFICATION`,
   `MISSING_SIMULATED_VERIFICATION`, `MISSING_REAL_RUNTIME_VERIFICATION`, `MISSING_EXECUTION_VERIFICATION`).
9. Execution depth: `EXECUTION_DID_NOT_WRITE_BLOCKS`, `EXECUTION_DID_NOT_COMPLETE`.
10. `realRuntimeTested == false` → `NOT_CERTIFIED` + `SIMULATED_EVIDENCE_ONLY` + `RUNTIME_TEST_NOT_PERFORMED`, level
    `SIMULATED_E2E_VERIFIED`. Simulation proves wiring, never a runtime.
11. World state not read back → `WORLD_STATE_NOT_VERIFIED`.
12. Only if nothing above applied → `CERTIFIED`, level `CERTIFIED`, mode `REAL_RUNTIME`, source `REAL_RUNTIME_TEST_RUN`,
    `authorizesExecution = true`, `maximumClaimableStatus = SUPPORTED`.

`MinecraftCertificationPolicy.DEFAULT` is the shipped policy:

- `requiredCategoriesForCertification` — all seven categories.
- `requiredCategoriesForSimulatedEvidence` — all except real-runtime integration and the decision itself.
- `blockingLimitations` — `LEGACY_RUNTIME_NOT_VERIFIED`, `LEGACY_BRIDGE_INTERFACE_UNVERIFIED`,
  `BLOCK_STATE_MAPPING_NOT_VERIFIED`.
- `realRuntimeCapableEnvironments` — **empty**. This is the gate that makes it impossible for this repository, this
  sandbox, or a fake host to mint certification. Declaring an environment is a deliberate, reviewed policy change and
  must be accompanied by a recorded real-runtime evidence artifact.
- `shippedProductionRecords` — `java-fabric-1.20.1` → `CERTIFIED` / `SUPPORTED`, with a policy statement recording that
  Phase 14 performed **no** new real-Minecraft runtime test for it and adds no new runtime evidence.

`evaluateShippedRecord(...)` and `evaluateShippedBedrockContract(...)` report what the project already recorded, with
`evidenceMode = NONE`, `source = SHIPPED_PRODUCTION_RECORD` (or `NONE`), and `evidence = null`, so a shipped record can
never be mistaken for a new verification run. `auditDeclaredStatus(...)` flags `DECLARED_STATUS_OVERCLAIM` when a
profile's declared status exceeds its recorded certification.

## 5. Current certification status

| Target | Compatibility status | Certification | Evidence | Execution |
| --- | --- | --- | --- | --- |
| **Java 1.20.1** (Fabric 0.16.10, Fabric API 0.92.2+1.20.1, Java 17, Bridge 1.2.0, protocol 2, schema 2) | `SUPPORTED` | `CERTIFIED` — shipped production record | Shipped record + Phase 14 simulated end-to-end verification of the pipeline. **No new real-runtime evidence.** | Allowed on that exact runtime, gated by detection, selection, resolution, session binding, and preflight. |
| **Bedrock** (`bedrock-bridge-contract`) | `EXPERIMENTAL` | `NOT_PERFORMED`, no certified Bedrock version | Contract-level only; simulated pipeline exercised; no runtime test. | **Disabled.** |
| **Legacy Java** (`java-legacy-forge-1.7.10`, `java-legacy-forge-1.12.2`) | `EXPERIMENTAL` | `NOT_PERFORMED` | Declared contracts only; simulated pipeline exercised; no runtime test. | **Disabled.** |
| Unregistered / unsupported / invalid runtimes (unknown version, 1.19.4, 1.20.2, snapshots, beta, alpha, wrong loader, wrong Fabric API, wrong bridge or protocol version, forged capability, missing limits, cross-edition) | `UNSUPPORTED` or `UNKNOWN` | none | Refusal evidence only. | **Disabled**, with a typed reason code. |

The Java 1.20.1 certification is **not** downgraded because this sandbox has no Minecraft: the shipped record stands, and
Phase 14 explicitly separates "existing shipped certification record" from "certification newly established by this
phase" — of the latter there is none, and none is claimed.

## 6. The universal certification matrix

`MinecraftCompatibilityCertificationMatrix` is data, not prose: **35 runtime rows** in five groups plus **15 execution
failure cases**, each declaring the layers that apply (`STATIC`, `UNIT`, `PROTOCOL`, `SIMULATED_INTEGRATION`,
`END_TO_END_EXECUTION`, `CERTIFICATION_DECISION`), the expected detection status, selection status, stable reason code,
selected adapter, executability, and certification decision.

| Group | Rows | Covers |
| --- | --- | --- |
| `JAVA_PRODUCTION` | 1 | Java 1.20.1 through the complete pipeline including execution and the certification decision. |
| `JAVA_LEGACY` | 3 | Forge 1.7.10, Forge 1.12.2, and 1.7.10 with the wrong Java runtime. |
| `BEDROCK` | 1 | The Bedrock contract through the whole pipeline, refused before any bridge write. |
| `UNSUPPORTED_OR_INVALID` | 19 | Unknown version, unregistered 1.19.4, near version 1.20.2, Java 21, wrong loader, wrong loader version, wrong Fabric API, wrong bridge version, protocol downgrade, Java edition with a Bedrock loader, Bedrock with a Fabric loader, Java reporting Bedrock Native, malformed identity token, legacy edition routing, snapshot/beta/alpha channels, missing Java runtime, missing limits. |
| `SECURITY` | 11 | Unauthenticated report, missing session identity, changed bridge identity, app-version mismatch, forged capability, undeclared capability, oversized report, ambiguous adapter registry, runtime changed after prepare, session changed after prepare, world session changed after prepare. |

`CertificationMatrixTest` executes every row through production code and asserts each field, so a row that stops being
true is a failing test rather than a stale document. Rows the wire codec must reject first (malformed identity token,
oversized report, both cross-edition rows) are asserted at descriptor level through the production detector and marked
`STATIC` + `UNIT` only. No row claims real-runtime evidence, and only `java-production-1.20.1` expects `CERTIFIED`.

## 7. Simulated end-to-end pipeline

`SimulatedCertificationPipeline` (test code) drives `SimulatedCertificationBridge` — a protocol-faithful fake that
speaks the real envelope, the real Java and Bedrock wire codecs, and the real contract validator — through the
**production** pipeline in this order, without bypassing a layer:

```
bridge authentication → capability exchange → app-version echo → runtime detection → descriptor validation
  → adapter selection → compatibility resolution → BuildPlan validation → preflight
  → execution authorization → execution → progress → cancellation → completion
```

Each stage records an outcome, a detail, and a stable reason code; a failure halts the run and the remaining stages are
recorded `NOT_RUN` ("an earlier stage failed and the pipeline fails closed"). Execution authorization always runs once a
report exists, because the binding check is the authoritative answer about whether a previously resolved compatibility
may still be used. Cancellation is verified on a **second** prepared execution so the completed build stays intact.

What a simulated run proves: CraftMind's wiring — codecs, detection, validation, selection, resolution, limits,
authorization, execution sequencing, cancellation, and evidence recording. What it can never prove: a Minecraft runtime.
Its evidence is capped at `SIMULATED_E2E_VERIFIED`, its mode is `SIMULATED`, its decision is `NOT_CERTIFIED`, and its
world read-back is labelled *"simulated read-back, not real world-state verification"*.

A stage that fails because a **security or contract control refused a forged, downgraded, replayed, or unauthorized
input** is recorded as a `FAILED` category (decision `FAILED`); a stage that fails because a runtime is simply
unsupported is recorded as `NOT_RUN` (decision `NOT_CERTIFIED`). The two are never blurred.

### Reproducing the simulated end-to-end run

With a working Android/Gradle toolchain (not available in this sandbox):

```bash
./gradlew :app:testDebugUnitTest --tests '*certification*'
./gradlew :bridge-protocol:test
```

In this checkout the same tests were executed with reconstructed JVM tooling kept entirely **outside** the repository
(Kotlin 2.1.10 + serialization plugin, a JDK 17 runtime, JDK 8 `javac` from a packaged `tools.jar`, and
gson/junit/hamcrest/kotlinx-serialization compiled from source). That out-of-repository harness has four steps —
bootstrap the toolchain, compile the Android-free main and test subsets, run the JUnit suite (optionally filtered, e.g.
to the certification package), and run the `bridge-protocol` Java tests — and produced:

```text
certification filter → running 7 test classes → OK (57 tests)
full JVM suite       → running 37 test classes → Tests run: 292, Failures: 4 (all pre-existing)
bridge-protocol      → OK (14 tests)
```

Nothing produced by that tooling is committed; the repository contains only sanitized, deterministic test data. Under a
working Android/Gradle toolchain the Gradle commands above are the authoritative way to reproduce the same results.

## 8. Execution verification stages

"The bridge accepted the request" is not proof of placement. `MinecraftExecutionVerificationStage` distinguishes:

`REQUEST_ACCEPTED` → `PRECHECK_PASSED` → `EXECUTION_STARTED` → `BLOCKS_WRITTEN` → `PROGRESS_REPORTED` →
`EXECUTION_COMPLETED`, with `EXECUTION_CANCELLED` and `EXECUTION_FAILED` as separate terminal outcomes.

`BLOCKS_WRITTEN`, `PROGRESS_REPORTED`, and `EXECUTION_COMPLETED` are marked `requiresRealWorld`: only a real runtime can
prove them by reading actual world state. In simulation they are recorded with mode `SIMULATED`, `worldStateVerified`
stays `false`, and the engine refuses to certify. A cancelled execution writes at most a bounded prefix and never
reports completion.

## 9. Failure injection

`CertificationFailureInjectionTest` drives the 15 documented execution failure cases and asserts, for each: the
documented reason code is reported by the layer that refuses, the refusal happens at the documented stage, and nothing
is written before the validation that refuses it.

| Case | Refusing layer | Reason code |
| --- | --- | --- |
| Authentication failure | Bridge authentication | `AUTH_SIGNATURE_INVALID` |
| Invalid/expired session | Bridge authentication | `AUTH_SESSION_EXPIRED` |
| Session identity missing / mismatch | Detection | `SESSION_IDENTITY_MISMATCH` |
| Runtime identity changed after prepare | Authorization (TOCTOU) | `RUNTIME_IDENTITY_CHANGED` |
| Bridge identity mismatch | Detection | `SESSION_IDENTITY_MISMATCH` |
| App-version mismatch | Detection | `APP_VERSION_MISMATCH` |
| Protocol mismatch / downgrade | Codec + detection | `BRIDGE_PROTOCOL_UNSUPPORTED` |
| Unsupported version / loader | Adapter selection | `AMBIGUOUS_ADAPTER_MATCH` / `UNSUPPORTED_*` |
| Fabric API mismatch | Compatibility | `BRIDGE_FABRIC_API_UNSUPPORTED` |
| Malformed descriptor | Descriptor validation | `RUNTIME_DESCRIPTOR_INVALID` |
| Forged capability | Detection | `RUNTIME_DESCRIPTOR_INVALID` |
| Missing limits | Detection | `INCOMPLETE` → authorization denied |
| Invalid BuildPlan | Production validator | `BUILD_PLAN_NOT_EXECUTABLE` |
| Unsupported block / block state | Production validator, then bridge contract | `INVALID_BLOCK_ID` / `INVALID_BLOCK_STATE`, and `UNSUPPORTED_BLOCK` / `UNSUPPORTED_BLOCK_STATE` for a payload that bypassed app validation |
| Preflight rejection | Adapter preflight | `BUILD_PLAN_REJECTED_BY_SERVER` |
| Duplicate execution ID | Bridge contract | `EXECUTION_ALREADY_EXISTS` |
| Second simultaneous build | Bridge contract | `EXECUTION_ALREADY_ACTIVE` |
| Cancellation | Execution | `CANCELLATION_ACCEPTED` at a batch boundary |
| Disconnect during execution | Execution | `BRIDGE_SESSION_UNAVAILABLE` |
| Reconnect | Detection re-run | previous binding invalidated |
| Stale execution binding | Authorization | `WORLD_SESSION_CHANGED` |
| Runtime changed after prepare | Authorization | `RUNTIME_IDENTITY_CHANGED` |
| Adapter ambiguity | Registry, then selection | registration refused (`DuplicateRuntimeProfile`); if an adapter republishes profiles after registration, selection returns `AMBIGUOUS_ADAPTER_MATCH` |

Every case ends with: not certified, execution not authorized, no real-runtime claim, and evidence at or below the
simulated ceiling.

## 10. Security certification regression

`CertificationSecurityRegressionTest` re-asserts the Phase 9–13 controls at the certification layer:

- Pinned HTTPS pairing, certificate pinning, and the pinned TLS fingerprint as part of the authenticated identity
  (**static** verification over `AndroidMinecraftBridgePairingRepository`, which cannot execute in a JVM harness; the
  test skips — never passes silently — when the source tree is unreachable).
- Mandatory authentication; an unauthenticated report is never inspected for runtime facts.
- Session binding, bridge identity, and pinned fingerprint mismatch all refuse detection and authorization.
- Replay/staleness: a non-positive envelope timestamp is refused (`INVALID_TIMESTAMP`); a reused execution ID
  (`EXECUTION_ALREADY_EXISTS`); a missing preflight (`PREFLIGHT_REQUIRED`); a forged preflight token
  (`PREFLIGHT_TOKEN_INVALID`).
- Capabilities come only from the authenticated bridge report; forged or undeclared capabilities make the runtime
  undetectable.
- Protocol downgrade refused by the codec (`UNSUPPORTED_PROTOCOL`) and by detection (`BRIDGE_PROTOCOL_MISMATCH`).
- Schema downgrade refused by the production contract validator (`UNSUPPORTED_BUILD_PLAN_SCHEMA`).
- Limits are `min(global, runtime)`; a runtime can lower them and can never raise them; missing limits fail closed.
- Block/state validation is server-side against the production catalog; the probe never widens it.
- No command, shell, script, console, or entity surface: the domain has exactly `PLACE_BLOCK` and `REMOVE_BLOCK`, and
  the execution payload carries exactly the eight documented keys with no credential-bearing key under any name.
- No API key, credential, host, IP, session, bridge ID, or fingerprint ever reaches evidence, a report, or a committed
  artifact; the machine-readable report round-trips and is scanned before it can exist.

## 11. Real-runtime certification hook

`MinecraftRealRuntimeCertificationHarness` is the only path that can produce `REAL_RUNTIME` evidence. It performs 17
steps, each with an honest outcome:

`CONNECT_REAL_RUNTIME`, `PAIR_AND_AUTHENTICATE`, `CAPABILITY_EXCHANGE`, `DETECT_RUNTIME`, `SELECT_ADAPTER`,
`VALIDATE_COMPATIBILITY`, `LOAD_CERTIFICATION_PLAN`, `VALIDATE_PLAN`, `PREFLIGHT`, `AUTHORIZE_EXECUTION`, `EXECUTE`,
`VERIFY_WORLD_STATE`, `VERIFY_PROGRESS`, `VERIFY_CANCELLATION`, `VERIFY_RECONNECT`,
`VERIFY_RUNTIME_CHANGE_INVALIDATION`, `RECORD_EVIDENCE`.

A host is supplied by `MinecraftRealRuntimeHostProvider`. The shipped provider is `NoRealRuntimeHostProvider`, which
returns no host: no Minecraft download, no network access, and no fake server presented as real. Without a host every
step is `NOT_PERFORMED`, the outcome is `RUNTIME_TEST_NOT_PERFORMED`, the environment label is `not-performed`, the
evidence level is `NOT_PERFORMED` with mode `NONE` and no timestamp, and the decision is `NOT_CERTIFIED`. A host that
fails mid-run produces `RUNTIME_TEST_FAILED` with the same honest evidence and always releases the runtime.

Even a host that claims to be real cannot certify under the shipped policy: its evidence is treated as simulated and the
decision is `NOT_CERTIFIED` + `ENVIRONMENT_NOT_DECLARED_FOR_REAL_RUNTIME`.

### How a real certification is performed in future

1. Provide a `MinecraftRealRuntimeHost` outside this repository's unit-test environment (a Gradle/instrumentation rig or
   a manual certification station) that starts or connects to a **real** Minecraft server with the CraftMind bridge
   installed, pairs over pinned HTTPS, and authenticates.
2. Implement `authenticate()` (returning the authenticated runtime report), `runExecution(record, executionId)`
   (executing the deterministic certification probe, reading back **actual** world state, verifying progress and
   cancellation), `reconnect()` (re-detection), and `close()`.
3. Declare the host's `environmentLabel` in `MinecraftCertificationPolicy.realRuntimeCapableEnvironments` — a deliberate,
   reviewed change, never a test-side edit.
4. Run `MinecraftRealRuntimeCertificationHarness.certify(profileId, testRunId = …)` and commit the resulting
   machine-readable report. Certification then requires all seven categories `PASSED`, verified world state, and no
   blocking limitation.
5. Only then may a Bedrock, legacy, or new-version target move above `NOT_PERFORMED`, and only for the exact runtime
   that was tested. There is no nearest-version, cross-edition, or theoretical upgrade path.

## 12. Machine-readable certification report

`MinecraftCertificationReport` (schema version `1`) is produced only through `MinecraftCertificationReportBuilder`,
which sanitizes every field before a report can exist. It carries: schema version, generated-by version and
environment, test suite ID, test run ID, optional generation timestamp, one entry per profile (identity facts, declared
status and certification, evidence level, evidence mode, certification source, decision, per-category outcomes, observed
execution stages, world-state verification, real-runtime flag, limitations, reason codes, reasons), a summary (counts
per decision, whether **this run** performed real-runtime tests, simulated runs recorded, matrix case counts), and a
reproducibility block (harness, toolchain, commands, optional source revision, notes).

`summary.realRuntimeTestsPerformed` counts only evidence this run produced against a real Minecraft runtime; a shipped
production record never makes it true. Reports are serialized with explicit nulls omitted and pretty-printed, and
round-trip exactly through `fromJson`. No report artifact is committed by Phase 14 — the only committed data is
deterministic, sanitized test fixtures.

## 13. The certification BuildPlan (probe)

`CertificationBuildPlan` is a three-placement probe (`minecraft:stone` at (0,0,0) and (1,0,0), `minecraft:glass_pane`
with `north=true` at (2,0,0)) inside 3×1×3 bounds, one `FOUNDATION` component, schema 2, status `READY`, fixed
`generatedAtEpochMillis`, `planId = certification-probe-v1`, `buildId = certification-probe`.

It is tiny, bounded, deterministic, safe, and never a user build: only `PLACE_BLOCK` operations, no commands, entities,
NBT, redstone, TNT, or spawners. It is validated by the **same** `DefaultBuildPlanValidator` and the **same**
`MinecraftBlockCatalog` as production, and it satisfies the production bridge contract validator. Production validation
was not weakened for it — `CertificationBuildPlanTest` asserts both that the probe passes and that the validator still
rejects unsupported blocks, invalid states, empty operations, invalid and duplicate coordinates, unsupported schema
versions, and oversized operation lists. Execution IDs are derived deterministically from the test run ID
(`CertificationExecutionIds`) so a run is reproducible and never collides across runs.

## 14. UI wording

Settings shows the existing compatibility block plus three certification lines derived only from recorded
certification (`MinecraftRuntimeResolution.certificationSummaryLines`): certification state, evidence state, and why
execution is allowed or blocked. Per-category verification detail and the evidence run identity appear in the existing
advanced panel. There is no redesign and no new screen.

Exact labels: `Supported / Certified production target`, `Experimental / Runtime certification not performed`,
`Experimental / Runtime test required`, `Not tested / Runtime test required`,
`Not certified / Blocked by a declared limitation`, `Not certified / Insufficient evidence`,
`Not certified / Verification failed`, `Not certified`, `Unsupported`,
`Unavailable — runtime could not be verified`, `Unavailable — no authenticated runtime report`.

Evidence lines always name the mode: `Evidence: simulated end-to-end (…) · no Minecraft runtime was started or
modified`, `Evidence: real Minecraft runtime (…)`, `Evidence: none recorded (…)`, or `Evidence: recorded production
certification · this build performed no new Minecraft runtime test`. A test asserts that **no** combination of status,
certification level, and decision can ever produce "fully compatible", "full compatibility", or "perfect", and that only
a support-authorizing certification may use the phrase "Certified production target".

## 15. Verification status in this checkout

- **Executed (PASS):** 292 JVM unit tests across 37 classes → 288 passed; the certification suite is
  `OK (57 tests)` across 7 classes (`SimulatedEndToEndCertificationTest` 9, `CertificationMatrixTest` 4,
  `CertificationFailureInjectionTest` 6, `CertificationSecurityRegressionTest` 11, `CertificationRuleEngineTest` 14,
  `CertificationBuildPlanTest` 5, `RealRuntimeCertificationHarnessTest` 8) plus
  `MinecraftRuntimeCompatibilityTextTest` `OK (16 tests)`; `bridge-protocol` Java tests `OK (14 tests)`;
  `scripts/check_release_config.py` PASS; `scripts/check_website.py` PASS.
- **Pre-existing failures (documented, unrelated, not modified):** 4 — `AiBuildEngineTest` ×2,
  `BuildPlanContextSerializerTest`, `BuildDiffTest`. They reproduce identically before Phase 14 (230 tests / 30 classes
  → 4 failures) and touch AI refinement, context serialization, and build diffs, none of which Phase 14 modifies.
- **NOT RUN:** Gradle (`test`, `lint`, `assembleDebug`, `connectedDebugAndroidTest`), Android/Compose compilation, the
  Fabric mod build, instrumentation tests, live bridge pairing, and any real Java/Bedrock/legacy Minecraft runtime test.
  Android-dependent files changed by Phase 14 (`MinecraftRuntimeCompatibility.kt`) were reviewed and parse-checked
  against their unmodified original; **no Android compilation is claimed**.
- **NOT AVAILABLE:** Android SDK/AGP, Gradle distribution, Maven Central/Google egress, a Minecraft runtime, network
  access to a server, an emulator or device.
- **NOT PERFORMED:** `REAL_RUNTIME_TESTS = NOT_PERFORMED`. No Bedrock, legacy, new-version, or real-runtime
  certification claim is made anywhere in this phase.
