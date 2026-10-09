# Membership, entitlements, and build credits (Phase 22)

Phase 22 turns the Phase 21 membership screens into a real, server-authoritative foundation: membership state, plan
definitions, plan entitlements, a credit ledger, grants, consumption, expiration, entitlement checks, a developer
promotion surface, audited APIs, idempotency, and concurrency safety.

It is also the phase that keeps money out. **No payment provider is integrated** — not Razorpay, Stripe, PayPal, UPI,
cards, checkout, subscription billing, webhooks, invoices, payouts, or tax. Nothing in this phase can charge a user, and
no plan is purchasable. Membership and credits are an internal entitlement system, and the credit ledger is fed only by
promotional grants an authorized developer makes.

- [1. The one rule: the server decides](#1-the-one-rule-the-server-decides)
- [2. Membership model](#2-membership-model)
- [3. Entitlement model](#3-entitlement-model)
- [4. Credit ledger](#4-credit-ledger)
- [5. Idempotency](#5-idempotency)
- [6. Expiration](#6-expiration)
- [7. Concurrency](#7-concurrency)
- [8. The account API](#8-the-account-api)
- [9. The developer grant boundary](#9-the-developer-grant-boundary)
- [10. The AI boundary](#10-the-ai-boundary)
- [11. Audit](#11-audit)
- [12. Migration v5](#12-migration-v5)
- [13. Website integration](#13-website-integration)
- [14. The payment integration boundary](#14-the-payment-integration-boundary)
- [15. The future Creator and Server boundary](#15-the-future-creator-and-server-boundary)
- [16. Verification](#16-verification)

---

## 1. The one rule: the server decides

Every value that describes what an account may do is resolved by the server from its own database:

| Question | Answered by | Never by |
| --- | --- | --- |
| Which plan is in effect? | `membership_accounts` row, resolved on read | A request body, a query string, browser state |
| Which capabilities are granted? | The plan's entitlement set plus active administrative grants, computed per read | A cached snapshot, a client flag |
| What is the credit balance? | The append-only ledger, read live | A stored counter, a browser value |
| May this consumption proceed? | The server, inside one write transaction | The caller's belief about its balance |

There is no endpoint in this phase that accepts an account identifier, a plan, a status, a balance, or an entitlement
from a client. A caller asks a question about *itself* (identified by the bearer session) and the server answers. A
fabricated `{"plan":"PRO"}`, an invented `{"credits":500}`, or another account's identifier has no code path to travel.

The website follows the same rule: `adapters.js` may *read* the plan, the entitlement list, and the balance, and it
computes none of them. No website feature consumes credits, so the site never calls the consumption endpoint, and the
authoritative balance is never written to browser storage.

## 2. Membership model

Plans are normalized and stable, and the catalog is defined once in `backend/src/membership-plans.js`:

| Plan | Availability | Purchasable | Grantable | Entitlements |
| --- | --- | --- | --- | --- |
| `FREE` | **Available today** | No | No (it is the baseline) | `BUILD_GENERATION`, `LOCAL_BUILD_HISTORY`, `VISUAL_REFERENCE` |
| `PRO` | Planned | No | Yes, by an authorized developer | Free, plus `HIGHER_GENERATION_LIMITS`, `ADVANCED_AI` |
| `CREATOR` | Planned | No | Yes | Pro, plus `CREATOR_TOOLS`, `CREATOR_ANALYTICS` |
| `SERVER` | Planned | No | Yes | Creator, plus `SERVER_TOOLS`, `TEAM_WORKSPACE` |

Three properties are enforced rather than described:

- **No price exists.** There is no price, amount, cost, or currency field anywhere in the catalog, and the tier
  invariant (each tier contains the one below it) is validated when the module loads, so an edit cannot silently strip a
  capability.
- **`availability` is not `purchasable`.** `AVAILABLE_TODAY` means "may be presented as something you have today".
  `grantable` is a separate flag that only the developer tool consults, so an internal evaluation grant never implies a
  working checkout.
- **A plan can be granted without being purchasable.** That is the only way a paid plan is ever applied in this phase.

Statuses are `ACTIVE`, `EXPIRED`, `CANCELLED`, `PENDING`, `UNAVAILABLE`; sources are `DEFAULT_BASELINE`,
`DEVELOPER_GRANT`, and `BASELINE_FALLBACK`. There is no billing-derived state, because nothing bills.

Every registered account gets a deterministic free baseline **at registration**, inside the same transaction that
creates the account, so no account can exist without one. The baseline is also ensured idempotently on every entitlement
read (a `UNIQUE(user_id)` constraint plus `INSERT OR IGNORE`), so an account created before this phase resolves to
exactly one baseline the first time anything asks about it, and two concurrent readers still produce one row.

## 3. Entitlement model

`getAccountEntitlements(database, configuration, accountId)` returns, for one account:

```json
{
  "membership": { "plan": "FREE", "planName": "Free", "status": "ACTIVE", "source": "DEFAULT_BASELINE",
                  "startsAt": "…", "endsAt": null, "updatedAt": "…" },
  "plan": { "id": "FREE", "availability": "AVAILABLE_TODAY", "availabilityLabel": "Available today — no charge",
            "priceState": "No price applies", "purchasable": false, "grantable": false,
            "generationAllowance": { "perDay": 10, "enforced": false } },
  "entitlements": [ { "key": "BUILD_GENERATION", "label": "Build generation", "description": "…",
                      "grantedBy": "PLAN", "plan": "FREE" } ],
  "has": { "BUILD_GENERATION": true },
  "administrativeGrants": [ { "entitlementKey": "BETA_ACCESS", "grantedAt": "…", "expiresAt": "…" } ],
  "credits": { "available": 0 }
}
```

Feature code asks typed questions instead of comparing plan strings:

```js
requireEntitlement(view, ENTITLEMENT.CREATOR_TOOLS);         // throws ENTITLEMENT_REQUIRED
enforceEntitlement(database, view, key, { targetUserId });   // throws, and appends an ENTITLEMENT_DENIED audit row
hasEntitlement(view, entitlementKey);                        // boolean; an unknown key is never granted
```

Entitlement keys are stable identifiers (`BUILD_GENERATION`, `CREATOR_TOOLS`, `ADVANCED_AI`, …), an unknown key is
rejected rather than silently allowed, and entitlements are **never persisted as a snapshot**: they are computed from
the plan plus active grants on every read, so a plan change or a revoked grant takes effect immediately and cannot drift.

Daily generation allowances are recorded per plan for the future AI gateway and are **not enforced** by this phase: no
request is blocked on an allowance. Claiming otherwise in the interface would be a fabrication.

## 4. Credit ledger

Credits live in `credit_ledger`, which is **append-only by database trigger**. `users` has no credit column and no plan
column, so there is nothing to increment and no way to assign a balance. Every row carries:

| Field | Meaning |
| --- | --- |
| `transaction_id` | Public `crd_…` reference. A reversal refers to it; it is never a row id. |
| `type` | `GRANT`, `CONSUME`, `EXPIRE`, `ADJUSTMENT`, `REVERSAL` |
| `amount` | Positive for credit-adding types, negative for credit-removing types (enforced by CHECK constraints) |
| `reason` | Bounded, human-readable, and server-authored on the public consumption path |
| `source` | `DEVELOPER_GRANT`, `PLAN_ALLOCATION`, `BUILD_CONSUMPTION`, `EXPIRATION`, `REVERSAL` |
| `reference_id` | The grant a reversal corrects, or the grant an expiry marker closes |
| `actor_kind` | `DEVELOPER`, `SYSTEM_SECURITY`, `AI`, `SYSTEM` — a `SYSTEM` row always has a null developer id |
| `expires_at` | Optional; a `GRANT`/`ADJUSTMENT` may carry one |

The balance is **derived**, not stored. Credits are fungible, so consumption is attributed to grants in the order they
expire (soonest first, oldest next, no-expiry last):

```
negatives = Σ|CONSUME| + Σ|REVERSAL|
for each grant, in expiry order:
    spend = min(grant, negatives); negatives -= spend; leftover = grant - spend
    if the grant has expired: it lapsed (an EXPIRE marker may record it, worth nothing)
    else:                     leftover is spendable
```

This is what makes "10 unexpired + 10 expiring, then spend 15" leave exactly 5: the 15 came out of the credits that
would have lapsed first. `EXPIRE` rows are **markers, not arithmetic** — a grant past its `expires_at` already stops
counting, so subtracting the marker as well would take the same credits away twice. Markers exist so history states
plainly what happened, and they are aggregated into the `expired` figure the API reports.

The balance can never be negative. A consumption larger than the available balance is refused with
`INSUFFICIENT_CREDITS` **before** any row is written — no partial spend, no negative write.

Grants are developer-only in this phase: an authorized developer tool, or a plan grant's own promotional allocation.
There is no payment-derived allocation, no purchase, and no self-service top-up.

## 5. Idempotency

Any credit operation that a client can retry — and any operation a developer tool performs — carries an idempotency key.
The key is never stored as sent: the database holds `HMAC(AUTH_SECRET, account ∥ operation ∥ key)`, so a stolen database
copy cannot be used to replay an operation and cannot reveal the key a client used. The same key from a different
account, or for a different operation, can never collide.

Behaviour:

- **Same key, same request** → the original transaction is returned (`reused: true`), the balance reflects the ledger as
  it stands, and **nothing is written**. A timeout, a process restart, or an impatient client cannot consume or grant
  twice.
- **Same key, different request** → `CREDIT_OPERATION_DUPLICATE`. The server refuses rather than guessing which request
  the caller meant.
- **Same key, different operation** → `CREDIT_OPERATION_CONFLICT`.
- **No key at all** on `POST /account/credits/consume` → `CREDIT_OPERATION_INVALID`. Idempotency is not optional for a
  credit movement.

For developer tools the confirmation challenge is the first layer (a challenge is single-use and consumed at execution)
and the ledger key is the second.

## 6. Expiration

**Credits.** A grant may carry `expires_at`. Once that moment passes, the credits stop counting in the balance
immediately, and the next balance read records what lapsed.

> **Design choice — reconciliation on read, not a background job.** The service has no scheduler, so there is nothing to
> keep running, nothing to drift, and no window in which a lapsed credit still counts. The pass is bounded
> (`MEMBERSHIP_RECONCILIATION_BATCH`), processed oldest-expiry-first, and every grant it examines receives an
> `operation_key` marker whether or not anything lapsed, so the scan always advances and a second read writes nothing.
> A service that was down for a month reconciles the first time anyone looks.

**Membership.** A plan with an end timestamp is treated as expired the moment it is read: the transition is persisted
(with a domain-history row and a `MEMBERSHIP_EXPIRED` audit entry), the account falls back to the **free baseline** with
source `BASELINE_FALLBACK`, and it loses the plan's entitlements rather than the product. Nothing renews automatically
and nothing is collected, because nothing bills — the ledger's promotional grants keep their own expiry dates and are
never silently extended or deleted.

## 7. Concurrency

Every credit mutation and every entitlement resolution runs inside `BEGIN IMMEDIATE`, so SQLite takes the write lock
before the read that the write depends on:

- **Balance 10, two simultaneous consumptions of 7** → exactly one succeeds and the other is refused with
  `INSUFFICIENT_CREDITS`; the balance ends at 3 and the ledger holds one `CONSUME` row. The test suite asserts this
  through the real HTTP surface, not by simulating it.
- **Two concurrent baseline readers** → one membership row (`UNIQUE(user_id)` plus `INSERT OR IGNORE`).
- **Lock contention past `busy_timeout`** → a typed `CREDIT_LEDGER_BUSY` (503) with no SQL text and no stack trace; the
  refusal is safe to retry with the same idempotency key.

Concurrency is enforced by the database, never by a JavaScript variable, an in-process counter, or a lock held in memory.

## 8. The account API

Five authenticated routes, all identified by the bearer session alone:

| Route | Returns |
| --- | --- |
| `GET /account/membership` | `{ membership, plan, credits }` for the signed-in account |
| `GET /account/entitlements` | `{ plan, status, entitlements[], administrativeGrants[], credits }` |
| `GET /account/credits` | `{ credits: { available, expiring, expired, expiringInDays, lifetimeGranted, lifetimeConsumed } }` |
| `GET /account/credits/transactions` | `{ transactions[], count, limit }`, newest first, `limit`/`type` optional |
| `POST /account/credits/consume` | `{ reused, transaction, credits: { available } }` |

Stable typed errors: `INSUFFICIENT_CREDITS` (409), `ENTITLEMENT_REQUIRED` (403), `MEMBERSHIP_UNAVAILABLE` (409),
`CREDIT_OPERATION_INVALID` (400), `CREDIT_OPERATION_DUPLICATE` (409), `CREDIT_OPERATION_CONFLICT` (409), and
`CREDIT_LEDGER_BUSY` (503). A refusal never returns SQL text, a stack trace, or an internal identifier.

`POST /account/credits/consume` accepts exactly `{ amount, purpose?, idempotencyKey? }` (or an `Idempotency-Key` header),
where `purpose` is a closed enum and the ledger's `reason` text is written by the server. Unknown body keys, unknown
query keys, non-integer amounts, zero, negatives, amounts above `MEMBERSHIP_MAX_CONSUME_CREDITS`, and oversized payloads
are all refused with a typed error. Two different idempotency keys in one request are refused rather than resolved.

The responses are deliberately narrow: a plan, a status, timestamps, typed entitlement keys, the balance, and
transactions identified by their own `crd_` reference. No internal membership id, session id, session token, password
material, developer identity, audit metadata, or provider secret is ever returned. There is **no adjust, set, or
top-up endpoint**: a balance cannot be assigned through the API, only moved by an audited ledger operation.

The routes are rate-limited on the existing limiter (`RATE_CREDITS_*`, `RATE_CREDIT_CONSUME_*`) — no second limiter is
introduced — and they join the Phase 20 security-sensitive prefix set (`/account/`), so an active protection applies to
them exactly as it does elsewhere. Reading one's own membership and spending one's own credits are the account's own
data, so the routes are not subject to the non-enumerating response floor that protects sign-in.

## 9. The developer grant boundary

Phase 22 extends the Phase 19 control plane with five tools. Nothing new is invented: they use the same registry, the
same role checks, the same single confirmation boundary, the same audit log, and the same dashboard.

| Tool | Mutating | Roles | Purpose |
| --- | --- | --- | --- |
| `inspectMembership` | No | OWNER, ADMIN, DEVELOPER | Plan, status, entitlements, balance summary, recent ledger entries |
| `listCreditTransactions` | No | OWNER, ADMIN, DEVELOPER | Bounded, newest-first ledger page for one account |
| `grantMembership` | **Yes** | OWNER, ADMIN | Apply `PRO`/`CREATOR`/`SERVER` for a bounded number of days, with its promotional allocation |
| `grantCredits` | **Yes** | OWNER, ADMIN | Grant a bounded number of promotional credits, with a mandatory reason and optional expiry |
| `reverseCreditGrant` | **Yes** | OWNER, ADMIN | Reverse part or all of a still-standing grant by transaction reference |

Mutating tools are schema-validated, produce a human-readable summary, and require an explicit second call to confirm;
the confirmation is actor-bound, single-use, and short-lived, and a failure is audited with its outcome. A grant always
carries a bounded amount, a mandatory reason, the acting developer, a timestamp, an optional expiry, and an idempotency
key, and it writes both a ledger row and an audit row inside one transaction — so a privileged action is never
acknowledged unless its audit write committed with it.

There is deliberately **no** unrestricted tool: no `admin.updateCredits(anything)`, no balance setter, no SQL surface,
and no tool that edits or deletes ledger history. Reversal is the only correction path, and it is bounded by what still
stands.

## 10. The AI boundary

The Phase 19 boundary is unchanged. The Developer AI may:

- inspect membership and credit state through the read-only tools;
- summarize entitlement status;
- **propose** a grant by emitting a tool call.

A proposal travels the full path — AI → registered tool → schema validation → authorization → confirmation → backend
action → audit — and stops at the confirmation boundary: the AI's call returns a challenge, executes nothing, and moves
no credits. Only a human developer's confirmation performs the grant, and the audit row records that developer as the
acting identity. The database makes the rule redundant-safe as well: a ledger row whose actor kind is `AI` with a
`GRANT` type violates a CHECK constraint and aborts.

## 11. Audit

Phase 22 reuses the **one** Phase 19/20 `admin_audit_log`; it introduces no competing audit system. Membership grants and
state changes, credit grants, consumptions, expirations, reversals, rejected operations, and entitlement denials all
land there with an actor kind of `DEVELOPER`, `SYSTEM_SECURITY`, `AI`, or `SYSTEM`. A `SYSTEM` row records engine
bookkeeping — a baseline assignment, an expiry, a ledger refusal — and always carries a null developer id, so a domain
event can never be mistaken for a person's decision. Action types are registered in `AUDIT_ACTION_TYPES` and the audit
writer fails closed on an unregistered type, which keeps the module and the schema's CHECK constraint in step.

Signing out revokes a session and nothing else: membership, its history, and the full ledger survive.

## 12. Migration v5

One additive migration. It creates `membership_accounts`, `membership_transitions`, `credit_ledger`,
`credit_operation_keys`, and it **rebuilds two existing tables** to widen their CHECK lists:

- `admin_audit_log`, to accept the Phase 22 action types and the `SYSTEM` actor kind. Every existing row is copied with
  its actor, target, incident reference, and metadata, and all three append-only triggers are recreated. Forgetting the
  triggers would silently allow audit mutation, so their existence is asserted by the test suite.
- `developer_action_confirmations`, to accept the new confirmable tool names.

Nothing else is dropped or rewritten. `users`, `sessions`, `guest_identities`, `developer_accounts`,
`developer_sessions`, `developer_bootstrap_state`, `developer_access_grants`, the Phase 20 security tables, and every
Phase 21 website artefact are untouched. A test migrates a real v4 database with accounts, sessions, guest identities,
developer accounts, audit rows, and a security incident, and asserts every one of them survives with its values intact.

Append-only enforcement is also part of the schema: `admin_audit_log`, `credit_ledger`, and `membership_transitions`
refuse update and delete at the database level.

## 13. Website integration

`assets/adapters.js` remains the only module that performs a network request. Phase 22 adds the four read endpoints to
its documented inventory and two connection-aware adapters:

- `membership.loadMembership()` / `loadEntitlements()` / `loadCredits()` — reads for the signed-in account;
- `entitlements.listEntitlements()` / `loadCredits()`.

`checkAccess` is deliberately absent: access is decided by the server, never in a browser. So is any write method — there
is nothing to upgrade, downgrade, cancel, or purchase, and no website feature consumes credits.

- **No service configured** → the membership page and the account membership page keep their Phase 21 honest states, and
  the credit line reads *"Credit balance unavailable"*; no zero, no invented plan.
- **Configured, no session** → the panel asks the visitor to sign in, and makes no request.
- **Configured, signed in** → the panel renders the server's own plan, status, end date, entitlements, and balance. The
  session lives in a page-local module variable (shared by the controllers on that page, never written to any browser
  persistence), so the figures disappear on reload along with the session — which is the honest behaviour while no
  refresh-token storage exists.

Plan cards, the comparison table, and the period control are unchanged: `PRO`, `CREATOR`, and `SERVER` stay
"Planned — not purchasable", every upgrade control stays disabled, and no price, currency, or billing date appears.

## 14. The payment integration boundary

Explicitly **not** in this phase, and not stubbed as if it were: no Razorpay, Stripe, PayPal, UPI, cards, payment
intents, checkout, subscription billing, webhooks, payouts, commission settlement, invoices, or tax calculation.

What a future payment phase would have to add — and what the current design deliberately leaves open:

1. A provider integration and a checkout surface (the website has none, and `payments` stays an inert adapter).
2. An order/subscription record distinct from the entitlement ledger.
3. A webhook ingestion path with signature verification, and settlement reconciliation.
4. A policy that converts a settled payment into a `GRANT` with a purchase-derived `source` value. The ledger's `source`
   CHECK list intentionally has no payment value yet, and a plan's `purchasable` flag stays `false`, so a purchase path
   cannot half-exist: adding one means adding both deliberately, in a phase that also adds invoices and tax handling.
5. Pricing. No price exists anywhere in the codebase, which is why no screen can display one.

## 15. The future Creator and Server boundary

`CREATOR` and `SERVER` are defined, granted, and enforced today — an account with `CREATOR` genuinely receives
`CREATOR_TOOLS` and `CREATOR_ANALYTICS` from the entitlement engine. What does **not** exist is the service those
entitlements gate: there is no marketplace backend, no listing storage, no analytics source, and no team workspace. The
`marketplace`, `creator`, `orders`, `reviews`, and `analytics` adapters stay inert, and the creator studio keeps its
honest states.

So the correct reading of `CREATOR_TOOLS` today is "this account may use the creator surfaces when they exist", not
"this account may publish a listing". The entitlement engine is the switch a later phase flips when the service behind
it is real; nothing in the interface claims otherwise.

## 16. Verification

- `cd backend && npm test` → **125 tests across 16 suites**, including the new Phase 22 suite (27 tests: baseline,
  membership and entitlement reads, unknown entitlement rejection, grants, consumption, insufficient balance, no
  negative balance, duplicate idempotency keys, concurrent consumption, expiry, reversal, invalid and excessive amounts,
  developer authorization, unauthorized and forged tool calls, developer grant audit, membership expiration, sign-out
  preservation, the AI boundary, migration preservation, append-only enforcement, rate limits, malformed and oversized
  payloads, response privacy, and cross-account isolation).
- `python3 scripts/check_website.py` → PASS (30 pages, 884 links) with the membership and account pages still satisfying
  every honesty, storage, and payment-provider rule.
- A jsdom check of the rendered membership and account-membership states: unconfigured, configured-without-session, and
  configured-with-session, asserting that no price, no invented plan, and no invented balance is ever rendered.
- `python3 scripts/check_release_config.py` → PASS; `node --check` on every changed module; `git diff --check` clean; a
  secret scan of the diff; **ANDROID_BUILD = NOT_RUN** (no Android toolchain in this environment).
- Not performed, and not claimed: no live deployment, no external security review, no load test, no multi-instance
  shared-state test, no payment provider call (there is no provider), and no live AI provider call.
