/**
 * Marketplace analytics: aggregate, privacy-safe insight over the real marketplace tables (Phase 31).
 *
 * This module *reads* the state that Phases 23–30 already write. It never collects, stores, or invents an event:
 * every number is derived on demand by an aggregate query over an existing domain table, so there is no second
 * analytics database to drift out of sync and nothing to backfill. That constraint drives what can and cannot be
 * reported:
 *
 *   * **Only what is recorded.** Listings, jobs, proposals, orders, milestones, deliveries, revision requests,
 *     disputes, and reports are all real rows, so they can be counted and trended. Listing *views*, clicks, ratings,
 *     and revenue are **not** recorded by any service, so they are returned under `unavailable` with a reason — never
 *     as a fabricated zero. A measurement that was never taken must not masquerade as "nothing happened."
 *   * **Definitions travel with the numbers.** `DEFINITIONS` states, precisely, what each metric counts. In
 *     particular a *completed order* (the `orders.status` transition) is always distinct from an *approved
 *     milestone* (`order_milestones.status`), and an order with five milestones is still one order: order-level
 *     counts never fan out over the milestone/delivery/revision children, which is why they are `COUNT(*)` on `orders`
 *     while the child tables are counted separately.
 *   * **Aggregates, never enumeration.** The global overview carries no ids and no per-account breakdown — only
 *     coarse totals and date buckets — so it cannot be used to probe whether a particular account, order, or listing
 *     exists or to learn who reported whom. Report and dispute figures are category/state roll-ups only; the report
 *     note, the reporter, the target, statement text, and every personal field stay in their own tables and are
 *     never read here.
 *   * **Identity is the session, not the request.** The per-account view resolves the caller from the bearer token in
 *     the API layer; this module takes an already-resolved `userId` and scopes every query to it with bound
 *     parameters. There is no code path where a client-supplied id selects whose analytics are returned, so there is
 *     no cross-account read to bypass.
 *   * **Security signals are not product analytics.** Abuse, rate-limit, and incident counts live in the security
 *     tables and the audit log and stay there: they are deliberately not surfaced as analytics (see `excluded`), so
 *     this endpoint cannot turn the security record into a public feed.
 *
 * Money is out of scope for the whole phase, as it is for every deferred monetization phase: `agreed_budget_*` and
 * `order_milestones.amount` exist as the Phase 27 snapshot but are never read, summed, or reported here, and no
 * revenue, payout, commission, or refund metric is offered or implied.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { requireAccount } from "./entitlements.js";

/** Bounded, well-defined time ranges. `all` is an explicit all-time view, not an unbounded scan of raw rows. */
export const ANALYTICS_WINDOWS = Object.freeze({ "7d": 7, "30d": 30, "90d": 90, "365d": 365, all: null });
export const DEFAULT_ANALYTICS_WINDOW = "30d";
/** A trend series is returned newest-first then reversed to chronological order; the cap bounds work per request. */
const TREND_BUCKET_LIMIT = 400;
const DAY_MS = 86_400_000;

/** Metrics a consumer might reasonably expect but that cannot be measured, stated so they are never guessed as 0. */
export const UNAVAILABLE_METRICS = Object.freeze([
  Object.freeze({ key: "listingViews", reason: "No view or impression event is recorded, so listing views cannot be counted or trended." }),
  Object.freeze({ key: "engagement", reason: "Clicks and interactions with listings are not tracked; there is no event source to aggregate." }),
  Object.freeze({ key: "ratings", reason: "No review or rating service exists yet (the creator/server capability flags remain FUTURE)." }),
  Object.freeze({ key: "revenue", reason: "Monetization is out of scope: no money field is read, so no revenue figure exists to report." }),
]);

/** Signals that are captured elsewhere and deliberately kept out of analytics. */
export const EXCLUDED_FROM_ANALYTICS = Object.freeze([
  "rateLimitCounts", "abuseIndicators", "securityIncidents", "auditLog", "reporterIdentity", "reportNotes",
]);

/** Precise meaning of every reported metric, embedded in each response so numbers are never re-interpreted wrongly. */
export const DEFINITIONS = Object.freeze({
  "listings.total": "Every marketplace listing row, any status (DRAFT, PUBLISHED, ARCHIVED).",
  "listings.published": "Listings whose status is PUBLISHED right now — the discoverable supply.",
  "jobs.total": "Every buyer job row, any status.",
  "jobs.open": "Buyer jobs whose status is OPEN (still accepting proposals).",
  "proposals.total": "Every job proposal row, any status.",
  "orders.total": "Every order row. One row is one order regardless of how many milestones it contains.",
  "orders.completed": "Orders whose status is COMPLETED (the order-level transition, not a milestone).",
  "orders.cancelled": "Orders whose status is CANCELLED.",
  "orders.currentlyDisputed": "Distinct orders that have an OPEN order_disputes row right now.",
  "orders.everDisputed": "Distinct orders that have any order_disputes row, open or resolved.",
  "milestones.approved": "Order milestones whose status is APPROVED — a completed milestone, which is not a completed order.",
  "delivery.submissions": "Rows in milestone_deliveries: each submitted delivery version counts once.",
  "delivery.distinctOrders": "Distinct orders that received at least one delivery — never derived from the submission count.",
  "revisions.requests": "Rows in milestone_revision_requests (REVISION or SCOPE_CHANGE).",
  "disputes": "Roll-ups over order_disputes by status/outcome/reason category. No text, parties, or ids.",
  "reports": "Roll-ups over marketplace_reports by category/state. No reporter, target, note, or subject id.",
  activity: "Counts of events whose recorded timestamp falls within the selected window; the window end is `generatedAt`.",
  window: "Bounded range: N calendar days up to `generatedAt` (UTC), or all-time when window is `all`.",
});

function nowIso(nowMillis) {
  return new Date(nowMillis).toISOString();
}

/**
 * Parses the single `window` query parameter against a closed set. Unknown keys are ignored (never read), unknown
 * window values are refused — so a client cannot smuggle a filter that widens scope or escapes the bounded range.
 */
export function parseAnalyticsQuery(searchParams) {
  const raw = searchParams?.get?.("window");
  const label = raw === null || raw === undefined || raw === "" ? DEFAULT_ANALYTICS_WINDOW : String(raw);
  if (!Object.hasOwn(ANALYTICS_WINDOWS, label)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST, `Unknown window '${label}'. Use 7d, 30d, 90d, 365d, or all.`);
  }
  return Object.freeze({ window: label, days: ANALYTICS_WINDOWS[label] });
}

/** The inclusive lower bound for a window (`null` for all-time); ISO UTC, matching how timestamps are stored. */
function windowStart(days, nowMillis) {
  return days === null ? null : nowIso(nowMillis - days * DAY_MS);
}

function countRows(database, sql, params = []) {
  const row = database.prepare(sql).get(...params);
  return Number(row?.count ?? 0);
}

/** Chronological daily buckets of a timestamp column; only days with activity appear, capped per request. */
function dailyTrend(database, table, timeColumn, since, { extraWhere = "1=1", params = [] } = {}) {
  const timeFilter = since ? ` AND ${timeColumn} IS NOT NULL AND ${timeColumn} >= ?` : ` AND ${timeColumn} IS NOT NULL`;
  const rows = database.prepare(
    `SELECT substr(${timeColumn}, 1, 10) AS day, COUNT(*) AS count
       FROM ${table}
      WHERE ${extraWhere}${timeFilter}
      GROUP BY day
      ORDER BY day DESC
      LIMIT ?`,
  ).all(...params, ...(since ? [since] : []), TREND_BUCKET_LIMIT);
  return Object.freeze(rows.map((row) => Object.freeze({ day: row.day, count: row.count })).reverse());
}

/* ------------------------------------------------------------------------------- marketplace overview */

/**
 * Whole-marketplace aggregates for an authorized participant. Global and coarse by design: it describes the state
 * of the marketplace, never the state of an account, and it performs no joins back to identities.
 */
export function overviewAnalytics(database, filters, { now = Date.now() } = {}) {
  const since = windowStart(filters.days, now);
  const time = (column) => (since ? ` AND ${column} >= ?` : "");
  const tp = since ? [since] : [];

  // --- Current-state snapshots (independent of the window; counts of rows that exist right now). ---
  const listingStatus = Object.fromEntries(
    database.prepare("SELECT status AS k, COUNT(*) AS c FROM marketplace_listings GROUP BY status").all().map((r) => [r.k, r.c]),
  );
  const listings = { total: countRows(database, "SELECT COUNT(*) AS count FROM marketplace_listings"), published: listingStatus.PUBLISHED ?? 0, draft: listingStatus.DRAFT ?? 0, archived: listingStatus.ARCHIVED ?? 0 };

  const jobStatus = Object.fromEntries(
    database.prepare("SELECT status AS k, COUNT(*) AS c FROM buyer_jobs GROUP BY status").all().map((r) => [r.k, r.c]),
  );
  const jobs = { total: countRows(database, "SELECT COUNT(*) AS count FROM buyer_jobs"), open: jobStatus.OPEN ?? 0, awarded: jobStatus.AWARDED ?? 0, cancelled: jobStatus.CANCELLED ?? 0 };

  const orderStatus = Object.fromEntries(
    database.prepare("SELECT status AS k, COUNT(*) AS c FROM orders GROUP BY status").all().map((r) => [r.k, r.c]),
  );
  const orders = {
    total: countRows(database, "SELECT COUNT(*) AS count FROM orders"),
    active: orderStatus.ACTIVE ?? 0,
    completed: orderStatus.COMPLETED ?? 0,
    cancelled: orderStatus.CANCELLED ?? 0,
    // Dispute presence is a property of the order, read via the dispute table's state; it does not change the
    // order's status, so it is reported separately and counted as DISTINCT orders (not dispute rows).
    currentlyDisputed: countRows(database, "SELECT COUNT(DISTINCT order_id) AS count FROM order_disputes WHERE status = 'OPEN'"),
    everDisputed: countRows(database, "SELECT COUNT(DISTINCT order_id) AS count FROM order_disputes"),
  };

  const proposalStatus = Object.fromEntries(
    database.prepare("SELECT status AS k, COUNT(*) AS c FROM job_proposals GROUP BY status").all().map((r) => [r.k, r.c]),
  );
  const proposals = { total: countRows(database, "SELECT COUNT(*) AS count FROM job_proposals"), submitted: proposalStatus.SUBMITTED ?? 0, selected: proposalStatus.SELECTED ?? 0 };

  const milestoneStatus = Object.fromEntries(
    database.prepare("SELECT status AS k, COUNT(*) AS c FROM order_milestones GROUP BY status").all().map((r) => [r.k, r.c]),
  );
  const milestones = { total: countRows(database, "SELECT COUNT(*) AS count FROM order_milestones"), approved: milestoneStatus.APPROVED ?? 0, revisionRequested: milestoneStatus.REVISION_REQUESTED ?? 0 };

  // --- Windowed activity (events whose timestamp falls in the range). ---
  const activity = {
    listingsPublished: countRows(database, `SELECT COUNT(*) AS count FROM marketplace_listings WHERE status = 'PUBLISHED'${time("published_at")}`, tp),
    jobsPosted: countRows(database, `SELECT COUNT(*) AS count FROM buyer_jobs WHERE 1=1${time("created_at")}`, tp),
    proposalsSubmitted: countRows(database, `SELECT COUNT(*) AS count FROM job_proposals WHERE 1=1${time("created_at")}`, tp),
    ordersCreated: countRows(database, `SELECT COUNT(*) AS count FROM orders WHERE 1=1${time("created_at")}`, tp),
    ordersCompleted: countRows(database, `SELECT COUNT(*) AS count FROM orders WHERE status = 'COMPLETED'${time("completed_at")}`, tp),
    ordersCancelled: countRows(database, `SELECT COUNT(*) AS count FROM orders WHERE status = 'CANCELLED'${time("cancelled_at")}`, tp),
    deliverySubmissions: countRows(database, `SELECT COUNT(*) AS count FROM milestone_deliveries WHERE 1=1${time("submitted_at")}`, tp),
    // An order that shipped five milestones shows once here and five times in deliverySubmissions — the fan-out this
    // file is explicit about. This is why orders are never counted through their milestones.
    ordersWithDeliveries: countRows(database, `SELECT COUNT(DISTINCT order_id) AS count FROM milestone_deliveries WHERE 1=1${time("submitted_at")}`, tp),
    revisionRequests: countRows(database, `SELECT COUNT(*) AS count FROM milestone_revision_requests WHERE 1=1${time("created_at")}`, tp),
    disputesOpened: countRows(database, `SELECT COUNT(*) AS count FROM order_disputes WHERE 1=1${time("created_at")}`, tp),
    reportsFiled: countRows(database, `SELECT COUNT(*) AS count FROM marketplace_reports WHERE 1=1${time("created_at")}`, tp),
  };

  const trends = {
    ordersCreated: dailyTrend(database, "orders", "created_at", since),
    ordersCompleted: dailyTrend(database, "orders", "completed_at", since, { extraWhere: "status = 'COMPLETED'" }),
    listingsPublished: dailyTrend(database, "marketplace_listings", "published_at", since, { extraWhere: "status = 'PUBLISHED'" }),
  };

  // --- Operations roll-ups. Category/state only; no subject ids, reporters, targets, notes, or text. ---
  const disputeStatus = Object.fromEntries(database.prepare("SELECT status AS k, COUNT(*) AS c FROM order_disputes GROUP BY status").all().map((r) => [r.k, r.c]));
  const disputeOutcome = Object.fromEntries(database.prepare("SELECT COALESCE(outcome, 'PENDING') AS k, COUNT(*) AS c FROM order_disputes GROUP BY outcome").all().map((r) => [r.k, r.c]));
  const disputeCategory = Object.fromEntries(database.prepare("SELECT reason_category AS k, COUNT(*) AS c FROM order_disputes GROUP BY reason_category").all().map((r) => [r.k, r.c]));
  const disputes = {
    total: countRows(database, "SELECT COUNT(*) AS count FROM order_disputes"),
    open: disputeStatus.OPEN ?? 0,
    resolved: disputeStatus.RESOLVED ?? 0,
    byOutcome: Object.freeze({ withdrawn: disputeOutcome.WITHDRAWN ?? 0, continued: disputeOutcome.CONTINUED ?? 0, closed: disputeOutcome.CLOSED ?? 0, pending: disputeOutcome.PENDING ?? 0 }),
    byReasonCategory: Object.freeze(disputeCategory),
  };

  const reportCategory = Object.fromEntries(database.prepare("SELECT category AS k, COUNT(*) AS c FROM marketplace_reports GROUP BY category").all().map((r) => [r.k, r.c]));
  const reportStatus = Object.fromEntries(database.prepare("SELECT status AS k, COUNT(*) AS c FROM marketplace_reports GROUP BY status").all().map((r) => [r.k, r.c]));
  const reports = {
    total: countRows(database, "SELECT COUNT(*) AS count FROM marketplace_reports"),
    open: reportStatus.OPEN ?? 0,
    withdrawn: reportStatus.WITHDRAWN ?? 0,
    byCategory: Object.freeze(reportCategory),
  };

  return Object.freeze({
    scope: "marketplace_global_aggregates",
    generatedAt: nowIso(now),
    window: Object.freeze({ label: filters.window, days: filters.days, sinceIso: since }),
    snapshot: Object.freeze({ listings, jobs, orders, proposals, milestones }),
    activity: Object.freeze(activity),
    trends: Object.freeze(trends),
    operations: Object.freeze({ disputes, reports }),
    unavailable: UNAVAILABLE_METRICS,
    excluded: EXCLUDED_FROM_ANALYTICS,
    definitions: DEFINITIONS,
  });
}

/* ------------------------------------------------------------------------------- per-account insights */

/**
 * Personal marketplace analytics for one account, resolved from its own session by the API layer. Every query is
 * scoped with a bound parameter to `userId`; there is no way to ask about anyone else. Metrics mirror the overview
 * but restricted to rows the account owns or is a party to, so it doubles as the creator dashboard's data source.
 */
export function creatorAnalytics(database, userId, filters, { now = Date.now() } = {}) {
  requireAccount(database, userId); // a deleted or suspended account resolves to nothing; there is no dashboard for it.
  const since = windowStart(filters.days, now);
  const time = (column) => (since ? ` AND ${column} >= ?` : "");
  const windowParams = (extra = []) => (since ? [...extra, since] : extra);

  const profile = database.prepare(
    "SELECT creator_id AS creatorId, status, verification_status AS verificationStatus FROM creator_profiles WHERE user_id = ?",
  ).get(userId);
  const creatorId = profile?.creatorId ?? null;

  // Listings owned by this account's creator profile. A buyer without a profile simply has none (a true zero),
  // while `hasCreatorProfile` tells a consumer the difference between "not a seller" and "a seller with no listings."
  const listingStatus = creatorId
    ? Object.fromEntries(database.prepare("SELECT status AS k, COUNT(*) AS c FROM marketplace_listings WHERE creator_id = ? GROUP BY status").all(creatorId).map((r) => [r.k, r.c]))
    : {};
  const listings = {
    total: creatorId ? countRows(database, "SELECT COUNT(*) AS count FROM marketplace_listings WHERE creator_id = ?", [creatorId]) : 0,
    published: listingStatus.PUBLISHED ?? 0,
    draft: listingStatus.DRAFT ?? 0,
    archived: listingStatus.ARCHIVED ?? 0,
    publishedInWindow: creatorId ? countRows(database, `SELECT COUNT(*) AS count FROM marketplace_listings WHERE creator_id = ? AND status = 'PUBLISHED'${time("published_at")}`, windowParams([creatorId])) : 0,
  };

  // Proposals this account submitted (the seller action in Hire a Builder).
  const proposalStatus = Object.fromEntries(database.prepare("SELECT status AS k, COUNT(*) AS c FROM job_proposals WHERE user_id = ? GROUP BY status").all(userId).map((r) => [r.k, r.c]));
  const proposals = {
    total: countRows(database, "SELECT COUNT(*) AS count FROM job_proposals WHERE user_id = ?", [userId]),
    submitted: proposalStatus.SUBMITTED ?? 0,
    selected: proposalStatus.SELECTED ?? 0,
    notSelected: proposalStatus.NOT_SELECTED ?? 0,
    withdrawn: proposalStatus.WITHDRAWN ?? 0,
    submittedInWindow: countRows(database, `SELECT COUNT(*) AS count FROM job_proposals WHERE user_id = ?${time("created_at")}`, windowParams([userId])),
  };

  // Orders where this account is the creator (the counterparty that does the build work).
  const orderStatus = Object.fromEntries(database.prepare("SELECT status AS k, COUNT(*) AS c FROM orders WHERE creator_user_id = ? GROUP BY status").all(userId).map((r) => [r.k, r.c]));
  const orders = {
    total: countRows(database, "SELECT COUNT(*) AS count FROM orders WHERE creator_user_id = ?", [userId]),
    active: orderStatus.ACTIVE ?? 0,
    completed: orderStatus.COMPLETED ?? 0,
    cancelled: orderStatus.CANCELLED ?? 0,
    createdInWindow: countRows(database, `SELECT COUNT(*) AS count FROM orders WHERE creator_user_id = ?${time("created_at")}`, windowParams([userId])),
    completedInWindow: countRows(database, `SELECT COUNT(*) AS count FROM orders WHERE creator_user_id = ? AND status = 'COMPLETED'${time("completed_at")}`, windowParams([userId])),
    // Scoped through the creator's own orders so a dispute on someone else's order can never appear here.
    currentlyDisputed: countRows(database, "SELECT COUNT(DISTINCT d.order_id) AS count FROM order_disputes d JOIN orders o ON o.order_id = d.order_id WHERE o.creator_user_id = ? AND d.status = 'OPEN'", [userId]),
    everDisputed: countRows(database, "SELECT COUNT(DISTINCT d.order_id) AS count FROM order_disputes d JOIN orders o ON o.order_id = d.order_id WHERE o.creator_user_id = ?", [userId]),
  };

  // Milestone/delivery/revision work on this creator's orders, counted separately so an order with many milestones
  // never inflates an order figure. The child tables all carry `order_id`, so scoping is a single join to `orders`.
  const milestonesApproved = countRows(database, "SELECT COUNT(*) AS count FROM order_milestones m JOIN orders o ON o.order_id = m.order_id WHERE o.creator_user_id = ? AND m.status = 'APPROVED'", [userId]);
  const milestoneTotal = countRows(database, "SELECT COUNT(*) AS count FROM order_milestones m JOIN orders o ON o.order_id = m.order_id WHERE o.creator_user_id = ?", [userId]);
  const deliverySubmissions = countRows(database, `SELECT COUNT(*) AS count FROM milestone_deliveries d JOIN orders o ON o.order_id = d.order_id WHERE o.creator_user_id = ?${time("d.submitted_at")}`, windowParams([userId]));
  const ordersWithDeliveries = countRows(database, `SELECT COUNT(DISTINCT d.order_id) AS count FROM milestone_deliveries d JOIN orders o ON o.order_id = d.order_id WHERE o.creator_user_id = ?${time("d.submitted_at")}`, windowParams([userId]));
  const revisionRequests = countRows(database, `SELECT COUNT(*) AS count FROM milestone_revision_requests r JOIN orders o ON o.order_id = r.order_id WHERE o.creator_user_id = ?${time("r.created_at")}`, windowParams([userId]));

  // Disputes this account is a party to (either side). State roll-ups only — no reason text, statement bodies, or the
  // other party's identity. A dispute the other side opened still counts because this account is a participant.
  const disputeStatus = Object.fromEntries(database.prepare("SELECT status AS k, COUNT(*) AS c FROM order_disputes WHERE opened_by = ? OR other_participant = ? GROUP BY status").all(userId, userId).map((r) => [r.k, r.c]));
  const disputes = { total: countRows(database, "SELECT COUNT(*) AS count FROM order_disputes WHERE opened_by = ? OR other_participant = ?", [userId, userId]), open: disputeStatus.OPEN ?? 0, resolved: disputeStatus.RESOLVED ?? 0 };

  return Object.freeze({
    scope: "self_only",
    generatedAt: nowIso(now),
    window: Object.freeze({ label: filters.window, days: filters.days, sinceIso: since }),
    account: Object.freeze({ hasCreatorProfile: creatorId !== null, profileStatus: profile?.status ?? null, verificationStatus: profile?.verificationStatus ?? null }),
    snapshot: Object.freeze({ listings, proposals, orders }),
    activity: Object.freeze({ milestonesApproved, milestoneTotal, deliverySubmissions, ordersWithDeliveries, revisionRequests }),
    disputes: Object.freeze(disputes),
    trends: Object.freeze({
      ordersCreated: dailyTrend(database, "orders", "created_at", since, { extraWhere: "creator_user_id = ?", params: [userId] }),
      ordersCompleted: dailyTrend(database, "orders", "completed_at", since, { extraWhere: "creator_user_id = ? AND status = 'COMPLETED'", params: [userId] }),
    }),
    unavailable: UNAVAILABLE_METRICS,
    definitions: DEFINITIONS,
  });
}
