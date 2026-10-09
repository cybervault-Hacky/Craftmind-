/**
 * Hire a Builder — buyer job requests and creator proposals (Phase 26).
 *
 * The hiring foundation is deliberately built from the same boundaries the rest of the service uses:
 *
 *   * **Identity is the session.** A job belongs to the account that posted it and a proposal to the account that
 *     submitted it — never to a client-supplied owner, status, or award field (the strict body reader refuses
 *     unknown keys, and this module refuses server-controlled state again at the domain boundary).
 *   * **Lifecycle is small and explicit.** Jobs are OPEN → AWARDED or OPEN → CANCELLED; there is no payment,
 *     milestone, delivery, or escrow claim anywhere — an award records which builder the buyer selected, nothing
 *     more. Proposals are SUBMITTED → WITHDRAWN (by their creator), SELECTED (exactly one, on award), or
 *     NOT_SELECTED (everyone else, when the job closes).
 *   * **The database is the last line of defence.** A partial unique index makes two live proposals from the same
 *     creator on the same job impossible even under a race, and every lifecycle write is a conditional UPDATE
 *     inside the single-writer transaction the service already serializes through — a double award cannot happen.
 *   * **Visibility follows ownership.** Public reads serve OPEN jobs only and never a proposal's content; the
 *     buyer who owns a job reviews its proposals; a creator reads and manages only their own. Cross-account
 *     attempts get the same uniform not-found as a missing row, with a denial audit that outlives a rollback.
 *   * **Eligibility is reused, never reinvented.** Submitting a proposal runs the exact publish prerequisites the
 *     listing pipeline enforces (active account, Creator entitlement, active profile, recorded agreement) — this
 *     phase adds no new paid gate and bypasses none. Posting a job needs a verified, active account: jobs are
 *     buyer-side and require no creator entitlement.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";
import { requireAccount } from "./entitlements.js";
import { newJobId, newProposalId } from "./ids.js";
import { publishPrerequisitesInTransaction } from "./capabilities.js";
import { RESOURCE_KIND } from "./ownership.js";
import { validateBoundedText, validateSafeReference } from "./text-fields.js";
// Phase 30 creator protection: a block placed by either party stops a new proposal from connecting a creator and a
// buyer. Read-only predicate, no cycle back into hire-jobs from the trust module; behavior is unchanged when absent.
import { isBlockedBetween } from "./marketplace-trust.js";
import {
  COMPATIBILITY_LIMITS,
  isKnownEdition,
  isKnownLoader,
  isWellFormedMinecraftVersion,
  loaderBelongsToEditions,
} from "./compatibility.js";

export const JOB_STATUS = Object.freeze({ OPEN: "OPEN", AWARDED: "AWARDED", CANCELLED: "CANCELLED" });

export const PROPOSAL_STATUS = Object.freeze({
  SUBMITTED: "SUBMITTED",
  WITHDRAWN: "WITHDRAWN",
  SELECTED: "SELECTED",
  NOT_SELECTED: "NOT_SELECTED",
});

/** Budgets are recorded in one of four currencies and never converted — no exchange-rate mechanism exists here. */
export const HIRE_CURRENCIES = Object.freeze(["INR", "USD", "EUR", "GBP"]);

export const HIRE_FIELD_LIMITS = Object.freeze({
  title: { minimum: 3, maximum: 120 },
  description: { minimum: 10, maximum: 5000 },
  scope: { minimum: 5, maximum: 1000 },
  proposalScope: { minimum: 5, maximum: 2000 },
  message: { minimum: 10, maximum: 2000 },
  images: { maximum: 4, referenceMaximum: 300 },
  budget: { minimum: 0, maximum: 100_000_000 },
  deliveryEstimateDays: { minimum: 1, maximum: 365 },
  searchQuery: { maximum: 200 },
  page: { defaultLimit: 12, maximumLimit: 48, maximumOffset: 100_000 },
});

function nowIso(nowMillis = Date.now()) {
  return new Date(nowMillis).toISOString();
}

/* ------------------------------------------------------------------------------ validation (one place) */

function validateClaimList(values, { maximum, itemMaximum, accept }) {
  if (values === undefined) return [];
  if (!Array.isArray(values)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const unique = [...new Set(values)];
  if (unique.length > maximum) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  if (unique.some((value) => typeof value !== "string" || value.length === 0 || value.length > itemMaximum || !accept(value))) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  return unique;
}

/** The budget triple: all three parts or none. Ranges are validated, ordered, and bounded — never repaired. */
function validateBudget({ budgetMin, budgetMax, budgetCurrency }) {
  const present = [budgetMin, budgetMax, budgetCurrency].filter((value) => value !== undefined && value !== null);
  if (present.length === 0) return { budgetMin: null, budgetMax: null, budgetCurrency: null };
  if (budgetMin === undefined || budgetMax === undefined || budgetCurrency === undefined) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  if (budgetMin === null || budgetMax === null || budgetCurrency === null) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  if (!Number.isSafeInteger(budgetMin) || !Number.isSafeInteger(budgetMax)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  if (budgetMin < HIRE_FIELD_LIMITS.budget.minimum || budgetMax > HIRE_FIELD_LIMITS.budget.maximum) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  if (budgetMin > budgetMax) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  if (typeof budgetCurrency !== "string" || !HIRE_CURRENCIES.includes(budgetCurrency)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  return { budgetMin, budgetMax, budgetCurrency };
}

/** An optional `YYYY-MM-DD` deadline: a real calendar date that is not in the past. No timezone machinery beyond UTC. */
function validateDeadline(deadline, now) {
  if (deadline === undefined || deadline === null || deadline === "") return null;
  if (typeof deadline !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(deadline)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const parsed = new Date(`${deadline}T00:00:00.000Z`);
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== deadline) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  const today = nowIso(now).slice(0, 10);
  if (deadline < today) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  return deadline;
}

function validateDeliveryEstimate(value) {
  if (value === undefined || value === null) return null;
  if (!Number.isSafeInteger(value) || value < HIRE_FIELD_LIMITS.deliveryEstimateDays.minimum || value > HIRE_FIELD_LIMITS.deliveryEstimateDays.maximum) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  return value;
}

/** Normalizes a create/update job body into storable fields. Throws `INVALID_REQUEST` — never repairs silently. */
export function validateJobContent(body, { now = Date.now() } = {}) {
  const { title, description, edition, minecraftVersion, scope } = body;
  const normalizedTitle = validateBoundedText(title, { ...HIRE_FIELD_LIMITS.title });
  const normalizedDescription = validateBoundedText(description, { ...HIRE_FIELD_LIMITS.description, allowNewlines: true });
  const normalizedScope = validateBoundedText(scope, { ...HIRE_FIELD_LIMITS.scope, allowNewlines: true });
  if (typeof edition !== "string" || !isKnownEdition(edition)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  if (typeof minecraftVersion !== "string" || !isWellFormedMinecraftVersion(minecraftVersion)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  const loaders = validateClaimList(body.loaders, {
    maximum: COMPATIBILITY_LIMITS.maximumLoaders,
    itemMaximum: 32,
    accept: (loader) => isKnownLoader(loader) && loaderBelongsToEditions(loader, [edition]),
  });
  const imageReferences = validateClaimList(body.imageReferences, {
    maximum: HIRE_FIELD_LIMITS.images.maximum,
    itemMaximum: HIRE_FIELD_LIMITS.images.referenceMaximum,
    accept: (reference) => validateSafeReference(reference, { maximum: HIRE_FIELD_LIMITS.images.referenceMaximum }) === reference,
  });
  return {
    title: normalizedTitle,
    description: normalizedDescription,
    edition,
    minecraftVersion,
    loaders,
    imageReferences,
    ...validateBudget(body),
    deadline: validateDeadline(body.deadline, now),
    scope: normalizedScope,
  };
}

/** Normalizes a proposal body. `message` and `scope` are the creator's own words; budget and estimate are optional. */
export function validateProposalContent(body) {
  const message = validateBoundedText(body.message, { ...HIRE_FIELD_LIMITS.message, allowNewlines: true });
  const scope = validateBoundedText(body.scope, { ...HIRE_FIELD_LIMITS.proposalScope, allowNewlines: true });
  return {
    message,
    scope,
    ...validateBudget(body),
    deliveryEstimateDays: validateDeliveryEstimate(body.deliveryEstimateDays),
  };
}

/* ----------------------------------------------------------------------------- identity and prerequisites */

/**
 * The buyer identity behind every job write: the account from the session must exist and be active. Email
 * verification is already enforced by the session layer (`loadLiveSession` refuses unverified accounts), so no
 * job or proposal route can be reached without it.
 */
export function requireBuyerAccount(database, userId) {
  return requireAccount(database, userId);
}

/**
 * The proposal identity: the exact publish prerequisites from the listing pipeline — active account, Creator
 * entitlement, active profile, recorded agreement — never a looser gate. Suspended-profile denials are audited
 * before the refusal, exactly as Phase 23/25 do.
 */
export function requireProposalCreatorInTransaction(database, configuration, userId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const prerequisites = publishPrerequisitesInTransaction(database, configuration, userId, { now });
  if (!prerequisites.entitled) throw new AccountApiError(ErrorCode.CREATOR_ENTITLEMENT_REQUIRED);
  if (!prerequisites.profileExists) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
  if (!prerequisites.operational) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "CREATOR_ACCESS_DENIED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { resourceKind: RESOURCE_KIND.JOB_PROPOSAL, profileStatus: prerequisites.profile.status, surface: "proposal" },
      occurredAt: nowIso(now),
    });
    throw new AccountApiError(ErrorCode.CREATOR_PROFILE_SUSPENDED);
  }
  return prerequisites;
}

function jobRowById(database, jobId) {
  if (typeof jobId !== "string" || !/^job_[0-9a-fA-F-]{36}$/.test(jobId)) throw new AccountApiError(ErrorCode.JOB_NOT_FOUND);
  return database.prepare("SELECT * FROM buyer_jobs WHERE job_id = ?").get(jobId) ?? null;
}

/**
 * Loads a job for an actor who must OWN it. Another buyer's job is reported with the same uniform not-found as a
 * missing one — an attacker learns nothing from the difference — while the attempt itself is audited as a denial
 * against the acting account, inside this transaction (the capture frames flush after any rollback).
 */
function ownedJobRow(database, jobId, userId) {
  const row = jobRowById(database, jobId);
  if (!row) throw new AccountApiError(ErrorCode.JOB_NOT_FOUND);
  if (row.buyer_id !== userId) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "JOB_ACCESS_DENIED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { resourceKind: RESOURCE_KIND.BUYER_JOB, reason: "not_owner", surface: "job" },
      occurredAt: nowIso(),
    });
    throw new AccountApiError(ErrorCode.JOB_NOT_FOUND);
  }
  return row;
}

function proposalRowById(database, proposalId) {
  if (typeof proposalId !== "string" || !/^prp_[0-9a-fA-F-]{36}$/.test(proposalId)) {
    throw new AccountApiError(ErrorCode.PROPOSAL_NOT_FOUND);
  }
  return database.prepare("SELECT * FROM job_proposals WHERE proposal_id = ?").get(proposalId) ?? null;
}

function ownedProposalRow(database, proposalId, userId) {
  const row = proposalRowById(database, proposalId);
  if (!row) throw new AccountApiError(ErrorCode.PROPOSAL_NOT_FOUND);
  if (row.user_id !== userId) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "PROPOSAL_ACCESS_DENIED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { resourceKind: RESOURCE_KIND.JOB_PROPOSAL, reason: "not_owner", surface: "proposal" },
      occurredAt: nowIso(),
    });
    throw new AccountApiError(ErrorCode.PROPOSAL_NOT_FOUND);
  }
  return row;
}

/* ------------------------------------------------------------------------------------ views */

function parseJsonList(raw) {
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) throw new Error("not a list");
    return parsed;
  } catch {
    // Only this module writes these columns; anything else reaching the parser is tampering — fail closed.
    throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  }
}

function budgetView(row) {
  if (row.budget_min === null || row.budget_max === null || row.budget_currency === null) return null;
  return Object.freeze({ min: row.budget_min, max: row.budget_max, currency: row.budget_currency });
}

function proposalCounts(rows) {
  return Object.freeze({
    total: rows.length,
    submitted: rows.filter((row) => row.status === PROPOSAL_STATUS.SUBMITTED).length,
    selected: rows.filter((row) => row.status === PROPOSAL_STATUS.SELECTED).length,
    withdrawn: rows.filter((row) => row.status === PROPOSAL_STATUS.WITHDRAWN).length,
    notSelected: rows.filter((row) => row.status === PROPOSAL_STATUS.NOT_SELECTED).length,
  });
}

function proposalRowsForJob(database, jobId) {
  return database.prepare(
    "SELECT * FROM job_proposals WHERE job_id = ? ORDER BY created_at, proposal_id",
  ).all(jobId);
}

function proposalCountForJob(database, jobId) {
  return database.prepare("SELECT COUNT(*) AS count FROM job_proposals WHERE job_id = ?").get(jobId).count;
}

/** The buyer's own view of a job: full content plus lifecycle state. Never includes the buyer's account id. */
export function ownerJobView(row, proposalCount) {
  return Object.freeze({
    id: row.job_id,
    title: row.title,
    description: row.description,
    edition: row.edition,
    minecraftVersion: row.minecraft_version,
    loaders: Object.freeze(parseJsonList(row.loaders)),
    imageReferences: Object.freeze(parseJsonList(row.image_references)),
    budget: budgetView(row),
    deadline: row.deadline,
    scope: row.scope,
    status: row.status,
    proposalCount,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    awardedAt: row.awarded_at,
  });
}

/**
 * The public projection of an OPEN job: exactly what a stranger needs to decide whether to propose — no buyer
 * identity, no lifecycle history, and only an aggregate proposal count, never proposal content.
 */
export function publicJobView(row, proposalCount) {
  const description = row.description;
  return Object.freeze({
    id: row.job_id,
    title: row.title,
    description,
    summary: description.length > 180 ? `${description.slice(0, 180)}…` : description,
    edition: row.edition,
    minecraftVersion: row.minecraft_version,
    loaders: Object.freeze(parseJsonList(row.loaders)),
    imageReferences: Object.freeze(parseJsonList(row.image_references)),
    budget: budgetView(row),
    deadline: row.deadline,
    scope: row.scope,
    proposalCount,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  });
}

function jobRef(database, jobId) {
  const row = jobRowById(database, jobId);
  if (!row) throw new AccountApiError(ErrorCode.JOB_NOT_FOUND);
  return Object.freeze({ id: row.job_id, title: row.title, status: row.status, edition: row.edition, minecraftVersion: row.minecraft_version });
}

/** The creator's own view of their proposal: their words, their budget, the job it answers, and its status. */
export function proposalOwnerView(database, row) {
  return Object.freeze({
    id: row.proposal_id,
    job: jobRef(database, row.job_id),
    message: row.message,
    scope: row.scope,
    budget: budgetView(row),
    deliveryEstimateDays: row.delivery_estimate_days,
    status: row.status,
    // Phase 27 additive link: when the buyer converted this proposal into an order, both parties can follow it
    // straight to the order from the proposal surfaces they already visit. Null until an order exists.
    orderId: database.prepare("SELECT order_id FROM orders WHERE proposal_id = ?").get(row.proposal_id)?.order_id ?? null,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  });
}

/** The buyer's view of someone else's proposal: same content, plus the public creator identity behind it. */
export function proposalBuyerView(database, row) {
  const creator = database.prepare(
    "SELECT handle, display_name, avatar_reference FROM creator_profiles WHERE creator_id = ?",
  ).get(row.creator_id);
  if (!creator) throw new AccountApiError(ErrorCode.JOB_NOT_FOUND);
  return Object.freeze({
    ...proposalOwnerView(database, row),
    creator: Object.freeze({ handle: creator.handle, displayName: creator.display_name, avatarReference: creator.avatar_reference ?? null }),
  });
}

/* ------------------------------------------------------------------------------------ audits */

function auditHire(database, actionType, userId, metadata, now) {
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType,
    targetUserId: userId,
    outcome: "SUCCESS",
    // Ids, statuses, counts, and vocabularies only — never proposal contents, never personal details.
    metadata,
    occurredAt: nowIso(now),
  });
}

/* ---------------------------------------------------------------------------------- job writes (in-tx) */

export function createJobInTransaction(database, configuration, userId, body, { now = Date.now() } = {}) {
  requireBuyerAccount(database, userId);
  const content = validateJobContent(body, { now });
  const jobId = newJobId();
  const timestamp = nowIso(now);
  database.prepare(
    `INSERT INTO buyer_jobs
       (job_id, buyer_id, title, description, edition, minecraft_version, loaders, image_references,
        budget_min, budget_max, budget_currency, deadline, scope, status, created_at, updated_at, awarded_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, NULL)`,
  ).run(
    jobId, userId, content.title, content.description, content.edition, content.minecraftVersion,
    JSON.stringify(content.loaders), JSON.stringify(content.imageReferences),
    content.budgetMin, content.budgetMax, content.budgetCurrency, content.deadline, content.scope,
    timestamp, timestamp,
  );
  auditHire(database, "JOB_CREATED", userId, { jobId, edition: content.edition, hasBudget: content.budgetMin !== null }, now);
  return ownerJobView(jobRowById(database, jobId), 0);
}

export function updateJobInTransaction(database, configuration, userId, jobId, body, { now = Date.now() } = {}) {
  requireBuyerAccount(database, userId);
  const row = ownedJobRow(database, jobId, userId);
  if (row.status !== JOB_STATUS.OPEN) throw new AccountApiError(ErrorCode.JOB_STATE_CONFLICT);
  const patch = { ...body };
  delete patch.jobId;
  delete patch.status;
  if (Object.keys(patch).length === 0) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  // Validate the patch as a complete document over the stored row, so a partial update cannot produce a state the
  // create path would have rejected.
  const content = validateJobContent({
    title: patch.title ?? row.title,
    description: patch.description ?? row.description,
    edition: patch.edition ?? row.edition,
    minecraftVersion: patch.minecraftVersion ?? row.minecraft_version,
    loaders: patch.loaders ?? parseJsonList(row.loaders),
    imageReferences: patch.imageReferences ?? parseJsonList(row.image_references),
    budgetMin: patch.budgetMin ?? row.budget_min,
    budgetMax: patch.budgetMax ?? row.budget_max,
    budgetCurrency: patch.budgetCurrency ?? row.budget_currency,
    deadline: patch.deadline ?? row.deadline,
    scope: patch.scope ?? row.scope,
  }, { now });
  const timestamp = nowIso(now);
  database.prepare(
    `UPDATE buyer_jobs
        SET title = ?, description = ?, edition = ?, minecraft_version = ?, loaders = ?, image_references = ?,
            budget_min = ?, budget_max = ?, budget_currency = ?, deadline = ?, scope = ?, updated_at = ?
      WHERE job_id = ? AND status = 'OPEN'`,
  ).run(
    content.title, content.description, content.edition, content.minecraftVersion,
    JSON.stringify(content.loaders), JSON.stringify(content.imageReferences),
    content.budgetMin, content.budgetMax, content.budgetCurrency, content.deadline, content.scope,
    timestamp, jobId,
  );
  auditHire(database, "JOB_UPDATED", userId, { jobId, fields: Object.keys(patch).sort() }, now);
  return ownerJobView(jobRowById(database, jobId), proposalCountForJob(database, jobId));
}

export function cancelJobInTransaction(database, configuration, userId, jobId, { now = Date.now() } = {}) {
  requireBuyerAccount(database, userId);
  const row = ownedJobRow(database, jobId, userId);
  if (row.status !== JOB_STATUS.OPEN) throw new AccountApiError(ErrorCode.JOB_STATE_CONFLICT);
  const timestamp = nowIso(now);
  // Conditional on OPEN inside the serialized transaction: a cancel and an award can never both land.
  const changed = database.prepare("UPDATE buyer_jobs SET status = 'CANCELLED', updated_at = ? WHERE job_id = ? AND status = 'OPEN'")
    .run(timestamp, jobId).changes;
  if (changed !== 1) throw new AccountApiError(ErrorCode.JOB_STATE_CONFLICT);
  const closed = database.prepare(
    "UPDATE job_proposals SET status = 'NOT_SELECTED', updated_at = ? WHERE job_id = ? AND status = 'SUBMITTED'",
  ).run(timestamp, jobId).changes;
  auditHire(database, "JOB_CANCELLED", userId, { jobId, closedProposals: closed }, now);
  return ownerJobView(jobRowById(database, jobId), proposalCountForJob(database, jobId));
}

/**
 * The single-award transition. The proposal must belong to this job and still be SUBMITTED; the job must still be
 * OPEN. The award UPDATE carries `AND status = 'OPEN'`, the proposal flips to SELECTED, and every other live
 * proposal flips to NOT_SELECTED — all in one transaction, so repeated or concurrent award attempts either win
 * exactly once or lose with a typed conflict. No payment, milestone, or delivery state changes here.
 */
export function awardJobInTransaction(database, configuration, userId, jobId, proposalId, { now = Date.now() } = {}) {
  requireBuyerAccount(database, userId);
  const row = ownedJobRow(database, jobId, userId);
  if (row.status !== JOB_STATUS.OPEN) throw new AccountApiError(ErrorCode.JOB_STATE_CONFLICT);
  if (typeof proposalId !== "string" || !/^prp_[0-9a-fA-F-]{36}$/.test(proposalId)) {
    throw new AccountApiError(ErrorCode.PROPOSAL_NOT_FOUND);
  }
  const proposal = database.prepare("SELECT * FROM job_proposals WHERE proposal_id = ?").get(proposalId) ?? null;
  if (!proposal || proposal.job_id !== jobId) throw new AccountApiError(ErrorCode.PROPOSAL_NOT_FOUND);
  if (proposal.status !== PROPOSAL_STATUS.SUBMITTED) throw new AccountApiError(ErrorCode.PROPOSAL_STATE_CONFLICT);
  const timestamp = nowIso(now);
  const awarded = database.prepare(
    "UPDATE buyer_jobs SET status = 'AWARDED', awarded_at = ?, updated_at = ? WHERE job_id = ? AND status = 'OPEN'",
  ).run(timestamp, timestamp, jobId).changes;
  if (awarded !== 1) throw new AccountApiError(ErrorCode.JOB_STATE_CONFLICT);
  database.prepare("UPDATE job_proposals SET status = 'SELECTED', updated_at = ? WHERE proposal_id = ?")
    .run(timestamp, proposalId);
  const closed = database.prepare(
    "UPDATE job_proposals SET status = 'NOT_SELECTED', updated_at = ? WHERE job_id = ? AND status = 'SUBMITTED'",
  ).run(timestamp, jobId).changes;
  auditHire(database, "JOB_AWARDED", userId, { jobId, proposalId, closedProposals: closed }, now);
  auditHire(database, "PROPOSAL_SELECTED", proposal.user_id, { jobId, proposalId }, now);
  return Object.freeze({
    job: ownerJobView(jobRowById(database, jobId), proposalCountForJob(database, jobId)),
    selectedProposalId: proposalId,
    closedProposals: closed,
  });
}

/* ------------------------------------------------------------------------------ proposal writes (in-tx) */

export function submitProposalInTransaction(database, configuration, userId, body, { now = Date.now() } = {}) {
  const prerequisites = requireProposalCreatorInTransaction(database, configuration, userId, { now });
  if (!prerequisites.agreementRecorded) {
    // Submitting a proposal is this module's "publish" moment, so it carries the same genuine prerequisite the
    // listing pipeline enforces — refused with an actionable typed error and a denial record that outlives this
    // transaction (the audit capture frames flush after rollback).
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "CREATOR_ACCESS_DENIED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { resourceKind: RESOURCE_KIND.JOB_PROPOSAL, reason: "agreement_missing", surface: "proposal" },
      occurredAt: nowIso(now),
    });
    throw new AccountApiError(ErrorCode.PROPOSAL_BLOCKED);
  }
  const jobId = typeof body.jobId === "string" ? body.jobId : "";
  const job = jobRowById(database, jobId);
  if (!job) throw new AccountApiError(ErrorCode.JOB_NOT_FOUND);
  if (job.status !== JOB_STATUS.OPEN) {
    throw new AccountApiError(ErrorCode.JOB_STATE_CONFLICT, "This job is no longer open for proposals.");
  }
  if (job.buyer_id === userId) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST, "You posted this job, so you cannot propose on it.");
  }
  // Phase 30: if either the buyer or this creator has blocked the other, no proposal may connect them. The denial is
  // audited so the protection is observable, and a blocked pair simply cannot reach the proposal rows. When no block
  // exists between the two, this predicate is false and the proposal proceeds exactly as it did before this phase.
  if (isBlockedBetween(database, job.buyer_id, userId)) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "BLOCK_ENFORCED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { resourceKind: RESOURCE_KIND.MARKETPLACE_BLOCK, jobId: job.job_id, reason: "blocked_between_accounts" },
      occurredAt: nowIso(now),
    });
    throw new AccountApiError(ErrorCode.MARKETPLACE_BLOCKED);
  }
  const duplicate = database.prepare(
    "SELECT proposal_id FROM job_proposals WHERE user_id = ? AND job_id = ? AND status = 'SUBMITTED'",
  ).get(userId, jobId);
  if (duplicate) {
    throw new AccountApiError(ErrorCode.PROPOSAL_STATE_CONFLICT, "You already have an active proposal on this job.");
  }
  const content = validateProposalContent(body);
  const proposalId = newProposalId();
  const timestamp = nowIso(now);
  try {
    database.prepare(
      `INSERT INTO job_proposals
         (proposal_id, job_id, user_id, creator_id, message, scope, budget_min, budget_max, budget_currency,
          delivery_estimate_days, status, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, ?)`,
    ).run(
      proposalId, jobId, userId, prerequisites.profile.creatorId, content.message, content.scope,
      content.budgetMin, content.budgetMax, content.budgetCurrency, content.deliveryEstimateDays,
      timestamp, timestamp,
    );
  } catch (error) {
    // The partial unique index is the race backstop: if two submissions from one creator crossed, one loses here
    // with the same typed conflict the pre-check raises — never a raw constraint error to the client.
    if (String(error?.message ?? "").includes("UNIQUE")) {
      throw new AccountApiError(ErrorCode.PROPOSAL_STATE_CONFLICT, "You already have an active proposal on this job.");
    }
    throw error;
  }
  auditHire(database, "PROPOSAL_SUBMITTED", userId, { jobId, proposalId }, now);
  const row = proposalRowById(database, proposalId);
  return proposalOwnerView(database, row);
}

export function updateProposalInTransaction(database, configuration, userId, proposalId, body, { now = Date.now() } = {}) {
  requireProposalCreatorInTransaction(database, configuration, userId, { now });
  const row = ownedProposalRow(database, proposalId, userId);
  if (row.status !== PROPOSAL_STATUS.SUBMITTED) throw new AccountApiError(ErrorCode.PROPOSAL_STATE_CONFLICT);
  const job = jobRowById(database, row.job_id);
  if (!job || job.status !== JOB_STATUS.OPEN) {
    throw new AccountApiError(ErrorCode.PROPOSAL_STATE_CONFLICT, "This job is no longer open for changes.");
  }
  const patch = { ...body };
  delete patch.proposalId;
  delete patch.jobId;
  delete patch.status;
  if (Object.keys(patch).length === 0) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const content = validateProposalContent({
    message: patch.message ?? row.message,
    scope: patch.scope ?? row.scope,
    budgetMin: patch.budgetMin ?? row.budget_min,
    budgetMax: patch.budgetMax ?? row.budget_max,
    budgetCurrency: patch.budgetCurrency ?? row.budget_currency,
    deliveryEstimateDays: patch.deliveryEstimateDays ?? row.delivery_estimate_days,
  });
  const timestamp = nowIso(now);
  database.prepare(
    `UPDATE job_proposals
        SET message = ?, scope = ?, budget_min = ?, budget_max = ?, budget_currency = ?,
            delivery_estimate_days = ?, updated_at = ?
      WHERE proposal_id = ? AND status = 'SUBMITTED'`,
  ).run(
    content.message, content.scope, content.budgetMin, content.budgetMax, content.budgetCurrency,
    content.deliveryEstimateDays, timestamp, proposalId,
  );
  auditHire(database, "PROPOSAL_UPDATED", userId, { proposalId, fields: Object.keys(patch).sort() }, now);
  return proposalOwnerView(database, proposalRowById(database, proposalId));
}

export function withdrawProposalInTransaction(database, configuration, userId, proposalId, { now = Date.now() } = {}) {
  requireProposalCreatorInTransaction(database, configuration, userId, { now });
  const row = ownedProposalRow(database, proposalId, userId);
  if (row.status !== PROPOSAL_STATUS.SUBMITTED) throw new AccountApiError(ErrorCode.PROPOSAL_STATE_CONFLICT);
  const timestamp = nowIso(now);
  const changed = database.prepare(
    "UPDATE job_proposals SET status = 'WITHDRAWN', updated_at = ? WHERE proposal_id = ? AND status = 'SUBMITTED'",
  ).run(timestamp, proposalId).changes;
  if (changed !== 1) throw new AccountApiError(ErrorCode.PROPOSAL_STATE_CONFLICT);
  auditHire(database, "PROPOSAL_WITHDRAWN", userId, { proposalId }, now);
  return proposalOwnerView(database, proposalRowById(database, proposalId));
}

/* ------------------------------------------------------------------------------------ reads (in-tx) */

export function ownJobsInTransaction(database, configuration, userId) {
  requireBuyerAccount(database, userId);
  const rows = database.prepare(
    "SELECT * FROM buyer_jobs WHERE buyer_id = ? ORDER BY updated_at DESC, job_id DESC",
  ).all(userId);
  const views = rows.map((row) => ownerJobView(row, proposalCountForJob(database, row.job_id)));
  return Object.freeze({
    jobs: Object.freeze(views),
    counts: Object.freeze({
      total: views.length,
      open: views.filter((view) => view.status === JOB_STATUS.OPEN).length,
      awarded: views.filter((view) => view.status === JOB_STATUS.AWARDED).length,
      cancelled: views.filter((view) => view.status === JOB_STATUS.CANCELLED).length,
    }),
  });
}

/** The buyer's job detail with its proposals for review: only the job's owner ever sees proposal content. */
export function ownJobDetailInTransaction(database, configuration, userId, jobId) {
  requireBuyerAccount(database, userId);
  const row = ownedJobRow(database, jobId, userId);
  const proposals = proposalRowsForJob(database, jobId);
  // Phase 27 additive link: an AWARDED job has at most one order (unique per selected proposal), so the buyer's
  // job page can offer a direct path to it without inventing a new lookup endpoint.
  const orderId = database.prepare("SELECT order_id FROM orders WHERE job_id = ? LIMIT 1").get(jobId)?.order_id ?? null;
  return Object.freeze({
    job: Object.freeze({ ...ownerJobView(row, proposals.length), orderId }),
    proposals: Object.freeze(proposals.map((proposal) => proposalBuyerView(database, proposal))),
    counts: proposalCounts(proposals),
  });
}

export function ownProposalsInTransaction(database, configuration, userId) {
  requireProposalCreatorInTransaction(database, configuration, userId);
  const rows = database.prepare(
    "SELECT * FROM job_proposals WHERE user_id = ? ORDER BY updated_at DESC, proposal_id DESC",
  ).all(userId);
  return Object.freeze({
    proposals: Object.freeze(rows.map((row) => proposalOwnerView(database, row))),
    counts: proposalCounts(rows),
  });
}

export function ownProposalDetailInTransaction(database, configuration, userId, proposalId) {
  requireProposalCreatorInTransaction(database, configuration, userId);
  const row = ownedProposalRow(database, proposalId, userId);
  return proposalOwnerView(database, row);
}

/** Escapes LIKE wildcards so a search for `100%` or `a_b` matches those characters, never everything. */
function escapeLike(value) {
  return value.replace(/[\\%_]/g, (character) => `\\${character}`);
}

/**
 * Database-backed search over OPEN jobs only — `status = 'OPEN'` lives in the WHERE clause, not in a filter the
 * caller controls. Parameterized LIKE with escaped wildcards, deterministic ordering (`created_at DESC, job_id`
 * DESC), bounded pagination, accurate totals. An empty result is a normal 200.
 */
export function searchOpenJobsInTransaction(database, filters, { now = Date.now() } = {}) {
  const where = ["status = 'OPEN'"];
  const parameters = [];
  if (filters.q) {
    const needle = `%${escapeLike(filters.q)}%`;
    where.push("(title LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' OR scope LIKE ? ESCAPE '\\')");
    parameters.push(needle, needle, needle);
  }
  if (filters.edition) {
    where.push("edition = ?");
    parameters.push(filters.edition);
  }
  if (filters.version) {
    where.push("minecraft_version = ?");
    parameters.push(filters.version);
  }
  const clause = `WHERE ${where.join(" AND ")}`;
  const total = database.prepare(`SELECT COUNT(*) AS count FROM buyer_jobs ${clause}`).get(...parameters).count;
  const rows = database.prepare(
    `SELECT * FROM buyer_jobs ${clause}
      ORDER BY created_at DESC, job_id DESC
      LIMIT ? OFFSET ?`,
  ).all(...parameters, filters.limit, filters.offset);
  const items = rows.map((row) => publicJobView(row, proposalCountForJob(database, row.job_id)));
  return Object.freeze({
    items: Object.freeze(items),
    total,
    limit: filters.limit,
    offset: filters.offset,
    hasMore: filters.offset + items.length < total,
    searchedAt: nowIso(now),
  });
}

export function publicJobInTransaction(database, jobId) {
  const row = jobRowById(database, jobId);
  if (!row || row.status !== JOB_STATUS.OPEN) throw new AccountApiError(ErrorCode.JOB_NOT_FOUND);
  return publicJobView(row, proposalCountForJob(database, jobId));
}

/** Validates and normalizes the public job search query string. Anything unbounded or unshaped is a typed 400. */
export function parseJobSearchQuery(searchParams) {
  const text = (name, maximum) => {
    const raw = searchParams.get(name);
    if (raw === null || raw.trim() === "") return null;
    return validateBoundedText(raw, { minimum: 1, maximum });
  };
  const integer = (name, { maximum, fallback = null }) => {
    const raw = searchParams.get(name);
    if (raw === null || raw === "") return fallback;
    if (!/^\d{1,9}$/.test(raw)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
    const value = Number(raw);
    if (!Number.isSafeInteger(value) || value > maximum) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
    return value;
  };

  const q = text("q", HIRE_FIELD_LIMITS.searchQuery.maximum);
  const edition = searchParams.get("edition");
  if (edition && !isKnownEdition(edition)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const version = text("version", 16);
  if (version && !isWellFormedMinecraftVersion(version)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const limit = integer("limit", { maximum: HIRE_FIELD_LIMITS.page.maximumLimit, fallback: HIRE_FIELD_LIMITS.page.defaultLimit });
  const offset = integer("offset", { maximum: HIRE_FIELD_LIMITS.page.maximumOffset, fallback: 0 });
  if (limit !== null && limit < 1) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  return {
    q: q ?? undefined,
    edition: edition ?? undefined,
    version: version ?? undefined,
    limit: limit ?? HIRE_FIELD_LIMITS.page.defaultLimit,
    offset: offset ?? 0,
  };
}
