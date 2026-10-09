/**
 * Marketplace order lifecycle (Phase 27).
 *
 * An order converts exactly one **SELECTED** proposal on an **AWARDED** job into a formal work agreement. The
 * conversion is a one-way, snapshot-based operation:
 *
 *   * **Terms are frozen at creation.** Scope, budget range, deadline, delivery estimate, edition/version/loaders,
 *     and the revision limit are copied from the job and proposal rows into the order, so later edits to either
 *     can never silently rewrite an agreed order. Client-supplied ownership, terms, amounts, or statuses are never
 *     trusted: buyer, creator, proposal state, and budget all come from server-side records.
 *   * **One order per awarded proposal**, enforced by a unique index — retries and concurrent creates converge on
 *     one row and a predictable typed conflict.
 *   * **Milestones drive the state machine**: PENDING → IN_PROGRESS → SUBMITTED → (REVISION_REQUESTED ⇄
 *     SUBMITTED) → APPROVED. The creator starts and delivers; only the buyer approves or requests a revision; an
 *     approved milestone never reopens; completion requires every milestone approved; cancellation is available
 *     to either participant only while no milestone has been approved, and never erases history.
 *   * **Deliveries are immutable versions.** A revised submission appends version N+1 — the earlier note and
 *     references stay readable to both parties. Evidence is text plus validated https references only; this
 *     phase has no file upload, and nothing here implies the backend verified an external link.
 *   * **The revision policy is finite and counted from rows.** Each milestone allows `revision_limit` in-scope
 *     revision rounds; requests beyond it are refused with a dedicated error. A request flagged as outside the
 *     original scope is recorded as a SCOPE_CHANGE row that does **not** consume the limit and does not alter
 *     any agreed term — no payment or extra-fee mechanism exists in this phase to settle it.
 *   * **No payment fiction.** Creating, delivering, or completing an order never states or implies that money
 *     moved: there is no escrow, refund, commission, or settlement surface anywhere in this module.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";
import { requireAccount } from "./entitlements.js";
import { newDeliveryId, newMilestoneId, newOrderId, newRevisionId } from "./ids.js";
import { RESOURCE_KIND } from "./ownership.js";
import { validateBoundedText, validateSafeReference } from "./text-fields.js";
import { isKnownEdition } from "./compatibility.js";
import { JOB_STATUS, PROPOSAL_STATUS } from "./hire-jobs.js";

export const ORDER_STATUS = Object.freeze({ ACTIVE: "ACTIVE", COMPLETED: "COMPLETED", CANCELLED: "CANCELLED" });

export const MILESTONE_STATUS = Object.freeze({
  PENDING: "PENDING",
  IN_PROGRESS: "IN_PROGRESS",
  SUBMITTED: "SUBMITTED",
  REVISION_REQUESTED: "REVISION_REQUESTED",
  APPROVED: "APPROVED",
});

/**
 * The platform's revision policy for every order in this phase: in-scope revisions are finite, and the limit is
 * snapshotted onto the order so both parties read the same number forever. There is no per-order override —
 * neither side can unilaterally weaken or inflate the policy after creation.
 */
export const ORDER_POLICY = Object.freeze({ revisionLimit: 2, milestoneMaximum: 12 });

export const ORDER_FIELD_LIMITS = Object.freeze({
  jobTitle: { minimum: 3, maximum: 120 },
  scope: { minimum: 5, maximum: 2000 },
  milestoneTitle: { minimum: 3, maximum: 120 },
  milestoneDescription: { minimum: 5, maximum: 2000 },
  acceptanceCriteria: { minimum: 3, maximum: 1000 },
  note: { minimum: 1, maximum: 2000 },
  compatibilityNote: { maximum: 500 },
  reason: { minimum: 5, maximum: 1000 },
  evidence: { maximum: 4, referenceMaximum: 300 },
  list: { defaultLimit: 20, maximumLimit: 50, maximumOffset: 100_000 },
  history: { defaultLimit: 50, maximumLimit: 100, maximumOffset: 100_000 },
});

function nowIso(nowMillis = Date.now()) {
  return new Date(nowMillis).toISOString();
}

/* ---------------------------------------------------------------------------- validation (one place) */

function validateEvidenceReferences(values) {
  if (values === undefined) return [];
  if (!Array.isArray(values)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  // Each reference is normalized through the shared safe-reference validator: https-only, host-bearing,
  // credential-free — `javascript:`, `data:`, `http:`, and malformed URLs all throw INVALID_REQUEST here.
  // Dedupe happens after normalization, and the bounded count applies to the deduped set.
  const normalized = values.map((value) =>
    validateSafeReference(value, { maximum: ORDER_FIELD_LIMITS.evidence.referenceMaximum }),
  ).filter((value) => value !== null);
  const unique = [...new Set(normalized)];
  if (unique.length > ORDER_FIELD_LIMITS.evidence.maximum) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  return unique;
}

/**
 * Validates the milestone plan for a new order. Positions are derived from array order — a client-sent `position`
 * or `status` key is an unknown field and is refused before this function runs (strict body) and again here.
 * Amounts exist only when the snapshotted proposal budget does; their sum can never exceed the agreed maximum.
 */
export function validateOrderMilestones(milestones, { agreedBudgetMax }) {
  if (!Array.isArray(milestones) || milestones.length === 0 || milestones.length > ORDER_POLICY.milestoneMaximum) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  let amountTotal = 0;
  let anyAmount = false;
  const allowedKeys = new Set(["title", "description", "acceptanceCriteria", "amount"]);
  const normalized = milestones.map((milestone, index) => {
    if (milestone === null || typeof milestone !== "object" || Array.isArray(milestone)) {
      throw new AccountApiError(ErrorCode.INVALID_REQUEST);
    }
    for (const key of Object.keys(milestone)) {
      if (!allowedKeys.has(key)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
    }
    const title = validateBoundedText(milestone.title, { ...ORDER_FIELD_LIMITS.milestoneTitle });
    const description = validateBoundedText(milestone.description, { ...ORDER_FIELD_LIMITS.milestoneDescription, allowNewlines: true });
    const acceptanceCriteria = validateBoundedText(milestone.acceptanceCriteria, { ...ORDER_FIELD_LIMITS.acceptanceCriteria, allowNewlines: true });
    let amount = null;
    if (milestone.amount !== undefined && milestone.amount !== null) {
      if (agreedBudgetMax === null) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
      if (!Number.isSafeInteger(milestone.amount) || milestone.amount < 0 || milestone.amount > agreedBudgetMax) {
        throw new AccountApiError(ErrorCode.INVALID_REQUEST);
      }
      amount = milestone.amount;
      anyAmount = true;
      amountTotal += amount;
    }
    return { position: index + 1, title, description, acceptanceCriteria, amount };
  });
  if (anyAmount && agreedBudgetMax !== null && amountTotal > agreedBudgetMax) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  return normalized;
}

/* ----------------------------------------------------------------------------- identity and ownership */

function orderIdRow(orderId) {
  if (typeof orderId !== "string" || !/^ord_[0-9a-fA-F-]{36}$/.test(orderId)) {
    throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
  }
  return orderId;
}

function milestoneIdShape(milestoneId) {
  if (typeof milestoneId !== "string" || !/^mil_[0-9a-fA-F-]{36}$/.test(milestoneId)) {
    throw new AccountApiError(ErrorCode.MILESTONE_NOT_FOUND);
  }
  return milestoneId;
}

function orderRowById(database, orderId) {
  orderIdRow(orderId);
  return database.prepare("SELECT * FROM orders WHERE order_id = ?").get(orderId) ?? null;
}

/**
 * Loads an order for a participant. Anyone else receives the same uniform not-found as a missing row — an
 * attacker learns nothing from the difference — while the attempt is audited as a denial against the acting
 * account, inside this transaction (the capture frames flush after any rollback).
 */
function participantOrderRow(database, orderId, userId) {
  const row = orderRowById(database, orderId);
  if (!row) throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
  if (row.buyer_id !== userId && row.creator_user_id !== userId) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "ORDER_ACCESS_DENIED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { resourceKind: RESOURCE_KIND.ORDER, reason: "not_participant", surface: "order" },
      occurredAt: nowIso(),
    });
    throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
  }
  return row;
}

function participantRole(order, userId) {
  if (order.buyer_id === userId) return "BUYER";
  if (order.creator_user_id === userId) return "CREATOR";
  throw new AccountApiError(ErrorCode.ORDER_NOT_FOUND);
}

function requireActiveOrder(order) {
  if (order.status !== ORDER_STATUS.ACTIVE) {
    throw new AccountApiError(ErrorCode.ORDER_STATE_CONFLICT, "This order is closed, so its milestones no longer change.");
  }
}

function milestoneRow(database, milestoneId, orderId) {
  milestoneIdShape(milestoneId);
  const row = database.prepare("SELECT * FROM order_milestones WHERE milestone_id = ?").get(milestoneId) ?? null;
  if (!row || row.order_id !== orderId) throw new AccountApiError(ErrorCode.MILESTONE_NOT_FOUND);
  return row;
}

function jobRowForOrder(database, jobId) {
  if (typeof jobId !== "string" || !/^job_[0-9a-fA-F-]{36}$/.test(jobId)) {
    throw new AccountApiError(ErrorCode.JOB_NOT_FOUND);
  }
  return database.prepare("SELECT * FROM buyer_jobs WHERE job_id = ?").get(jobId) ?? null;
}

function proposalRowForOrder(database, proposalId) {
  if (typeof proposalId !== "string" || !/^prp_[0-9a-fA-F-]{36}$/.test(proposalId)) {
    throw new AccountApiError(ErrorCode.PROPOSAL_NOT_FOUND);
  }
  return database.prepare("SELECT * FROM job_proposals WHERE proposal_id = ?").get(proposalId) ?? null;
}

/* ------------------------------------------------------------------------------------ views */

function parseJsonList(raw) {
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) throw new Error("not a list");
    return parsed;
  } catch {
    throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  }
}

function budgetView(minimum, maximum, currency) {
  if (minimum === null || maximum === null || currency === null) return null;
  return Object.freeze({ min: minimum, max: maximum, currency });
}

/** The agreed-terms view both parties read: identical fields for buyer and creator, never a hidden asymmetry. */
export function orderView(row, counts, creator) {
  return Object.freeze({
    id: row.order_id,
    jobId: row.job_id,
    proposalId: row.proposal_id,
    jobTitle: row.job_title,
    scope: row.scope,
    edition: row.edition,
    minecraftVersion: row.minecraft_version,
    loaders: Object.freeze(parseJsonList(row.loaders)),
    budget: budgetView(row.agreed_budget_min, row.agreed_budget_max, row.agreed_currency),
    deadline: row.agreed_deadline,
    deliveryEstimateDays: row.agreed_delivery_estimate_days,
    revisionLimit: row.revision_limit,
    status: row.status,
    milestoneCounts: counts,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    completedAt: row.completed_at,
    cancelledAt: row.cancelled_at,
    ...(creator ? { creator } : {}),
  });
}

export function milestoneView(row, latestVersion) {
  return Object.freeze({
    id: row.milestone_id,
    position: row.position,
    title: row.title,
    description: row.description,
    acceptanceCriteria: row.acceptance_criteria,
    amount: row.amount,
    status: row.status,
    latestDeliveryVersion: latestVersion ?? 0,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    startedAt: row.started_at,
    submittedAt: row.submitted_at,
    approvedAt: row.approved_at,
  });
}

/** Delivery content view: note, references, and version only — never internal user ids. */
export function deliveryView(row) {
  return Object.freeze({
    id: row.delivery_id,
    milestoneId: row.milestone_id,
    version: row.version,
    note: row.note,
    evidenceReferences: Object.freeze(parseJsonList(row.evidence_references)),
    compatibilityNote: row.compatibility_note,
    submittedAt: row.submitted_at,
  });
}

export function revisionRequestView(row) {
  return Object.freeze({
    id: row.revision_id,
    milestoneId: row.milestone_id,
    kind: row.kind,
    reason: row.reason,
    createdAt: row.created_at,
  });
}

function milestoneCounts(database, orderId) {
  const rows = database.prepare("SELECT status, COUNT(*) AS count FROM order_milestones WHERE order_id = ? GROUP BY status").all(orderId);
  const counts = { total: 0, pending: 0, inProgress: 0, submitted: 0, revisionRequested: 0, approved: 0 };
  const keys = {
    PENDING: "pending",
    IN_PROGRESS: "inProgress",
    SUBMITTED: "submitted",
    REVISION_REQUESTED: "revisionRequested",
    APPROVED: "approved",
  };
  for (const row of rows) {
    counts.total += row.count;
    counts[keys[row.status]] = row.count;
  }
  return Object.freeze(counts);
}

function creatorSummary(database, userId) {
  const profile = database.prepare(
    "SELECT handle, display_name FROM creator_profiles WHERE user_id = ? ORDER BY created_at DESC LIMIT 1",
  ).get(userId);
  if (!profile) return Object.freeze({ handle: null, displayName: null });
  return Object.freeze({ handle: profile.handle, displayName: profile.display_name });
}

function auditOrder(database, actionType, userId, metadata, now) {
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType,
    targetUserId: userId,
    outcome: "SUCCESS",
    // Ids, statuses, counts, versions, and reason categories only — never delivery contents or links.
    metadata,
    occurredAt: nowIso(now),
  });
}

/* ------------------------------------------------------------------------------- order creation */

/**
 * Creates one order from the buyer's own AWARDED job and its SELECTED proposal. Everything authoritative is
 * derived here: ownership from the session, proposal state from the row, terms from the frozen job/proposal
 * values. Retries and concurrent requests hit the unique proposal index and leave with a typed conflict —
 * never a second order.
 */
export function createOrderInTransaction(database, configuration, userId, body, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const job = jobRowForOrder(database, body.jobId);
  if (!job) throw new AccountApiError(ErrorCode.JOB_NOT_FOUND);
  if (job.buyer_id !== userId) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "ORDER_ACCESS_DENIED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { resourceKind: RESOURCE_KIND.ORDER, reason: "not_job_owner", surface: "order" },
      occurredAt: nowIso(now),
    });
    throw new AccountApiError(ErrorCode.JOB_NOT_FOUND);
  }
  if (job.status !== JOB_STATUS.AWARDED) {
    throw new AccountApiError(ErrorCode.ORDER_STATE_CONFLICT, "An order can only be created from a job that has been awarded.");
  }
  const proposal = proposalRowForOrder(database, body.proposalId);
  if (!proposal || proposal.job_id !== job.job_id) throw new AccountApiError(ErrorCode.PROPOSAL_NOT_FOUND);
  if (proposal.status !== PROPOSAL_STATUS.SELECTED) {
    throw new AccountApiError(ErrorCode.ORDER_STATE_CONFLICT, "Only the selected proposal on an awarded job can become an order.");
  }
  // Request content is validated before any state conflict is judged, so a malformed payload always answers
  // INVALID_REQUEST — it never learns whether an order already exists from a validation-order side channel.
  const agreedBudgetMax = proposal.budget_max;
  const milestones = validateOrderMilestones(body.milestones, { agreedBudgetMax });
  const existing = database.prepare("SELECT order_id FROM orders WHERE proposal_id = ?").get(proposal.proposal_id);
  if (existing) {
    throw new AccountApiError(ErrorCode.ORDER_STATE_CONFLICT, "An order already exists for that proposal.");
  }
  const orderId = newOrderId();
  const timestamp = nowIso(now);
  try {
    database.prepare(
      `INSERT INTO orders
         (order_id, job_id, proposal_id, buyer_id, creator_user_id, job_title, scope, edition, minecraft_version,
          loaders, agreed_budget_min, agreed_budget_max, agreed_currency, agreed_deadline,
          agreed_delivery_estimate_days, revision_limit, status, created_at, updated_at, completed_at, cancelled_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, NULL, NULL)`,
    ).run(
      orderId, job.job_id, proposal.proposal_id, job.buyer_id, proposal.user_id,
      job.title, proposal.scope, job.edition, job.minecraft_version, job.loaders,
      proposal.budget_min, proposal.budget_max, proposal.budget_currency,
      job.deadline, proposal.delivery_estimate_days, ORDER_POLICY.revisionLimit,
      timestamp, timestamp,
    );
  } catch (error) {
    // The unique proposal index is the race backstop: a crossed duplicate loses here with the same typed
    // conflict the pre-check raises — never a raw constraint error to the client.
    if (/UNIQUE/i.test(String(error?.message ?? ""))) {
      throw new AccountApiError(ErrorCode.ORDER_STATE_CONFLICT, "An order already exists for that proposal.");
    }
    throw error;
  }
  const insertMilestone = database.prepare(
    `INSERT INTO order_milestones
       (milestone_id, order_id, position, title, description, acceptance_criteria, amount, status,
        created_at, updated_at, started_at, submitted_at, approved_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, NULL, NULL, NULL)`,
  );
  for (const milestone of milestones) {
    insertMilestone.run(
      newMilestoneId(), orderId, milestone.position, milestone.title, milestone.description,
      milestone.acceptanceCriteria, milestone.amount, timestamp, timestamp,
    );
  }
  auditOrder(database, "ORDER_CREATED", userId, {
    orderId, jobId: job.job_id, proposalId: proposal.proposal_id,
    milestoneCount: milestones.length, hasBudget: agreedBudgetMax !== null, revisionLimit: ORDER_POLICY.revisionLimit,
  }, now);
  const row = orderRowById(database, orderId);
  return orderView(row, milestoneCounts(database, orderId), creatorSummary(database, proposal.user_id));
}

/* ------------------------------------------------------------------------------------ reads */

function parseListQuery(searchParams, limits) {
  const integer = (name, { maximum, fallback = null }) => {
    const raw = searchParams.get(name);
    if (raw === null || raw === "") return fallback;
    if (!/^\d{1,9}$/.test(raw)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
    const value = Number(raw);
    if (!Number.isSafeInteger(value) || value > maximum) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
    return value;
  };
  const limit = integer("limit", { maximum: limits.maximumLimit, fallback: limits.defaultLimit });
  const offset = integer("offset", { maximum: limits.maximumOffset, fallback: 0 });
  if (limit !== null && limit < 1) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  return { limit: limit ?? limits.defaultLimit, offset: offset ?? 0 };
}

export function parseOrderListQuery(searchParams) {
  return parseListQuery(searchParams, ORDER_FIELD_LIMITS.list);
}

export function parseOrderHistoryQuery(searchParams) {
  return parseListQuery(searchParams, ORDER_FIELD_LIMITS.history);
}

function listOrdersFor(database, column, userId, filters, now) {
  const total = database.prepare(`SELECT COUNT(*) AS count FROM orders WHERE ${column} = ?`).get(userId).count;
  const rows = database.prepare(
    `SELECT * FROM orders WHERE ${column} = ? ORDER BY updated_at DESC, rowid DESC LIMIT ? OFFSET ?`,
  ).all(userId, filters.limit, filters.offset);
  const items = rows.map((row) => orderView(row, milestoneCounts(database, row.order_id), creatorSummary(database, row.creator_user_id)));
  return Object.freeze({
    items: Object.freeze(items),
    total,
    limit: filters.limit,
    offset: filters.offset,
    hasMore: filters.offset + items.length < total,
    readAt: nowIso(now),
  });
}

export function listBuyerOrdersInTransaction(database, configuration, userId, filters, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  return listOrdersFor(database, "buyer_id", userId, filters, now);
}

export function listCreatorOrdersInTransaction(database, configuration, userId, filters, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  return listOrdersFor(database, "creator_user_id", userId, filters, now);
}

/** The authorized detail: agreed terms, every milestone with its latest delivery version, and both identities. */
export function orderDetailInTransaction(database, configuration, userId, orderId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const order = participantOrderRow(database, orderId, userId);
  const milestones = database.prepare(
    "SELECT * FROM order_milestones WHERE order_id = ? ORDER BY position, milestone_id",
  ).all(order.order_id);
  const latestVersions = new Map(
    database.prepare(
      "SELECT milestone_id, MAX(version) AS version FROM milestone_deliveries WHERE order_id = ? GROUP BY milestone_id",
    ).all(order.order_id).map((row) => [row.milestone_id, row.version]),
  );
  return Object.freeze({
    order: orderView(order, milestoneCounts(database, order.order_id), creatorSummary(database, order.creator_user_id)),
    role: participantRole(order, userId),
    milestones: Object.freeze(milestones.map((row) => milestoneView(row, latestVersions.get(row.milestone_id) ?? 0))),
    readAt: nowIso(now),
  });
}

/**
 * The authorized history: every delivery version and every revision or scope-change request for the order,
 * bounded and paginated over deliveries (revisions are naturally capped by the finite policy plus the bounded
 * scope-change rate). Both parties read the same facts.
 */
export function orderHistoryInTransaction(database, configuration, userId, orderId, filters, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const order = participantOrderRow(database, orderId, userId);
  const totalDeliveries = database.prepare("SELECT COUNT(*) AS count FROM milestone_deliveries WHERE order_id = ?").get(order.order_id).count;
  const deliveries = database.prepare(
    `SELECT * FROM milestone_deliveries WHERE order_id = ?
      ORDER BY submitted_at DESC, rowid DESC LIMIT ? OFFSET ?`,
  ).all(order.order_id, filters.limit, filters.offset);
  const revisions = database.prepare(
    "SELECT * FROM milestone_revision_requests WHERE order_id = ? ORDER BY created_at DESC, rowid DESC LIMIT 100",
  ).all(order.order_id);
  return Object.freeze({
    deliveries: Object.freeze(deliveries.map(deliveryView)),
    revisions: Object.freeze(revisions.map(revisionRequestView)),
    totalDeliveries,
    limit: filters.limit,
    offset: filters.offset,
    hasMore: filters.offset + deliveries.length < totalDeliveries,
    readAt: nowIso(now),
  });
}

/* -------------------------------------------------------------------------- milestone transitions */

export function startMilestoneInTransaction(database, configuration, userId, orderId, milestoneId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const order = participantOrderRow(database, orderId, userId);
  if (participantRole(order, userId) !== "CREATOR") {
    throw new AccountApiError(ErrorCode.ORDER_ROLE_DENIED, "Only the creator can start work on a milestone.");
  }
  requireActiveOrder(order);
  const milestone = milestoneRow(database, milestoneId, order.order_id);
  if (milestone.status !== MILESTONE_STATUS.PENDING && milestone.status !== MILESTONE_STATUS.REVISION_REQUESTED) {
    throw new AccountApiError(ErrorCode.MILESTONE_STATE_CONFLICT, "Only a pending or revision-requested milestone can be started.");
  }
  const timestamp = nowIso(now);
  database.prepare(
    `UPDATE order_milestones
        SET status = 'IN_PROGRESS', started_at = COALESCE(started_at, ?), updated_at = ?
      WHERE milestone_id = ? AND status IN ('PENDING', 'REVISION_REQUESTED')`,
  ).run(timestamp, timestamp, milestone.milestone_id);
  database.prepare("UPDATE orders SET updated_at = ? WHERE order_id = ?").run(timestamp, order.order_id);
  auditOrder(database, "MILESTONE_STARTED", userId, { orderId: order.order_id, milestoneId: milestone.milestone_id }, now);
  return milestoneView(milestoneRow(database, milestone.milestone_id, order.order_id));
}

/**
 * Submits a delivery (first version or a revision). The creator is the only submitter; the milestone must be
 * IN_PROGRESS or REVISION_REQUESTED (start work first); every submission appends an immutable version, so an
 * earlier delivery is never erased.
 */
export function submitDeliveryInTransaction(database, configuration, userId, orderId, milestoneId, body, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const order = participantOrderRow(database, orderId, userId);
  if (participantRole(order, userId) !== "CREATOR") {
    throw new AccountApiError(ErrorCode.ORDER_ROLE_DENIED, "Only the creator can submit a delivery for a milestone.");
  }
  requireActiveOrder(order);
  const milestone = milestoneRow(database, milestoneId, order.order_id);
  if (milestone.status !== MILESTONE_STATUS.IN_PROGRESS && milestone.status !== MILESTONE_STATUS.REVISION_REQUESTED) {
    throw new AccountApiError(
      ErrorCode.MILESTONE_STATE_CONFLICT,
      "Start work on this milestone before submitting a delivery.",
    );
  }
  const note = validateBoundedText(body.note, { ...ORDER_FIELD_LIMITS.note, allowNewlines: true });
  const evidenceReferences = validateEvidenceReferences(body.evidenceReferences);
  const compatibilityNote = body.compatibilityNote === undefined || body.compatibilityNote === null
    ? ""
    : validateBoundedText(body.compatibilityNote, { ...ORDER_FIELD_LIMITS.compatibilityNote, allowNewlines: true, allowEmpty: true });
  const version = database.prepare(
    "SELECT COALESCE(MAX(version), 0) AS version FROM milestone_deliveries WHERE milestone_id = ?",
  ).get(milestone.milestone_id).version + 1;
  const timestamp = nowIso(now);
  database.prepare(
    `INSERT INTO milestone_deliveries
       (delivery_id, milestone_id, order_id, version, note, evidence_references, compatibility_note, submitted_by, submitted_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    newDeliveryId(), milestone.milestone_id, order.order_id, version, note,
    JSON.stringify(evidenceReferences), compatibilityNote, userId, timestamp,
  );
  database.prepare(
    "UPDATE order_milestones SET status = 'SUBMITTED', submitted_at = ?, updated_at = ? WHERE milestone_id = ?",
  ).run(timestamp, timestamp, milestone.milestone_id);
  database.prepare("UPDATE orders SET updated_at = ? WHERE order_id = ?").run(timestamp, order.order_id);
  auditOrder(
    database,
    version === 1 ? "DELIVERY_SUBMITTED" : "DELIVERY_REVISED",
    userId,
    { orderId: order.order_id, milestoneId: milestone.milestone_id, version },
    now,
  );
  return deliveryView(database.prepare("SELECT * FROM milestone_deliveries WHERE milestone_id = ? AND version = ?").get(milestone.milestone_id, version));
}

/**
 * A buyer revision request on a SUBMITTED milestone. In-scope requests consume one of the order's snapshotted
 * revision limit and move the milestone to REVISION_REQUESTED. A request flagged as outside the original scope
 * is recorded as a SCOPE_CHANGE row that does not consume the limit and changes no agreed term — there is no
 * payment mechanism in this phase that could settle extra work, and none is implied.
 */
export function requestRevisionInTransaction(database, configuration, userId, orderId, milestoneId, body, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const order = participantOrderRow(database, orderId, userId);
  if (participantRole(order, userId) !== "BUYER") {
    throw new AccountApiError(ErrorCode.ORDER_ROLE_DENIED, "Only the buyer can request a revision or a scope change.");
  }
  requireActiveOrder(order);
  const milestone = milestoneRow(database, milestoneId, order.order_id);
  if (milestone.status !== MILESTONE_STATUS.SUBMITTED) {
    throw new AccountApiError(ErrorCode.MILESTONE_STATE_CONFLICT, "Only a submitted milestone can be sent back for revision.");
  }
  const reason = validateBoundedText(body.reason, { ...ORDER_FIELD_LIMITS.reason, allowNewlines: true });
  if (body.outsideScope !== undefined && typeof body.outsideScope !== "boolean") {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  const outsideScope = body.outsideScope === true;
  const timestamp = nowIso(now);
  if (outsideScope) {
    database.prepare(
      `INSERT INTO milestone_revision_requests (revision_id, milestone_id, order_id, kind, reason, requested_by, created_at)
       VALUES (?, ?, ?, 'SCOPE_CHANGE', ?, ?, ?)`,
    ).run(newRevisionId(), milestone.milestone_id, order.order_id, reason, userId, timestamp);
    database.prepare("UPDATE orders SET updated_at = ? WHERE order_id = ?").run(timestamp, order.order_id);
    auditOrder(database, "SCOPE_CHANGE_REQUESTED", userId, { orderId: order.order_id, milestoneId: milestone.milestone_id }, now);
    return Object.freeze({
      milestone: milestoneView(milestoneRow(database, milestone.milestone_id, order.order_id), undefined),
      scopeChange: true,
      revisionsUsed: revisionCount(database, milestone.milestone_id),
      revisionLimit: order.revision_limit,
      note: "The scope change was recorded for the creator. It does not change the agreed terms and no payment mechanism exists for additional work in this phase.",
    });
  }
  const revisionsUsed = revisionCount(database, milestone.milestone_id);
  if (revisionsUsed >= order.revision_limit) {
    throw new AccountApiError(ErrorCode.REVISION_LIMIT_REACHED);
  }
  database.prepare(
    `INSERT INTO milestone_revision_requests (revision_id, milestone_id, order_id, kind, reason, requested_by, created_at)
     VALUES (?, ?, ?, 'REVISION', ?, ?, ?)`,
  ).run(newRevisionId(), milestone.milestone_id, order.order_id, reason, userId, timestamp);
  database.prepare(
    "UPDATE order_milestones SET status = 'REVISION_REQUESTED', updated_at = ? WHERE milestone_id = ? AND status = 'SUBMITTED'",
  ).run(timestamp, milestone.milestone_id);
  database.prepare("UPDATE orders SET updated_at = ? WHERE order_id = ?").run(timestamp, order.order_id);
  auditOrder(database, "REVISION_REQUESTED", userId, {
    orderId: order.order_id, milestoneId: milestone.milestone_id, revisionNumber: revisionsUsed + 1, revisionLimit: order.revision_limit,
  }, now);
  return Object.freeze({
    milestone: milestoneView(milestoneRow(database, milestone.milestone_id, order.order_id), undefined),
    scopeChange: false,
    revisionsUsed: revisionsUsed + 1,
    revisionLimit: order.revision_limit,
    note: "The milestone returned to the creator for one of the agreed revision rounds.",
  });
}

function revisionCount(database, milestoneId) {
  return database.prepare(
    "SELECT COUNT(*) AS count FROM milestone_revision_requests WHERE milestone_id = ? AND kind = 'REVISION'",
  ).get(milestoneId).count;
}

/** Buyer-only approval of a SUBMITTED milestone. Creators can never approve their own delivery. */
export function approveMilestoneInTransaction(database, configuration, userId, orderId, milestoneId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const order = participantOrderRow(database, orderId, userId);
  if (participantRole(order, userId) !== "BUYER") {
    throw new AccountApiError(ErrorCode.ORDER_ROLE_DENIED, "Only the buyer can approve a milestone.");
  }
  requireActiveOrder(order);
  const milestone = milestoneRow(database, milestoneId, order.order_id);
  if (milestone.status !== MILESTONE_STATUS.SUBMITTED) {
    throw new AccountApiError(ErrorCode.MILESTONE_STATE_CONFLICT, "Only a submitted milestone with a delivery can be approved.");
  }
  const timestamp = nowIso(now);
  const changed = database.prepare(
    "UPDATE order_milestones SET status = 'APPROVED', approved_at = ?, updated_at = ? WHERE milestone_id = ? AND status = 'SUBMITTED'",
  ).run(timestamp, timestamp, milestone.milestone_id).changes;
  if (changed !== 1) throw new AccountApiError(ErrorCode.MILESTONE_STATE_CONFLICT);
  database.prepare("UPDATE orders SET updated_at = ? WHERE order_id = ?").run(timestamp, order.order_id);
  auditOrder(database, "MILESTONE_APPROVED", userId, {
    orderId: order.order_id, milestoneId: milestone.milestone_id,
    approvedCount: milestoneCounts(database, order.order_id).approved, milestoneTotal: milestoneCounts(database, order.order_id).total,
  }, now);
  return milestoneView(milestoneRow(database, milestone.milestone_id, order.order_id));
}

/** Completion by either participant once — and only once — every milestone is approved. */
export function completeOrderInTransaction(database, configuration, userId, orderId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const order = participantOrderRow(database, orderId, userId);
  if (order.status !== ORDER_STATUS.ACTIVE) throw new AccountApiError(ErrorCode.ORDER_STATE_CONFLICT);
  const counts = milestoneCounts(database, order.order_id);
  if (counts.approved !== counts.total || counts.total === 0) {
    throw new AccountApiError(
      ErrorCode.ORDER_COMPLETION_BLOCKED,
      `${counts.approved} of ${counts.total} milestones are approved. Every milestone must be approved before the order can complete.`,
    );
  }
  const timestamp = nowIso(now);
  const changed = database.prepare(
    "UPDATE orders SET status = 'COMPLETED', completed_at = ?, updated_at = ? WHERE order_id = ? AND status = 'ACTIVE'",
  ).run(timestamp, timestamp, order.order_id).changes;
  if (changed !== 1) throw new AccountApiError(ErrorCode.ORDER_STATE_CONFLICT);
  auditOrder(database, "ORDER_COMPLETED", userId, { orderId: order.order_id, milestoneCount: counts.total }, now);
  const row = orderRowById(database, order.order_id);
  return orderView(row, milestoneCounts(database, order.order_id), creatorSummary(database, row.creator_user_id));
}

/**
 * Cancellation policy (documented in the UI as well): either participant may cancel only while **no milestone
 * has been approved** — once any milestone is approved the order is sealed against unilateral cancellation, so
 * neither side can strand accepted work. Cancelling never deletes the order, its milestones, deliveries, or
 * revisions; it freezes the workflow. Mid-order termination with approved milestones is deliberately left to a
 * future dispute/payment phase rather than guessed at here.
 */
export function cancelOrderInTransaction(database, configuration, userId, orderId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const order = participantOrderRow(database, orderId, userId);
  if (order.status !== ORDER_STATUS.ACTIVE) throw new AccountApiError(ErrorCode.ORDER_STATE_CONFLICT);
  const counts = milestoneCounts(database, order.order_id);
  if (counts.approved > 0) {
    throw new AccountApiError(
      ErrorCode.ORDER_STATE_CONFLICT,
      "This order has approved milestones, so it can no longer be cancelled. Closing an order with approved work belongs to a future dispute phase.",
    );
  }
  const timestamp = nowIso(now);
  const changed = database.prepare(
    "UPDATE orders SET status = 'CANCELLED', cancelled_at = ?, updated_at = ? WHERE order_id = ? AND status = 'ACTIVE'",
  ).run(timestamp, timestamp, order.order_id).changes;
  if (changed !== 1) throw new AccountApiError(ErrorCode.ORDER_STATE_CONFLICT);
  auditOrder(database, "ORDER_CANCELLED", userId, { orderId: order.order_id, approvedMilestones: 0 }, now);
  const row = orderRowById(database, order.order_id);
  return orderView(row, milestoneCounts(database, order.order_id), creatorSummary(database, row.creator_user_id));
}
