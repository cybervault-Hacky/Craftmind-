/**
 * Buyer and seller onboarding (Phase 24).
 *
 * One account can be both a buyer and a seller: there is no second credential, no role table, and no client-declared
 * identity anywhere in this module. The account comes from the session (the API layer resolves it), the buyer record
 * and the seller record are each a single row keyed by that account, and the seller's *creator identity* stays where
 * Phase 23 put it — in `creator_profiles`. Seller saves therefore run through Phase 23's own create/update services,
 * which keep the Creator entitlement and profile status authoritative; this module adds only what Phase 23 had no
 * place for: the referral answer, the compatibility claims, and the creator-agreement receipt.
 *
 * What onboarding is NOT:
 *   * it is not verification. Email status is derived from `users.email_verified_at` — never stored here — phone
 *     verification does not exist in this product and is refused outright if a client sends it, and creator
 *     verification remains the Phase 23 developer-controlled marker (`INTERNAL_MARKER_ONLY`, never identity/KYC);
 *   * it is not entitlement. Nothing in this module grants a plan, credits, publishing permission, or Trusted Seller
 *     status, and completing a profile leaves `canPublishCreatorContent` exactly where Phase 23 left it;
 *   * it is not commerce. No price, address, date of birth, government identifier, or payout detail is collected,
 *     because no purchase, tax duty, or payout exists to justify one.
 *
 * Completion is a server-persisted fact: a successful save validates, writes, audits, and stamps `completed_at` in one
 * transaction. A form that merely submits proves nothing; a row that exists proves the server accepted it.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";
import { requireAccount } from "./entitlements.js";
import {
  creatorEligibilityInTransaction,
  creatorProfileRowForUser,
  createCreatorProfileInTransaction,
  updateCreatorProfileInTransaction,
  validateHandleInput,
} from "./creator-profiles.js";
import { toOwnerCreatorProfile } from "./creator-catalog.js";
import { validateBoundedText } from "./text-fields.js";
import {
  COMPATIBILITY_LIMITS,
  EDITION_VALUES,
  LOADERS_BY_EDITION,
  loaderBelongsToEditions,
  isKnownEdition,
  isKnownLoader,
  isWellFormedMinecraftVersion,
} from "./compatibility.js";
import { runTransaction } from "./transactions.js";

/** The referral question's answer set. Wire values are stable identifiers; the website owns the display labels. */
export const REFERRAL_SOURCES = Object.freeze([
  "YOUTUBE", "INSTAGRAM", "GOOGLE", "REDDIT", "DISCORD", "FRIEND_REFERRAL", "MINECRAFT_COMMUNITY", "OTHER",
]);

/**
 * The creator agreement version this server can accept. The client must echo exactly this string: a form rendered
 * from an older page is refused rather than silently agreeing the user to text they never saw. Bumping this constant
 * is what re-presents the agreement — there is no grandfathering, because acceptance is only meaningful when it
 * names the version it accepted.
 */
export const CREATOR_AGREEMENT_VERSION = "creator-agreement-2026-10";

export const BUYER_FIELD_LIMITS = Object.freeze({
  fullName: { minimum: 2, maximum: 120 },
  referralDetail: { minimum: 3, maximum: 160 },
});

function nowIso(nowMillis = Date.now()) {
  return new Date(nowMillis).toISOString();
}

/** Referral answer + optional detail, validated as one unit so the pair can never disagree. */
export function validateReferral(referralSource, referralDetail) {
  if (typeof referralSource !== "string" || !REFERRAL_SOURCES.includes(referralSource)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  const detail = validateBoundedText(referralDetail ?? "", {
    ...BUYER_FIELD_LIMITS.referralDetail, allowEmpty: true,
  });
  return { referralSource, referralDetail: detail };
}

/**
 * A bounded string list where every entry satisfies `accept`. Sets the uniqueness rule at the boundary: duplicate
 * claims would inflate an answer about support, so repeats collapse rather than count.
 */
function validateClaimList(values, { maximum, accept }) {
  if (!Array.isArray(values) || values.length === 0) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const unique = [...new Set(values)];
  if (unique.length > maximum) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  if (unique.some((value) => typeof value !== "string" || value.length === 0 || value.length > 64 || !accept(value))) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  return unique;
}

/**
 * Editions, versions, and loaders — validated together because they are one claim. A loader must belong to at least
 * one selected edition (the same rule the website form applies), so "Bedrock Native" cannot ride along with a
 * Java-only answer. Free-form prose is rejected; see `compatibility.js` for why versions are shaped, not listed.
 */
export function validateCompatibilityClaims({ editions, minecraftVersions, loaders }) {
  const selectedEditions = validateClaimList(editions, {
    maximum: COMPATIBILITY_LIMITS.maximumEditions, accept: isKnownEdition,
  });
  const versions = validateClaimList(minecraftVersions, {
    maximum: COMPATIBILITY_LIMITS.maximumVersions, accept: isWellFormedMinecraftVersion,
  });
  const selectedLoaders = validateClaimList(loaders, {
    maximum: COMPATIBILITY_LIMITS.maximumLoaders,
    accept: (loader) => isKnownLoader(loader) && loaderBelongsToEditions(loader, selectedEditions),
  });
  return {
    editions: selectedEditions,
    minecraftVersions: versions,
    loaders: selectedLoaders,
  };
}

/** Agreement receipt: accepted must be exactly true, and the version must be the one this server is presenting. */
function validateAgreement(agreementAccepted, agreementVersion) {
  if (agreementAccepted !== true) throw new AccountApiError(ErrorCode.SELLER_AGREEMENT_REQUIRED);
  if (agreementVersion !== CREATOR_AGREEMENT_VERSION) {
    throw new AccountApiError(
      ErrorCode.SELLER_AGREEMENT_REQUIRED,
      "This form is presenting an older creator agreement. Reload the page to accept the current version.",
    );
  }
  return { agreementVersion: CREATOR_AGREEMENT_VERSION, agreedAt: null /* stamped by the caller */ };
}

function buyerOnboardingRow(database, userId) {
  return database.prepare("SELECT * FROM buyer_onboarding WHERE user_id = ?").get(userId) ?? null;
}

function sellerOnboardingRow(database, userId) {
  return database.prepare("SELECT * FROM seller_onboarding WHERE user_id = ?").get(userId) ?? null;
}

/**
 * Stable projections. Both shapes always carry the same keys — `exists: false` is a value, not a 404 — so the forms
 * can bind to the response without branching on absence, and an empty state never leaks whether another account's
 * row exists (the query is keyed by the session's account before any of this runs).
 */
export function buyerOnboardingView(row) {
  if (!row) {
    return Object.freeze({
      exists: false, fullName: null, referralSource: null, referralDetail: null,
      completed: false, completedAt: null, createdAt: null, updatedAt: null,
    });
  }
  return Object.freeze({
    exists: true,
    fullName: row.full_name,
    referralSource: row.referral_source,
    referralDetail: row.referral_detail ?? "",
    completed: true,
    completedAt: row.completed_at,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  });
}

export function sellerOnboardingView(row) {
  if (!row) {
    return Object.freeze({
      exists: false, referralSource: null, referralDetail: null,
      editions: Object.freeze([]), minecraftVersions: Object.freeze([]), loaders: Object.freeze([]),
      agreementVersion: null, agreedAt: null,
      completed: false, completedAt: null, createdAt: null, updatedAt: null,
    });
  }
  // Only this module writes these columns, and it writes JSON.stringify output; anything else reaching the parser is
  // tampering, and tampering fails closed rather than rendering as an empty claim.
  const parse = (column) => {
    try {
      const parsed = JSON.parse(row[column]);
      if (!Array.isArray(parsed)) throw new Error("not a list");
      return Object.freeze(parsed);
    } catch {
      throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
    }
  };
  return Object.freeze({
    exists: true,
    referralSource: row.referral_source,
    referralDetail: row.referral_detail ?? "",
    editions: parse("editions"),
    minecraftVersions: parse("minecraft_versions"),
    loaders: parse("loaders"),
    agreementVersion: row.agreement_version,
    agreedAt: row.agreed_at,
    completed: true,
    completedAt: row.completed_at,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  });
}

/**
 * Writes the buyer record. Idempotent by construction: one row per account, `created_at` preserved on later saves,
 * `completed_at` stamped because a successful save *is* completion — validated name + referral, persisted server-side.
 *
 * Deliberately absent columns: email verification (derived from `users` at read time, never copied), phone (no
 * mechanism exists), country (no documented product purpose exists yet), address, date of birth, government ID.
 */
export function saveBuyerOnboardingInTransaction(database, {
  userId, fullName, referralSource, referralDetail, now = Date.now(),
}) {
  requireAccount(database, userId);
  const name = validateBoundedText(fullName, { ...BUYER_FIELD_LIMITS.fullName });
  const referral = validateReferral(referralSource, referralDetail);
  const timestamp = nowIso(now);
  const existing = buyerOnboardingRow(database, userId);
  if (existing) {
    database.prepare(
      `UPDATE buyer_onboarding SET full_name = ?, referral_source = ?, referral_detail = ?, updated_at = ?
        WHERE user_id = ?`,
    ).run(name, referral.referralSource, referral.referralDetail, timestamp, userId);
  } else {
    database.prepare(
      `INSERT INTO buyer_onboarding (user_id, full_name, referral_source, referral_detail, completed_at, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).run(userId, name, referral.referralSource, referral.referralDetail, timestamp, timestamp, timestamp);
  }
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType: "BUYER_ONBOARDING_SAVED",
    targetUserId: userId,
    outcome: "SUCCESS",
    // Bounded and free of personal data: the record of *that* a save happened, never the answer itself.
    metadata: { completed: true, referralSource: referral.referralSource, created: existing === null },
    occurredAt: timestamp,
  });
  return buyerOnboardingView(buyerOnboardingRow(database, userId));
}

/**
 * Writes the seller record, and creates or updates the Phase 23 creator profile that anchors it — in that order of
 * meaning: the profile call enforces the Creator entitlement and the profile's own status (refusing first-class
 * fakes like a client-supplied `VERIFIED`), and only a profile that already exists inside this transaction can be
 * paired with a seller row. An account that already completed buyer onboarding simply gains a second row; no new
 * account, and no second identity, is involved anywhere.
 */
export function saveSellerOnboardingInTransaction(database, configuration, {
  userId, handle, displayName, bio, category, avatarReference,
  referralSource, referralDetail, editions, minecraftVersions, loaders,
  agreementAccepted, agreementVersion, now = Date.now(),
}) {
  requireAccount(database, userId);
  const referral = validateReferral(referralSource, referralDetail);
  const claims = validateCompatibilityClaims({ editions, minecraftVersions, loaders });
  const agreement = validateAgreement(agreementAccepted, agreementVersion);

  // Creator identity goes through Phase 23's services unchanged: handle rules, reserved words, field bounds, one
  // profile per account, entitlement, and status policy are all decided there, not re-implemented here.
  const existingProfile = creatorProfileRowForUser(database, userId);
  if (existingProfile) {
    // Phase 23 keeps the handle immutable after creation; onboarding re-presents it, so a *different* handle is a
    // contradiction the server states plainly instead of quietly renaming a published identity.
    if (validateHandleInput(handle) !== existingProfile.handle) {
      throw new AccountApiError(
        ErrorCode.INVALID_REQUEST,
        "This account already has a creator handle. Onboarding cannot change it.",
      );
    }
    updateCreatorProfileInTransaction(database, configuration, {
      userId, displayName, bio, category, avatarReference, now,
    });
  } else {
    createCreatorProfileInTransaction(database, configuration, {
      userId, handle, displayName, bio, category, avatarReference, now,
    });
  }

  const timestamp = nowIso(now);
  const existing = sellerOnboardingRow(database, userId);
  if (existing) {
    database.prepare(
      `UPDATE seller_onboarding
          SET referral_source = ?, referral_detail = ?, editions = ?, minecraft_versions = ?, loaders = ?,
              agreement_version = ?, agreed_at = ?, updated_at = ?
        WHERE user_id = ?`,
    ).run(
      referral.referralSource, referral.referralDetail,
      JSON.stringify(claims.editions), JSON.stringify(claims.minecraftVersions), JSON.stringify(claims.loaders),
      agreement.agreementVersion, timestamp, timestamp, userId,
    );
  } else {
    database.prepare(
      `INSERT INTO seller_onboarding
         (user_id, referral_source, referral_detail, editions, minecraft_versions, loaders,
          agreement_version, agreed_at, completed_at, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(
      userId, referral.referralSource, referral.referralDetail,
      JSON.stringify(claims.editions), JSON.stringify(claims.minecraftVersions), JSON.stringify(claims.loaders),
      agreement.agreementVersion, timestamp, timestamp, timestamp, timestamp,
    );
  }
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType: "SELLER_ONBOARDING_SAVED",
    targetUserId: userId,
    outcome: "SUCCESS",
    metadata: {
      completed: true,
      referralSource: referral.referralSource,
      agreementVersion: agreement.agreementVersion,
      created: existing === null,
      profileCreated: existingProfile === null,
    },
    occurredAt: timestamp,
  });
  return sellerOnboardingView(sellerOnboardingRow(database, userId));
}

/**
 * Requirements and next steps, derived — never asserted. `pending` names what the *server* still needs before it will
 * call that role complete, so the website can render an honest checklist instead of guessing; an unentitled account
 * is told the entitlement is pending rather than being told it is ready.
 */
function requirementsFor({ buyer, seller, profile, entitled, emailVerified }) {
  const buyerPending = [];
  if (!buyer) {
    buyerPending.push("FULL_NAME", "REFERRAL");
    if (!emailVerified) buyerPending.push("EMAIL_VERIFIED");
  }
  const sellerPending = [];
  if (!entitled) sellerPending.push("CREATOR_ENTITLEMENT");
  if (!profile) sellerPending.push("CREATOR_PROFILE");
  else if (profile.status !== "ACTIVE") sellerPending.push("PROFILE_ACTIVE");
  if (!seller) sellerPending.push("REFERRAL", "EDITIONS", "MINECRAFT_VERSIONS", "LOADERS", "SELLER_AGREEMENT");
  return Object.freeze({
    buyer: Object.freeze({ pending: Object.freeze(buyerPending), complete: buyer !== null }),
    seller: Object.freeze({ pending: Object.freeze(sellerPending), complete: Boolean(seller && profile && profile.status === "ACTIVE" && entitled) }),
  });
}

function nextStepsFor(requirements) {
  const steps = [];
  if (!requirements.buyer.complete) steps.push("COMPLETE_BUYER_ONBOARDING");
  if (!requirements.seller.complete) steps.push("COMPLETE_SELLER_ONBOARDING");
  return Object.freeze(steps);
}

/**
 * The composed read behind `GET /account/onboarding`: state, verification posture, requirements, next steps, and the
 * option vocabulary the forms need — in one response so the website never has to invent client-side rules the server
 * does not hold.
 */
export function onboardingStateInTransaction(database, configuration, userId, { now = Date.now() } = {}) {
  const user = requireAccount(database, userId);
  const buyer = buyerOnboardingRow(database, userId);
  const seller = sellerOnboardingRow(database, userId);
  const profile = creatorProfileRowForUser(database, userId);
  const eligibility = creatorEligibilityInTransaction(database, configuration, userId, { now });
  const entitled = eligibility.entitlement.granted === true;
  const emailVerified = user.email_verified_at !== null;
  const requirements = requirementsFor({ buyer, seller, profile, entitled, emailVerified });
  return Object.freeze({
    onboarding: Object.freeze({
      buyer: buyerOnboardingView(buyer),
      seller: sellerOnboardingView(seller),
      creatorProfile: Object.freeze({ exists: Boolean(profile), handle: profile?.handle ?? null, status: profile?.status ?? null }),
    }),
    verification: Object.freeze({
      email: Object.freeze({ status: emailVerified ? "VERIFIED" : "UNVERIFIED", verified: emailVerified }),
      phone: Object.freeze({
        status: "UNAVAILABLE",
        available: false,
        reason: "Phone verification does not exist in CraftMind yet, so no phone number is collected or treated as verified.",
      }),
      creator: Object.freeze({
        status: profile?.verification_status ?? "UNVERIFIED",
        scope: "INTERNAL_MARKER_ONLY",
        note: "A developer-controlled marker, not identity or KYC verification.",
      }),
    }),
    requirements,
    nextSteps: nextStepsFor(requirements),
    options: Object.freeze({
      referralSources: REFERRAL_SOURCES,
      agreement: Object.freeze({ version: CREATOR_AGREEMENT_VERSION, required: true }),
      editions: Object.freeze(EDITION_VALUES.map((wire) => Object.freeze({ wire, loaders: LOADERS_BY_EDITION[wire] }))),
      minecraftVersionHint: "1.20.1",
    }),
  });
}

/** The private buyer read for the form: this account's row, plus the verification posture it is judged against. */
export function buyerOnboardingStateInTransaction(database, userId, { now = Date.now() } = {}) {
  const user = requireAccount(database, userId);
  const emailVerified = user.email_verified_at !== null;
  return Object.freeze({
    buyer: buyerOnboardingView(buyerOnboardingRow(database, userId)),
    verification: Object.freeze({
      email: Object.freeze({ status: emailVerified ? "VERIFIED" : "UNVERIFIED", verified: emailVerified }),
      phone: Object.freeze({
        status: "UNAVAILABLE",
        available: false,
        reason: "Phone verification does not exist in CraftMind yet, so no phone number is collected or treated as verified.",
      }),
    }),
  });
}

/** The private seller read: the Phase 24 record, the Phase 23 owner profile, and the entitlement/capability truth. */
export function sellerOnboardingStateInTransaction(database, configuration, userId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const profile = creatorProfileRowForUser(database, userId);
  const eligibility = creatorEligibilityInTransaction(database, configuration, userId, { now });
  return Object.freeze({
    seller: sellerOnboardingView(sellerOnboardingRow(database, userId)),
    // `null` (no profile yet) versus the Phase 23 owner projection — with `profileExists` as the explicit
    // discriminator, so the form never has to probe object shape to know which state it is in.
    profile: profile ? Object.freeze(toOwnerCreatorProfile(profile)) : null,
    profileExists: Boolean(profile),
    entitled: eligibility.entitlement.granted === true,
    eligibility,
    agreement: Object.freeze({ version: CREATOR_AGREEMENT_VERSION, required: true }),
  });
}

/** Atomic wrappers. Reads for free, writes inside the one transaction helper (so refusals keep their audit records). */
export function onboardingState(database, configuration, userId, options) {
  return runTransaction(database, () => onboardingStateInTransaction(database, configuration, userId, options));
}

export function buyerOnboardingState(database, userId, options) {
  return runTransaction(database, () => buyerOnboardingStateInTransaction(database, userId, options));
}

export function sellerOnboardingState(database, configuration, userId, options) {
  return runTransaction(database, () => sellerOnboardingStateInTransaction(database, configuration, userId, options));
}

export function saveBuyerOnboarding(database, request) {
  return runTransaction(database, () => saveBuyerOnboardingInTransaction(database, request));
}

export function saveSellerOnboarding(database, configuration, request) {
  return runTransaction(database, () => saveSellerOnboardingInTransaction(database, configuration, request));
}
