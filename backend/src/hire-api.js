/**
 * The Hire a Builder API surface (Phase 26).
 *
 * Every session-scoped function resolves the acting account from the **session token** — never from the request
 * body — so no caller can post a job, propose, review, award, or cancel on someone else's behalf. Bodies carry
 * content only: the strict body reader rejects unknown keys outright, which is what refuses client-supplied
 * `buyerId`, `jobId` (on reads/patches where it is not content), `ownerId`, `creatorId`, `status`, `award`,
 * `selectedProposalId`, or timestamps instead of silently dropping them.
 *
 * Public reads serve OPEN jobs only and never audit (public reads are not security events); every write and
 * ownership-checked read runs inside the service's serialized transaction, which is what makes the single-award
 * guarantee and the duplicate-proposal index real rather than advisory.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { requireAccountIdForToken } from "./accounts.js";
import { strictBody } from "./creator-server-api.js";
import { runTransaction } from "./transactions.js";
import {
  awardJobInTransaction,
  cancelJobInTransaction,
  createJobInTransaction,
  ownJobDetailInTransaction,
  ownJobsInTransaction,
  ownProposalDetailInTransaction,
  ownProposalsInTransaction,
  parseJobSearchQuery,
  publicJobInTransaction,
  searchOpenJobsInTransaction,
  submitProposalInTransaction,
  updateJobInTransaction,
  updateProposalInTransaction,
  withdrawProposalInTransaction,
} from "./hire-jobs.js";

const JOB_CONTENT_KEYS = [
  "title", "description", "edition", "minecraftVersion", "loaders", "imageReferences",
  "budgetMin", "budgetMax", "budgetCurrency", "deadline", "scope",
];
const PROPOSAL_CONTENT_KEYS = ["jobId", "message", "scope", "budgetMin", "budgetMax", "budgetCurrency", "deliveryEstimateDays"];
const PROPOSAL_PATCH_KEYS = ["message", "scope", "budgetMin", "budgetMax", "budgetCurrency", "deliveryEstimateDays"];

export function createJobForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["title", "description", "edition", "minecraftVersion", "scope"], optional: JOB_CONTENT_KEYS.filter((key) => !["title", "description", "edition", "minecraftVersion", "scope"].includes(key)) });
  return runTransaction(database, () => createJobInTransaction(database, configuration, userId, payload));
}

export function ownJobsForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => ownJobsInTransaction(database, configuration, userId));
}

export function ownJobDetailForSession(database, configuration, accessToken, jobId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => ownJobDetailInTransaction(database, configuration, userId, jobId));
}

export function updateJobForSession(database, configuration, accessToken, jobId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { optional: JOB_CONTENT_KEYS });
  return runTransaction(database, () => updateJobInTransaction(database, configuration, userId, jobId, payload));
}

export function cancelJobForSession(database, configuration, accessToken, jobId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => cancelJobInTransaction(database, configuration, userId, jobId));
}

/** Awarding takes exactly one field — the chosen proposal — and nothing else; ownership and state are derived. */
export function awardJobForSession(database, configuration, accessToken, jobId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["proposalId"] });
  return runTransaction(database, () => awardJobInTransaction(database, configuration, userId, jobId, payload.proposalId));
}

export function submitProposalForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["jobId", "message", "scope"], optional: PROPOSAL_CONTENT_KEYS.filter((key) => !["jobId", "message", "scope"].includes(key)) });
  return runTransaction(database, () => submitProposalInTransaction(database, configuration, userId, payload));
}

export function ownProposalsForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => ownProposalsInTransaction(database, configuration, userId));
}

export function ownProposalDetailForSession(database, configuration, accessToken, proposalId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => ownProposalDetailInTransaction(database, configuration, userId, proposalId));
}

export function updateProposalForSession(database, configuration, accessToken, proposalId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { optional: PROPOSAL_PATCH_KEYS });
  return runTransaction(database, () => updateProposalInTransaction(database, configuration, userId, proposalId, payload));
}

export function withdrawProposalForSession(database, configuration, accessToken, proposalId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => withdrawProposalInTransaction(database, configuration, userId, proposalId));
}

/** Public search: validate the query string first (outside the transaction), then run the bounded OPEN-only read. */
export function searchOpenJobs(database, searchParams) {
  const filters = parseJobSearchQuery(searchParams);
  return runTransaction(database, () => searchOpenJobsInTransaction(database, filters));
}

export function publicJob(database, jobId) {
  if (typeof jobId !== "string" || jobId.length === 0) throw new AccountApiError(ErrorCode.JOB_NOT_FOUND);
  return runTransaction(database, () => publicJobInTransaction(database, jobId));
}
