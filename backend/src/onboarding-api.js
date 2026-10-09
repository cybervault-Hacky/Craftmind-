/**
 * The authenticated onboarding surface (Phase 24).
 *
 * Like Phase 23's `creator-server-api.js`, every function here takes a **session token** and resolves the account
 * from it — never from the request body — so there is no route through which one account can read, start, or edit
 * another account's onboarding answers. Bodies carry content only. The privileged fields a client might try to
 * smuggle (`userId`, `ownerId`, `plan`, `role`, `status`, `verification`, `emailVerified`) are refused as unknown
 * keys rather than ignored, and any `phone…`/`mobile…` key is refused with the dedicated `PHONE_VERIFICATION_UNAVAILABLE`
 * answer: a phone number cannot become a verified phone number, because the mechanism that would verify it does not
 * exist and the service says so instead of pretending.
 *
 * Responses are the composed reads from `onboarding.js`: state, verification posture, requirements, and next steps.
 * A save returns the same shape as the matching read, so the website renders server truth after a write without a
 * second round trip and without inventing a local "completed" flag.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { requireAccountIdForToken } from "./accounts.js";
import { strictBody } from "./creator-server-api.js";
import {
  buyerOnboardingStateInTransaction,
  onboardingStateInTransaction,
  saveBuyerOnboardingInTransaction,
  saveSellerOnboardingInTransaction,
  sellerOnboardingStateInTransaction,
} from "./onboarding.js";
import { runTransaction } from "./transactions.js";

/**
 * Phone-shaped keys are a distinct, honest refusal — not a generic unknown-field error — so a client that asked for
 * phone verification is told the mechanism is unavailable instead of being left to guess why the field was dropped.
 */
const PHONE_KEY_PATTERN = /^(phone|mobile)/i;

function rejectPhoneFields(body) {
  if (body !== null && typeof body === "object" && !Array.isArray(body)) {
    if (Object.keys(body).some((key) => PHONE_KEY_PATTERN.test(key))) {
      throw new AccountApiError(ErrorCode.PHONE_VERIFICATION_UNAVAILABLE);
    }
  }
}

export function onboardingStateForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => onboardingStateInTransaction(database, configuration, userId));
}

export function buyerOnboardingForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => buyerOnboardingStateInTransaction(database, userId));
}

export function saveBuyerOnboardingForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  rejectPhoneFields(body);
  const payload = strictBody(body, { required: ["fullName", "referralSource"], optional: ["referralDetail"] });
  return runTransaction(database, () => {
    saveBuyerOnboardingInTransaction(database, {
      userId,
      fullName: payload.fullName,
      referralSource: payload.referralSource,
      referralDetail: payload.referralDetail,
    });
    return buyerOnboardingStateInTransaction(database, userId);
  });
}

export function sellerOnboardingForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => sellerOnboardingStateInTransaction(database, configuration, userId));
}

export function saveSellerOnboardingForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  rejectPhoneFields(body);
  const payload = strictBody(body, {
    required: [
      "handle", "displayName", "referralSource", "editions", "minecraftVersions", "loaders",
      "agreementAccepted", "agreementVersion",
    ],
    optional: ["bio", "category", "avatarReference", "referralDetail"],
  });
  return runTransaction(database, () => {
    saveSellerOnboardingInTransaction(database, configuration, {
      userId,
      handle: payload.handle,
      displayName: payload.displayName,
      bio: payload.bio,
      category: payload.category,
      avatarReference: payload.avatarReference,
      referralSource: payload.referralSource,
      referralDetail: payload.referralDetail,
      editions: payload.editions,
      minecraftVersions: payload.minecraftVersions,
      loaders: payload.loaders,
      agreementAccepted: payload.agreementAccepted,
      agreementVersion: payload.agreementVersion,
    });
    return sellerOnboardingStateInTransaction(database, configuration, userId);
  });
}
