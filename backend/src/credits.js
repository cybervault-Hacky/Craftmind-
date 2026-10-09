/**
 * The build-credit ledger (Phase 22).
 *
 * Credits are never a column on `users`. The balance of an account is always *derived* from `credit_ledger`, which the
 * database itself refuses to update or delete (triggers, see migration v5). That single decision removes the entire
 * class of bugs where a retry, a crash, or a second request moves a balance twice: there is nothing to increment.
 *
 * ## The model
 *
 * A ledger row is one of five types. GRANT and ADJUSTMENT add credits; CONSUME and REVERSAL take credits away; EXPIRE
 * is a *marker* that records credits lapsed without being spent. Markers exist so history states plainly what happened;
 * they are deliberately excluded from the arithmetic, because a grant that has passed its `expires_at` already stops
 * counting. Handling expiry any other way would subtract the same credits twice.
 *
 * ## The spendable balance, precisely
 *
 * Credits are fungible, so consumption is attributed to grants in the order they expire (soonest first, then oldest;
 * credits with no expiry come last). One pass over an account's grants answers everything:
 *
 *     negatives = Σ|CONSUME| + Σ|REVERSAL|
 *     for each grant, in expiry order:
 *         spend = min(grant, negatives); negatives -= spend; leftover = grant - spend
 *         if the grant has expired: it lapsed (a marker may be recorded, worth nothing)
 *         else:                       leftover is spendable
 *
 * This is why "10 unexpired + 10 expiring, then spend 15" leaves 5 spendable rather than 0 or 10: the 15 came out of
 * the credits that would have lapsed first. The balance can never be negative, and it never counts a lapsed credit.
 *
 * ## The other rules
 *
 *   * **Atomic and serialized.** Every mutation runs inside `BEGIN IMMEDIATE`, so SQLite serializes writers. Two
 *     simultaneous consumptions of 7 against a balance of 10 cannot both succeed.
 *   * **Idempotent.** A caller may retry with the same idempotency key as often as it likes. The key is stored as an
 *     HMAC digest scoped to the account and the operation, so a retry with the same request returns the original
 *     transaction instead of writing a second one, while the same key used for a *different* request is a conflict
 *     rather than a silent success.
 *   * **Reconciled on read.** Credits past `expires_at` stop counting immediately; the EXPIRE marker is written the
 *     next time the balance is read, bounded and idempotent. No background job, no scheduler, and a service that was
 *     down for a month still reconciles the first time anyone looks.
 *   * **Reversible, never rewritten.** A reversal is a new REVERSAL row referencing the original transaction, bounded
 *     to what still stands, and recorded in the audit log. History is never edited or deleted.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { creditOperationDigest, newCreditTransactionId } from "./ids.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";

/** Ledger transaction types. Each one maps to a positive or negative amount, and the database enforces both. */
export const CREDIT_TRANSACTION_TYPE = Object.freeze({
  GRANT: "GRANT",
  CONSUME: "CONSUME",
  EXPIRE: "EXPIRE",
  ADJUSTMENT: "ADJUSTMENT",
  REVERSAL: "REVERSAL",
});

/** The set of operations an idempotency key may be used for. */
export const CREDIT_OPERATION = Object.freeze({
  GRANT: "GRANT",
  CONSUME: "CONSUME",
  EXPIRE: "EXPIRE",
  ADJUSTMENT: "ADJUSTMENT",
  REVERSAL: "REVERSAL",
});

/** Where a ledger entry originated. Payment-derived allocation does not exist in this phase and has no source value. */
export const CREDIT_SOURCE = Object.freeze({
  DEVELOPER_GRANT: "DEVELOPER_GRANT",
  PLAN_ALLOCATION: "PLAN_ALLOCATION",
  BUILD_CONSUMPTION: "BUILD_CONSUMPTION",
  EXPIRATION: "EXPIRATION",
  REVERSAL: "REVERSAL",
});

const DEFAULT_EXPIRY_DAYS = 90;
const DEFAULT_RECONCILIATION_BATCH = 50;
const MAXIMUM_REASON_LENGTH = 200;
const MAXIMUM_PAGE_SIZE = 100;

function nowIso(nowMillis = Date.now()) {
  return new Date(nowMillis).toISOString();
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

function requirePositiveAmount(amount, maximum) {
  if (!Number.isSafeInteger(amount) || amount <= 0 || amount > maximum) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
  return amount;
}

function requireReason(reason) {
  const trimmed = typeof reason === "string" ? reason.trim() : "";
  if (trimmed.length < 3 || trimmed.length > MAXIMUM_REASON_LENGTH) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
  return trimmed;
}

function requireIdempotencyKey(idempotencyKey) {
  const key = typeof idempotencyKey === "string" ? idempotencyKey.trim() : "";
  if (!/^[A-Za-z0-9._:-]{8,128}$/.test(key)) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  return key;
}

/** A stable fingerprint of a request, so the same key used for a different request is detectable. */
function requestDigest(parts) {
  return JSON.stringify(parts);
}

function accountExists(database, userId) {
  const user = database.prepare("SELECT user_id, status FROM users WHERE user_id = ?").get(userId);
  if (!user) throw new AccountApiError(ErrorCode.ACCOUNT_NOT_FOUND);
  if (user.status === "DELETED") throw new AccountApiError(ErrorCode.ACCOUNT_DELETED);
  if (user.status === "SUSPENDED") throw new AccountApiError(ErrorCode.ACCOUNT_SUSPENDED);
  return user;
}

/**
 * One pass over an account's grants, attributing every negative row in expiry order.
 *
 * @returns {{ spendable: number, standing: Array<{transactionId: string, remainder: number, expiresAt: string|null}>,
 *             lapsed: Map<string, number>, unattributed: number }}
 */
function ledgerState(database, userId, nowIsoTimestamp) {
  const grants = database.prepare(
    `SELECT transaction_id, amount, expires_at, created_at FROM credit_ledger
      WHERE user_id = ? AND amount > 0
      ORDER BY (expires_at IS NULL) ASC, expires_at ASC, created_at ASC, transaction_id ASC`,
  ).all(userId);
  // Marker rows are excluded on purpose: a lapsed grant already stops counting because of its own `expires_at`.
  const negativeTotal = Math.abs(Number(database.prepare(
    `SELECT COALESCE(SUM(amount), 0) AS total FROM credit_ledger
      WHERE user_id = ? AND amount < 0 AND type <> ?`,
  ).get(userId, CREDIT_TRANSACTION_TYPE.EXPIRE).total));

  let unattributed = negativeTotal;
  let spendable = 0;
  const standing = [];
  const lapsed = new Map();
  for (const grant of grants) {
    const amount = Number(grant.amount);
    const spend = Math.min(amount, unattributed);
    unattributed -= spend;
    const remainder = amount - spend;
    const expired = grant.expires_at !== null && grant.expires_at <= nowIsoTimestamp;
    if (expired) {
      lapsed.set(grant.transaction_id, remainder);
    } else {
      spendable += remainder;
      standing.push({ transactionId: grant.transaction_id, remainder, expiresAt: grant.expires_at });
    }
  }
  return { spendable: Math.max(0, spendable), standing, lapsed, unattributed };
}

/** The spendable balance of an account: an unexpired, unspent, unreversed figure that is never negative. */
function availableCredit(database, userId, nowIsoTimestamp) {
  return ledgerState(database, userId, nowIsoTimestamp).spendable;
}

/** Credits still standing inside grants that expire within the horizon, capped by what is actually spendable. */
function expiringWithin(database, userId, fromIso, toIso) {
  const rows = database.prepare(
    `SELECT transaction_id, amount, expires_at FROM credit_ledger
      WHERE user_id = ? AND amount > 0 AND expires_at IS NOT NULL AND expires_at > ? AND expires_at <= ?
      ORDER BY expires_at ASC LIMIT 500`,
  ).all(userId, fromIso, toIso);
  if (rows.length === 0) return 0;
  const state = ledgerState(database, userId, fromIso);
  const standings = new Map(state.standing.map((grant) => [grant.transactionId, grant.remainder]));
  const lapsed = state.lapsed;
  const total = rows.reduce((sum, row) => sum + (standings.get(row.transaction_id) ?? lapsed.get(row.transaction_id) ?? 0), 0);
  return Math.min(total, state.spendable);
}

/** The unspent, unreversed remainder of one specific grant, whether or not it has expired. */
function grantRemainder(database, userId, transactionId, nowIsoTimestamp) {
  const state = ledgerState(database, userId, nowIsoTimestamp);
  const grant = database.prepare(
    "SELECT expires_at FROM credit_ledger WHERE transaction_id = ? AND user_id = ?",
  ).get(transactionId, userId);
  if (!grant) return { found: false, expired: false, remainder: 0 };
  const expired = grant.expires_at !== null && grant.expires_at <= nowIsoTimestamp;
  if (expired) return { found: true, expired: true, remainder: state.lapsed.get(transactionId) ?? 0 };
  const standing = state.standing.find((entry) => entry.transactionId === transactionId);
  return { found: true, expired: false, remainder: standing?.remainder ?? 0 };
}

/**
 * The append-only bookkeeping pass for expired credits.
 *
 * DESIGN CHOICE — reconciliation on read, not a background job. Credits past `expires_at` already stop counting; the
 * EXPIRE marker exists only so the ledger states plainly that they lapsed, and so an operator reading the history sees
 * a cause rather than an unexplained disappearance. Doing it lazily keeps the service free of a scheduler, makes
 * behaviour identical in a test and in production, and means a process that was down for a month still reconciles the
 * first time anyone looks. The pass is bounded by `configuration.membership.reconciliationBatch`, processed
 * oldest-expiry-first, and every grant it examines receives an `operation_key` marker whether or not anything lapsed —
 * so the scan always advances and a second call is a no-op.
 *
 * Runs inside the caller's transaction.
 */
function reconcileExpiredCredits(database, configuration, userId, transactionKey, {
  now, batch, actorKind = AUDIT_ACTOR_KIND.SYSTEM, actorDeveloperId = null,
}) {
  const timestamp = nowIso(now);
  const candidates = database.prepare(
    `SELECT transaction_id, expires_at FROM credit_ledger l
      WHERE l.user_id = ? AND l.amount > 0 AND l.type = ? AND l.expires_at IS NOT NULL AND l.expires_at <= ?
        AND NOT EXISTS (
          SELECT 1 FROM credit_operation_keys k WHERE k.operation_digest = ? || ':expiry:' || l.transaction_id
        )
      ORDER BY l.expires_at ASC, l.transaction_id ASC LIMIT ?`,
  ).all(userId, CREDIT_TRANSACTION_TYPE.GRANT, timestamp, transactionKey, batch);
  if (candidates.length === 0) return 0;

  const state = ledgerState(database, userId, timestamp);
  let lapsedTotal = 0;
  for (const grant of candidates) {
    const amount = state.lapsed.get(grant.transaction_id) ?? 0;
    let transactionId = null;
    if (amount > 0) {
      transactionId = writeTransaction(database, {
        userId,
        type: CREDIT_TRANSACTION_TYPE.EXPIRE,
        amount: -amount,
        reason: `Promotional credits expired on ${String(grant.expires_at).slice(0, 10)}`,
        source: CREDIT_SOURCE.EXPIRATION,
        referenceId: grant.transaction_id,
        actorKind,
        actorDeveloperId,
        createdAt: timestamp,
      });
      lapsedTotal += amount;
      appendAuditRecord(database, {
        actorKind,
        actorDeveloperId,
        actionType: "CREDIT_EXPIRED",
        targetUserId: userId,
        outcome: "SUCCESS",
        metadata: { amount, grantTransactionId: grant.transaction_id, expiresAt: grant.expires_at },
        occurredAt: timestamp,
      });
    }
    recordOperationKey(database, {
      digest: `${transactionKey}:expiry:${grant.transaction_id}`,
      userId,
      operation: CREDIT_OPERATION.EXPIRE,
      requestDigestValue: requestDigest([CREDIT_OPERATION.EXPIRE, grant.transaction_id, amount]),
      transactionId,
      createdAt: timestamp,
    });
  }
  return lapsedTotal;
}

function toPublicTransaction(row) {
  return Object.freeze({
    transactionId: row.transaction_id,
    type: row.type,
    amount: row.amount,
    reason: row.reason,
    source: row.source,
    referenceId: row.reference_id,
    actorKind: row.actor_kind,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
  });
}

function listCreditTransactionsInternal(database, userId, { limit = 25, type = null } = {}) {
  const bounded = Math.max(1, Math.min(limit, MAXIMUM_PAGE_SIZE));
  if (type) {
    return database.prepare(
      `SELECT transaction_id, type, amount, reason, source, reference_id, actor_kind, created_at, expires_at
         FROM credit_ledger WHERE user_id = ? AND type = ?
        ORDER BY created_at DESC, rowid DESC LIMIT ?`,
    ).all(userId, type, bounded).map(toPublicTransaction);
  }
  return database.prepare(
    `SELECT transaction_id, type, amount, reason, source, reference_id, actor_kind, created_at, expires_at
       FROM credit_ledger WHERE user_id = ?
      ORDER BY created_at DESC, rowid DESC LIMIT ?`,
  ).all(userId, bounded).map(toPublicTransaction);
}

/**
 * The deterministic balance of an account, including a bounded reconciliation of anything already expired.
 *
 * @param {import('node:sqlite').DatabaseSync} database
 * @param {object} configuration
 * @param {string} userId
 * @param {{ now?: number, includeHistory?: boolean, historyLimit?: number, reconcile?: boolean }} [options]
 * @returns {{ available: number, expiring: number, expired: number, expiringInDays: number,
 *             lifetimeGranted: number, lifetimeConsumed: number, history: Array<object>|null }}
 */
export function creditBalanceInTransaction(database, configuration, userId, {
  now = Date.now(), includeHistory = false, historyLimit = 25, reconcile = true,
} = {}) {
  const timestamp = nowIso(now);
  accountExists(database, userId);
  if (reconcile) {
    // The reconciliation handle is derived from the account and the operation, so two concurrent readers coalesce on
    // the same digest instead of writing two sets of markers.
    const transactionKey = creditOperationDigest(configuration?.authSecret ?? "", {
      userId,
      operation: CREDIT_OPERATION.EXPIRE,
      idempotencyKey: "reconciliation-v1",
    });
    reconcileExpiredCredits(database, configuration, userId, transactionKey, {
      now,
      batch: configuration?.membership?.reconciliationBatch ?? DEFAULT_RECONCILIATION_BATCH,
    });
  }
  const state = ledgerState(database, userId, timestamp);
  const windowDays = configuration?.membership?.creditExpiryDays ?? DEFAULT_EXPIRY_DAYS;
  const horizon = new Date(now + windowDays * 24 * 60 * 60 * 1000).toISOString();
  const lifetimeGranted = Number(database.prepare(
    "SELECT COALESCE(SUM(amount), 0) AS total FROM credit_ledger WHERE user_id = ? AND amount > 0",
  ).get(userId).total);
  const lifetimeConsumed = Math.abs(Number(database.prepare(
    "SELECT COALESCE(SUM(amount), 0) AS total FROM credit_ledger WHERE user_id = ? AND type = ?",
  ).get(userId, CREDIT_TRANSACTION_TYPE.CONSUME).total));
  const expiredTotal = Math.abs(Number(database.prepare(
    "SELECT COALESCE(SUM(amount), 0) AS total FROM credit_ledger WHERE user_id = ? AND type = ?",
  ).get(userId, CREDIT_TRANSACTION_TYPE.EXPIRE).total));
  return Object.freeze({
    available: state.spendable,
    expiring: expiringWithin(database, userId, timestamp, horizon),
    expired: expiredTotal,
    expiringInDays: windowDays,
    lifetimeGranted,
    lifetimeConsumed,
    history: includeHistory ? listCreditTransactionsInternal(database, userId, { limit: historyLimit }) : null,
  });
}

/** Credit history, newest first. `type` is an optional filter; only public fields are returned. */
export function listCreditTransactionsInTransaction(database, userId, { limit = 25, type = null } = {}) {
  accountExists(database, userId);
  return listCreditTransactionsInternal(database, userId, { limit, type });
}

function assertOperationMatch(existing, { operation, digest }) {
  if (existing.operation !== operation) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_CONFLICT);
  if (existing.request_digest !== digest) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_DUPLICATE);
}

/**
 * The answer to a retried request: the original transaction and the balance as it stands now. Nothing is written, so a
 * timeout, a restart, or an impatient client can never consume or grant twice.
 */
function reuseTransaction(database, userId, transactionId, nowIsoTimestamp) {
  if (transactionId === null || transactionId === undefined) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_CONFLICT);
  }
  const row = database.prepare(
    `SELECT transaction_id, type, amount, reason, source, reference_id, actor_kind, created_at, expires_at
       FROM credit_ledger WHERE user_id = ? AND transaction_id = ?`,
  ).get(userId, transactionId);
  if (!row) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_CONFLICT);
  return Object.freeze({
    reused: true,
    transaction: toPublicTransaction(row),
    available: availableCredit(database, userId, nowIsoTimestamp),
  });
}

function writeTransaction(database, {
  userId, type, amount, reason, source, referenceId = null, actorKind, actorDeveloperId = null, createdAt, expiresAt = null,
}) {
  const transactionId = newCreditTransactionId();
  database.prepare(
    `INSERT INTO credit_ledger
       (transaction_id, user_id, type, amount, reason, source, reference_id, actor_kind, actor_developer_id, created_at, expires_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(transactionId, userId, type, amount, reason, source, referenceId, actorKind, actorDeveloperId, createdAt, expiresAt);
  return transactionId;
}

function recordOperationKey(database, { digest, userId, operation, requestDigestValue, transactionId, createdAt }) {
  database.prepare(
    `INSERT INTO credit_operation_keys (operation_digest, user_id, operation, request_digest, transaction_id, created_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
  ).run(digest, userId, operation, requestDigestValue, transactionId, createdAt);
}

function existingKey(database, digest) {
  return database.prepare("SELECT * FROM credit_operation_keys WHERE operation_digest = ?").get(digest) ?? null;
}

/**
 * Grants promotional credits. Developer-tool only: the caller has already authenticated a developer and passed role
 * authorization, and the actor identifiers travel into the ledger row and the audit record so a grant always names who
 * made it. `PLAN_ALLOCATION` grants come from a membership grant and use the same path.
 *
 * Runs inside the caller's transaction.
 */
export function grantCreditsInTransaction(database, configuration, {
  userId,
  amount,
  reason,
  idempotencyKey,
  source = CREDIT_SOURCE.DEVELOPER_GRANT,
  expiresAt = null,
  expiresInDays = null,
  referenceId = null,
  actorKind = AUDIT_ACTOR_KIND.DEVELOPER,
  actorDeveloperId = null,
  authSecret = null,
  now = Date.now(),
}) {
  const maximum = configuration?.membership?.maximumGrantCredits ?? 10_000;
  const boundedAmount = requirePositiveAmount(amount, maximum);
  const cleanReason = requireReason(reason);
  const key = requireIdempotencyKey(idempotencyKey);
  const createdAt = nowIso(now);
  let expiry = null;
  if (expiresAt !== null && expiresAt !== undefined) {
    const parsed = Date.parse(expiresAt);
    if (!Number.isFinite(parsed) || parsed <= now) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
    expiry = new Date(parsed).toISOString();
  } else if (Number.isInteger(expiresInDays) && expiresInDays > 0) {
    expiry = new Date(now + expiresInDays * 24 * 60 * 60 * 1000).toISOString();
  }
  const digest = creditOperationDigest(authSecret ?? configuration?.authSecret ?? "", {
    userId, operation: CREDIT_OPERATION.GRANT, idempotencyKey: key,
  });
  const expected = requestDigest([CREDIT_OPERATION.GRANT, boundedAmount, cleanReason, source, expiry, referenceId]);

  accountExists(database, userId);
  const existing = existingKey(database, digest);
  if (existing) {
    assertOperationMatch(existing, { operation: CREDIT_OPERATION.GRANT, digest: expected });
    return reuseTransaction(database, userId, existing.transaction_id, createdAt);
  }
  const transactionId = writeTransaction(database, {
    userId,
    type: CREDIT_TRANSACTION_TYPE.GRANT,
    amount: boundedAmount,
    reason: cleanReason,
    source,
    referenceId,
    actorKind,
    actorDeveloperId,
    createdAt,
    expiresAt: expiry,
  });
  recordOperationKey(database, {
    digest, userId, operation: CREDIT_OPERATION.GRANT, requestDigestValue: expected, transactionId, createdAt,
  });
  const available = availableCredit(database, userId, createdAt);
  appendAuditRecord(database, {
    actorKind,
    actorDeveloperId,
    actionType: "CREDIT_GRANTED",
    targetUserId: userId,
    outcome: "SUCCESS",
    metadata: { amount: boundedAmount, source, expiresAt: expiry, availableAfter: available, reason: cleanReason },
    occurredAt: createdAt,
  });
  return Object.freeze({
    reused: false,
    transaction: Object.freeze({
      transactionId,
      type: CREDIT_TRANSACTION_TYPE.GRANT,
      amount: boundedAmount,
      reason: cleanReason,
      source,
      referenceId,
      actorKind,
      createdAt,
      expiresAt: expiry,
    }),
    available,
  });
}

/**
 * Consumes build credits for one operation. This is the only way credits leave an account through normal use, and it is
 * refused — with nothing written — when the balance is short.
 *
 * Runs inside the caller's transaction, which is what makes the read and the write one atomic step: SQLite serializes
 * the writers, so a second concurrent consumption sees the balance the first one left behind.
 */
export function consumeBuildCreditsInTransaction(database, configuration, {
  userId,
  amount,
  reason,
  idempotencyKey,
  referenceId = null,
  authSecret = null,
  now = Date.now(),
}) {
  const maximum = configuration?.membership?.maximumConsumeCredits ?? 100;
  const boundedAmount = requirePositiveAmount(amount, maximum);
  const cleanReason = requireReason(reason);
  const key = requireIdempotencyKey(idempotencyKey);
  const createdAt = nowIso(now);
  const digest = creditOperationDigest(authSecret ?? configuration?.authSecret ?? "", {
    userId, operation: CREDIT_OPERATION.CONSUME, idempotencyKey: key,
  });
  const expected = requestDigest([CREDIT_OPERATION.CONSUME, boundedAmount, cleanReason, referenceId]);

  accountExists(database, userId);
  const existing = existingKey(database, digest);
  if (existing) {
    assertOperationMatch(existing, { operation: CREDIT_OPERATION.CONSUME, digest: expected });
    return reuseTransaction(database, userId, existing.transaction_id, createdAt);
  }
  const available = availableCredit(database, userId, createdAt);
  if (available < boundedAmount) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "CREDIT_OPERATION_REJECTED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { operation: CREDIT_OPERATION.CONSUME, requested: boundedAmount, available },
      occurredAt: createdAt,
    });
    throw new AccountApiError(ErrorCode.INSUFFICIENT_CREDITS);
  }
  const transactionId = writeTransaction(database, {
    userId,
    type: CREDIT_TRANSACTION_TYPE.CONSUME,
    amount: -boundedAmount,
    reason: cleanReason,
    source: CREDIT_SOURCE.BUILD_CONSUMPTION,
    referenceId,
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    createdAt,
  });
  recordOperationKey(database, {
    digest, userId, operation: CREDIT_OPERATION.CONSUME, requestDigestValue: expected, transactionId, createdAt,
  });
  const remaining = availableCredit(database, userId, createdAt);
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType: "CREDIT_CONSUMED",
    targetUserId: userId,
    outcome: "SUCCESS",
    metadata: { amount: boundedAmount, availableAfter: remaining, referenceId },
    occurredAt: createdAt,
  });
  return Object.freeze({
    reused: false,
    transaction: Object.freeze({
      transactionId,
      type: CREDIT_TRANSACTION_TYPE.CONSUME,
      amount: -boundedAmount,
      reason: cleanReason,
      source: CREDIT_SOURCE.BUILD_CONSUMPTION,
      referenceId,
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      createdAt,
      expiresAt: null,
    }),
    available: remaining,
  });
}

/**
 * Reverses part or all of a still-standing grant. Developer-tool only: the original transaction is never modified, the
 * reversal is bounded by what has not already been spent, lapsed, or reversed, and the account is never taken below
 * zero by a correction.
 *
 * Runs inside the caller's transaction.
 */
export function reverseCreditGrantInTransaction(database, configuration, {
  userId,
  transactionId,
  amount = null,
  reason,
  idempotencyKey,
  actorDeveloperId = null,
  authSecret = null,
  now = Date.now(),
}) {
  const maximum = configuration?.membership?.maximumGrantCredits ?? 10_000;
  const requested = amount === null || amount === undefined ? null : requirePositiveAmount(amount, maximum);
  const cleanReason = requireReason(reason);
  const key = requireIdempotencyKey(idempotencyKey);
  const createdAt = nowIso(now);
  const digest = creditOperationDigest(authSecret ?? configuration?.authSecret ?? "", {
    userId, operation: CREDIT_OPERATION.REVERSAL, idempotencyKey: key,
  });
  const expected = requestDigest([CREDIT_OPERATION.REVERSAL, transactionId, requested, cleanReason]);

  accountExists(database, userId);
  const existing = existingKey(database, digest);
  if (existing) {
    assertOperationMatch(existing, { operation: CREDIT_OPERATION.REVERSAL, digest: expected });
    return reuseTransaction(database, userId, existing.transaction_id, createdAt);
  }
  const original = database.prepare(
    "SELECT * FROM credit_ledger WHERE transaction_id = ? AND user_id = ?",
  ).get(transactionId, userId);
  // A grant belonging to another account is simply not found, so the endpoint cannot be used to probe other ledgers.
  if (!original) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  if (original.type !== CREDIT_TRANSACTION_TYPE.GRANT) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_CONFLICT);
  const remainder = grantRemainder(database, userId, transactionId, createdAt);
  if (remainder.expired || remainder.remainder <= 0) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_CONFLICT, "That grant has already been spent, reversed, or expired.");
  }
  const reversalAmount = requested ?? remainder.remainder;
  if (reversalAmount > remainder.remainder) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_CONFLICT, "That grant no longer holds that many credits.");
  }
  const reversalId = writeTransaction(database, {
    userId,
    type: CREDIT_TRANSACTION_TYPE.REVERSAL,
    amount: -reversalAmount,
    reason: cleanReason,
    source: CREDIT_SOURCE.REVERSAL,
    referenceId: transactionId,
    actorKind: AUDIT_ACTOR_KIND.DEVELOPER,
    actorDeveloperId,
    createdAt,
  });
  recordOperationKey(database, {
    digest, userId, operation: CREDIT_OPERATION.REVERSAL, requestDigestValue: expected, transactionId: reversalId, createdAt,
  });
  const available = availableCredit(database, userId, createdAt);
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.DEVELOPER,
    actorDeveloperId,
    actionType: "CREDIT_REVERSED",
    targetUserId: userId,
    outcome: "SUCCESS",
    metadata: { amount: reversalAmount, originalTransactionId: transactionId, availableAfter: available, reason: cleanReason },
    occurredAt: createdAt,
  });
  return Object.freeze({
    reused: false,
    transaction: Object.freeze({
      transactionId: reversalId,
      type: CREDIT_TRANSACTION_TYPE.REVERSAL,
      amount: -reversalAmount,
      reason: cleanReason,
      source: CREDIT_SOURCE.REVERSAL,
      referenceId: transactionId,
      actorKind: AUDIT_ACTOR_KIND.DEVELOPER,
      createdAt,
      expiresAt: null,
    }),
    available,
  });
}

/**
 * Applies a plan's promotional credit allocation when the plan itself is granted. Called by the developer tool inside
 * the same transaction as the membership change, so a member either receives both the plan and its allocation, or
 * neither. A plan with no allocation writes no ledger row at all.
 */
export function grantPlanAllocation(database, configuration, {
  userId,
  planId,
  allocation,
  expiresInDays,
  idempotencyKey,
  actorDeveloperId,
  authSecret = null,
  now = Date.now(),
}) {
  if (!Number.isInteger(allocation) || allocation <= 0) return null;
  return grantCreditsInTransaction(database, configuration, {
    userId,
    amount: allocation,
    reason: `Promotional allocation for the ${planId} membership plan.`,
    idempotencyKey,
    source: CREDIT_SOURCE.PLAN_ALLOCATION,
    expiresInDays: Number.isInteger(expiresInDays) && expiresInDays > 0
      ? expiresInDays
      : (configuration?.membership?.creditExpiryDays ?? DEFAULT_EXPIRY_DAYS),
    actorKind: AUDIT_ACTOR_KIND.DEVELOPER,
    actorDeveloperId,
    authSecret,
    now,
  });
}

// ------------------------------------------------------------------------------------------------- atomic entry points

/** Atomic wrappers for API callers. Every credit read and mutation joins one write transaction. */
export function creditBalance(database, configuration, userId, options = {}) {
  return runTransaction(database, () => creditBalanceInTransaction(database, configuration, userId, options));
}

export function listCreditTransactions(database, userId, options = {}) {
  return runTransaction(database, () => listCreditTransactionsInTransaction(database, userId, options));
}

export function grantCredits(database, configuration, request) {
  return runTransaction(database, () => grantCreditsInTransaction(database, configuration, request));
}

export function consumeBuildCredits(database, configuration, request) {
  return runTransaction(database, () => consumeBuildCreditsInTransaction(database, configuration, request));
}

export function reverseCreditGrant(database, configuration, request) {
  return runTransaction(database, () => reverseCreditGrantInTransaction(database, configuration, request));
}

export {
  availableCredit as availableCreditAmount,
  ledgerState as creditLedgerState,
  requestDigest as creditRequestDigest,
  MAXIMUM_PAGE_SIZE as MAXIMUM_CREDIT_PAGE_SIZE,
  MAXIMUM_REASON_LENGTH as MAXIMUM_CREDIT_REASON_LENGTH,
};
