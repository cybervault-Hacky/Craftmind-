/**
 * Marketplace trust: reports, the avoid/block list, and a per-account trust posture (Phase 30).
 *
 * This module gives buyers and creators protection that is entirely non-monetary. It reads and writes only the state
 * this phase added; it never touches a balance, a price, a refund, a payout, or an entitlement, and it does not
 * fabricate a public trust score — CraftMind does not invent a reputation it cannot substantiate.
 *
 * Design rules, in the same spirit as every earlier marketplace phase:
 *
 *   * **The server decides.** Every relationship — who filed a report, who the report is about, who blocked whom — is
 *     derived from server-side rows and the authenticated session. No endpoint accepts a target id, a reporter id, or
 *     a status from a client. The actor is always the account resolved from the bearer token.
 *   * **A report is about a real subject.** `LISTING`, `JOB`, or `ORDER`. The subject must exist; an `ORDER` report is
 *     restricted to that order's own participants so nobody can report an unrelated order or learn anything from the
 *     difference between "not a participant" and "no such order" (both are the uniform not-found).
 *   * **Reporting protects the reporter.** The party a report is about sees the category, the subject, and the fact
 *     that an open report exists — never who filed it and never the free-text note. Attribution of accusations would
 *     turn a safety tool into a harassment tool. The reporter, by contrast, always sees their own full submission.
 *   * **Blocking is relationship-bound.** You can only block a counterparty you actually have a one-to-one
 *     relationship with through an order or one of your jobs' proposals — never an arbitrary account id. That keeps the
 *     feature from becoming a way to enumerate or target strangers. A block only prevents proposals; it deletes no
 *     record and blocks nothing outside this phase.
 *   * **Idempotent where it matters.** Re-blocking the same account is a no-op success; a duplicate open report on the
 *     same subject is a typed conflict rather than a second row (also enforced by a partial unique index).
 *   * **One audit log.** Every state change, and every access denial, is written through the shared append-only audit
 *     boundary with ids and categories only — never a note.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";
import { requireAccount } from "./entitlements.js";
import { newReportId } from "./ids.js";
import { RESOURCE_KIND } from "./ownership.js";
import { validateBoundedText } from "./text-fields.js";

export const REPORT_SUBJECT_TYPE = Object.freeze({ LISTING: "LISTING", JOB: "JOB", ORDER: "ORDER" });
export const REPORT_CATEGORY = Object.freeze({
  SPAM: "SPAM",
  INAPPROPRIATE: "INAPPROPRIATE",
  MISREPRESENTATION: "MISREPRESENTATION",
  SAFETY: "SAFETY",
  CONDUCT: "CONDUCT",
  OTHER: "OTHER",
});
export const REPORT_STATUS = Object.freeze({ OPEN: "OPEN", WITHDRAWN: "WITHDRAWN" });
export const BLOCK_SOURCE_TYPE = Object.freeze({ ORDER: "ORDER", PROPOSAL: "PROPOSAL" });

const SUBJECT_ID_PATTERNS = Object.freeze({
  LISTING: /^lst_[0-9a-fA-F-]{36}$/,
  JOB: /^job_[0-9a-fA-F-]{36}$/,
  ORDER: /^ord_[0-9a-fA-F-]{36}$/,
});

export const TRUST_FIELD_LIMITS = Object.freeze({
  note: { minimum: 1, maximum: 1000 },
  blockReason: { maximum: 240 },
  list: { defaultLimit: 20, maximumLimit: 50, maximumOffset: 100_000 },
});

const SUBJECT_NOT_FOUND = Object.freeze({
  LISTING: ErrorCode.LISTING_NOT_FOUND,
  JOB: ErrorCode.JOB_NOT_FOUND,
  ORDER: ErrorCode.ORDER_NOT_FOUND,
});

function nowIso(nowMillis = Date.now()) {
  return new Date(nowMillis).toISOString();
}

function auditTrust(database, actionType, userId, metadata, now, outcome = "SUCCESS") {
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType,
    targetUserId: typeof userId === "string" ? userId : null,
    outcome,
    // Ids, categories, and state only — never a report note or any personal detail.
    metadata,
    occurredAt: nowIso(now),
  });
}

/** Parses the shared `limit`/`offset` shape without importing the order/listing readers, so trust stays decoupled. */
export function parseTrustListQuery(searchParams, limits = TRUST_FIELD_LIMITS.list) {
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

/* ------------------------------------------------------------------------------- subject resolution */

function validatedSubject(type, subjectId) {
  if (typeof type !== "string" || !Object.values(REPORT_SUBJECT_TYPE).includes(type)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  if (typeof subjectId !== "string" || !SUBJECT_ID_PATTERNS[type].test(subjectId)) {
    throw new AccountApiError(SUBJECT_NOT_FOUND[type]);
  }
  return subjectId;
}

/**
 * Resolves the report's subject into the account it concerns (the *target*). Returns `null` for an unknown subject,
 * and for an order when the reporter is not a participant. The caller turns any `null` into the same not-found so a
 * probe cannot tell "no such order" from "not your order."
 */
function resolveSubjectTarget(database, reporterUserId, type, subjectId) {
  if (type === REPORT_SUBJECT_TYPE.LISTING) {
    const row = database.prepare(
      `SELECT ml.status, cp.user_id AS owner_user_id
         FROM marketplace_listings ml JOIN creator_profiles cp ON cp.creator_id = ml.creator_id
        WHERE ml.listing_id = ?`,
    ).get(subjectId);
    if (!row) return null;
    return { targetUserId: row.owner_user_id, subjectStatus: row.status };
  }
  if (type === REPORT_SUBJECT_TYPE.JOB) {
    const row = database.prepare("SELECT buyer_id, status FROM buyer_jobs WHERE job_id = ?").get(subjectId);
    if (!row) return null;
    return { targetUserId: row.buyer_id, subjectStatus: row.status };
  }
  // ORDER: the target is always the counterparty from the reporter's point of view, and only a participant may report.
  const order = database.prepare(
    "SELECT buyer_id, creator_user_id, status FROM orders WHERE order_id = ?",
  ).get(subjectId);
  if (!order) return null;
  if (order.buyer_id === reporterUserId) return { targetUserId: order.creator_user_id, subjectStatus: order.status };
  if (order.creator_user_id === reporterUserId) return { targetUserId: order.buyer_id, subjectStatus: order.status };
  return null;
}

/* ------------------------------------------------------------------------------------- views */

/** The reporter's own view: full submission including their note, which the target never sees. */
function ownReportView(row) {
  return Object.freeze({
    id: row.report_id,
    subjectType: row.subject_type,
    subjectId: row.subject_id,
    category: row.category,
    note: row.note,
    status: row.status,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    withdrawnAt: row.withdrawn_at,
  });
}

/** The subject-owner's view: what is open against them, stripped of reporter identity and note text. */
function receivedReportView(row) {
  return Object.freeze({
    id: row.report_id,
    subjectType: row.subject_type,
    subjectId: row.subject_id,
    category: row.category,
    status: row.status,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  });
}

function blockView(row) {
  return Object.freeze({
    blockedUserId: row.blocked_user_id,
    handle: row.handle ?? null,
    displayName: row.display_name ?? null,
    reason: row.reason,
    createdAt: row.created_at,
  });
}

/* -------------------------------------------------------------------------------- report write paths */

/**
 * Files a report against a listing, job, or the counterparty of the caller's own order. Idempotency is by subject:
 * the same reporter cannot hold two OPEN reports on the same subject (a typed conflict, and a partial unique index).
 */
export function createReportInTransaction(database, configuration, userId, body, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const type = body.subjectType;
  const subjectId = validatedSubject(type, body.subjectId);
  if (typeof body.category !== "string" || !Object.values(REPORT_CATEGORY).includes(body.category)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  const note = body.note === undefined || body.note === null
    ? null
    : validateBoundedText(body.note, { ...TRUST_FIELD_LIMITS.note, allowNewlines: true, allowEmpty: false });
  const resolved = resolveSubjectTarget(database, userId, type, subjectId);
  if (!resolved) {
    // A missing subject and a subject the caller may not report are the same answer; neither leaks existence.
    throw new AccountApiError(SUBJECT_NOT_FOUND[type]);
  }
  const targetUserId = resolved.targetUserId;
  if (targetUserId === userId) {
    // Reporting your own listing/job, or (for an order) a self-target, is meaningless and is refused rather than
    // stored. The schema's `target_user_id <> reporter_user_id` CHECK is the backstop.
    throw new AccountApiError(ErrorCode.INVALID_REQUEST, "You cannot report your own resource.");
  }
  const existingOpen = database.prepare(
    "SELECT report_id FROM marketplace_reports WHERE reporter_user_id = ? AND subject_type = ? AND subject_id = ? AND status = 'OPEN'",
  ).get(userId, type, subjectId);
  if (existingOpen) {
    throw new AccountApiError(ErrorCode.REPORT_STATE_CONFLICT, "You already have an open report on that subject.");
  }
  const reportId = newReportId();
  const timestamp = nowIso(now);
  try {
    database.prepare(
      `INSERT INTO marketplace_reports
         (report_id, reporter_user_id, target_user_id, subject_type, subject_id, category, note, status, created_at, updated_at, withdrawn_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, NULL)`,
    ).run(reportId, userId, targetUserId, type, subjectId, body.category, note, timestamp, timestamp);
  } catch (error) {
    if (/UNIQUE/i.test(String(error?.message ?? ""))) {
      throw new AccountApiError(ErrorCode.REPORT_STATE_CONFLICT, "You already have an open report on that subject.");
    }
    throw error;
  }
  auditTrust(database, "REPORT_FILED", userId, { reportId, subjectType: type, subjectId, category: body.category }, now);
  return ownReportView(database.prepare("SELECT * FROM marketplace_reports WHERE report_id = ?").get(reportId));
}

/** Withdraws one's own OPEN report. Withdrawal never deletes the row; the record of the report and its removal persist. */
export function withdrawReportInTransaction(database, configuration, userId, reportId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  if (typeof reportId !== "string" || !/^rpt_[0-9a-fA-F-]{36}$/.test(reportId)) {
    throw new AccountApiError(ErrorCode.REPORT_NOT_FOUND);
  }
  const row = database.prepare("SELECT * FROM marketplace_reports WHERE report_id = ?").get(reportId) ?? null;
  if (!row) throw new AccountApiError(ErrorCode.REPORT_NOT_FOUND);
  if (row.reporter_user_id !== userId) {
    // Touching someone else's report is a denial against the acting account; the report's existence is not confirmed.
    auditTrust(database, "REPORT_ACCESS_DENIED", userId, {
      resourceKind: RESOURCE_KIND.MARKETPLACE_REPORT, reportId, reason: "not_reporter",
    }, now, "DENIED");
    throw new AccountApiError(ErrorCode.REPORT_NOT_FOUND);
  }
  if (row.status !== REPORT_STATUS.OPEN) {
    throw new AccountApiError(ErrorCode.REPORT_STATE_CONFLICT, "That report is already closed, so nothing was changed.");
  }
  const timestamp = nowIso(now);
  const changed = database.prepare(
    "UPDATE marketplace_reports SET status = 'WITHDRAWN', withdrawn_at = ?, updated_at = ? WHERE report_id = ? AND status = 'OPEN'",
  ).run(timestamp, timestamp, reportId).changes;
  if (changed !== 1) throw new AccountApiError(ErrorCode.REPORT_STATE_CONFLICT, "That report is already closed, so nothing was changed.");
  auditTrust(database, "REPORT_WITHDRAWN", userId, { reportId, subjectType: row.subject_type, subjectId: row.subject_id }, now);
  return ownReportView(database.prepare("SELECT * FROM marketplace_reports WHERE report_id = ?").get(reportId));
}

/* ------------------------------------------------------------------------------------- report reads */

/** Reports the caller filed, newest first. */
export function listMyReportsInTransaction(database, configuration, userId, filters, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const total = database.prepare("SELECT COUNT(*) AS count FROM marketplace_reports WHERE reporter_user_id = ?").get(userId).count;
  const rows = database.prepare(
    `SELECT * FROM marketplace_reports WHERE reporter_user_id = ?
      ORDER BY created_at DESC, report_id LIMIT ? OFFSET ?`,
  ).all(userId, filters.limit, filters.offset);
  return Object.freeze({ total, limit: filters.limit, offset: filters.offset, reports: Object.freeze(rows.map(ownReportView)) });
}

/**
 * Reports open against the caller (their listings, jobs, or order-counterparty reports), newest first, with the
 * reporter and note removed. This is the transparency half of creator protection, not an accusation from nowhere.
 */
export function listReportsAboutMeInTransaction(database, configuration, userId, filters, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  return listReceivedReports(database, userId, filters);
}

export function listReceivedReports(database, userId, filters) {
  // The target is resolved and stored at write time, so "reports about me" is a direct, indexed read on
  // target_user_id — the reporter is never the target (enforced by a CHECK), which keeps a filed report out of the
  // filer's own inbox. The reporter identity and the note are stripped by `receivedReportView`.
  const rows = database.prepare(
    `SELECT * FROM marketplace_reports
      WHERE status = 'OPEN' AND target_user_id = ?
      ORDER BY created_at DESC, report_id LIMIT ? OFFSET ?`,
  ).all(userId, filters.limit, filters.offset);
  const total = database.prepare(
    "SELECT COUNT(*) AS count FROM marketplace_reports WHERE status = 'OPEN' AND target_user_id = ?",
  ).get(userId).count;
  return Object.freeze({
    openCount: total,
    limit: filters.limit,
    offset: filters.offset,
    reports: Object.freeze(rows.map(receivedReportView)),
  });
}

/* ------------------------------------------------------------------------------- blocks (creator protection) */

/**
 * Derives the counterparty to block from a one-to-one relationship the caller genuinely holds: the other participant
 * of one of their orders, or the creator of one of their job's proposals. Any other account is unreachable by design.
 */
function resolveBlockedCounterparty(database, actorUserId, sourceType, sourceId) {
  if (sourceType === BLOCK_SOURCE_TYPE.ORDER) {
    if (typeof sourceId !== "string" || !SUBJECT_ID_PATTERNS.ORDER.test(sourceId)) {
      throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
    }
    const order = database.prepare("SELECT buyer_id, creator_user_id FROM orders WHERE order_id = ?").get(sourceId);
    if (!order) throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
    if (order.buyer_id === actorUserId) return order.creator_user_id;
    if (order.creator_user_id === actorUserId) return order.buyer_id;
    auditTrust(database, "BLOCK_ENFORCED", actorUserId, {
      resourceKind: RESOURCE_KIND.MARKETPLACE_BLOCK, reason: "not_order_participant", sourceType,
    }, Date.now(), "DENIED");
    throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
  }
  if (sourceType === BLOCK_SOURCE_TYPE.PROPOSAL) {
    if (typeof sourceId !== "string" || !/^prp_[0-9a-fA-F-]{36}$/.test(sourceId)) {
      throw new AccountApiError(ErrorCode.PROPOSAL_NOT_FOUND);
    }
    const proposal = database.prepare(
      `SELECT jp.user_id AS creator_user_id, jb.buyer_id AS job_buyer_id
         FROM job_proposals jp JOIN buyer_jobs jb ON jb.job_id = jp.job_id
        WHERE jp.proposal_id = ?`,
    ).get(sourceId);
    if (!proposal) throw new AccountApiError(ErrorCode.PROPOSAL_NOT_FOUND);
    // Only the job's owner may block a proposer on it; a stranger cannot reach a creator through someone else's job.
    if (proposal.job_buyer_id !== actorUserId) {
      throw new AccountApiError(ErrorCode.PROPOSAL_NOT_FOUND);
    }
    return proposal.creator_user_id;
  }
  throw new AccountApiError(ErrorCode.INVALID_REQUEST);
}

/** Blocks the counterparty of an order, or a proposer on one of your jobs. Re-blocking is an idempotent no-op. */
export function addBlockInTransaction(database, configuration, userId, body, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const sourceType = body.sourceType;
  if (!Object.values(BLOCK_SOURCE_TYPE).includes(sourceType)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const blockedUserId = resolveBlockedCounterparty(database, userId, sourceType, body.sourceId);
  if (blockedUserId === userId) throw new AccountApiError(ErrorCode.INVALID_REQUEST, "You cannot block yourself.");
  const reason = body.reason === undefined || body.reason === null
    ? null
    : validateBoundedText(body.reason, { ...TRUST_FIELD_LIMITS.blockReason, allowEmpty: false });
  const already = database.prepare(
    "SELECT created_at FROM marketplace_blocks WHERE blocker_user_id = ? AND blocked_user_id = ?",
  ).get(userId, blockedUserId);
  if (already) {
    return Object.freeze({ alreadyBlocked: true, blockedUserId, createdAt: already.created_at });
  }
  const timestamp = nowIso(now);
  try {
    database.prepare(
      "INSERT INTO marketplace_blocks (blocker_user_id, blocked_user_id, reason, created_at) VALUES (?, ?, ?, ?)",
    ).run(userId, blockedUserId, reason, timestamp);
  } catch (error) {
    if (/UNIQUE|PRIMARY/i.test(String(error?.message ?? ""))) {
      return Object.freeze({ alreadyBlocked: true, blockedUserId, createdAt: timestamp });
    }
    throw error;
  }
  auditTrust(database, "BLOCK_ADDED", userId, {
    resourceKind: RESOURCE_KIND.MARKETPLACE_BLOCK, sourceType, blockedUserId, hasReason: reason !== null,
  }, now);
  return Object.freeze({ alreadyBlocked: false, blockedUserId, createdAt: timestamp });
}

/** Removes one of the caller's own blocks. A block that is not theirs is indistinguishable from one that is absent. */
export function removeBlockInTransaction(database, configuration, userId, blockedUserId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  if (typeof blockedUserId !== "string" || !/^usr_[0-9a-fA-F-]{36}$/.test(blockedUserId)) {
    throw new AccountApiError(ErrorCode.MARKETPLACE_BLOCK_NOT_FOUND);
  }
  const changed = database.prepare(
    "DELETE FROM marketplace_blocks WHERE blocker_user_id = ? AND blocked_user_id = ?",
  ).run(userId, blockedUserId).changes;
  if (changed !== 1) throw new AccountApiError(ErrorCode.MARKETPLACE_BLOCK_NOT_FOUND);
  auditTrust(database, "BLOCK_REMOVED", userId, { resourceKind: RESOURCE_KIND.MARKETPLACE_BLOCK, blockedUserId }, now);
  return Object.freeze({ removed: true, blockedUserId });
}

export function listMyBlocksInTransaction(database, configuration, userId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const rows = database.prepare(
    `SELECT b.blocked_user_id, b.reason, b.created_at, cp.handle, cp.display_name
       FROM marketplace_blocks b LEFT JOIN creator_profiles cp ON cp.user_id = b.blocked_user_id
      WHERE b.blocker_user_id = ?
      ORDER BY b.created_at DESC, b.blocked_user_id`,
  ).all(userId);
  return Object.freeze({ blocks: Object.freeze(rows.map(blockView)) });
}

/**
 * The proposal-path check. `true` when a block exists in either direction between the two accounts, so a buyer who
 * blocked a creator and a creator who blocked that buyer both get the same protection: no new proposals connect them.
 * Kept as a pure predicate so the hire path can call it without importing the whole trust surface.
 */
export function isBlockedBetween(database, leftUserId, rightUserId) {
  if (typeof leftUserId !== "string" || typeof rightUserId !== "string") return false;
  if (leftUserId === rightUserId) return false;
  const row = database.prepare(
    `SELECT 1 FROM marketplace_blocks
      WHERE (blocker_user_id = ? AND blocked_user_id = ?) OR (blocker_user_id = ? AND blocked_user_id = ?)
      LIMIT 1`,
  ).get(leftUserId, rightUserId, rightUserId, leftUserId);
  return row !== undefined && row !== null;
}

/* ---------------------------------------------------------------------------- trust posture (my summary) */

/** The caller's trust posture: how many open reports concern them, how many they have filed, and how many blocks they hold. */
export function trustSummaryInTransaction(database, configuration, userId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const received = listReceivedReports(database, userId, { limit: 1, offset: 0 });
  const filedOpen = database.prepare(
    "SELECT COUNT(*) AS count FROM marketplace_reports WHERE reporter_user_id = ? AND status = 'OPEN'",
  ).get(userId).count;
  const blocked = database.prepare("SELECT COUNT(*) AS count FROM marketplace_blocks WHERE blocker_user_id = ?").get(userId).count;
  const blocking = database.prepare("SELECT COUNT(*) AS count FROM marketplace_blocks WHERE blocked_user_id = ?").get(userId).count;
  return Object.freeze({
    receivedOpenReports: received.openCount,
    filedOpenReports: filedOpen,
    blocksPlaced: blocked,
    blocksAgainstMe: blocking,
    // Honest note: this is a protection signal for the account itself, not a public score, and it never moves money.
    scope: "self_visible_only",
  });
}
