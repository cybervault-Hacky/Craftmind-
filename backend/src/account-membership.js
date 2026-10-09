/**
 * The authenticated account surface for membership, entitlements, and build credits (Phase 22).
 *
 * This module exists so the HTTP layer stays thin and, more importantly, so that *every* value a client sees is derived
 * from the caller's own session. There is no route here that accepts an account identifier, a plan, a status, a
 * balance, or an entitlement from a request body: a client asks a question about itself and the server answers from the
 * database. Anything else — a fabricated plan, an invented balance, another account's history — has no way in.
 *
 * The responses are deliberately narrow. They carry the information the interface needs (plan, status, entitlement
 * keys, available credits) and nothing else: no internal membership id, no session id, no token, no developer identity,
 * no audit metadata, no SQL. A transaction is identified by its own public `crd_` reference, which is what a reversal
 * refers to, and never by a row id.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { requireAccountIdForToken } from "./accounts.js";
import { creditBalance, listCreditTransactions, consumeBuildCredits } from "./credits.js";
import { getAccountEntitlements, hasEntitlement } from "./entitlements.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";

const MAXIMUM_TRANSACTION_PAGE_SIZE = 100;

/**
 * Purposes a client may consume credits for. A closed set: the reason text stored in the ledger is chosen by the
 * server, never by the caller, so no client can write arbitrary content into an append-only table.
 */
const CONSUMPTION_PURPOSES = Object.freeze({
  BUILD_GENERATION: "Build generation",
});

function consumeBody(body) {
  if (body === null || typeof body !== "object" || Array.isArray(body)) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
  const allowed = ["amount", "purpose", "idempotencyKey"];
  if (Object.keys(body).some((key) => !allowed.includes(key))) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
  if (!Object.hasOwn(body, "amount") || !Number.isSafeInteger(body.amount)) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
  const purpose = body.purpose ?? "BUILD_GENERATION";
  if (typeof purpose !== "string" || !Object.hasOwn(CONSUMPTION_PURPOSES, purpose)) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
  if (body.idempotencyKey !== undefined &&
      (typeof body.idempotencyKey !== "string" || !/^[A-Za-z0-9._:-]{8,128}$/.test(body.idempotencyKey.trim()))) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
  return { amount: body.amount, purpose, purposeLabel: CONSUMPTION_PURPOSES[purpose], idempotencyKey: body.idempotencyKey ?? null };
}

function publicBalance(balance) {
  return Object.freeze({
    available: balance.available,
    expiring: balance.expiring,
    expired: balance.expired,
    expiringInDays: balance.expiringInDays,
    lifetimeGranted: balance.lifetimeGranted,
    lifetimeConsumed: balance.lifetimeConsumed,
  });
}

/** The membership view for the authenticated account: plan, lifecycle state, entitlements, and a credit summary. */
export function membershipForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const view = getAccountEntitlements(database, configuration, userId);
  return Object.freeze({
    membership: view.membership,
    plan: {
      id: view.plan.id,
      name: view.plan.name,
      availability: view.plan.availability,
      availabilityLabel: view.plan.availabilityLabel,
      priceState: view.plan.priceState,
      purchasable: view.plan.purchasable,
      generationAllowance: view.plan.generationAllowance,
      entitlements: view.entitlements.map((entry) => entry.key),
    },
    credits: view.credits ? publicBalance(view.credits) : null,
  });
}

/** The entitlement view: what this account may do, and why. */
export function entitlementsForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const view = getAccountEntitlements(database, configuration, userId);
  return Object.freeze({
    plan: view.membership.plan,
    status: view.membership.status,
    entitlements: view.entitlements,
    administrativeGrants: view.administrativeGrants,
    credits: view.credits ? { available: view.credits.available } : null,
  });
}

/** The authoritative credit balance. The browser never computes or stores this figure. */
export function creditsForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return Object.freeze({ credits: publicBalance(creditBalance(database, configuration, userId)) });
}

/** Ledger history for the authenticated account, newest first. */
export function creditTransactionsForSession(database, configuration, accessToken, { limit = 25, type = null } = {}) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  if (type !== null && !["GRANT", "CONSUME", "EXPIRE", "ADJUSTMENT", "REVERSAL"].includes(type)) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
  const bounded = Math.min(Math.max(1, Number.isInteger(limit) ? limit : 25), MAXIMUM_TRANSACTION_PAGE_SIZE);
  const transactions = listCreditTransactions(database, userId, { limit: bounded, type });
  return Object.freeze({ transactions, count: transactions.length, limit: bounded });
}

/**
 * Consumes build credits for one operation on behalf of the authenticated account.
 *
 * The amount, the purpose, and the idempotency key come from the request; the account, the reason text, the balance
 * check, the ledger row, and the audit entry are all the server's. A retried request with the same key returns the
 * original transaction and never spends twice, and a shortfall is refused without writing anything.
 */
export function consumeCreditsForSession(database, configuration, accessToken, body, headerKey = null) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const request = consumeBody(body);
  if (headerKey !== null && request.idempotencyKey !== null && headerKey !== request.idempotencyKey) {
    // Two different keys in one request: refuse rather than pick one, because either choice could be wrong.
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
  const idempotencyKey = request.idempotencyKey ?? headerKey;
  if (idempotencyKey === null) {
    // Idempotency is not optional for a credit movement: a retried request must never be able to spend twice.
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
  const maximum = configuration?.membership?.maximumConsumeCredits ?? 100;
  if (request.amount < 1 || request.amount > maximum) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
  const consumed = consumeBuildCredits(database, configuration, {
    userId,
    amount: request.amount,
    reason: `${request.purposeLabel}: ${request.amount} build credits.`,
    idempotencyKey,
    authSecret: configuration.authSecret,
  });
  return Object.freeze({
    reused: consumed.reused,
    transaction: consumed.transaction,
    credits: { available: consumed.available },
  });
}

/**
 * A single enforcement helper for future protected endpoints, with the denial recorded in the one audit log. It is
 * exported so a later feature does not reimplement the rule — and so no endpoint ever compares plan strings.
 */
export function enforceSessionEntitlement(database, configuration, accessToken, entitlementKey) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const view = getAccountEntitlements(database, configuration, userId);
  if (!hasEntitlement(view, entitlementKey)) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "ENTITLEMENT_DENIED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { entitlement: entitlementKey, plan: view.membership.plan },
    });
    throw new AccountApiError(ErrorCode.ENTITLEMENT_REQUIRED);
  }
  return Object.freeze({ entitlement: entitlementKey, plan: view.membership.plan });
}

export { MAXIMUM_TRANSACTION_PAGE_SIZE, CONSUMPTION_PURPOSES };
