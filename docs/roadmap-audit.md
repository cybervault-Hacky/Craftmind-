# CraftMind roadmap audit — Phase 36

**Audit date:** 2026-10-10. **Commit audited:** `f547986` (tip of `arena/6e167066-craftmind`), whose working tree is
byte-identical to this checkout. **Type:** verification and evidence only — no product code, test, dependency,
workflow, deployment, or history was changed by this phase. Every number below was produced by a command in this
environment, listed in §7, or it is labelled as not measured.

Method: read the repository, run what this environment can run, and treat prior agent reports as claims to be tested.
Two of those claims did not survive unchanged (§1.3), and one reported blocker was slightly mis-described (§4 B7).

## 1. Git baseline as actually found

### 1.1 Recorded state

| Item | Actual value |
| --- | --- |
| Working directory | `/home/user/Craftmind-` (single worktree, no submodules) |
| Branch checked out | `arena/6e167066-craftmind` |
| Local `HEAD` | `0cb2f42` — *"Merge pull request #4 from cybervault-Hacky/arena/ca67a923-craftmind"* |
| Upstream of the local branch | **none configured** (`git rev-parse --abbrev-ref @{u}` → fatal) |
| Remote | `origin https://github.com/cybervault-Hacky/Craftmind-.git` |
| Origin's tip of this branch | `f547986` — *"Phase 35: Security Guardian…"* |
| `main` (local and remote) | `0cb2f42`; no phase 30–35 content |
| Repository depth | **shallow clone** (`--is-shallow-repository` = true, local history = 1 commit) |
| `git status` | 20 tracked files modified, 29 untracked paths — see §1.2 |
| `git stash` / extra worktrees | none / none |
| `git diff --check` | clean |

### 1.2 Why the tree looked dirty, and what it proves

The sandbox was rebuilt by cloning `main` (`0cb2f42`) and restoring the Phase 35 workspace files on top of it. The
index therefore still holds the `main` content, so `git status` reports Phase 33–35 file edits as "modified" and the
new Phase 35 files as "untracked" even though **nothing was uncommitted or lost**.

Verified, not assumed: every path in the published commit was compared blob-by-blob against the working tree.

```
paths in published commit f547986: 467
MISSING from working tree: 0     CONTENT DIFFERS: 0     extra/untracked-but-unexpected: 0
```

Consequence for Git safety: no stash, reset, or cleanup was needed. The local branch ref was intentionally left at
`0cb2f42` for the duration of the analysis, because realigning it is a state change, not an audit finding; the
one realignment made at the end (a mixed `git reset` to `f547986`, files untouched, content re-verified afterwards) is
recorded in §9.

### 1.3 Discrepancies against the reported history

1. **Phases 30–35 are not on `main`.** `git rev-list --count main..f547986` → **7** commits
   (`010a7b0` Phase 30, `4ddb838` Phase 31, `1f4ad5d` Phase 32, `709dd8f` Phase 33, `c1d62b6` + `2381f36` Phase 34,
   `f547986` Phase 35). No pull request exists for them: `gh`-free inspection of `git ls-remote` shows `refs/pull/1…4`
   only, and PR #4 merged `arena/ca67a923-craftmind`. Anything described as "shipped" is therefore **published on a
   branch, not integrated**. This is the single most consequential correction to earlier phase reports: Phases 31–35
   were described without noting that the line their work must land on is still `main` minus six phases.
2. **`LICENSE` exists only on `arena/01a1014e-craftmind`** (3 commits ahead of `main`, never merged). `main` and
   `f547986` have no license file. A repository that publishes an APK and a public website has no license on its
   integration branch — an owner decision, not something an audit should invent.
3. **Other session branches were checked for stranded work.** `arena/01a1056e` (16 ahead), `arena/01a10c3f` (14),
   `arena/41b5c384` (25), `arena/ca67a923` (34). Path-level comparison against `f547986`: two branches contain files
   this tree lacks — `01a1014e` 60 (design system + OpenAI provider under `app/.../core/`, which this tree relocated to
   `app/designsystem/` and `app/data/ai/`, plus `LICENSE`) and `01a10c3f` 1
   (`BedrockBlockStateCompatibility.kt`, superseded by `MinecraftBlockStateCompatibility.kt`). The 92 lines of
   `ca67a923` content absent here were config lines that exist in this tree reflowed
   (`SECURITY_DEFAULT_PROTECTION_SECONDS`, `deniableScopes`, `throttleWindowMs`, TTLs all confirmed present in
   `backend/src/config.js`). **Conclusion: no agent work is stranded apart from `LICENSE`.**

## 2. Verified phase-status matrix

Classification is from source, tests, and documentation — never from a commit message. "Complete" below means *the
implementation and its automated tests exist in this tree*, which is not a claim that the feature was exercised on a
device, in a browser, or against a hosted service.

| Phase | Reported | Verified state | Evidence in this tree |
| --- | --- | --- | --- |
| 1–5 (foundation, AI engine, bridge) | complete | **Verified complete**, on the feature branch rather than in `main` at audit time; branch/protocol code present and unit-tested | `bridge-protocol/src/main` (15 `.java`), `minecraft-bridge/src/main` (32), 4 + 9 test files; README §Minecraft bridge |
| 6 | — | **Historical status unclear** — no README section, no commit subject naming it, no dedicated module found | searched `README.md`, `docs/*.md`, commit index |
| 7 (public video references) | complete | **Verified complete** | README §"Phase 7", `app/.../data/ai/` reference pipeline, backend tests |
| 8 (v1.0.0 release prep) | complete | **Verified complete as preparation**; release NOT performed (correctly) | `RELEASE_NOTES_v1.0.0.md`, `RELEASE_CHECKLIST.md` (all items still open), `scripts/verify-release-apk.sh` |
| 9 | complete | **Verified complete as specification** | `docs/minecraft-certification-testing.md` |
| 10–15 | complete | **Verified complete**; 11/12 are explicitly *architecture / recognized-but-not-executable* by design | compatibility core + adapters in `app/.../domain/minecraft/`, premium UI pages, `docs/premium-ui-ux-architecture.md`; 64 unit-test files pin the fail-closed rules |
| 16–17 | complete | **Verified complete** (accounts hardening, marketplace UI foundation) | README §Phase 18 preamble, `docs/account-authentication.md`, `docs/marketplace-ui-foundation.md` |
| 18 | complete | **Verified complete** | `backend/src/accounts.js`, `auth-hardening.test.js`, `docs/account-authentication.md` |
| 19 | complete | **Verified complete** | `backend/src/developer-*.js`, `admin-*.js`, `docs/developer-control-plane.md` |
| 20 | complete | **Verified complete** (runtime security subsystem) | `security-events/-detection/-policy/-tools.js` + `security.test.js`, `security-detection.test.js`, `docs/autonomous-security-response.md` |
| 21–22 | complete | **Verified complete** | creator/membership/credit services + tests, `docs/membership-entitlements-and-credits.md` |
| 23–27 | complete | **Verified complete** (creator capability, onboarding, listings, hire-a-builder, order lifecycle) | `creator-profiles.js`, `marketplace-listings.js`, `hire-jobs.js`, `order-lifecycle.js` + one test file each; README §"note on numbering" |
| 28 payments/commissions/refunds/payouts | deferred | **Deferred by owner decision — boundary intact** | no route, service, or client path: grep for `charge|stripe|paypal|payout` over `backend/src`, `website/assets`, `app/src/main` → **0 hits**; adapters answer `NO_BACKEND_IMPLEMENTED` |
| 29 purchasable memberships/credit purchase/Trusted Seller | deferred | **Deferred by owner decision — boundary intact** | grep `trustedSeller|trusted_seller` → **0 hits** in code; `referrals.js:48` states "no payout, no commission, no subscription, no entitlement, and no balance. Phases 28-29 stay deferred." |
| 30 trust, reports, disputes | complete | **Verified complete** (integration: see the PR history) | `marketplace-trust.js`, `order-disputes.js`, `*-api.js`, `marketplace-trust.test.js`, `order-disputes.test.js`, `docs/marketplace-trust-disputes.md` |
| 31 analytics & insights (read-only) | complete | **Verified complete** (integration: see the PR history) | `marketplace-analytics*.js` + test, `docs/marketplace-analytics.md` |
| 32 referral tracking & attribution | complete | **Verified complete** (integration: see the PR history) | `referrals*.js` + `referrals.test.js`, `docs/referrals-and-attribution.md` |
| 33 production hardening | complete | **Verified complete** (integration: see the PR history) — see §2.1 | `config.js`, `server.js`, `health.js` (88 lines), `db-maintenance.js` (180), `index.js` (SIGTERM), `backend/scripts/{backup,restore}-database.mjs`, `production-hardening.test.js` (58 KB), `docs/production-operations.md` |
| 34 release readiness | complete-with-blockers | **Partially complete by design** (audit + 2 defect fixes + checker; the artifact work it documents remains open) | see §2.2 |
| 35 Security Guardian | complete | **Verified complete** (integration: see the PR history) — see §2.3 and §5 | `backend/src/security-guardian.js` (1 419 lines, 20 rules), `scripts/security-guardian.mjs` (214), `test/security-guardian.test.js` (648, 62 tests/7 suites), `docs/security-guardian.md` |
| Live AI Build Mode | deferred | **Deferred; no partial implementation found** | grep `Live AI Build|liveAiBuild|LIVE_BUILD` → 0 hits |

### 2.1 Phase 33 — re-verified in source and by execution

| Claim | Check performed here | Result |
| --- | --- | --- |
| Startup validation refuses unsafe production configuration | `backend/src/config.js` + `auth-hardening.test.js:351` "requires production webhook mail, TLS termination, persistent storage, and non-wildcard HTTPS origins" | present, test passes |
| HTTP request/header/keep-alive bounds | `headersTimeout`, `requestTimeout`, `keepAliveTimeout` in `config.js` **and** `server.js` | present |
| `/health` and `/live` | both served from `server.js`, implemented in `src/health.js` | present |
| Graceful shutdown | `SIGTERM` handling in `src/index.js` | present |
| Backup/restore primitives | `src/db-maintenance.js` + `scripts/backup-database.mjs`, `scripts/restore-database.mjs` | present (no scheduler, correctly) |
| Operational documentation | `docs/production-operations.md`, incl. "Deployment prerequisites (not configured, not claimed)" | present |
| Suite still green | `cd backend && npm test` | **468/468 pass, 88 suites, 0 skipped** (104.7 s) |

No Phase 33 test was weakened or removed: `production-hardening.test.js` is still 58 KB and runs.

### 2.2 Phase 34 — the two fixes are still in the shipped path

- `website/assets/adapters.js:344` — `confirmPasswordReset({ token, newPassword })` posting
  `/auth/password-reset/confirm`, matching `backend/src/server.js:679` and the server's `requireString(body,"token")` +
  `("newPassword")`. Regression coverage: `backend/test/website-api-contract.test.js` (drives the **real** adapter over
  HTTP against a live `startService()`).
- `saveSellerOnboarding` renamed back from `saveSellerNoardboarding` (`adapters.js:177`, `:364`) and the 403 entitlement
  refusal is now `refusedNotUnauthenticated: true` (`:241`, `:265`) instead of being mapped to `UNAUTHORIZED`.
- `scripts/check_api_contracts.py` exists, runs, and PASSes: *"bridge protocol/schema/bridge-version facts agree between
  Java, Gradle, README, download.html and verify-release-apk.sh"*, plus 6 NOTE lines — 4 routes whose body contract
  it cannot read mechanically (`/auth/logout`, `/auth/password-reset/request`, `/auth/resend-verification`,
  `/auth/sessions/revoke-all`, reviewed by hand), the coverage ratio (20 of 106 machine-readable), and its own scope.
- Download experience: `website/download.html:64` is `class="button button-disabled" … disabled` with
  *"Not available — no signed APK has been published."* ✓ no dead link, no fabricated URL.
- Classification: **partially complete by design** — correct for an audit phase; the blocker list it wrote down is
  re-tested in §4 rather than copied.

### 2.3 Phase 35 — implementation present and behaving as documented

`grep -c 'id: "CRAFTMIND_'` → **20 rules**; stamps `GUARDIAN_VERSION 1.0.0`, `FINDING_SCHEMA_VERSION 1`,
`RULE_SET_VERSION 1`. Containment, masking, bounds, suppression, and exit-code behaviour were re-verified against the
running tool, not the write-up: §5 records a fresh scan and §5.3 the files it deliberately did not read. The suite runs
**62/62 pass in 7 suites** on its own and inside the full 468.

**One of Phase 35's verification claims does not survive its own commit** — see B9 in §4: the suite and the scan are
green, but `scripts/check_release_config.py` fails on the committed tip because the phase added a tracked file
containing deliberately fake credential text. The claim "`check_release_config.py` → PASS" was true the moment it was
measured and false once the same files were tracked.

One measurement difference worth recording so nobody chases a phantom: `trackedFilesReviewed` was **430** in this
audit and **456** in the Phase 35 report. The rule counts files from the **git index**; in this shallow re-clone the
index sits at `main`, so the 26 files added by Phases 33–35 were not in it. Same tree, different index — an operator
should confirm `git status` is clean before trusting that number, or the report silently under-covers tracked-file
review.

## 3. Component inventory (measured in this tree)

| Component | Implementation state | Paths | Automated tests | Last verification here | Known gaps |
| --- | --- | --- | --- | --- | --- |
| **Android app** | present, 146 Kotlin sources; Compose UI, account/bridge/AI layers | `app/src/main/java/com/craftmind/app/` | **64 unit files / 563 `@Test`**, 10 instrumented files / 28 `@Test` | **never executed in this environment** (no JDK/SDK) — static reading only | B1/B2: no build, no lint, no install on a device; `schemaVersion` literals (§5.2) |
| **Website** | 41 HTML pages, 13 modules, one stylesheet | `website/` | validated structurally by `check_website.py`; behaviour covered at service level by `website-api-contract.test.js` | checkers PASS (41 pages, 1179 links) | **no browser E2E**; no password-recovery *confirm* UI (B4); `account.js` is loaded through `site.js:10` so pages have no direct per-page scripts |
| **Backend API** | 54 modules / 19 851 lines, 106 routes, zero runtime deps | `backend/src/` | 21 test files, 435 `it()` blocks; runner reports 468 tests / 88 suites | full suite green in 104.7 s | no hosted database/SMTP/backup target; no lockfile (none needed at zero deps) |
| **Bridge protocol** | 15 `.java`, `VERSION = 2`, `BUILD_PLAN_SCHEMA_VERSION = 2`, codec rejects mismatched versions with `UNSUPPORTED_PROTOCOL` | `bridge-protocol/src/main/.../protocol/` | 4 JUnit files | not compiled here | needs JDK to prove (B2/B6) |
| **Minecraft bridge (Fabric)** | 32 `.java`, capability-gated construction, HTTP listener on a private interface, app-version pattern enforced | `minecraft-bridge/src/main/.../fabric/` | 9 test files | not compiled here; **no real server test exists** | B6 |
| **Security Guardian** | 1 419-line module + 214-line CLI + 648-line suite, 20 rules | `backend/src/security-guardian.js`, `backend/scripts/security-guardian.mjs` | 62 tests / 7 suites | **green here, and the scan was re-run** | 20 patterns only; no dependency/CVE analysis; `.css`/`.svg`/`.gitignore`/wrapper files unread (§5.3) |
| **Release & deployment tooling** | 4 repo checkers, APK verifier, backup/restore scripts, 3 example workflows, `netlify.toml` | `scripts/check_*.py`, `scripts/verify-release-apk.sh`, `backend/scripts/`, `ci-workflow.yml.example` | the checkers themselves | all four PASS; `bash -n` clean | `.github` absent by owner choice (B5); nothing deploys or publishes |

Cross-cutting: no monetisation or "live build" surface exists anywhere in the client adapters
(`NOT_IMPLEMENTED: "NO_BACKEND_IMPLEMENTED"`, `unavailable()`/`unconfigured()`), which is the *correct* state for the
deferred phases; `check_api_contracts.py` fails if such a surface ever appears in `adapters.js` unannounced.

## 4. Release blockers, re-tested

| ID | Reported | Actual state now | Basis |
| --- | --- | --- | --- |
| **B1** signed APK/AAB | open | **BLOCKED — unchanged.** No artifact exists; `app/build/` does not exist; no `.apk`/`.aab`/`.dex` anywhere in the repo or workspace | `find` over repo + `/home/user`; website download button still disabled and its copy still says none is published ✓ consistent |
| **B2** Android build/tests | open | **BLOCKED — toolchain absent, not skipped by choice**: no `java`, `javac`, `keytool`, `gradle`, `adb`, `sdkmanager`, `aapt2`; `ANDROID_HOME`/`ANDROID_SDK_ROOT` unset; no `~/.gradle`, no `/usr/lib/jvm` | PATH probe; the Gradle wrapper cannot bootstrap because `services.gradle.org` and the Android/Maven hosts are outside this sandbox's network allowlist |
| **B3** browser E2E | open | **BLOCKED.** No `chromium`/`chrome`/`playwright` binary, no browser test project, no `node_modules`. What exists is *service-level* HTTP testing of the real adapter module — explicitly not browser E2E | PATH probe; `website-api-contract.test.js` header |
| **B4** password-recovery UI | open | **OPEN, CONFIRMED as a real product gap** (not merely a contract question). `requestPasswordReset` is wired (`website/assets/account.js:228`); `confirmPasswordReset` exists **only** in `adapters.js:344` — no page or module calls it, and `website/account/` has `index, membership, profile, purchases, saved, security, sessions` and **no recovery-confirmation screen**. The backend route and the Android path both exist, so a web sign-up cannot finish recovery while an Android user can | grep across `website/` for each method; `server.js:679`; `HttpAccountApi.kt` |
| **B5** CI | open | **OPEN, AND NOT CLOSEABLE FROM THIS WORKSPACE.** `.github` absent; `ci-workflow.yml.example`, `android-apk-workflow.yml.example` and `website/github-pages-workflow.yml.example` remain examples because GitHub refuses every workflow-file write from the agent's App token — measured twice in the final phase, by `git push` (`refusing to allow a GitHub App to create or update workflow … without workflows permission`) and by the Contents API (`403 Resource not accessible by integration`). Nothing runs automatically, and no run has ever existed (`GET /repos/…/actions/workflows` → `total_count: 0`) | `ls -a .github`; `gh api repos/…/actions/workflows`; a push that adds a workflow file |
| **B6** Minecraft runtime | open | **BLOCKED.** Fabric 1.20.1 mod + loader 0.16.10 + API 0.92.2 exist as source; no JDK/Gradle, no server, no Docker → pairing/preflight/cancellation/refusal behaviour is unproven against a live instance. Static evidence that unsupported input is rejected *in code*: codec version check, `APP_VERSION_PATTERN`, and 9 unexecuted test files | source reading; PATH probe |
| **B7** hosted deployment, SMTP, backups | open | **OPEN — with one correction to the earlier wording.** There is no Dockerfile, compose file, Procfile, fly/vercel/netlify config, or k8s manifest ✓; `backend/.env.example` carries 112 assignments (the readiness checker's figure is **107 validated keys, all documented**). The repo ships **no SMTP client by design**: `config.js:272-285` accepts `EMAIL_PROVIDER=memory|webhook` and *refuses to start* in production unless a webhook URL is supplied, so "SMTP not configured" understates it — delivery is delegated to an owner-hosted webhook. Backup/restore are single commands with no scheduler, which the doc already states | `ls` for deploy artifacts; `check_deployment_readiness.py` PASS line; `config.js` lines |
| **B8** dependency/supply chain | open | **PARTIALLY VERIFIED.** Backend: `package.json` declares no `dependencies`/`devDependencies` and no lockfile exists → nothing to audit *for the backend*, and that must not be read as project-wide. The real surface is Gradle: 46 pinned entries in `gradle/libs.versions.toml`, **no dynamic or `+`/`latest.release` ranges** (checked), but the declared versions were never resolved or verified by this environment, and `scripts/*.py` use the Python standard library only | manifests read; grep for dynamic ranges; no `npm audit`/`pip audit` run (nothing to audit locally, no network to the registries) |
| **B9** repository gate red on the current tip | not previously reported | **RESOLVED in Phase 37A** (was OPEN — found by this audit). `python3 scripts/check_release_config.py` → `FAIL: known credential/private-key pattern requires review in tracked file: backend/test/fixtures/security-guardian/rule-cases.corpus`. The corpus is the Security Guardian's own test data and legitimately contains a PEM marker in its `CRAFTMIND_PRIVATE_KEY_BLOCK` case, which is the one pattern of the checker's four (PEM, `AKIA…`, `AIza…`, `gh[pousr]_…`) that matches. The `sk_live_…` string this row originally blamed is not one of the checker's patterns — corrected here rather than silently rewritten, because the distinction is what made the diagnosis trustworthy. The checker sweeps *tracked* files and had no exclusion for it. `main` is green because the file does not exist there — **the gate broke exactly at `f547986`**. Consequence: line 75 of `ci-workflow.yml.example` runs this checker, so installing CI (B5) would have failed on the first run, and any release process running the documented gates failed. Fixed in Phase 37A by an exact-path exemption that validates its own reason to exist; see §4.1. | all four checkers PASS on the fixed tree; 8 new tests in `backend/test/release-config-corpus.test.js` |


### 4.1 B9: what the fix does, and what it deliberately cannot do

`scripts/check_release_config.py` now carries one allow-listed path in `SECRET_SWEEP_EXEMPTIONS`, naming
`backend/test/fixtures/security-guardian/rule-cases.corpus`, and the exemption applies to the credential sweep alone.
The corpus stays tracked, stays inside the key/keystore/APK artifact check, and every other file keeps being scanned.

Three guards keep the exception from rotting into a hole: an entry outside `backend/test/fixtures/security-guardian/`
fails the gate, so the list can never be used to shelter application code; an entry that is no longer tracked fails as
stale; and an entry whose contents no longer match any secret pattern also fails — which is precisely the check that
would catch somebody "fixing" the gate by sanding the adversarial examples off the corpus. The skip is announced in the
output (`NOTE: credential sweep skipped 1 tracked file(s) by exact-path exemption: …`), so an exemption can never be
read as a sweep that covered it.

Detection was re-proven rather than asserted: temporarily appending a PEM marker to `docs/security-guardian.md` made
the gate exit 1 naming that file and the rule, with no key material echoed, and the file was restored byte-for-byte
before committing. The check turned out to be load-bearing on its own author's work too: the first draft of the new
regression test wrote the marker out as a contiguous literal, and once that file was tracked both gates caught it —
the release gate failed on `backend/test/release-config-corpus.test.js`, and the Guardian gained a 22nd finding — until
the literal was assembled at runtime the way the Guardian's suite already does it. A fix that had been graded only by
"does the corpus stop complaining" would have shipped that instead. The rest — application code, a different fixture, files sitting adjacent to the exempt corpus inside
the same directory — is covered by the eight new tests instead of by argument.

**Also open, found by this audit and not in the earlier list:** (a) Phases 30–35 not yet in `main` when this audit ran (§1.3.1);
(b) no `LICENSE` on the integration branch (§1.3.2); (c) `docs/release-readiness.md` still describes B7 as
"no hosted backend/SMTP" and B1/B2 as pending without noting that **this environment can never run them**, which is
fine but should be stated so no future agent "fixes" it by faking a build.

## 5. Security Guardian — fresh scan and finding-by-finding review

### 5.1 The run

```
cd /home/user/Craftmind-/backend && node --no-warnings=ExperimentalWarning scripts/security-guardian.mjs
  --format json --output /tmp/p36-scan.json            → exit 0 (nothing at or above the HIGH gate)

status FINDINGS | open 21 | total 21 | {CRITICAL 0, HIGH 0, MEDIUM 4, LOW 0, INFORMATIONAL 17}
files 459 / 467 considered | rules consulted 20 of 20 | routes inspected 39 | tracked files 430 (see §2.3)
readFailures [] | skippedBytes 0 | limitsHit [] | cancellation none | complete true | enumeration git
skipped {not-source: 6, generated-or-binary: 1, no-applicable-rule: 1}
```

Two calibrations were re-proved rather than trusted: the credential rule's test-path policy (73 fake test passwords
are *not* reported, while a provider-prefixed key still would be) and the four exit-code semantics
(`--fail-on HIGH` → 0, `--fail-on MEDIUM` → 1 on the same tree, i.e. the gate bites exactly where it says).

### 5.2 All 21 open findings, individually

| # | Rule | Sev / conf | Location | File read? | Assessment | Follow-up |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `PROTOCOL_VERSION_LITERAL` | MEDIUM / HIGH | `app/src/main/java/com/craftmind/app/data/ai/BuildPlanRefinementPrompt.kt:69` | yes | Requires manual review — protocol constant duplicated as a literal in shipped code, **not an exploitable vulnerability** | Single-source the value from `BridgeProtocol` **or** record an accepted-risk decision; either way extend `check_api_contracts.py` to assert the Kotlin sites (it pins the Java/Gradle/README/download page today and would not notice drift here) |
| 2 | `PROTOCOL_VERSION_LITERAL` | MEDIUM / HIGH | `app/src/main/java/com/craftmind/app/presentation/builds/BuildExecutionViewModel.kt:132` | yes | Requires manual review — protocol constant duplicated as a literal in shipped code, **not an exploitable vulnerability** | Single-source the value from `BridgeProtocol` **or** record an accepted-risk decision; either way extend `check_api_contracts.py` to assert the Kotlin sites (it pins the Java/Gradle/README/download page today and would not notice drift here) |
| 3 | `PROTOCOL_VERSION_LITERAL` | MEDIUM / HIGH | `app/src/main/java/com/craftmind/app/presentation/builds/PlanReviewScreen.kt:477` | yes | Requires manual review — protocol constant duplicated as a literal in shipped code, **not an exploitable vulnerability** | Single-source the value from `BridgeProtocol` **or** record an accepted-risk decision; either way extend `check_api_contracts.py` to assert the Kotlin sites (it pins the Java/Gradle/README/download page today and would not notice drift here) |
| 4 | `PROTOCOL_VERSION_LITERAL` | MEDIUM / HIGH | `app/src/main/java/com/craftmind/app/presentation/builds/PlanReviewScreen.kt:622` | yes | Requires manual review — protocol constant duplicated as a literal in shipped code, **not an exploitable vulnerability** | Single-source the value from `BridgeProtocol` **or** record an accepted-risk decision; either way extend `check_api_contracts.py` to assert the Kotlin sites (it pins the Java/Gradle/README/download page today and would not notice drift here) |
| 5 | `PROTOCOL_VERSION_LITERAL` | INFORMATIONAL / HIGH | `app/src/test/java/com/craftmind/app/domain/account/LocalOwnershipMigrationTest.kt:118` | yes | Requires manual review — protocol constant duplicated as a literal in shipped code, **not an exploitable vulnerability** | Single-source the value from `BridgeProtocol` **or** record an accepted-risk decision; either way extend `check_api_contracts.py` to assert the Kotlin sites (it pins the Java/Gradle/README/download page today and would not notice drift here) |
| 6 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/accounts.js:144` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 7 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/accounts.js:145` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 8 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/accounts.js:157` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 9 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/accounts.js:195` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 10 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/accounts.js:559` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 11 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/creator-profiles.js:260` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 12 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/hire-jobs.js:701` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 13 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/marketplace-listings.js:412` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 14 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/order-lifecycle.js:436` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 15 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/security-engine.js:303` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 16 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/security-engine.js:304` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 17 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/security-engine.js:335` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 18 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/security-tools.js:301` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 19 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/security-tools.js:304` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 20 | `SQL_INTERPOLATION` | INFORMATIONAL / MEDIUM | `backend/src/server-workspaces.js:228` | yes | Informational by design — the scanner proved the interpolated identifier is fed only literals/closed-set values in this file; each record carries that proof in `ruleNote` | None required. Re-run after any change to the helper's call sites; the `?`-bound values themselves are already parameterised |
| 21 | `SENSITIVE_VALUE_IN_LOG` | INFORMATIONAL / MEDIUM | `backend/test/security-guardian.test.js:247` | yes | Scanner self-match: the flagged line is a *fixture string inside the Guardian's own test*. Deliberately reported rather than exempted | None. Fixing it by exempting the scanner's own tests would weaken the tool; it is INFORMATIONAL because the rule downgrades test paths |

**Do not read these as 21 vulnerabilities.** Four are review recommendations about a duplicated protocol constant in
Android UI, sixteen are the tool writing down *why it is not* a finding (each one's `ruleNote` states the proof it
found), and one is the Guardian reporting a fixture inside its own test file. Nothing here is a confirmed exploit;
nothing here needed suppression, and nothing was suppressed — `staleSuppressions` is empty and no
`security-guardian.json` exists in the repository.

On the four MEDIUM items specifically (all `schemaVersion == 2` / `== 1` comparisons, read in context):

| Site | What the code actually does | Security character |
| --- | --- | --- |
| `BuildPlanRefinementPrompt.kt:69` | `if (request.basePlan.metadata.schemaVersion == 1)` selects a **legacy-v1 upgrade instruction** for the refinement prompt | not a vulnerability; wrong-branch risk is prompt wording, and it is the one site where a future bump would silently keep generating v1 guidance |
| `BuildExecutionViewModel.kt:132` | refuses execution with `BUILD_PLAN_NOT_EXECUTABLE` unless `schemaVersion != 2` fails, alongside status and operation-count bounds | a **fail-closed pre-flight gate**; drift here causes refusal, not unsafe action |
| `PlanReviewScreen.kt:477` and `:622` | enable/disable the construction and re-execution affordances on the same equality | UX gating; the bridge independently rejects unsupported versions (`BridgeProtocolCodec.java:70`) so the server-side boundary does not depend on this literal |

Verdict: **intentional protocol constants that should be single-sourced, plus one real hardening gap** — nothing
automatic checks that these four Kotlin literals agree with `BridgeProtocol.VERSION = 2` / `BUILD_PLAN_SCHEMA_VERSION = 2`
(the Phase 34 checker pins the Java, Gradle, README, `download.html` and verifier sides, not the Kotlin ones). Drift
would mean a confusing refusal or stale prompt guidance rather than a compromise. No Android code was changed in this
audit, per instruction; the fix belongs to an approved follow-up with the test that makes it stick.

### 5.3 Scanner limitations found while auditing it (not suppression, coverage facts)

- **8 of 467 candidate files are never read**, all by policy: `backend/test/fixtures/security-guardian/rule-cases.corpus`,
  `.gitignore`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `website/favicon.svg`,
  `website/styles.css`, `backend/public/developer.css`. Two of those are **live application assets**: `.css` and `.svg`
  are not in `SOURCE_SUFFIXES`, so an `@import url(http://…)`, a cleartext origin, or a token parked in a CSS custom
  property would be missed. This is the most useful single improvement available to the Guardian.
- The report gives skip **counts by reason**, not the paths, so "what did it not read?" needs the above manual step.
- `no-applicable-rule` and `trackedFilesReviewed` depend on the git **index**, so a shallow or mid-merge clone changes
  them without any change in code (§2.3).
- Scope is source text only: no dependency/CVE analysis, no taint tracking across files, no runtime probing, no
  authorisation-logic reasoning. "0 HIGH" means "no pattern matched", never "secure" — the report says this itself in
  its closing section.

## 6. Environment capabilities and limits (what made B1/B2/B3/B6 blocked)

| Tool | Present | Consequence |
| --- | --- | --- |
| Node `v22.22.3`, npm `10.9.8` | yes | backend suite, Guardian, and CLI checks all runnable — and were run |
| Python `3.11.2` | yes | the four `check_*.py` scripts (stdlib only) |
| git `2.39.5` | yes | history, index, and blob comparison (repo is a **shallow** clone) |
| `unzip` 6.00 | yes | would allow APK inspection; no APK exists to inspect |
| `java` / `javac` / `keytool` / `gradle` / `adb` / `sdkmanager` / `apksigner` / `aapt2` | **no** | B1, B2, B6 cannot be advanced here, by any means short of installing a JDK |
| `chromium` / `chrome` / `playwright` | **no** | B3 cannot be advanced here |
| `sqlite3` CLI | no (Node's `node:sqlite` is used by the app itself) | ad-hoc DB inspection is possible only through the repo's own modules |
| `docker` | **no** | no real Minecraft server, no hosted-service simulation |
| Network | allowlisted to `github.com`, `codeload`, `api.github.com`, `registry.npmjs.org`, `pypi.org`, `files.pythonhosted.org` | Gradle/Maven/Fabric artifact hosts and any deployment target are unreachable — so even a present JDK could not resolve dependencies |

## 7. Commands executed here, with exact results

| Command | Result |
| --- | --- |
| `git rev-parse --abbrev-ref HEAD`, `git rev-parse HEAD`, `@{u}`, `git remote -v`, `git status`, `git stash list`, `git worktree list` | recorded in §1.1 |
| `git ls-remote origin` | `refs/heads/arena/6e167066-craftmind = f547986…`, `main = 0cb2f42…`, PR refs 1–4 |
| `git merge-base --is-ancestor {709dd8f,c1d62b6,2381f36} f547986` | YES for all three; `f547986` is **not** an ancestor of `main` |
| blob-by-blob tree comparison, `f547986` vs working tree (467 paths) | 0 missing, 0 differing, 0 unexpected |
| `git rev-list --count main..f547986` | 7 |
| `cd backend && npm test` | **468 tests / 88 suites / 468 pass / 0 fail / 0 skipped / 0 todo**, 104.7 s |
| `node --no-warnings=ExperimentalWarning --test test/security-guardian.test.js` | **62 tests / 62 pass / 0 fail** |
| `python3 scripts/check_api_contracts.py` | PASS (protocol/schema/bridge-version facts agree; 4 NOTEs for hand-reviewed contracts) |
| `python3 scripts/check_website.py` | PASS (41 pages) |
| `python3 scripts/check_deployment_readiness.py` | PASS (107 configuration keys, all documented; zero declared dependencies) |
| `python3 scripts/check_release_config.py` | **FAIL** as audited, **PASS** after Phase 37A — see B9 and §4.1. The identical command reported PASS during Phase 35 because the corpus was not yet tracked; re-run here on a clean index it failed. `check_api_contracts.py`, `check_website.py`, `check_deployment_readiness.py` all PASS on the same tree, and still do. |
| `node scripts/security-guardian.mjs …` (full scan + gate probes) | §5.1; `--fail-on HIGH` → exit 0, `--fail-on MEDIUM` → exit 1, `--paths docs` → exit 0 |
| `node --check` on module, CLI, test | clean (3 files) |
| `git diff --check` | clean |
| `find` for `*.apk`, `*.aab`, `*.dex` in repo and workspace; `ls app/build` | none; `app/build` does not exist |
| `ls -a .github`, `ls` for Dockerfile/compose/Procfile/fly/vercel/netlify/k8s | absent; two `*.yml.example` files present |
| grep sweeps for deferred monetisation / Trusted Seller / live-build | 0 code hits (§2 matrix rows 28, 29, Live AI Build Mode) |

## 8. Checks **not** run, and why

1. `./gradlew :app:testDebugUnitTest`, `:app:lintRelease`, `:app:assembleRelease`, `:app:connectedDebugAndroidTest`,
   `:bridge-protocol:test`, `:minecraft-bridge:test`, `cd minecraft-bridge && ../gradlew test build` — **no JDK, no
   Android SDK, and Gradle/Maven/Fabric hosts unreachable**. 563 unit and 28 instrumented Kotlin tests and 13
   Java test files therefore remain unexecuted.
2. `scripts/verify-release-apk.sh <artifact>` — no artifact exists to verify.
3. Browser E2E of any journey (sign-in, recovery, marketplace, creator onboarding, security page) — **no browser or
   harness**. Service-level HTTP tests of `adapters.js` ran instead; they are not browser tests.
4. Real Minecraft server / pairing / preflight / cancellation / Bedrock-refusal tests — no server, no Docker, no JDK.
5. `npm audit`, dependency resolution, keystore generation, Play upload, deployment, publishing, any hosted-service
   contact, and any email send — out of scope by instruction and/or impossible here.
6. `git push --force`, history rewrite, `main` mutation, workflow installation, dependency additions — deliberately
   not done.

## 9. Changes made by this audit

One file: this document (`docs/roadmap-audit.md`). No production code, test, configuration, or dependency was touched;
`git diff --check` is clean and no artifact, database, key, or secret is included (the scan report was written to
`/tmp/p36-scan.json`, outside the repository, because a report contains internal paths even when masked).

Git actions, in order: read-only inspection → `git fetch origin` (plus one fetch of all heads, to test other
branches for stranded work) → after the document was complete, `git reset f547986` (mixed: index and `HEAD` only,
working tree untouched) so the local branch matches the published tip it was already carrying, then one focused
commit and a fast-forward push to `arena/6e167066-craftmind`. `main` was not modified, nothing was force-pushed, and
the 467-path blob comparison was re-run after the reset to confirm zero content change.

## 10. Recommended next implementation phases

Prioritised by *verified* blocker, kept deliberately narrow, and none of them can be completed inside this
environment — which is itself the main finding of this audit.

**Phase 37 prerequisite — make the repository's own gates green again (B9): DONE in Phase 37A.** The gate passes on a
clean tree now, and `ci-workflow.yml.example` needed no change — its invocation was always correct, the checker was
wrong to call the corpus a leak. What shipped is an exact-path exemption rather than the directory-wide one this audit
first sketched (`…/security-guardian/**` would also have exempted the benign fixture tree and anything later dropped
into that directory), which is the kind of widening an audit's prose tends to authorise and an implementer should
refuse. Two other options stayed rejected in execution as they were on paper: deleting or neutering the fixture, and
encoding it so the checker cannot see it.

**Phase 37 — Get an actual signed release artifact, and integrate it.** Addresses B1 + B2.
- *Owner prerequisites:* JDK 17, Android SDK 35, a keystore created **outside** the repository, network access to
  Gradle/Maven/Google artifact hosts, and a GitHub release with asset-upload permission. Nothing here can substitute
  for these.
- *User-visible outcome:* the download page links a real, verified, signed APK with a published SHA-256, and the
  Android build is proven rather than assumed.
- *Scope:* run `./gradlew test lint assembleDebug`; `:app:testDebugUnitTest`; `:app:lintRelease`; `:app:assembleRelease`;
  `scripts/verify-release-apk.sh app/build/outputs/apk/release/app-release.apk`; fix whatever the compiler and lint
  report (the 563 unit tests have never seen a compiler, so expect failures); then attach the artifact and checksum to a
  `v1.0.0` release and update `download.html` to link the published asset, keeping its pinned VERSION/VERSION
  CODE/PACKAGE/minSdk facts asserted by `check_api_contracts.py`.
- *Acceptance:* all four Gradle tasks green; verifier PASS; download button enabled **only** for the exact published
  asset; `check_website.py`, `check_release_config.py`, `check_api_contracts.py` PASS; `cd backend && npm test` still
  468/468; `RELEASE_CHECKLIST.md` items 1, 3, 4, 5, 8 ticked with recorded evidence.
- *Tests required:* the existing Kotlin suites (now actually executed), the existing four Python checkers, one new
  assertion that the published asset's checksum matches what the page links, plus manual install on a clean API 26+
  device. **Do not** bundle website, Minecraft, or deployment work into this phase.

**Phase 38 — Close the web recovery gap and give the site a real browser harness.** Addresses B4 + B3.
- *Outcome:* a signed-out user can complete password recovery in the website (request → open the emailed link →
  enter token + new password → confirmed and sessions revoked), and the website's journeys are covered by an actual
  browser test rather than only service-level HTTP tests.
- *Dependencies:* the owner's decision on adding a Playwright (or equivalent) project, since that introduces dev
  dependencies to a project that currently has none, and a hosted or locally-run backend for the harness to point at.
- *Acceptance:* recovery completes end to end in a browser against `startService()`; the Phase 34 service-level suite
  stays green; `check_website.py` still PASSes; the recovery page is reachable from the sign-in page and from
  `account/security.html`.

**Phase 39 — Repository integration hygiene.** Addresses the two new findings of this audit: open the pull request(s)
that bring Phases 30–35 onto `main` (or record why they are intentionally branch-only), and settle the `LICENSE`
question, which currently exists only on an abandoned session branch. Small, but everything else compounds on top of
it: today `main` — the branch anyone cloning the repository gets — lacks six phases, including all of the production
hardening, the release-readiness checker, and the Security Guardian.

Deferred for now, with reasons: the four `schemaVersion` literals (recommendation, not risk — fold into Phase 37 if
the Kotlin compiler forces the change anyway); dependency/SCA tooling (blocked on the same Gradle resolution, and
worthless until `app`/`bridge` dependencies are actually fetched); CI installation (owner decision, `B5`); everything
in Phases 28–29 (owner decision).

## 11. Owner actions required before any release claim

1. Provision the Android toolchain and run Phase 37's Gradle commands; sign and publish the artifact (B1, B2).
2. Install or decline `ci-workflow.yml.example`; nothing runs automatically today (B5).
3. Decide the web recovery path: add the confirm screen, or state plainly that recovery completes in the app (B4).
4. Provide a hosted backend, TLS terminator, `EMAIL_PROVIDER=webhook` endpoint, CORS origins, and the `CRAFTMIND_*`
   values; then schedule `backup-database.mjs`, copy backups off-host, and rehearse `restore-database.mjs` (B7).
5. Run the Fabric bridge against a disposable 1.20.1 server and pair a device; do not test on a valuable world (B6).
6. Merge or explicitly set aside Phases 30–35, and resolve the license (`main` currently has none).
7. Re-run the Guardian from a clean, fully-staged checkout so its tracked-file review covers everything (§2.3), and
   consider adding `.css`/`.svg` to its source set (§5.3).

Until items 1, 3, 4 and 5 are done, CraftMind is a well-tested **source** project with a disabled download button —
not a deployable, installable product, and no report in this repository claims otherwise.
