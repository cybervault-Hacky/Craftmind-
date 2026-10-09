/**
 * The marketplace trust API surface (Phase 30): reports and the avoid/block list.
 *
 * Every function resolves the acting account from the **session token** — never from the request body — so no caller
 * can file or withdraw a report on someone's behalf, or place a block that touches another account. The strict body
 * reader refuses unknown keys outright, which is what rejects a client-supplied `reporterUserId`, `targetUserId`,
 * `blockedUserId` on a create, or a `status`, instead of silently dropping them. Reads and writes run inside the
 * service's serialized transaction so the single-open-report-per-subject and single-open-dispute-per-order guarantees
 * are real, not advisory.
 */

import { requireAccountIdForToken } from "./accounts.js";
import { strictBody } from "./creator-server-api.js";
import { runTransaction } from "./transactions.js";
import {
  addBlockInTransaction,
  createReportInTransaction,
  listMyBlocksInTransaction,
  listMyReportsInTransaction,
  listReportsAboutMeInTransaction,
  parseTrustListQuery,
  removeBlockInTransaction,
  trustSummaryInTransaction,
  withdrawReportInTransaction,
} from "./marketplace-trust.js";

const REPORT_KEYS = ["subjectType", "subjectId", "category", "note"];
const BLOCK_KEYS = ["sourceType", "sourceId", "reason"];
const NO_CONTENT_KEYS = [];

export function createReportForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["subjectType", "subjectId", "category"], optional: REPORT_KEYS });
  return runTransaction(database, () => createReportInTransaction(database, configuration, userId, payload));
}

export function withdrawReportForSession(database, configuration, accessToken, reportId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => withdrawReportInTransaction(database, configuration, userId, reportId));
}

export function myReportsForSession(database, configuration, accessToken, searchParams) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const filters = parseTrustListQuery(searchParams);
  return runTransaction(database, () => listMyReportsInTransaction(database, configuration, userId, filters));
}

export function reportsAboutMeForSession(database, configuration, accessToken, searchParams) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const filters = parseTrustListQuery(searchParams);
  return runTransaction(database, () => listReportsAboutMeInTransaction(database, configuration, userId, filters));
}

export function trustSummaryForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => trustSummaryInTransaction(database, configuration, userId));
}

export function addBlockForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["sourceType", "sourceId"], optional: BLOCK_KEYS });
  return runTransaction(database, () => addBlockInTransaction(database, configuration, userId, payload));
}

export function removeBlockForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["blockedUserId"], optional: ["blockedUserId"] });
  return runTransaction(database, () => removeBlockInTransaction(database, configuration, userId, payload.blockedUserId));
}

export function myBlocksForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => listMyBlocksInTransaction(database, configuration, userId));
}
