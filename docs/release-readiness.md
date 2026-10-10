# Release readiness — Android, website, backend, and Minecraft bridge

Phase 34. Baseline audited: `709dd8f` on `arena/6e167066-craftmind` (equal to the remote tip; `main` at `0cb2f42`).
Scope: the end-to-end journeys a release would ship, verified against the sources in this repository. Nothing here was
inferred from a previous agent's claim that a phase was finished, and nothing in this document describes a deployment.

> **A green test suite is not a release.** The checks recorded below are static analysis, unit tests, and service-level
> HTTP tests against an in-process backend with an in-memory mail sink. No Android build, no APK signature, no real
> device, no browser engine, no Minecraft server, and no hosted CraftMind service was involved, because this
> environment cannot produce them. The items in "Blocked checks" are open, not passed.

## 1. What this environment can and cannot run

| Capability | Status here | Why |
| --- | --- | --- |
| Backend unit + service-level tests (`node --test`) | **RUN** | Node 22+ present, zero runtime dependencies |
| Static Python checkers in `scripts/` | **RUN** | `python3` only |
| `node --check` syntax validation of website modules | **RUN** | |
| Loopback HTTP against the real service | **RUN** | `startService()` in `backend/test/helpers.js` |
| Gradle build, `lint`, unit tests for `app/` | **NOT RUN — blocked** | no `java`, `javac`, `gradle`, `adb`; `JAVA_HOME`/`ANDROID_HOME` unset; `services.gradle.org` and Google's Maven are outside the allowed network |
| Fabric mod build/test (`minecraft-bridge/`) | **NOT RUN — blocked** | same toolchain gap; needs a Minecraft client/server to be meaningful |
| Instrumented (`androidTest`) and emulator tests | **NOT RUN — blocked** | no device, no emulator |
| Browser end-to-end (Playwright/Cypress) | **NOT RUN — absent** | no browser or test runner is installed in this repo |
| Signed APK/AAB production, Play upload, hosting | **NOT RUN — out of scope** | requires keystore secrets and a release operator; no deployment is performed |

The `app/` module contains 234 files (158 main, 64 JVM unit, 10 instrumented). None of its Kotlin was compiled in this
phase; the strongest thing said about it below is "read and cross-checked against the backend".

## 2. Component readiness map (the journeys a release ships)

Legend — **VERIFIED**: read end to end and confirmed against the code, and (where noted) exercised over HTTP.
**CODED, UNRUNNABLE**: implemented in the repository, but this environment cannot execute it. **BLOCKED**: cannot be
declared either way from here.

### Android app (`app/`)
| Journey | State | Evidence |
| --- | --- | --- |
| Startup, configuration, first-run without a backend | VERIFIED (read) | `CraftMindApplication`, `SettingsScreen`; empty `CRAFTMIND_ACCOUNT_BASE_URL` default + HTTPS-only gate; no crash path when unconfigured |
| Home → build creation, prompt validation | CODED, UNRUNNABLE | `HomeScreen`, prompt validators; `AiBuildEngineTest` exists but needs Gradle |
| Image-reference and video-reference workflows | CODED, UNRUNNABLE | reference selection flows; `PublicVideoDnsTest` present, not executed |
| BuildPlan generation → validation | CODED, UNRUNNABLE | provider adapters (`GoogleGeminiProviderAdapterTest`), schema validation in `bridge-protocol` |
| Provider API key entry + secure credential storage | VERIFIED (read) | `AndroidKeystoreCredentialStore` (Android Keystore, non-exportable, EncryptedSharedPreferences); no plaintext key path found in `app/src/main` |
| Build history / settings persistence | CODED, UNRUNNABLE | DataStore-based; instrumented tests exist (`app/src/androidTest`, 10 files), not executed |
| Bridge pairing, permission grant, capability exchange | VERIFIED (read) | pairing repository + `BridgeCapabilitiesWireCodecTest` (unexecuted); capability codec round-trips `protocolVersion`, edition, version, loader |
| Edition / version compatibility and refusal | VERIFIED (read) | `UNSUPPORTED_PROTOCOL` → `BRIDGE_UPDATE_REQUIRED`; `BuildPlanContractValidator` fails closed on `schemaVersion != 2` |
| Construction execution, cancellation, error recovery | CODED, UNRUNNABLE | `BridgeRuntimeReportReader` maps server progress reports; requires a live server to prove |
| Release build configuration | VERIFIED (read) | `versionCode 10000`, `versionName "1.0.0"`, `minSdk 26`, `compileSdk`/`targetSdk` 35, release minify+shrink on, `isDebuggable=false` in release, `-debug` suffix on debug |
| Signing and secret handling | VERIFIED (read) | keystore supplied **only** by `CRAFTMIND_RELEASE_STORE_FILE/_STORE_PASSWORD/_KEY_ALIAS/_KEY_PASSWORD`; partial configuration is refused; a keystore path inside the checkout is refused; `packageRelease`/`signReleaseBundle` throw when unsigned. No key material exists in the repo (`check_release_config.py` PASS) |
| Manifest surface | VERIFIED | exactly one exported component (`MainActivity`, launcher); only `INTERNET` permission; `allowBackup=false`; `usesCleartextTraffic=false`; no `android:debuggable`; no `network_security_config` reference without a file |
| Resource integrity (proxy for "will it compile") | VERIFIED (static) | all 14 XML resource references resolve; 0 `R.*` references from Kotlin (pure Compose, no generated-ID use); 0 unresolved |
| Analytics / tracking / fingerprinting | VERIFIED | no third-party SDK, no device identifier, no telemetry endpoint in `app/src/main` |

### Website and download experience (`website/`)
| Journey | State | Evidence |
| --- | --- | --- |
| Landing, features, pricing, download | VERIFIED | 41 pages, 1179 links resolve (`check_website.py` PASS); download page states Gradle-matching facts and a **disabled** APK button because no artifact exists in this repository |
| Auth / session (register, verify, sign in, sessions, sign out, password change) | VERIFIED **over HTTP** | driven through the shipped `adapters.js` against a live service in `backend/test/website-api-contract.test.js` |
| Password recovery | FIXED + VERIFIED over HTTP | see defect A1 below; request half is wired in the UI, **no confirm panel exists on the site** (gap G1) |
| Creator onboarding (buyer + seller), profile, eligibility | VERIFIED over HTTP | buyer save succeeds and reflects in `loadOnboardingState`; seller step is entitlement-gated and now reports that refusal correctly (defect A2) |
| Marketplace browse/search, listing detail | VERIFIED over HTTP | `{items,total,limit,offset,hasMore,searchedAt}` matches `marketplace.js`; 8 categories equal the server's `MARKETPLACE_CATEGORIES` |
| Creator studio (draft/save own listings) | VERIFIED (read) | `serviceBody()` key set ⊆ server `CONTENT_KEYS`; `?listing=` id matches `ownerListingView`'s `id`; entitlement refusal reaches the page as `CREATOR_ENTITLEMENT_REQUIRED` |
| Hire: jobs, proposals, orders, milestones, delivery, revisions | VERIFIED (read) | job/proposal/order bodies match `JOB_CONTENT_KEYS`, `PROPOSAL_*_KEYS`, `NO_CONTENT_KEYS`; order lifecycle bodies are `{}` by design |
| Trust, reporting, disputes | VERIFIED (read) | public trust summary + report intake exist server-side; the website shows what is implemented and labels the rest; no private report/dispute content is exposed by any public surface |
| Analytics / referrals | VERIFIED (read) | Phase 31 window contract intact; referral routes unchanged; no client-side counting is presented as measured data |
| Payments, checkout, subscriptions, credits purchase | VERIFIED INERT | `payments`, `orders`, `reviews`, `analytics` adapters return `NOT_IMPLEMENTED`; asserted in the contract suite and policed by `check_api_contracts.py` so a monetisation surface cannot appear silently |
| Failure states (offline, misconfigured origin, 4xx/5xx) | VERIFIED over HTTP | no origin → `configured=false` and honest "not configured" copy; 401 → sign-in prompt; 403 with a typed code → the server's own message; empty result → empty state, never a fake success |

### Backend (`backend/`)
| Concern | State | Evidence |
| --- | --- | --- |
| Contract surface | VERIFIED | 106 routes (75 static + 31 parameterised); both clients resolve against them; 30 unrouted-by-clients routes are the developer plane, health, and intentionally-uncalled compatibility aliases |
| Session/token handling | VERIFIED over HTTP | Bearer sessions, single-use email/recovery codes, per-route rate domains, `isCurrent` marker, self-revocation refused, `revoke-all` keeps the caller alive, password change/recovery revoke every session |
| Ownership checks | VERIFIED | listing/job/proposal/order/report/dispute reads are scoped per account in the 406-test suite (e.g. own-listing views, `getOwnedJob`, buyer-only and seller-only order reads) |
| Input validation | VERIFIED | `strictBody` refuses unknown keys on every content route; typed domains enforced (`REFERRAL_SOURCES`, `EDITION_VALUES` = `java/bedrock/legacy`, `LOADER_VALUES` = `Fabric/Forge/NeoForge/Vanilla/Bedrock Native`, agreement version must equal the server's) |
| Error shapes | VERIFIED | stable `{error:{code,message,requestId}}`; 108 `ErrorCode` keys; `INVALID_REQUEST` for body-shape failures; unknown paths 400, wrong media type 415, oversized 413, CORS 403, insecure 400, throttled 429 |
| Log redaction / audit | VERIFIED | 102 audit action types; no token, password, or recovery code written to logs (asserted in existing suites); Phase 33 audit semantics untouched |
| Migration compatibility | VERIFIED | `SCHEMA_VERSION = 12`, forward-only migrations, backup/restore scripts unchanged this phase |
| Startup / shutdown, health / readiness | VERIFIED | `startService()` boots and closes cleanly in tests; `/health` shape preserved; `/live` separate |
| Ops documentation | VERIFIED (read) | `docs/production-operations.md` still describes only mechanisms that exist in-repo; no provider assumed; no automated-backup claim without a verified mechanism |

### Minecraft bridge (`bridge-protocol/`, `minecraft-bridge/`)
| Concern | State | Evidence |
| --- | --- | --- |
| Pairing and permissions | VERIFIED (read) | pairing code/token exchange with capability grant set; no anonymous build execution |
| Protocol/schema compatibility | VERIFIED | `BridgeProtocol.VERSION = 2`, `BUILD_PLAN_SCHEMA_VERSION = 2`; the mod echoes the constant (single source of truth), the validator refuses any other schema version; four JUnit tests exist (unexecuted) |
| Java vs Bedrock representation | VERIFIED (read) | Java block-state/coordinate path implemented; Bedrock/legacy refused explicitly where no representation exists — no silent downgrade of protocol or schema version anywhere |
| Version / loader mismatch | VERIFIED (read) | `fabric.mod.json` pins loader `0.16.10`, Fabric API `0.92.2+1.20.1`, Minecraft `1.20.1` — identical to README and `docs/universal-minecraft-compatibility.md` |
| Connection loss, timeout, reconnect | VERIFIED (read) | runtime report reader tolerates gaps; bounded retry; **not exercised against a server** |
| Construction preflight and cancellation bounds | VERIFIED (read) | preflight checks region/permissions before placement; cancellation bounded; requires a live server to prove |
| Mod release facts | VERIFIED | `minecraft-bridge/build.gradle` `version = '1.2.0'` matches README's shipped line and the mod metadata |

## 3. Contract matrix — what was traced, and the verdict per row

Reproducible with `python3 scripts/check_api_contracts.py` (106 routes; 56 declared + 32 called website endpoints;
14 Android calls; machine-readable body contracts for 20 routes; everything else reported as a NOTE, never as a pass).

| # | Caller | Receiver | Request | Auth | Client key set vs server contract | Error / timeout path | Coverage | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `adapters.js` | `POST /auth/register` | `{email,password,displayName}` | none | equal | 400 shape, 409 exists, 429 | new suite | MATCH |
| 2 | `adapters.js` | `POST /auth/verify-email` | `{token}` | none | equal (token from the sink) | 400 invalid/expired | new suite | MATCH |
| 3 | `adapters.js` | `POST /auth/login` / `POST /auth/logout` | `{email,password}` / `{accessToken,refreshToken}` | none / body | equal | 401, 429 | new suite | MATCH |
| 4 | `adapters.js` | `POST /auth/password-reset/request` | `{email}` | none | equal | enumeration-neutral 202 | new suite | MATCH |
| 5 | `adapters.js` | `POST /auth/password-reset/confirm` | `{token,newPassword}` → `200 {reset,revokedSessions}` | none | **was `{email,code,newPassword}`** | guaranteed 400 before fix | new suite (fails without fix) | **FIXED — A1** |
| 6 | `adapters.js` | `POST /auth/password/change` | `{currentPassword,newPassword}` | bearer | equal | 400 weak, revoke-all side effect | new suite | MATCH |
| 7 | `adapters.js` | `GET /auth/sessions`, `POST /auth/sessions/revoke` | — / `{sessionId}` | bearer | equal | 401; self-revoke refused by design | new suite (revoked from a 2nd session) | MATCH |
| 8 | `adapters.js` | `POST /onboarding/buyer` | `BUYER_ONBOARDING_KEYS` | bearer | equal | 400 unknown key (asserted) | new suite | MATCH |
| 9 | `adapters.js` | `POST /onboarding/seller` | + agreement pair, editions/versions/loaders | bearer | equal; **403 was mapped to "sign in"** | entitlement refusal now typed | new suite (fails without fix) | **FIXED — A2** |
| 10 | `adapters.js` | `GET /membership /entitlements /credits` | — | bearer | `{membership,plan,credits}`, `{credits:{available,…}}` | 401 | new suite | MATCH |
| 11 | `marketplace.js` | `POST/GET /marketplace/search`, `/categories`, `/listings/:id` | `{query,category,edition,minecraftVersion,limit,offset}` | none | `{items,total,hasMore}` matches; 8 categories match | empty state honoured | new suite + checker | MATCH |
| 12 | `creator-studio.js` | `GET /creator/listings`, `POST /creator/listing`, `PUT /creator/listing/:id` | draft key set ⊆ `CONTENT_KEYS` | bearer | `id` matches `ownerListingView` | `CREATOR_ENTITLEMENT_REQUIRED` surfaces | new suite + read | MATCH |
| 13 | `hire.js` | `POST /hire/jobs`, `GET /hire/jobs/mine`, `/hire/search`, `POST /hire/jobs/:id/cancel` | `JOB_CONTENT_KEYS` | bearer | equal; job view returned at top level | 400 casing, 404 unknown id | new suite (create → list → search → cancel) | MATCH |
| 14 | `adapters.js` | orders / milestones / delivery / revisions | `{}` bodies where the server declares no content keys | bearer | equal | entitlement refusal typed | read | MATCH |
| 15 | `HttpAccountApi.kt` | all 14 declared Android paths | builders in `AccountApiRequests` | bearer / body tokens | equal, incl. `{token,newPassword}` | typed `AccountApiOutcome` errors | existing JVM suites (unexecuted) + checker | MATCH |
| 16 | Android bridge codecs | mod `BridgeRuntime` | capability JSON + BuildPlan | pairing token | `protocolVersion`/schema echoed from one constant | `BRIDGE_UPDATE_REQUIRED` | unit tests exist, not executed | CODED |
| 17 | `payments/orders/reviews/analytics` adapters | *(no route)* | — | — | must stay inert | `NOT_IMPLEMENTED` | new suite + checker guard | DELIBERATE |

## 4. Defects found and fixed

Only verified release blockers were touched, each with the smallest change that makes the shipped contract correct.

**A1 — the website could never consume a password-recovery token.** `website/assets/adapters.js` posted
`{email, code, newPassword}` to `POST /auth/password-reset/confirm`, while `confirmPasswordRecovery` calls
`requireString(body,"token")` and `requireString(body,"newPassword")`. Every browser submission of that form was
guaranteed a 400. The server, `docs/account-authentication.md:60` (`{token,newPassword}` → `200 {reset,revokedSessions}`),
and the Android builder all agreed; the website was the sole deviant. Fixed by sending the fields the server names.
*Reach caveat:* no website page currently calls this method, so this was a contract defect in a shipped module rather
than a live user outage; the recovery *request* panel is wired and its copy stays honest.

**A2 — a signed-in account without the Creator entitlement was told to sign in again.** `saveSellerOnboarding` mapped
the server's `403 CREATOR_ENTITLEMENT_REQUIRED` to `RESULT.UNAUTHORIZED`, so `onboarding.js`'s own
`CREATOR_ENTITLEMENT_REQUIRED` branch — the one that offers "View membership" — was dead code, and the panel rendered a
sign-in prompt for a user who is already signed in. Because no plan is purchasable in this phase, *every* seller
applicant hit that dead end. Fixed by passing the established `refusedNotUnauthenticated: true` flag used by the
listing and hire writes, so the typed code and the server's own explanation reach the form.

**No other defect was found.** Fifteen further mismatch hypotheses (listings key names, `payload.items` vs
`payload.listings`, job response nesting, credits field names, casing of `java`/`Fabric`, session-revocation
semantics, order `{}` bodies, category lists, agreement version, referral tokens, loader caps, protocol version) were
tested against the live service and each one resolved to a match; the earlier apparent mismatches were errors in this
phase's own probes, recorded in the commit notes so nobody re-chases them.

## 5. Regression coverage added

`backend/test/website-api-contract.test.js` — 13 tests / 5 suites. It imports the **real** `website/assets/adapters.js`
and drives it over HTTP against `startService()`, so it fails when either side drifts, not when a mock disagrees.
To make the module address a test origin, `createAdapters({origin})` gained an optional override; pages never pass it,
so the `^https://` gate in `siteConfiguration()` still governs browsers.

Proof the tests are load-bearing: reverting A1 and A2 together yields `# pass 11 / # fail 2` with exactly the two
recovery/onboarding tests failing; restoring them yields `# pass 13 / # fail 0`.

`scripts/check_api_contracts.py` — the static three-way checker described above. It fails on a client route that the
service does not implement, on a missing required key, on an extra key `strictBody` refuses, on a monetisation surface
appearing in the inert adapters, and on README/download-page/Gradle/`verify-release-apk.sh` fact drift. Verified to
fail on reverted A1 (`missing ['token']`) and on a deliberately dropped `password` field. It reports the routes whose
body contract it could not read as NOTEs instead of guessing them into a pass.

## 6. Verification actually performed (this phase)

| Command | Result | Tier |
| --- | --- | --- |
| `git fetch origin`; `git log`/`status`/`rev-parse` reconciliation | branch == `709dd8f` == remote tip; `main` == `0cb2f42`; no unrelated work in the tree | repo state |
| `cd backend && timeout 600 npm test` (before changes) | **393 tests / 76 suites / 393 pass / 0 fail** (~97 s) | unit + service-level |
| `node --check website/assets/adapters.js` | clean (3 edits) | syntax |
| `python3 scripts/check_website.py` | **PASS** — 41 pages, 1179 links, one disabled APK placeholder, no remote resource/secret/hardcoded origin | static |
| `python3 scripts/check_release_config.py` | **PASS** — release ID/version, external signing config, R8 keep rule, manifest flags, no tracked key/APK artifact, 107 config keys | static |
| `python3 scripts/check_deployment_readiness.py` | **PASS** — 107 configuration keys cross-checked against `backend/.env.example` | static |
| `python3 scripts/check_api_contracts.py` (new) | **PASS** — 106 routes, 56 declared + 32 called website endpoints, 14 Android calls, 20 machine-readable contracts | static cross-component |
| `node --test test/website-api-contract.test.js` | **13 / 13 pass**; 11/13 with the fixes reverted | service-level HTTP through the real client |
| `cd backend && timeout 900 npm test` (after changes) | **406 tests / 81 suites / 406 pass / 0 fail** (~98 s) — no prior test weakened, skipped, or deleted | unit + service-level |
| Throwaway probes (`/tmp/audit/`, not shipped) | printed the live response payloads and vocabularies behind every row in §3 | service-level |
| Android resource/reference audit | 14 XML refs resolve, 0 `R.` refs, 0 unresolved | static |
| `bash -n scripts/verify-release-apk.sh` | clean | syntax |

No browser was launched, no emulator attached, no Minecraft server contacted, nothing deployed, no keystore created.

## 7. Blocked and not-run checks

1. **Android compile/unit/instrumented/lint** — no JDK/SDK/Gradle distribution and no network to fetch them. The 64 JVM
   unit tests and 10 `androidTest` files are unexecuted; the Kotlin in this repo has therefore never been compiled in
   this environment, by this phase or any prior one.
2. **Fabric mod build + `BridgeProtocolTest`/codec tests** — same toolchain gap; also needs a Minecraft server to mean
   anything.
3. **Signed release artifact** — `verify-release-apk.sh` needs an APK that does not exist; producing it needs the
   keystore that is deliberately not in the repository.
4. **Browser E2E** — no Playwright/Cypress in the repo, and installing one is a dependency decision outside this phase.
   The website is therefore proven at the HTTP boundary, which is where the contract defects lived, and not at the DOM.
5. **Hosted backend / real SMTP / backup target** — none configured; `check_deployment_readiness.py` covers the keys,
   not the infrastructure.
6. **Dependency audit** — `backend/` has no lockfile and zero runtime dependencies; `npm audit` has nothing to audit.

## 8. Release artifacts (honest state)

- **No APK or AAB exists anywhere in this repository**, and none can be produced here. `website/download.html` says so
  and keeps the button **disabled**, with the Gradle facts (VERSION `1.0.0`, VERSION CODE `10000`, PACKAGE
  `com.craftmind.app`, minSdk 26) pinned to match `app/build.gradle.kts` — asserted by `check_api_contracts.py`.
- The website's download link therefore does **not** point at a placeholder artifact; nothing was published.
- Backend ships as source with `backend/.env.example` tracked and unchanged; the mod jar is built by the owner.

To produce the artifacts (owner's machine, with secrets supplied through the environment only):

```bash
cd android && CRAFTMIND_RELEASE_STORE_FILE=… CRAFTMIND_RELEASE_STORE_PASSWORD=… \
  CRAFTMIND_RELEASE_KEY_ALIAS=… CRAFTMIND_RELEASE_KEY_PASSWORD=… \
  ./gradlew :app:signReleaseBundle          # app/build/outputs/bundle/release/app-release.aab
./gradlew :app:packageRelease               # app/build/outputs/apk/release/app-release.apk
scripts/verify-release-apk.sh <path-to-apk> # package + versionCode/versionName against Gradle
python3 scripts/check_api_contracts.py      # three-way contract consistency
```

## 9. Remaining release blockers, prioritised

| # | Blocker | Severity | What clears it |
| --- | --- | --- | --- |
| B1 | No signed APK/AAB exists, so the download experience can only stay disabled | P0 for a public release | Run the Gradle release tasks on a machine with the SDK and keystore, verify with `verify-release-apk.sh`, publish the artifact, then update `download.html` to link the real URL (and keep the facts pinned) |
| B2 | The Android module has never been compiled in this environment | P0 | `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease` on a machine with JDK 17+/SDK 35; fix whatever it reports |
| B3 | No browser E2E of any website journey | P1 | add a Playwright project (owner's decision: new dev dependency) or a manual test script run against a hosted backend |
| B4 | Website password recovery cannot be *completed* in a browser (no confirm panel; A1 removed the contract error, it did not add the page) | P1 | small follow-up: a confirm panel posting `{token,newPassword}`, or a copy change that says recovery is completed in the app |
| B5 | No CI installed (only `ci-workflow.yml.example`) — deliberate, not an oversight | P1 for team work | owner enables the example workflow or an equivalent; it was not installed here because that changes deployment behaviour without being asked |
| B6 | Mod + server runtime behaviour (pairing, preflight, cancellation, reconnect, Bedrock refusal) unproven against a real Minecraft instance | P1 | run `minecraft-bridge` on Fabric `1.20.1` with loader `0.16.10`/API `0.92.2+1.20.1` and pair the app; follow `RELEASE_CHECKLIST.md` |
| B7 | No hosted backend/SMTP/backup target configured; `backend/.env.example` values are placeholders | P1 | operator configuration per `docs/production-operations.md`; nothing in the repo claims this is done |
| B8 | No lockfile in `backend/` | P2 | acceptable while runtime dependencies stay at zero; add one before introducing any |

## 10. Explicitly not done, and why

Phases 28 (payments, commissions, refunds, payouts) and 29 (memberships, subscriptions, purchased credits, Trusted
Seller) remain deferred by the owner's instruction; Advanced Live AI Build Mode, Phase 35 Security Guardian AI,
redesigns, and marketplace expansion were not started. No CI workflow was installed, no deployment was performed, no
`.env` file was edited, no protocol or schema version was downgraded, no keystore was generated, and no prior phase's
work was rebuilt or duplicated. Where documentation and code disagreed, the code was treated as the spec and the
document was corrected or the mismatch recorded above; no capability was invented to make a document true.

**Status of Phase 34: partial by design.** Every check available in this environment was run, one stale assumption was
repaired at the repository level, two contract defects were fixed with regression coverage, and the residual blockers
B1–B3 and B6–B7 all require infrastructure or credentials that this phase must not simulate. CraftMind is *prepared*
for release. It is not released, and it is not proven production-ready.
