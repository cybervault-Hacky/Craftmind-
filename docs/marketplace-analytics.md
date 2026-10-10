# Marketplace Analytics & Insights (Phase 31)

Phase 31 adds a **read-only, privacy-safe analytics foundation** over the marketplace Phases 23–30 already wrote.
It is derived aggregate insight, not a data-collection pipeline: every figure is computed on demand from an existing
domain table, so there is no second store to drift, nothing to backfill, and no metric invented for data that was
never recorded.

This phase is analytics-only. It implements **no** payment, commission, refund, payout, subscription, or purchased-
credit behavior, and it reads **no** money column. `orders.agreed_budget_*` and `order_milestones.amount` (the Phase 27
snapshot) are never summed or surfaced, and no revenue, payout, or commission metric is offered or implied.

## Endpoints

Both endpoints are `GET`, require an authenticated session, and accept a single optional `window` query parameter
(`7d | 30d | 90d | 365d | all`; default `30d`). They sit in the same `SESSION` security category and the dedicated
`analytics-read` rate bucket as the Phase 30 trust reads.

| Endpoint | Audience | Returns |
| --- | --- | --- |
| `GET /marketplace/analytics/overview` | any authorized participant | Whole-marketplace aggregates: current-state snapshots, windowed activity counts, daily trend buckets, and dispute/report operations roll-ups. Global and coarse — no ids, no per-account breakdown. |
| `GET /marketplace/analytics/creator` | the authenticated account, about itself only | Personal insight over rows the caller owns or is a party to: their listings, submitted proposals, orders they hold as creator, milestone/delivery/revision activity, and disputes they are a participant in. |

`src/marketplace-analytics.js` holds the queries; `src/marketplace-analytics-api.js` resolves the acting account from
the bearer token and runs each request inside the shared serialized read transaction so the many aggregates see one
consistent snapshot. No request body is read and **no `userId`/`creatorId` parameter is ever consulted** — the scope
key for the personal view is the session, and the overview is not account-scoped at all.

### Added by Phase 32 (same endpoint, no new analytics engine)

Referral attribution is the only *marketing* activity the platform records, so it is derived here rather than measured
by a second service: `operations.referrals`, `trends.referralsAttributed`, and the personal
`creatorAnalytics.referrals` block. See
[Referral tracking & campaign attribution](referrals-and-attribution.md) for the definitions and the counting rule
(each referred account once, at observation). `unavailable` gained `conversionRate` (no impression/click source) and
`referralRewards` (no payouts), and `excluded` gained `referralCodes`, `referralRelationships`, and `referralEmails`.
The Phase 31 contract above is unchanged: same response shape, same windows, same `400 INVALID_REQUEST` on a bad
`window`, unknown query parameters still ignored.

## Metric definitions (authoritative)

Every response embeds a `definitions` object; these are the meanings, stated so a consumer never re-derives them:

- **Snapshot (current state, independent of `window`).** `listings.{total,published,draft,archived}` over
  `marketplace_listings.status`; `jobs.{total,open,awarded,cancelled}` over `buyer_jobs.status`;
  `proposals.{total,submitted,selected}` over `job_proposals.status`; `milestones.{total,approved,revisionRequested}`
  over `order_milestones.status`; `orders.{total,active,completed,cancelled}` over `orders.status`.
- **Disputed orders are reported separately from order status.** `orders.currentlyDisputed` = DISTINCT orders with an
  OPEN `order_disputes` row right now; `orders.everDisputed` = DISTINCT orders with any dispute. A dispute does not
  change `orders.status` (Phase 30 semantics), so completion and dispute counts are never conflated.
- **A completed *order* is not a completed *milestone*.** `orders.completed` counts the order-level transition;
  `milestones.approved` counts approved milestones. One order with five milestones is exactly one `orders.*`.
- **Deliveries are counted twice on purpose.** `activity.deliverySubmissions` counts rows in `milestone_deliveries`
  (each submitted version once); `activity.ordersWithDeliveries` counts DISTINCT orders that received one. An order
  that shipped five milestones shows 5 and 1 respectively — which is why order counts are never taken through children.
- **Windowed activity.** `activity.*` counts events whose timestamp (`created_at`/`published_at`/`completed_at`/
  `cancelled_at`/`submitted_at`) falls in `[generatedAt − window, generatedAt]`; `all` removes the lower bound.
- **Trends.** `trends.<metric>` is an array of `{ day, count }` in UTC-day buckets (`substr(ts,1,10)`), newest-first
  capped to 400 buckets, including only days that actually had activity. A day with no events is absent, not zero.

## Honesty and exclusions

`unavailable` lists signals the platform does not track — **listing views, engagement/clicks, ratings, and revenue** —
each with a reason, so an absent measurement is never rendered as a "0 activity" that could be read as proof nothing
happened. The site's creators do not record views; inventing a views metric would be fabrication, so none exists.

`excluded` names what is captured elsewhere and deliberately kept out of analytics: `rateLimitCounts`,
`abuseIndicators`, `securityIncidents`, `auditLog`, `reporterIdentity`, `reportNotes`. The security record and the
audit log are **not** re-surfaced as product analytics; no abuse or rate-limit figure is exposed here.

## Privacy and authorization guarantees

- **Identity is the session, never the request.** The personal view scopes on the token-resolved `userId` with bound
  parameters; there is no path where a supplied id chooses whose data is returned, so a cross-account read is not
  merely denied — it is unrepresentable. An injected `userId`, `creatorId`, or `scope` is ignored (the response stays
  `self_only` with the caller's own counts).
- **Aggregates cannot enumerate.** The overview carries no account/order/listing ids and no per-target breakdown. For
  reports it exposes only per-category and per-state counts (Phase 30 stores a resolved `target_user_id`, but that
  column is never read or returned here); the report note, the reporter, the target, and every subject id are excluded.
  Disputes expose only status/outcome/reason-category totals — no statement bodies, parties, or ids.
- **Reads are not audited and audit nothing.** Analytics do not mutate state, do not write to `admin_audit_log`, and
  do not turn the audit log into a data source. A burst of reads changes no security signal except the ordinary limiter.
- **The window is a closed whitelist.** `parseAnalyticsQuery` accepts only the five known labels and rejects anything
  else with `INVALID_REQUEST` (400); the value is looked up, never interpolated, and every count query is parameterized.
  There is no string-concatenated client input reaching SQL.

## Why there is no schema change

Analytics **prefer derived aggregates over stored redundancy** (the phase's stated preference), so no table, column,
index, or migration is added: `SCHEMA_VERSION` stays at 11, `REGISTERED_AUDIT_ACTION_TYPES` stays at 102, and no new
error code is introduced (validation reuses `INVALID_REQUEST`). The aggregate reads are full-table roll-ups over the
small marketplace tables and their existing status/partial indexes; adding dedicated analytics indexes is not justified
at this data volume and would only add migration and shadow-rebuild risk. If the marketplace grows enough for that to
change, the correct next step is a bounded, additive index migration — not pre-loading it here.

## Website integration — deliberate no-op with a prerequisite

The repository has **no authenticated website dashboard bound to a configured backend origin** to render these into.
The site is a static GitHub Pages experience whose `website/assets/adapters.js` registers a creator-*analytics*
adapter that is intentionally *unconfigured and returns a typed `unavailable` result*, and `scripts/check_website.py`
verifies a hard-coded page set. Surfacing this foundation would require first standing up an authenticated web session
against a deployed backend — a prerequisite this phase does not have and will not fake. Building a client-side
dashboard that only shows placeholder numbers, or inventing charts, is explicitly out of the task's honesty rules, so
**the website is left unchanged**: its existing "analytics unavailable" copy stays true from the site's perspective,
and the backend foundation is ready for a future authenticated dashboard to consume. The `CREATOR_ANALYTICS`
capability flag in `creator-catalog.js` and the membership-plan entitlement remain as-is by the same reasoning:
flipping them is product/monetization UI surfacing (deferred), not part of an analytics-only backend.

## Out of scope

Payments, commissions, refunds, payouts, subscriptions, purchased credits, monetization entitlements (deferred
Phases 28–29); any public/reputation trust *score*; view/click/rating collection (no event source exists); an
administrator analytics endpoint (the existing developer control plane already offers read-only aggregate account
counts as a confirmed dev tool, and a new admin analytics surface was not verified as necessary); any website UI.

## Verification

- `cd backend && npm test` → **325/325 pass across 60 suites** (up from 306/53). The new `marketplace-analytics.test.js`
  adds 19 tests over 7 suites: aggregate correctness, the completed-order-vs-approved-milestone and
  deliveries-vs-orders double-count distinctions, empty-dataset shape, date-range validation and window boundary
  (via real timestamp data), owner-scoping, cross-account non-leakage, injected-`userId`/`scope` refusal,
  report/dispute aggregate privacy, SQL-injection resistance, dedicated rate budget, no-audit/no-migration invariants,
  and Phase 27/30 backward compatibility.
- `node --check` on every module and the test; `python3 scripts/check_website.py` → PASS (41 pages, website unchanged);
  `python3 scripts/check_release_config.py` → PASS.
- `ANDROID_BUILD = NOT_RUN` (no Android toolchain here). No browser E2E, device test, live deployment, external security
  review, or payment-provider call is performed or claimed.
