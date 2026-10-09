/**
 * The order API surface (Phase 27).
 *
 * Every function resolves the acting account from the **session token** — never from the request body — so no
 * caller can create, read, approve, deliver, or cancel an order on someone else's behalf. Bodies carry content
 * only: the strict reader refuses unknown keys outright, which is what rejects client-supplied buyer/creator
 * ids, proposal or order statuses, amounts from nowhere, and milestone positions instead of silently dropping
 * them. Reads and writes run inside the service's serialized transaction, which is what makes the one-order-per-
 * proposal guarantee and every state transition atomic rather than advisory.
 */

import { requireAccountIdForToken } from "./accounts.js";
import { strictBody } from "./creator-server-api.js";
import { runTransaction } from "./transactions.js";
import {
  approveMilestoneInTransaction,
  cancelOrderInTransaction,
  completeOrderInTransaction,
  createOrderInTransaction,
  listBuyerOrdersInTransaction,
  listCreatorOrdersInTransaction,
  orderDetailInTransaction,
  orderHistoryInTransaction,
  parseOrderHistoryQuery,
  parseOrderListQuery,
  requestRevisionInTransaction,
  startMilestoneInTransaction,
  submitDeliveryInTransaction,
} from "./order-lifecycle.js";

const NO_CONTENT_KEYS = [];  // no-body transitions reject smuggled keys outright via strictBody({}).
const DELIVERY_KEYS = ["note", "evidenceReferences", "compatibilityNote"];
const REVISION_KEYS = ["reason", "outsideScope"];

export function createOrderForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["jobId", "proposalId", "milestones"] });
  return runTransaction(database, () => createOrderInTransaction(database, configuration, userId, payload));
}

export function buyerOrdersForSession(database, configuration, accessToken, searchParams) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const filters = parseOrderListQuery(searchParams);
  return runTransaction(database, () => listBuyerOrdersInTransaction(database, configuration, userId, filters));
}

export function creatorOrdersForSession(database, configuration, accessToken, searchParams) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const filters = parseOrderListQuery(searchParams);
  return runTransaction(database, () => listCreatorOrdersInTransaction(database, configuration, userId, filters));
}

export function orderDetailForSession(database, configuration, accessToken, orderId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => orderDetailInTransaction(database, configuration, userId, orderId));
}

export function orderHistoryForSession(database, configuration, accessToken, orderId, searchParams) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const filters = parseOrderHistoryQuery(searchParams);
  return runTransaction(database, () => orderHistoryInTransaction(database, configuration, userId, orderId, filters));
}

export function startMilestoneForSession(database, configuration, accessToken, orderId, milestoneId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  strictBody(body, { required: NO_CONTENT_KEYS, optional: NO_CONTENT_KEYS });
  return runTransaction(database, () => startMilestoneInTransaction(database, configuration, userId, orderId, milestoneId));
}

export function submitDeliveryForSession(database, configuration, accessToken, orderId, milestoneId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["note"], optional: DELIVERY_KEYS.filter((key) => key !== "note") });
  return runTransaction(database, () => submitDeliveryInTransaction(database, configuration, userId, orderId, milestoneId, payload));
}

export function requestRevisionForSession(database, configuration, accessToken, orderId, milestoneId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["reason"], optional: REVISION_KEYS });
  return runTransaction(database, () => requestRevisionInTransaction(database, configuration, userId, orderId, milestoneId, payload));
}

export function approveMilestoneForSession(database, configuration, accessToken, orderId, milestoneId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  strictBody(body, { required: NO_CONTENT_KEYS, optional: NO_CONTENT_KEYS });
  return runTransaction(database, () => approveMilestoneInTransaction(database, configuration, userId, orderId, milestoneId));
}

export function completeOrderForSession(database, configuration, accessToken, orderId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  strictBody(body, { required: NO_CONTENT_KEYS, optional: NO_CONTENT_KEYS });
  return runTransaction(database, () => completeOrderInTransaction(database, configuration, userId, orderId));
}

export function cancelOrderForSession(database, configuration, accessToken, orderId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  strictBody(body, { required: NO_CONTENT_KEYS, optional: NO_CONTENT_KEYS });
  return runTransaction(database, () => cancelOrderInTransaction(database, configuration, userId, orderId));
}
