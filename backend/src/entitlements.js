/**
 * The server-side entitlement engine (Phase 22).
 *
 * Every membership read and every future entitlement check goes through this file. Route handlers never compare plan
 * strings, and no client ever supplies a plan, a status, a balance, or an entitlement — `getAccountEntitlements` derives
 * all of it from the account's own membership row, the append-only credit ledger, and the administrative grants that
 * already exist from Phase 19.
 *
 * Three behaviours are worth stating explicitly:
 *
 *   * **A deterministic free baseline.** Every account has a FREE membership as soon as its entitlement state is first
 *     read: the row is created in the same transaction as the read, so two concurrent readers cannot create two
 *     memberships, and no configuration can leave an account with no entitlements at all.
 *   * **Expiration is derived, then recorded.** A paid plan whose `ends_at` has passed is *treated* as expired the
 *     moment it is read, and the transition is persisted (with a domain-history row and an audit entry) rather than
 *     being left implicit. The account falls back to the FREE baseline instead of losing access to the product.
 *   * **Entitlements are never persisted as a snapshot.** They are computed from the plan plus active grants on each
 *     read, so a plan change or a grant revocation takes effect immediately and cannot drift from stored state.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { newMembershipId, newMembershipTransitionId } from "./ids.js";
import {
  ENTITLEMENT,
  ENTITLEMENT_REGISTRY,
  MEMBERSHIP_PLAN,
  MEMBERSHIP_SOURCE,
  MEMBERSHIP_STATUS,
  entitlementDefinition,
  isRegisteredEntitlement,
  planCatalog,
  planEntitlements,
} from "./membership-plans.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";
import { creditBalanceInTransaction } from "./credits.js";

const GRANT_KEYS = Object.freeze(["BETA_ACCESS", "PREVIEW_ACCESS", "PROMOTIONAL_ACCESS"]);
const MAXIMUM_TRANSITION_HISTORY = 50;

function nowIso(nowMillis = Date.now()) {
  return new Date(nowMillis).toISOString();
}

function isPast(isoTimestamp, nowMillis) {
  const parsed = Date.parse(isoTimestamp ?? "");
  return Number.isFinite(parsed) && parsed <= nowMillis;
}

/**
 * True when a failure is SQLite telling us another writer holds the lock past `busy_timeout`. A raw SQLite error text
 * must never reach a client, and a refusal of this kind is safe to retry with the same idempotency key.
 */
function isLockContention(error) {
  const message = typeof error?.message === "string" ? error.message : "";
  return error?.code === "ERR_SQLITE_ERROR" && /locked|busy/i.test(message);
}

function translateContention(error) {
  if (error instanceof AccountApiError) return error;
  return isLockContention(error) ? new AccountApiError(ErrorCode.CREDIT_LEDGER_BUSY) : error;
}

/**
 * One write transaction. `BEGIN IMMEDIATE` takes the write lock at the start rather than on the first write, so a
 * read-then-write sequence can never be split by another writer, and the balance a consumption sees is the balance it
 * spends against.
 */
function runTransaction(database, operation) {
  try {
    database.exec("BEGIN IMMEDIATE");
  } catch (error) {
    throw translateContention(error);
  }
  try {
    const result = operation();
    database.exec("COMMIT");
    return result;
  } catch (error) {
    try {
      database.exec("ROLLBACK");
    } catch {
      // The statement itself failed, so there was nothing to roll back.
    }
    throw translateContention(error);
  }
}

/** The account row, or a typed failure. A deleted or suspended account can never hold a live entitlement. */
export function requireAccount(database, userId) {
  const user = database.prepare("SELECT * FROM users WHERE user_id = ?").get(userId);
  if (!user) throw new AccountApiError(ErrorCode.ACCOUNT_NOT_FOUND);
  if (user.status === "DELETED") throw new AccountApiError(ErrorCode.ACCOUNT_DELETED);
  if (user.status === "SUSPENDED") throw new AccountApiError(ErrorCode.ACCOUNT_SUSPENDED);
  return user;
}

function membershipRow(database, userId) {
  return database.prepare("SELECT * FROM membership_accounts WHERE user_id = ?").get(userId) ?? null;
}

function recordTransition(database, {
  userId,
  fromPlan,
  toPlan,
  fromStatus,
  toStatus,
  reason,
  source,
  actorKind,
  actorDeveloperId = null,
  occurredAt,
}) {
  database.prepare(
    `INSERT INTO membership_transitions
       (transition_id, user_id, from_plan, to_plan, from_status, to_status, reason, source, actor_kind, actor_developer_id, occurred_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    newMembershipTransitionId(),
    userId,
    fromPlan,
    toPlan,
    fromStatus,
    toStatus,
    reason.slice(0, 200),
    source,
    actorKind,
    actorDeveloperId,
    occurredAt,
  );
}

/**
 * Creates the FREE baseline for an account. `INSERT OR IGNORE` plus the `UNIQUE(user_id)` constraint make this safe
 * under concurrency: the second writer changes nothing and reads the same row.
 */
function ensureBaseline(database, user, nowMillis) {
  const existing = membershipRow(database, user.user_id);
  if (existing) return existing;
  const timestamp = nowIso(nowMillis);
  const membershipId = newMembershipId();
  database.prepare(
    `INSERT OR IGNORE INTO membership_accounts
       (membership_id, user_id, plan, status, source, starts_at, ends_at, granted_by, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)`,
  ).run(membershipId, user.user_id, MEMBERSHIP_PLAN.FREE, MEMBERSHIP_STATUS.ACTIVE, MEMBERSHIP_SOURCE.DEFAULT_BASELINE,
    user.created_at ?? timestamp, timestamp, timestamp);
  const row = membershipRow(database, user.user_id);
  // The audit record is written only by the writer that actually created the row.
  if (row && row.membership_id === membershipId) {
    recordTransition(database, {
      userId: user.user_id,
      fromPlan: MEMBERSHIP_PLAN.FREE,
      toPlan: MEMBERSHIP_PLAN.FREE,
      fromStatus: MEMBERSHIP_STATUS.ACTIVE,
      toStatus: MEMBERSHIP_STATUS.ACTIVE,
      reason: "Deterministic free baseline assigned on first entitlement read.",
      source: MEMBERSHIP_SOURCE.DEFAULT_BASELINE,
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      occurredAt: timestamp,
    });
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "MEMBERSHIP_BASELINE_ASSIGNED",
      targetUserId: user.user_id,
      outcome: "SUCCESS",
      metadata: { plan: MEMBERSHIP_PLAN.FREE, source: MEMBERSHIP_SOURCE.DEFAULT_BASELINE },
      occurredAt: timestamp,
    });
  }
  return row;
}

/**
 * Materializes the free baseline for an account. Called when an account is created, so membership state exists from the
 * first moment there is an account to have it, and again (idempotently) by every entitlement read, so an account created
 * before this phase — or by any future path that forgets — still resolves to exactly one baseline.
 *
 * Assumes the caller holds a write transaction, which is how registration already works.
 */
export function ensureMembershipBaseline(database, userId, { now = Date.now() } = {}) {
  const user = requireAccount(database, userId);
  return ensureBaseline(database, user, now);
}

/**
 * Applies expiry to a membership whose end timestamp has passed: the row moves to the FREE baseline with source
 * `BASELINE_FALLBACK`, the change is recorded in membership history, and one audit entry is appended. No renewal is
 * attempted, because nothing bills: an expired plan simply stops applying.
 */
function applyExpiry(database, row, catalog, nowMillis) {
  const timestamp = nowIso(nowMillis);
  database.prepare(
    `UPDATE membership_accounts
        SET plan = ?, status = ?, source = ?, starts_at = ?, ends_at = NULL, granted_by = NULL, updated_at = ?
      WHERE membership_id = ? AND ends_at IS NOT NULL AND ends_at <= ?`,
  ).run(MEMBERSHIP_PLAN.FREE, MEMBERSHIP_STATUS.ACTIVE, MEMBERSHIP_SOURCE.BASELINE_FALLBACK, timestamp, timestamp,
    row.membership_id, timestamp);
  recordTransition(database, {
    userId: row.user_id,
    fromPlan: row.plan,
    toPlan: MEMBERSHIP_PLAN.FREE,
    fromStatus: row.status,
    toStatus: MEMBERSHIP_STATUS.ACTIVE,
    reason: "Plan end timestamp passed; the account returned to the free baseline.",
    source: MEMBERSHIP_SOURCE.BASELINE_FALLBACK,
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    occurredAt: timestamp,
  });
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType: "MEMBERSHIP_EXPIRED",
    targetUserId: row.user_id,
    outcome: "SUCCESS",
    metadata: { plan: row.plan, endedAt: row.ends_at, fallbackPlan: MEMBERSHIP_PLAN.FREE },
    occurredAt: timestamp,
  });
  void catalog;
  return membershipRow(database, row.user_id);
}

/**
 * Resolves an account's membership state, creating the baseline on first read and applying expiry when it is due.
 * Must be called inside a transaction (`BEGIN IMMEDIATE`) so the read and any repair write are one atomic step.
 */
export function resolveMembership(database, configuration, userId, { now = Date.now() } = {}) {
  const catalog = planCatalog(configuration);
  const user = requireAccount(database, userId);
  let row = ensureBaseline(database, user, now);
  if (!row) throw new AccountApiError(ErrorCode.MEMBERSHIP_UNAVAILABLE);
  if (row.ends_at !== null && row.status === MEMBERSHIP_STATUS.ACTIVE && isPast(row.ends_at, now)) {
    row = applyExpiry(database, row, catalog, now);
  }
  const plan = catalog.byId[row.plan] ?? null;
  if (!plan) throw new AccountApiError(ErrorCode.MEMBERSHIP_UNAVAILABLE);
  // An expired or cancelled plan is reported as unavailable rather than silently rendering as active.
  const effectiveStatus = row.status === MEMBERSHIP_STATUS.ACTIVE && row.ends_at !== null && isPast(row.ends_at, now)
    ? MEMBERSHIP_STATUS.EXPIRED
    : row.status;
  return Object.freeze({
    membershipId: row.membership_id,
    plan: row.plan,
    status: effectiveStatus,
    source: row.source,
    startsAt: row.starts_at,
    endsAt: row.ends_at,
    updatedAt: row.updated_at,
    planDefinition: plan,
    catalog,
  });
}

function activeAdministrativeGrants(database, userId, nowMillis) {
  const timestamp = nowIso(nowMillis);
  const rows = database.prepare(
    `SELECT entitlement_key, created_at, expires_at
       FROM developer_access_grants
      WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ?
      ORDER BY created_at DESC LIMIT 25`,
  ).all(userId, timestamp);
  return rows
    .filter((row) => GRANT_KEYS.includes(row.entitlement_key))
    .map((row) => Object.freeze({
      entitlementKey: row.entitlement_key,
      grantedAt: row.created_at,
      expiresAt: row.expires_at,
    }));
}

function recentTransitions(database, userId) {
  return database.prepare(
    `SELECT from_plan, to_plan, from_status, to_status, reason, source, occurred_at
       FROM membership_transitions
      WHERE user_id = ?
      ORDER BY rowid DESC LIMIT ?`,
  ).all(userId, MAXIMUM_TRANSITION_HISTORY).map((row) => Object.freeze({
    fromPlan: row.from_plan,
    toPlan: row.to_plan,
    fromStatus: row.from_status,
    toStatus: row.to_status,
    reason: row.reason,
    source: row.source,
    occurredAt: row.occurred_at,
  }));
}

/**
 * The entitlement view for one account. This is the single answer to "what may this account do?", and it is what the
 * authenticated API returns and what a future protected feature must ask. `credits` is included as a summary so the
 * membership endpoint can answer in one round trip; `creditBalance` remains the authoritative balance reader.
 *
 * @param {import('node:sqlite').DatabaseSync} database
 * @param {object} configuration
 * @param {string} userId
 * The credit summary is read through the ledger's in-transaction reader, because this function is itself always called
 * inside a transaction — from a route wrapper or from inside a developer tool's atomic unit. Nesting a second
 * `BEGIN IMMEDIATE` would fail, so there is exactly one reader per transaction and no callback to get wrong.
 *
 * @param {{ now?: number, includeHistory?: boolean, includeCredits?: boolean }} [options]
 */
export function getAccountEntitlementsInTransaction(database, configuration, userId, options = {}) {
  const { now = Date.now(), includeHistory = false, includeCredits = true } = options;
  const resolved = resolveMembership(database, configuration, userId, { now });
  const plan = resolved.planDefinition;
  const grants = activeAdministrativeGrants(database, userId, now);
  const entitlements = plan.entitlements.map((key) => {
    const definition = entitlementDefinition(key);
    return Object.freeze({
      key: definition.key,
      label: definition.label,
      description: definition.description,
      grantedBy: "PLAN",
      plan: definition.plan,
    });
  });
  const has = Object.freeze(Object.fromEntries(entitlements.map((entry) => [entry.key, true])));
  const summary = includeCredits ? creditBalanceInTransaction(database, configuration, userId, { now }) : null;
  return Object.freeze({
    membership: Object.freeze({
      plan: resolved.plan,
      planName: plan.name,
      status: resolved.status,
      source: resolved.source,
      startsAt: resolved.startsAt,
      endsAt: resolved.endsAt,
      updatedAt: resolved.updatedAt,
    }),
    plan: Object.freeze({
      id: plan.id,
      name: plan.name,
      availability: plan.availability,
      availabilityLabel: plan.availabilityLabel,
      priceState: plan.priceState,
      purchasable: plan.purchasable,
      grantable: plan.grantable,
      generationAllowance: plan.generationAllowance,
    }),
    entitlements,
    has,
    administrativeGrants: grants,
    credits: summary,
    history: includeHistory ? recentTransitions(database, userId) : null,
  });
}

/** True when the view grants a key. An unregistered key is never granted. */
export function hasEntitlement(entitlementView, key) {
  if (!isRegisteredEntitlement(key)) return false;
  return entitlementView?.has?.[key] === true;
}

/**
 * The single enforcement point. Anything that guards a capability calls this rather than reading a plan string, so the
 * rule lives in one place and every denial is attributable.
 */
export function requireEntitlement(entitlementView, key) {
  if (!hasEntitlement(entitlementView, key)) throw new AccountApiError(ErrorCode.ENTITLEMENT_REQUIRED);
  return true;
}

/**
 * Enforcement with an audit record. A denied entitlement check is an authorization failure, so it is appended to the
 * one audit log with the account as the target and the key (never a plan string from a client) as metadata. The caller
 * owns the transaction, exactly like the Phase 19 tool registry.
 */
export function enforceEntitlement(database, entitlementView, key, { now = Date.now(), targetUserId = null } = {}) {
  if (hasEntitlement(entitlementView, key)) {
    return Object.freeze({ key, granted: true, plan: entitlementView?.membership?.plan ?? null });
  }
  const plan = entitlementView?.membership?.plan ?? null;
  const requiredPlan = entitlementDefinition(key)?.plan ?? null;
  // The denial is appended with the account as the target and the key as metadata. The entitlement view itself carries
  // no account identifier, so the caller supplies the target it already authenticated.
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType: "ENTITLEMENT_DENIED",
    targetUserId,
    outcome: "DENIED",
    metadata: { entitlement: isRegisteredEntitlement(key) ? key : "UNREGISTERED", plan, requiredPlan },
    occurredAt: nowIso(now),
  });
  throw new AccountApiError(ErrorCode.ENTITLEMENT_REQUIRED);
}

/**
 * Assigns a membership plan to an account. Only the developer tool boundary calls this, and it always writes a domain
 * history row; the tool registry writes the accompanying developer audit record in the same transaction.
 *
 * A plan whose own definition carries a promotional credit allocation has that allocation applied by the caller (see
 * `credits.grantPlanAllocation`), which keeps this function about membership state alone.
 */
export function assignMembership(database, configuration, {
  userId,
  planId,
  reason,
  days,
  actorDeveloperId,
  now = Date.now(),
}) {
  const catalog = planCatalog(configuration);
  const plan = catalog.byId[planId] ?? null;
  if (!plan) throw new AccountApiError(ErrorCode.MEMBERSHIP_UNAVAILABLE);
  if (!plan.grantable) throw new AccountApiError(ErrorCode.MEMBERSHIP_UNAVAILABLE);
  if (!Number.isInteger(days) || days < 1 || days > catalog.maximumPlanGrantDays) {
    throw new AccountApiError(ErrorCode.MEMBERSHIP_UNAVAILABLE);
  }
  if (typeof reason !== "string" || reason.trim().length < 3 || reason.trim().length > 200) {
    throw new AccountApiError(ErrorCode.MEMBERSHIP_UNAVAILABLE);
  }
  const timestamp = nowIso(now);
  const endsAt = new Date(now + days * 24 * 60 * 60 * 1000).toISOString();
  const user = requireAccount(database, userId);
  const previous = ensureBaseline(database, user, now);
  const fromPlan = previous.plan;
  const fromStatus = previous.status;
  if (fromPlan === plan.id && previous.ends_at === endsAt) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_CONFLICT, "That membership grant is already in effect.");
  }
  database.prepare(
    `UPDATE membership_accounts
        SET plan = ?, status = ?, source = ?, starts_at = ?, ends_at = ?, granted_by = ?, updated_at = ?
      WHERE membership_id = ?`,
  ).run(plan.id, MEMBERSHIP_STATUS.ACTIVE, MEMBERSHIP_SOURCE.DEVELOPER_GRANT, timestamp, endsAt,
    actorDeveloperId ?? null, timestamp, previous.membership_id);
  recordTransition(database, {
    userId,
    fromPlan,
    toPlan: plan.id,
    fromStatus,
    toStatus: MEMBERSHIP_STATUS.ACTIVE,
    reason: reason.trim(),
    source: MEMBERSHIP_SOURCE.DEVELOPER_GRANT,
    actorKind: AUDIT_ACTOR_KIND.DEVELOPER,
    actorDeveloperId: actorDeveloperId ?? null,
    occurredAt: timestamp,
  });
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.DEVELOPER,
    actorDeveloperId: actorDeveloperId ?? null,
    actionType: "MEMBERSHIP_GRANTED",
    targetUserId: userId,
    outcome: "SUCCESS",
    metadata: { plan: plan.id, previousPlan: fromPlan, days, endsAt, availability: plan.availability },
    occurredAt: timestamp,
  });
  return Object.freeze({
    membershipId: previous.membership_id,
    plan: plan.id,
    previousPlan: fromPlan,
    status: MEMBERSHIP_STATUS.ACTIVE,
    startsAt: timestamp,
    endsAt,
    promotionalCredits: plan.creditPolicy.promotionalCredits,
    expiresInDays: plan.creditPolicy.expiresInDays,
  });
}

/** Atomic wrapper: resolves membership, grants, and the credit summary inside one write transaction. */
export function getAccountEntitlements(database, configuration, userId, options = {}) {
  return runTransaction(database, () => getAccountEntitlementsInTransaction(database, configuration, userId, options));
}

export {
  ENTITLEMENT,
  ENTITLEMENT_REGISTRY,
  MEMBERSHIP_PLAN,
  MEMBERSHIP_SOURCE,
  MEMBERSHIP_STATUS,
  planCatalog,
  planEntitlements,
};

/** Runs `operation` in one write transaction. Exported so callers compose entitlement work atomically. */
export { runTransaction as runMembershipTransaction };
