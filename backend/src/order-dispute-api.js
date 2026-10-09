/**
 * The order-dispute API surface (Phase 30).
 *
 * Same contract as every other session-scoped marketplace surface: the acting account comes from the bearer token,
 * never the body; the strict reader refuses unknown keys so a client cannot name a dispute, a role, an order id in
 * the payload, a status, an outcome, or a position it did not itself record; and each operation runs inside the
 * service's serialized transaction, which is what makes one-open-dispute-per-order and the mutual-close resolution
 * atomic. No route here reads or writes money: the only effect a dispute has on an order is a freeze while open and,
 * on two-sided agreement, a status change to `CANCELLED`.
 */

import { requireAccountIdForToken } from "./accounts.js";
import { strictBody } from "./creator-server-api.js";
import { runTransaction } from "./transactions.js";
import {
  addDisputeStatementInTransaction,
  disputeDetailInTransaction,
  listMyDisputesInTransaction,
  listOrderDisputesInTransaction,
  openDisputeInTransaction,
  parseDisputeListQuery,
  setDisputePositionInTransaction,
  withdrawDisputeInTransaction,
} from "./order-disputes.js";

const DISPUTE_LIST_KEYS = ["reasonCategory", "reason"];
const STATEMENT_KEYS = ["body"];
const POSITION_KEYS = ["position"];

export function openDisputeForSession(database, configuration, accessToken, orderId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["reasonCategory", "reason"], optional: DISPUTE_LIST_KEYS });
  return runTransaction(database, () => openDisputeInTransaction(database, configuration, userId, orderId, payload));
}

export function listOrderDisputesForSession(database, configuration, accessToken, orderId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => listOrderDisputesInTransaction(database, configuration, userId, orderId));
}

export function listMyDisputesForSession(database, configuration, accessToken, searchParams) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const filters = parseDisputeListQuery(searchParams);
  return runTransaction(database, () => listMyDisputesInTransaction(database, configuration, userId, filters));
}

export function disputeDetailForSession(database, configuration, accessToken, orderId, disputeId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => disputeDetailInTransaction(database, configuration, userId, orderId, disputeId));
}

export function addDisputeStatementForSession(database, configuration, accessToken, orderId, disputeId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["body"], optional: STATEMENT_KEYS });
  return runTransaction(database, () => addDisputeStatementInTransaction(database, configuration, userId, orderId, disputeId, payload));
}

export function setDisputePositionForSession(database, configuration, accessToken, orderId, disputeId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["position"], optional: POSITION_KEYS });
  return runTransaction(database, () => setDisputePositionInTransaction(database, configuration, userId, orderId, disputeId, payload));
}

// Withdraw takes no body at all (like `cancelJobForSession`); the target dispute comes from the path, the actor from
// the token, and nothing about the caller's authority may be inferred from a payload.
export function withdrawDisputeForSession(database, configuration, accessToken, orderId, disputeId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => withdrawDisputeInTransaction(database, configuration, userId, orderId, disputeId));
}
