# Referral tracking & marketing campaign attribution (Phase 32)

Two related but separate capabilities, built on the existing architecture rather than beside it:

1. **Referral codes** — a user shares a code; another account claims it; the platform records that one account brought
   the other.
2. **Campaign attribution** — the normalized `source` / `medium` / `campaign` the claiming account arrived with,
   stored as marketing metadata on that same row.

Both are **append-only, private, and server-verified**. There are no rewards, no payouts, no "you earned ₹X"
statements, and nothing financially meaningful anywhere in this phase — a referral is recorded as a fact, not as a
debt.

Code: `backend/src/referrals.js` (logic), `backend/src/referrals-api.js` (HTTP wrappers), the v12 migration in
`backend/src/db.js`, four routes in `backend/src/server.js`, and the derived metrics in
`backend/src/marketplace-analytics.js`. Tests: `backend/test/referrals.test.js` (32 tests, 9 suites).

## Threat model, stated up front

An attacker's realistic goals here are: credit themselves for someone else's signup, mint unlimited codes to game a
future reward, learn who referred whom, replay a code to double-count a registration, or get a token in front of a
browser. Each is answered by construction, not by policy:

| Risk | Answer |
| --- | --- |
| **Fake referral / self-referral** | The claimer is always the session (`requireAccountIdForToken`); a claim for one's own code is refused; `CHECK (referrer_user_id <> referred_user_id)` makes the row itself unrepresentable. |
| **Replay / double count** | `UNIQUE` on `referred_user_id` — one attribution per account, ever, enforced by the database rather than by a check-then-insert. |
| **Code enumeration** | 48 bits of CSPRNG entropy, and the input must match `^CM-[0-9A-F]{12}$` before any query runs. Unknown, malformed and self-claims return **one identical** 400, so a response never distinguishes "no such code" from "that code is yours". |
| **Referrer impersonation** | `referrer_user_id` is copied from the **code row** by the server; `strictBody` rejects any extra key, so `{"referrerUserId":…}` is refused before the handler runs. |
| **Silent attribution rewriting** | A `BEFORE UPDATE` trigger aborts every change except the one-way `OBSERVED → VERIFIED` promotion — including re-pointing a row at another code. |
| **Private relationships surfacing** | Aggregate SQL only (counts, `bySource`) in analytics; the personal summary returns counts, never names. No id, email, or username of the other party appears in any referral response. |
| **Tokens in URLs** | Codes and campaign fields travel in POST bodies only. Nothing reads a query string, and the stored character sets make a URL, a path, or a query string unrepresentable. |
| **Unbounded write abuse** | A `referral-write` bucket (the existing trust-write budget: default 20 per 15 min, `RATE_TRUST_WRITE_MAX`) independent of the auth, trust and order budgets. |

**Not claimed:** cryptographic proof of *when* or *where* a visitor first saw a code, and any defence against a user
who deliberately shares their own code to farm a future reward — nothing here can tell a genuine advocate from a
self-server. That is exactly why rewards stay out of scope: this data is adequate for attribution analysis and
inadequate as a basis for payment.

## Lifecycle: OBSERVED → VERIFIED (the exact event)

| State | Meaning | Set by |
| --- | --- | --- |
| `OBSERVED` | A claim was recorded: both accounts existed, the code was valid, no conflict. Nothing has been confirmed about the relationship being worth anything yet. | the claim itself |
| `VERIFIED` | **Both** server-side facts hold — the referred account's `users.email_verified_at IS NOT NULL` **and** the referrer's `users.status = 'ACTIVE'`. | `POST /marketing/referrals/verify` |

`verifiedAt` is then written as the referred account's **own** `email_verified_at` value, so the attribution carries
the real verification time rather than the time someone happened to call `verify`.

The verifying event is deliberately *not*:

- **registration** — a throwaway signup would count as a completed referral;
- **first login** — a session event is not evidence of a person;
- **a client assertion** — every claim/confirm body is `strictBody`-closed, so `{"status":"VERIFIED"}` cannot exist.

Why the referrer term is part of it: an account suspended for abuse must not keep accumulating verified referrals. The
narrower alternative — verifying on the referee alone — is a no-op in disguise, because a session already requires a
verified email; and verifying on the referee *at claim time only* would strand every referral made by a later-closed
account in a dead state. Evaluating both facts at confirm time and writing once keeps the state honest and strictly
forward: the trigger rejects a `VERIFIED → OBSERVED` demotion (asserted directly in SQL by the tests).

Storage, API, metrics and this document all keep `observedAt`/`verifiedAt` and `observed`/`verified` apart; they are
never merged into one "referrals" number, and `OBSERVED` is never re-labelled as success by a read.

## Endpoints

All four are session-authoritative (`Authorization: Bearer`, resolved by `requireAccountIdForToken`), run inside the
shared `runTransaction`, and belong to the `SESSION` security category via the `routeSecurityCategory` prefix
`/marketing/referrals`.

| Endpoint | Body | Result |
| --- | --- | --- |
| `POST /marketing/referrals/code` | none | `200 { code, createdAt, issuedNow }`. Idempotent: one code per account, forever (`issuedNow: false` on every later call). |
| `POST /marketing/referrals/claim` | `{ referralCode, source?, medium?, campaign? }` | `201` new attribution · `200 { …, idempotent: true }` when the same code is re-submitted (never a second row, `observed_at` untouched) · `409 REFERRAL_CLAIM_CONFLICT` when a *different* code is attempted after one exists · `400 INVALID_REQUEST` otherwise. |
| `POST /marketing/referrals/verify` | none | `200 { status, verifiedAt, promoted }`. `promoted: false` is the honest "not yet / already done" answer, never an error. `400` when this account carries no attribution. |
| `GET /marketing/referrals/me` | — | `200 { code, codeCreatedAt, attribution, counts: { attributedRegistrations: {total,observed,verified}, received: {…} } }`. |

The claim response is `attributionView`: `attributionId, code, source, medium, campaign, status, observedAt,
verifiedAt, referrerDisclosed: false`. `referrerDisclosed: false` is not a stub — it is the endpoint telling you, in
its own shape, that the other half of the relationship is not available here. There is no read-by-user-id path, no
directory, and no list of "who used my code": an absent `supporters` array is the privacy decision, not an oversight.

Refusing without revealing: **unknown code**, **malformed code** (wrong length, wrong alphabet, oversized,
non-string) and **self-claim** all return the same `400 INVALID_REQUEST` with the message
`That referral code was not recognized.` A missing `referralCode` key is the only variant with its own required-field
message, because it never reached the lookup at all. Every refusal stores nothing, and none of them can be told apart
by an attacker probing for live codes.

## Campaign fields: one allowlist, one normalization, no URLs ever

| Field | Rule (service + `CHECK` constraint in the schema) |
| --- | --- |
| `source` | Must equal one of the **existing** `REFERRAL_SOURCES` in `onboarding.js` — `YOUTUBE`, `INSTAGRAM`, `GOOGLE`, `REDDIT`, `DISCORD`, `FRIEND_REFERRAL`, `MINECRAFT_COMMUNITY`, `OTHER` — compared exactly, so `youtube` and `GOOGLE_ADS` are both refused. Absent means `FRIEND_REFERRAL`: the one thing a code proves is that a person passed it on. Reusing that array is what keeps campaign reporting and the "how did you hear about us" survey speaking one vocabulary instead of two. |
| `medium` | `trim` → runs of whitespace become `-` → lowercase. Then 1–**40** characters and `^[a-z0-9][a-z0-9-]*$`. |
| `campaign` | Same normalization, 1–**64** characters, `^[a-z0-9][a-z0-9_-]*$`. A label, never a link. |

`youtube`, `YouTube`, `you  tube` and ` YOU TUBE ` all collapse to `youtube` — which is what makes grouping
meaningful. Values the class cannot express are refused rather than cleaned: `a.b`, `a/b` and `a?b` fail on the
separator characters, so `https://evil.test/x?ref=SECRET` can never be stored, and the test asserts the row count
stays 0 afterwards. Both the service pattern **and** a table `CHECK` enforce this, so a hand-written
`INSERT` cannot smuggle a URL into the column either. Blank-after-normalization means *not provided* and stores
`null`, never a stray empty string.

Over-length is a **rejection naming the field**, never a silent truncation: an accepted row always contains the whole
intended value. There is no referrer URL, landing path, query string, `gclid`/`fbclid`, cookie, `localStorage` read,
fingerprint, device identifier, or third-party analytics SDK anywhere in this phase — the only storage is these three
normalized columns.

## Storage (migration v12)

One entry in `MIGRATIONS`, following the repo's convention exactly (versioned, `schema_migrations`-guarded, additive,
re-running is a no-op, tested by upgrading a *real* v11 database rather than a fresh one):

- **`referral_codes`** — `code TEXT PRIMARY KEY` with `CHECK (length(code) = 15 AND substr(code,1,3) = 'CM-' AND
  substr(code,4) NOT GLOB '*[^0-9A-F]*')`; `user_id TEXT NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE
  CASCADE`; `created_at`, `updated_at`. The `UNIQUE` is what makes "one code per account" a database fact.
- **`referral_attributions`** — `attribution_id` PK; `referred_user_id NOT NULL UNIQUE REFERENCES users ON DELETE
  CASCADE` (one attribution per account ever); `referrer_user_id REFERENCES users ON DELETE CASCADE`; `code
  REFERENCES referral_codes(code) ON DELETE CASCADE`; the three campaign columns with their `CHECK`s; `status NOT NULL
  DEFAULT 'OBSERVED' CHECK (status IN ('OBSERVED','VERIFIED'))`; `observed_at NOT NULL`; `verified_at`;
  `CHECK (referrer_user_id <> referred_user_id)`; and `CHECK ((status='OBSERVED' AND verified_at IS NULL) OR
  (status='VERIFIED' AND verified_at IS NOT NULL))`, so a state and its timestamp can never disagree.
- **Indexes** — `referral_attributions_by_referrer (referrer_user_id, status, observed_at)` for the "who did I bring,
  and how many are verified" read, and `referral_attributions_by_observed (observed_at)` for the windowed analytics
  and trend scans.
- **Trigger `referral_attributions_immutable`** — `BEFORE UPDATE`, aborting with `referral attribution is immutable
  once recorded` if any of `attribution_id`, `referred_user_id`, `referrer_user_id`, `code`, `source`, `medium`,
  `campaign`, `observed_at` changes, if `OLD.status = 'VERIFIED'`, or if a promotion is attempted without a
  timestamp. `DELETE` is intentionally not blocked, so `ON DELETE CASCADE` still cleans up when an account is
  removed — a referral row must never outlive the accounts it names.

Ids come from `src/ids.js`: `newReferralCode()` (CSPRNG, `CM-` + 12 hex) and `newReferralAttributionId()`
(`rat_` + UUID, the same convention every other table here uses), plus `normalizeReferralCode()` which trims,
upper-cases and strips internal whitespace so a pasted code works — while never being able to *become* a different
code. Codes are not derived from the account id, the email, or a counter, so they cannot be enumerated by
observation. `node:sqlite` enforces foreign keys per connection, so the service opens with `foreign_keys = ON` like
every other service here.

### Two deliberate gaps, stated rather than papered over

- **Codes are permanent.** There is no `expires_at` and no revoke state, because nothing in the API could ever set or
  clear one: with no rewards attached, the only consequence of a circulating code is that a signup gets attributed. A
  revocation path becomes a requirement the day a code has value attached to it, and that is the monetization phase's
  job — not a column added now to look complete.
- **No pre-signup observation intake.** Storing an anonymous claim before an account exists was considered and
  rejected: nothing could consume it, it would amount to a cookie/localStorage persistence mechanism with no
  user-facing disclosure, and it invites exactly the URL/token handling this phase avoids.

### Why v12 is legitimate, not padding

`users.referral_source` (v7) is a *self-reported survey answer* on an account. It cannot express "account B was
brought by account A": there is no issuable code, no uniqueness, no relationship, no verification state, and it is
only filled in when the registrant types something. Grep over `backend/`, `website/` and `app/` before writing the
migration found no code store, no attribution table and no campaign columns — the only pre-existing marketing field is
that survey pair plus "promotional credits" wording. One additive version carrying both tables, their two indexes and
their trigger is the minimum that makes referral attribution a recorded fact.

## Guardrails this phase had to move (and what stayed put)

- `SCHEMA_VERSION` is now **12**; assertions across the suites that pin the current tip and the `schema_migrations`
  row count moved `11 → 12` (16 lines across 8 files, all pure version/row-count numbers). Seed-version assertions
  (`WHERE version = 4/7/8/9`, `count = 5`) were left alone, and the nine `migrateToVersion(db, <older>)` seeding calls
  are unchanged — the v11→v12 test really does start from a v11 database.
- `ErrorCode` gained **one** key, `REFERRAL_CLAIM_CONFLICT` (→ 409, with a fixed message); `security.test.js`'s key
  count moved `107 → 108`.
- The table set changed, so `security.test.js`'s sorted table-name list gained `referral_attributions` and
  `referral_codes`.
- `REGISTERED_AUDIT_ACTION_TYPES` stays **102** — and the migration test asserts exactly that.

## Deliberate non-decisions

- **No new audit action types, and no widening of the audit `CHECK(action IN …)` vocabulary.** `audit()` derives a
  resource kind from the table (needing a `RESOURCE_KIND` entry), and `admin_audit_log` is a **security** record
  readable by any developer account. A two-party private relationship does not belong in a developer-visible log, and
  routine claims do not belong in an append-only security record at all. Outcomes are already captured by the shared
  security log (success/failure of every write, with `rateLimitScope: "account"`), and the attribution table is itself
  the durable record.
- **One new error code, not five.** `REFERRAL_CLAIM_CONFLICT` is 409 because "already attributed to a different code"
  is a state the client should be able to branch on and recover from by reading `/me`; malformed, unknown, self and
  already-verified-edit cases are all `400 INVALID_REQUEST` with a field-specific message, which is what keeps the
  existence oracle closed.
- **No new config keys or environment variables, no dependency, no `.env` edit.** `referral-write` and
  `referral-read` are new *bucket labels* over existing budgets (`trustWrite`, `creatorProfileRead`).
- **No `RESOURCE_KIND` entry**, consistent with the audit decision above.
- **No `PATCH`/`PUT`/`DELETE` on any referral route** — an edit surface would need its own authorization model, and
  append-only is the privacy guarantee. `405` is asserted.
- **`/marketing/referrals/*` is the right namespace** (matching the existing `/marketing/campaigns/*`), not
  `/api/v1/admin/referrals`: none of this is admin-only. The admin surface stays the developer control plane.
- **Phase 31 analytics was extended, not duplicated.** Referral attribution is derived in `marketplace-analytics.js`
  from the same tables, in the same snapshot transaction, with no second service or store.

## Website integration: unchanged, with the exact missing prerequisite

| Surface | Status |
| --- | --- |
| `website/index.html` | **No change, on purpose.** Registration already collects `referralSource` / `referralDetail` as a self-reported survey; that stays the primary record, and this phase adds a *verified* record beside it. |
| `website/marketplace.html`, `website/settings.html` | **No change.** There is no referral entry point or disclosure surface to wire, and per this repo's rule a capture needs a visible place the user can see and clear. |
| `android/native-app` | **Not integrated.** There is no login or registration screen there at all — only guest access — so there is no code field to add. `ANDROID_BUILD = NOT_RUN`. |
| `backend/src/onboarding.js` | **Read, not modified.** `REFERRAL_SOURCES` is imported as the allowlist, which is the whole integration. |

A shareable link (`https://…/?ref=CM-…`) is **not** shippable today, and no fake button was added to imply it is.
What is missing, precisely:

1. **Signed issuance.** `apiBaseUrl` is injected at serve time by `renderSite()` (`__CRAFTMIND_API_BASE__` →
   `config.publicBaseUrl`) and `validateRegistrationInput()` already rejects a `referralCode` that fails
   `^CM-[0-9A-F]{12}$` — enough for a *typed* code, not for a *link*, because an unsigned query parameter is
   attacker-forgeable at scale. A link needs an HMAC over `{code, issuedAt, kid}` with its own secret. The repo has the
   primitive to build on and none of the machinery around it: `backend/src/ids.js` already derives keyed digests with
   `createHmac("sha256", authSecret)` under purpose-separating prefixes (`securitySourceDigest`, `securityAccountDigest`,
   `tokenDigest`), but there is **no** dedicated signing secret, no key-id concept, and no rotation script anywhere in
   this repository. Reusing `AUTH_SECRET` would not be free either: those digests are stored in rows, so rotating the
   secret invalidates what is already written — a link-signing key has to be its own, separable secret with a `kid`
   before rotation can mean anything.
2. **A pre-session verification path.** Token verification today lives inside authenticated handlers; a
   pre-registration consumer would need that primitive exposed with its own bounded budget.
3. **A disclosure surface.** The stored attribution has to be visible to the account it describes before capture ships
   — a `settings.html` referral section, which is UI work outside this backend phase.

Until those three exist, the feature is complete on its own terms: a code is issued through the API, shared however
the user likes, and typed in at claim time — which is the flow the tests drive over real HTTP.

## Verification

`cd backend && npm test` → **357/357 pass across 69 suites** (the baseline before this phase was 325/60: +32 tests,
+9 suites, zero pre-existing tests changed in behaviour). `node --check` on every touched module and the test file;
`python3 scripts/check_website.py` and `python3 scripts/check_release_config.py` pass; `git diff --check` clean.

`backend/test/referrals.test.js` drives real HTTP through the real router against real `:memory:` SQLite — these are
integration tests, not unit stubs:

- **Identifiers (6)** — one code per account across repeat calls (`issuedNow: false`); 12 accounts → 12 distinct
  codes, none containing any fragment of the account id or email; `UNIQUE`/format enforced directly in SQL;
  malformed, missing, empty and oversized input refused before lookup; unknown vs self identical; re-cased and padded
  input accepted and stored canonically.
- **Integrity (5)** — one attribution per account with `409` on a conflicting second claim; identical re-claim →
  `200 idempotent`, one row, `observed_at` unchanged; the trigger blocks campaign edits, re-pointing at another code
  and backdating; `CHECK` blocks self-referral at SQL level; **5 concurrent claims → exactly 1 row** (no TOCTOU).
- **Lifecycle (3)** — both accounts live → verified at claim time with `verifiedAt` equal to the account's own
  `email_verified_at`; referrer not live → stays `OBSERVED`, `promoted: false`, then promotes on re-check and the
  summary reflects it; an account with no attribution gets a clean `400`, not a silent success.
- **Campaign fields (3)** — normalization collapses ` PAID  SEARCH ` to `paid-search` and `Launch_Week 2026` to
  `launch_week-2026`, with the 40/64 ceilings accepted exactly at the limit and refused one character over; every non-allowlisted
  and URL-shaped value refused **and nothing stored** (row count asserted 0); an inserted row provably contains no
  URL, query string or credential.
- **Auth, authz, privacy (5)** — missing, unknown and forged tokens all `401`, indistinguishable and with no signing
  mechanism named in the body; `PUT`/`PATCH`/`DELETE` on every referral route return `405 METHOD_NOT_ALLOWED` (as
  does `GET` on a write route), so "append-only" is a property of the surface rather than of a handler's good
  intentions; injected `userId`, `referrerUserId`, `referredUserId`, `status`, `verified` and `email` keys are `400`s
  that store nothing for the victim; the other account's user id and email are asserted absent from the referrer's
  whole serialized payload, `attribution` carries no `referrerUserId`/`referrerEmail`/`referrerHandle` key, and
  `supporters`/`referredUsers` do not exist at all; an unrelated third account sees only its own empty state, and no
  summary can reflect a code someone else holds.
- **Query and abuse safety (3)** — `' OR 1=1 --`, `"); DROP TABLE …`, `%`, `_`, `*` all clean `400`s with tables and
  rows intact; `RATE_TRUST_WRITE_MAX=2` → `[200, 200, 429]` with `error.code = RATE_LIMITED` while a read still
  returns `200` (budget isolation proven, not asserted in a comment); a body mutated into a partial row stores
  nothing.
- **Regressions (2)** — register / verify-email / me / logout / guest behave exactly as before with referral code and
  no attribution; `marketplace_listings`, `buyer_jobs` and `orders` counts unchanged; `users.referral_source` still
  stored; audit vocabulary unchanged.
- **Analytics (4)** — an attributed registration raises `total` and `attributedInWindow` by exactly one, with repeats
  of the same code still counting once; `conversionRate` and `referralRewards` remain in `unavailable`; global
  aggregates carry no code, account or pair; `trends.referralsAttributed` returns chronological UTC-day buckets built
  from real timestamps — the aged fixture is `INSERT`ed at its past date rather than back-dated, because the
  immutability trigger rightly refuses that and a test that fought it would be testing the wrong thing — while a
  `90d` window shows only the recent bucket and the snapshot total still counts both; an untouched service returns the
  referral shape with honest zeroes rather than a missing key or a 500; every Phase 31 metric key, the `?window=5d` →
  `400`, and unknown-query-key-ignored contracts still hold; `/health` reports `schemaVersion: 12`.
- **Migration (1)** — a real v11 database (users, creator profile, audit row, completed order) first asserted to
  *lack* `referral_codes`/`referral_attributions`, upgraded to v12 additively: prior rows survive with values,
  `admin_audit_log` untouched, re-running is a no-op, and both tables plus the trigger exist and behave.

`ANDROID_BUILD = NOT_RUN` (no Android toolchain in this environment). No browser E2E, device test, live deployment,
external security review, or payment-provider call is performed or claimed.

## Out of scope

No payments, commissions, refunds, payout balances, subscriptions, purchased credits, cash referral rewards or reward
ledgers, coupon/discount codes, Trusted Seller status; monetization and entitlements (deferred Phases 28–29) are
untouched; no third-party analytics or marketing-automation SDK, no email sending, no server-rendered tracking pixel,
no admin referral dashboard beyond the existing developer control plane; no edit or delete API for an attribution; no
per-link short codes and no signed referral links (see the prerequisite above).
