/**
 * The referral and campaign-attribution API surface (Phase 32).
 *
 * Every function resolves the acting account from the **session token** and passes only that id to the service, so a
 * caller can issue, claim, verify, and read referrals for their own account and nobody else's. Request bodies go
 * through the shared strict reader, which rejects any key outside its allowlist outright rather than dropping it:
 * `userId`, `referrerUserId`, or a claimed verification status is a refusal, not something to be second-guessed. The
 * referrer is always whatever the submitted code resolves to server-side.
 *
 * Reads and writes run inside the service's serialized transaction, so "resolve the code, then record the claim" and
 * "read the verification state, then promote" are each one atomic step against one consistent snapshot rather than a
 * check another request can slip between.
 */

import { requireAccountIdForToken } from "./accounts.js";
import { strictBody } from "./creator-server-api.js";
import { runTransaction } from "./transactions.js";
import {
  claimAttributionInTransaction,
  confirmAttributionInTransaction,
  issueOrGetReferralCodeInTransaction,
  myReferralSummaryInTransaction,
} from "./referrals.js";

const CLAIM_KEYS = ["referralCode", "source", "medium", "campaign"];

export function referralCodeForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => issueOrGetReferralCodeInTransaction(database, configuration, userId));
}

export function claimReferralForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  // `referralCode` is the only field that carries meaning here, and it names nothing the client controls: it is an
  // input to a server-side lookup, never an identity assertion.
  const payload = strictBody(body, { required: ["referralCode"], optional: CLAIM_KEYS });
  return runTransaction(database, () => claimAttributionInTransaction(database, configuration, userId, payload));
}

export function confirmReferralForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => confirmAttributionInTransaction(database, configuration, userId));
}

export function myReferralSummaryForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => myReferralSummaryInTransaction(database, configuration, userId));
}
