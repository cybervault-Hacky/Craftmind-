/**
 * Order disputes (Phase 30).
 *
 * Phase 27 deliberately left one door open and said so: once any milestone is approved an order can no longer be
 * cancelled unilaterally, because "neither side can strand accepted work," and closing such an order "belongs to a
 * future dispute phase." This module is that phase. It lets the two people on an order raise a formal, immutable,
 * jointly-visible dispute and, only when **both** of them agree, close the order — with no third party needed and,
 * critically, with no money involved.
 *
 * What this is and is not:
 *
 *   * **Not settlement.** There is no refund, payout, commission, escrow, chargeback, or partial payment anywhere.
 *     A dispute can freeze an order and can close one as `CANCELLED`; it can never move a value, and nothing here
 *     states or implies that money moved. Amounts remain the frozen terms recorded in Phase 27 and are never edited.
 *   * **Participants only.** A dispute is opened by, responded to by, and visible to exactly the buyer and the
 *     creator of that order, all resolved server-side from the order row and the session token. A stranger receives
 *     the same uniform not-found whether the order has a dispute or not.
 *   * **Append-only evidence.** Statements are immutable versions (the delivery discipline from Phase 27): posting a
 *     new one never erases an earlier one, and a database trigger enforces immutability as well as the application.
 *   * **Two-of-two to close, one-of-one to step back.** Either party may record a position — `CONTINUE` (keep
 *     working) or `CLOSE` (finish the order through the dispute). The dispute resolves when both have recorded a
 *     position and both match: two `CONTINUE` closes the dispute and leaves the order active; two `CLOSE` closes the
 *     dispute and the order together (status `CANCELLED`, history preserved). A mixed pair stays open so the parties
 *     can keep talking. The party who opened a dispute may withdraw it at any time, which unblocks the order.
 *   * **A dispute freezes closure, not work.** While a dispute is open the order cannot be completed or cancelled by
 *     the normal Phase 27 routes (guarded inside those functions); milestones and deliveries keep flowing so the
 *     parties can converge on a resolution.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";
import { requireAccount } from "./entitlements.js";
import { newDisputeId, newDisputeStatementId } from "./ids.js";
import { RESOURCE_KIND } from "./ownership.js";
import { validateBoundedText } from "./text-fields.js";

export const DISPUTE_STATUS = Object.freeze({ OPEN: "OPEN", RESOLVED: "RESOLVED" });
export const DISPUTE_OUTCOME = Object.freeze({ WITHDRAWN: "WITHDRAWN", CONTINUED: "CONTINUED", CLOSED: "CLOSED" });
export const DISPUTE_REASON_CATEGORY = Object.freeze({
  DELIVERY: "DELIVERY",
  QUALITY: "QUALITY",
  COMMUNICATION: "COMMUNICATION",
  TIMELINE: "TIMELINE",
  SCOPE: "SCOPE",
  CONDUCT: "CONDUCT",
  OTHER: "OTHER",
});
export const DISPUTE_POSITION = Object.freeze({ CONTINUE: "CONTINUE", CLOSE: "CLOSE" });

export const DISPUTE_FIELD_LIMITS = Object.freeze({
  reason: { minimum: 5, maximum: 2000 },
  statement: { minimum: 1, maximum: 2000 },
  statementMaximum: 12,
  list: { defaultLimit: 20, maximumLimit: 50, maximumOffset: 100_000 },
});

function nowIso(nowMillis = Date.now()) {
  return new Date(nowMillis).toISOString();
}

function auditDispute(database, actionType, userId, metadata, now, outcome = "SUCCESS") {
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType,
    targetUserId: typeof userId === "string" ? userId : null,
    outcome,
    // Ids, categories, counts, and state only — never a statement body or a reason's free text.
    metadata,
    occurredAt: nowIso(now),
  });
}

export function parseDisputeListQuery(searchParams, limits = DISPUTE_FIELD_LIMITS.list) {
  const integer = (name, { maximum, fallback }) => {
    const raw = searchParams?.get?.(name);
    if (raw === null || raw === undefined || raw === "") return fallback;
    if (!/^\d{1,9}$/.test(raw)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
    const value = Number(raw);
    if (!Number.isSafeInteger(value) || value > maximum) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
    return value;
  };
  const limit = integer("limit", { maximum: limits.maximumLimit, fallback: limits.defaultLimit });
  const offset = integer("offset", { maximum: limits.maximumOffset, fallback: 0 });
  if (limit < 1) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  return { limit, offset };
}

/* --------------------------------------------------------------------- order/participant resolution (read-only) */

function orderIdShape(orderId) {
  if (typeof orderId !== "string" || !/^ord_[0-9a-fA-F-]{36}$/.test(orderId)) {
    throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
  }
  return orderId;
}

function disputeIdShape(disputeId) {
  if (typeof disputeId !== "string" || !/^dsp_[0-9a-fA-F-]{36}$/.test(disputeId)) {
    throw new AccountApiError(ErrorCode.DISPUTE_NOT_FOUND);
  }
  return disputeId;
}

function orderParticipants(database, orderId) {
  orderIdShape(orderId);
  return database.prepare("SELECT order_id, buyer_id, creator_user_id, status FROM orders WHERE order_id = ?").get(orderId) ?? null;
}

/** Loads a dispute and proves the caller is a participant, using only server rows. A non-participant gets not-found. */
function participantDisputeRow(database, userId, orderId, disputeId) {
  disputeIdShape(disputeId);
  const order = orderParticipants(database, orderId);
  if (!order) throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
  const isParticipant = order.buyer_id === userId || order.creator_user_id === userId;
  if (!isParticipant) {
    auditDispute(database, "DISPUTE_ACCESS_DENIED", userId, {
      resourceKind: RESOURCE_KIND.ORDER_DISPUTE, reason: "not_order_participant", surface: "dispute",
    }, Date.now(), "DENIED");
    throw new AccountApiError(ErrorCode.DISPUTE_NOT_FOUND);
  }
  const dispute = database.prepare("SELECT * FROM order_disputes WHERE dispute_id = ? AND order_id = ?").get(disputeId, orderId) ?? null;
  if (!dispute) throw new AccountApiError(ErrorCode.DISPUTE_NOT_FOUND);
  return { dispute, order };
}

function roleFor(order, userId) {
  if (order.buyer_id === userId) return "BUYER";
  if (order.creator_user_id === userId) return "CREATOR";
  throw new AccountApiError(ErrorCode.DISPUTE_NOT_FOUND);
}

/* ------------------------------------------------------------------------------------ views */

function statementView(row) {
  return Object.freeze({
    id: row.statement_id,
    authorRole: row.author_role ?? null,
    version: row.version,
    body: row.body,
    createdAt: row.created_at,
  });
}

function disputeView(row, order, { statements = [], positions = [] } = {}) {
  const positionByRole = { BUYER: null, CREATOR: null };
  for (const p of positions) positionByRole[p.role] = p.position;
  return Object.freeze({
    id: row.dispute_id,
    orderId: row.order_id,
    reasonCategory: row.reason_category,
    reason: row.reason,
    status: row.status,
    outcome: row.outcome,
    openedByRole: roleFor(order, row.opened_by),
    statementCount: row.statement_count,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    resolvedAt: row.resolved_at,
    positions: Object.freeze({ buyer: positionByRole.BUYER, creator: positionByRole.CREATOR }),
    statements: Object.freeze(statements),
  });
}

/* ---------------------------------------------------------------------------- write paths */

/** Opens a dispute on the caller's own ACTIVE order. At most one OPEN dispute per order at a time. */
export function openDisputeInTransaction(database, configuration, userId, orderId, body, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const order = orderParticipants(database, orderId);
  if (!order) throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
  if (order.buyer_id !== userId && order.creator_user_id !== userId) {
    auditDispute(database, "DISPUTE_ACCESS_DENIED", userId, {
      resourceKind: RESOURCE_KIND.ORDER_DISPUTE, reason: "not_order_participant", surface: "open",
    }, now, "DENIED");
    throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
  }
  if (order.status !== "ACTIVE") {
    throw new AccountApiError(ErrorCode.DISPUTE_STATE_CONFLICT, "Only an active order can be disputed; this order is already closed.");
  }
  if (typeof body.reasonCategory !== "string" || !Object.values(DISPUTE_REASON_CATEGORY).includes(body.reasonCategory)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  const reason = validateBoundedText(body.reason, { ...DISPUTE_FIELD_LIMITS.reason, allowNewlines: true });
  const existingOpen = database.prepare(
    "SELECT dispute_id FROM order_disputes WHERE order_id = ? AND status = 'OPEN'",
  ).get(order.order_id);
  if (existingOpen) {
    throw new AccountApiError(ErrorCode.DISPUTE_STATE_CONFLICT, "This order already has an open dispute.");
  }
  const disputeId = newDisputeId();
  const timestamp = nowIso(now);
  const other = order.buyer_id === userId ? order.creator_user_id : order.buyer_id;
  try {
    database.prepare(
      `INSERT INTO order_disputes
         (dispute_id, order_id, opened_by, other_participant, reason_category, reason, status, outcome, statement_count, created_at, updated_at, resolved_at)
       VALUES (?, ?, ?, ?, ?, ?, 'OPEN', NULL, 0, ?, ?, NULL)`,
    ).run(disputeId, order.order_id, userId, other, body.reasonCategory, reason, timestamp, timestamp);
  } catch (error) {
    if (/UNIQUE/i.test(String(error?.message ?? ""))) {
      throw new AccountApiError(ErrorCode.DISPUTE_STATE_CONFLICT, "This order already has an open dispute.");
    }
    throw error;
  }
  auditDispute(database, "DISPUTE_OPENED", userId, { disputeId, orderId: order.order_id, reasonCategory: body.reasonCategory }, now);
  return disputeView(database.prepare("SELECT * FROM order_disputes WHERE dispute_id = ?").get(disputeId), order, { statements: [], positions: [] });
}

/** Appends an immutable statement to an open dispute. Either participant may speak; the earlier statements persist. */
export function addDisputeStatementInTransaction(database, configuration, userId, orderId, disputeId, body, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const { dispute, order } = participantDisputeRow(database, userId, orderId, disputeId);
  if (dispute.status !== DISPUTE_STATUS.OPEN) {
    throw new AccountApiError(ErrorCode.DISPUTE_STATE_CONFLICT, "This dispute is closed, so nothing more can be added.");
  }
  const text = validateBoundedText(body.body, { ...DISPUTE_FIELD_LIMITS.statement, allowNewlines: true });
  const version = dispute.statement_count + 1;
  if (version > DISPUTE_FIELD_LIMITS.statementMaximum) {
    throw new AccountApiError(ErrorCode.DISPUTE_STATE_CONFLICT, "This dispute has reached its statement limit; record a position to resolve it.");
  }
  const timestamp = nowIso(now);
  try {
    database.prepare(
      `INSERT INTO order_dispute_statements (statement_id, dispute_id, order_id, author_user_id, version, body, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).run(newDisputeStatementId(), dispute.dispute_id, order.order_id, userId, version, text, timestamp);
  } catch (error) {
    if (/UNIQUE/i.test(String(error?.message ?? ""))) {
      // A crossed concurrent append to the same version loses; the caller retries against the new count.
      throw new AccountApiError(ErrorCode.DISPUTE_STATE_CONFLICT, "That statement already exists; try again.");
    }
    throw error;
  }
  database.prepare("UPDATE order_disputes SET statement_count = ?, updated_at = ? WHERE dispute_id = ?").run(version, timestamp, dispute.dispute_id);
  auditDispute(database, "DISPUTE_STATEMENT_ADDED", userId, { disputeId: dispute.dispute_id, orderId: order.order_id, version }, now);
  const row = database.prepare(
    `SELECT s.*, CASE WHEN s.author_user_id = o.buyer_id THEN 'BUYER' ELSE 'CREATOR' END AS author_role
       FROM order_dispute_statements s JOIN orders o ON o.order_id = s.order_id
      WHERE s.dispute_id = ? AND s.version = ?`,
  ).get(dispute.dispute_id, version);
  return statementView(row);
}

/**
 * Records or replaces the caller's position. When both participants end up on the same page the dispute resolves —
 * and only a mutual `CLOSE` is allowed to close the order, still with no money.
 */
export function setDisputePositionInTransaction(database, configuration, userId, orderId, disputeId, body, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const { dispute, order } = participantDisputeRow(database, userId, orderId, disputeId);
  if (dispute.status !== DISPUTE_STATUS.OPEN) {
    throw new AccountApiError(ErrorCode.DISPUTE_STATE_CONFLICT, "This dispute is already resolved, so its position cannot change.");
  }
  const position = body.position;
  if (typeof position !== "string" || !Object.values(DISPUTE_POSITION).includes(position)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  const timestamp = nowIso(now);
  database.prepare(
    `INSERT INTO order_dispute_positions (dispute_id, participant_user_id, position, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(dispute_id, participant_user_id) DO UPDATE SET position = excluded.position, updated_at = excluded.updated_at`,
  ).run(dispute.dispute_id, userId, position, timestamp, timestamp);
  database.prepare("UPDATE order_disputes SET updated_at = ? WHERE dispute_id = ?").run(timestamp, dispute.dispute_id);
  auditDispute(database, "DISPUTE_POSITION_SET", userId, { disputeId: dispute.dispute_id, orderId: order.order_id, position }, now);

  // Resolution is evaluated from the rows, never from the request: read both participants' current positions.
  const buyer = database.prepare("SELECT position FROM order_dispute_positions WHERE dispute_id = ? AND participant_user_id = ?")
    .get(dispute.dispute_id, order.buyer_id)?.position ?? null;
  const creator = database.prepare("SELECT position FROM order_dispute_positions WHERE dispute_id = ? AND participant_user_id = ?")
    .get(dispute.dispute_id, order.creator_user_id)?.position ?? null;

  if (buyer !== null && creator !== null && buyer === creator) {
    const outcome = buyer === DISPUTE_POSITION.CLOSE ? DISPUTE_OUTCOME.CLOSED : DISPUTE_OUTCOME.CONTINUED;
    resolveDispute(database, dispute.dispute_id, outcome, order, timestamp);
    if (outcome === DISPUTE_OUTCOME.CLOSED) {
      // Closing an order whose work was accepted is exactly the terminal action Phase 27 refused to do unilaterally.
      // It is a status change with full history preserved — no refund, payout, or settlement is performed or implied.
      database.prepare(
        "UPDATE orders SET status = 'CANCELLED', cancelled_at = ?, updated_at = ? WHERE order_id = ? AND status = 'ACTIVE'",
      ).run(timestamp, timestamp, order.order_id);
    }
    auditDispute(database, "DISPUTE_RESOLVED", userId, {
      disputeId: dispute.dispute_id, orderId: order.order_id, outcome, closedOrder: outcome === DISPUTE_OUTCOME.CLOSED,
    }, now);
  }

  return disputeDetailInTransaction(database, configuration, userId, orderId, dispute.dispute_id, { now });
}

/** Withdrawal by the party who opened the dispute. It closes the dispute and unblocks the order without closing it. */
export function withdrawDisputeInTransaction(database, configuration, userId, orderId, disputeId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const { dispute, order } = participantDisputeRow(database, userId, orderId, disputeId);
  if (dispute.opened_by !== userId) {
    auditDispute(database, "DISPUTE_ACCESS_DENIED", userId, {
      resourceKind: RESOURCE_KIND.ORDER_DISPUTE, reason: "not_opener", surface: "withdraw",
    }, now, "DENIED");
    throw new AccountApiError(ErrorCode.DISPUTE_NOT_FOUND);
  }
  if (dispute.status !== DISPUTE_STATUS.OPEN) {
    throw new AccountApiError(ErrorCode.DISPUTE_STATE_CONFLICT, "This dispute is already resolved, so there is nothing to withdraw.");
  }
  const timestamp = nowIso(now);
  resolveDispute(database, dispute.dispute_id, DISPUTE_OUTCOME.WITHDRAWN, order, timestamp);
  auditDispute(database, "DISPUTE_WITHDRAWN", userId, { disputeId: dispute.dispute_id, orderId: order.order_id }, now);
  return disputeDetailInTransaction(database, configuration, userId, orderId, dispute.dispute_id, { now });
}

function resolveDispute(database, disputeId, outcome, order, timestamp) {
  const changed = database.prepare(
    "UPDATE order_disputes SET status = 'RESOLVED', outcome = ?, resolved_at = ?, updated_at = ? WHERE dispute_id = ? AND status = 'OPEN'",
  ).run(outcome, timestamp, timestamp, disputeId).changes;
  if (changed !== 1) throw new AccountApiError(ErrorCode.DISPUTE_STATE_CONFLICT, "This dispute changed state; nothing was applied.");
}

/* ---------------------------------------------------------------------------- read paths */

export function disputeDetailInTransaction(database, configuration, userId, orderId, disputeId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const { dispute, order } = participantDisputeRow(database, userId, orderId, disputeId);
  const statements = database.prepare(
    `SELECT s.*, CASE WHEN s.author_user_id = o.buyer_id THEN 'BUYER' ELSE 'CREATOR' END AS author_role
       FROM order_dispute_statements s JOIN orders o ON o.order_id = s.order_id
      WHERE s.dispute_id = ? ORDER BY s.version`,
  ).all(dispute.dispute_id).map(statementView);
  const positions = disputePositionsFor(database, dispute.dispute_id, order);
  return disputeView(dispute, order, { statements, positions });
}

function disputePositionsFor(database, disputeId, order) {
  return database.prepare(
    "SELECT participant_user_id, position FROM order_dispute_positions WHERE dispute_id = ?",
  ).all(disputeId).map((row) => ({
    role: row.participant_user_id === order.buyer_id ? "BUYER" : "CREATOR",
    position: row.position,
  }));
}

/** Every dispute on one order (open and resolved), newest first, with positions but not full statement bodies. */
export function listOrderDisputesInTransaction(database, configuration, userId, orderId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const order = orderParticipants(database, orderId);
  if (!order) throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
  if (order.buyer_id !== userId && order.creator_user_id !== userId) {
    auditDispute(database, "DISPUTE_ACCESS_DENIED", userId, {
      resourceKind: RESOURCE_KIND.ORDER_DISPUTE, reason: "not_order_participant", surface: "list",
    }, now, "DENIED");
    throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
  }
  const rows = database.prepare(
    "SELECT * FROM order_disputes WHERE order_id = ? ORDER BY created_at DESC, dispute_id",
  ).all(order.order_id);
  return Object.freeze({
    disputes: Object.freeze(rows.map((row) => disputeView(row, order, { positions: disputePositionsFor(database, row.dispute_id, order) }))),
  });
}

/** The caller's own disputes across all of their orders, newest first. */
export function listMyDisputesInTransaction(database, configuration, userId, filters, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const total = database.prepare(
    "SELECT COUNT(*) AS count FROM order_disputes WHERE opened_by = ? OR other_participant = ?",
  ).get(userId, userId).count;
  const rows = database.prepare(
    `SELECT * FROM order_disputes WHERE opened_by = ? OR other_participant = ?
      ORDER BY updated_at DESC, dispute_id LIMIT ? OFFSET ?`,
  ).all(userId, userId, filters.limit, filters.offset);
  const view = rows.map((row) => {
    const order = orderParticipants(database, row.order_id) ?? { order_id: row.order_id, buyer_id: row.opened_by, creator_user_id: row.other_participant, status: "ACTIVE" };
    return Object.freeze({
      ...disputeView(row, order, { positions: disputePositionsFor(database, row.dispute_id, order) }),
      // A compact order reference so the list is navigable without exposing the counterparty's identity.
      orderStatus: order.status,
      openedByMe: row.opened_by === userId,
    });
  });
  return Object.freeze({ total, limit: filters.limit, offset: filters.offset, disputes: Object.freeze(view) });
}

/* ------------------------------------------------------------------ freeze hook consumed by Phase 27 */

/** True while an order carries an OPEN dispute. `order-lifecycle` uses this to freeze completion and cancellation. */
export function hasOpenDisputeForOrder(database, orderId) {
  const row = database.prepare("SELECT 1 AS x FROM order_disputes WHERE order_id = ? AND status = 'OPEN' LIMIT 1").get(orderId);
  return row !== undefined && row !== null;
}

/** The open-dispute summary surfaced on the order detail read, or null when the order is undisputed. */
export function openDisputeSummaryForOrder(database, orderId) {
  const row = database.prepare(
    "SELECT * FROM order_disputes WHERE order_id = ? AND status = 'OPEN' ORDER BY created_at DESC LIMIT 1",
  ).get(orderId);
  if (!row) return null;
  const closeCount = database.prepare(
    "SELECT COUNT(*) AS count FROM order_dispute_positions WHERE dispute_id = ? AND position = 'CLOSE'",
  ).get(row.dispute_id).count;
  const continueCount = database.prepare(
    "SELECT COUNT(*) AS count FROM order_dispute_positions WHERE dispute_id = ? AND position = 'CONTINUE'",
  ).get(row.dispute_id).count;
  return Object.freeze({
    id: row.dispute_id,
    reasonCategory: row.reason_category,
    openedAt: row.created_at,
    statementCount: row.statement_count,
    positions: Object.freeze({ close: closeCount, continue: continueCount }),
  });
}
