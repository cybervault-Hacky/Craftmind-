/**
 * The creator profile store (Phase 23).
 *
 * This is the module where "membership → entitlement → capability → identity" becomes concrete. A profile can only be
 * created by an authenticated account holding the Creator entitlement, it is stored as its own row keyed to that
 * account, and every mutation is checked against both the membership entitlement (Phase 22) and the profile's own
 * status (this phase). The two are genuinely different questions:
 *
 *   * the entitlement answers "may this account operate a creator profile at all?" — so a lapsed plan stops creation
 *     and updates without deleting anything;
 *   * the profile status answers "is this profile currently allowed to operate?" — so a suspension stops protected
 *     operations even while the Creator plan is still active.
 *
 * Public data is separated from private data by projection, not by convention: the owner view and the public view are
 * two different functions over the same row, and the public one cannot reach the account record at all. Nothing here
 * ever stores HTML, a script URL, or a client-supplied identifier.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { newCreatorId, newCreatorHistoryId } from "./ids.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";
import {
  ENTITLEMENT,
  enforceEntitlement,
  getAccountEntitlementsInTransaction,
  hasEntitlement,
  requireAccount,
} from "./entitlements.js";
import {
  CREATOR_CAPABILITY_REGISTRY,
  CREATOR_FIELD_LIMITS,
  CREATOR_STATUS,
  CREATOR_VERIFICATION,
  creatorCapabilityDefinition,
  creatorStatusDefinition,
  creatorVerificationDefinition,
  isCreatorStatus,
  isCreatorVerification,
  isPubliclyReadableCreatorStatus,
  normalizeHandle,
  toOwnerCreatorProfile,
  toPublicCreatorProfile,
} from "./creator-catalog.js";
import { validateBoundedText, validateSafeReference, validateSlugInput } from "./text-fields.js";
import { RESOURCE_KIND, isResourceOwner } from "./ownership.js";
import { runTransaction } from "./transactions.js";

/** `enforceEntitlement` writes the audit record; this maps its generic code to the creator-specific one. */
function requireCreatorEntitlement(database, configuration, userId, { now = Date.now() } = {}) {
  const view = getAccountEntitlementsInTransaction(database, configuration, userId, { now, includeCredits: false });
  try {
    enforceEntitlement(database, view, ENTITLEMENT.CREATOR_TOOLS, { now, targetUserId: userId });
  } catch (error) {
    if (error instanceof AccountApiError && error.code === ErrorCode.ENTITLEMENT_REQUIRED) {
      throw new AccountApiError(ErrorCode.CREATOR_ENTITLEMENT_REQUIRED);
    }
    throw error;
  }
  return view;
}

/**
 * The write policy for a creator profile — account active, at most one profile, entitlement held, profile operational —
 * evaluated in **one** place, inside the write transaction, so every refusal observes the same state it then denies on.
 *
 * A refusal here appends its audit record (the entitlement engine writes `ENTITLEMENT_DENIED`, a suspended profile
 * writes `CREATOR_ACCESS_DENIED`) and then throws. `runTransaction` is what keeps those records: it writes them after
 * the rollback, so a denied request remains attributable even though its change never happened.
 */
function evaluateCreatorWrite(database, configuration, userId, { now = Date.now(), existingProfile = false } = {}) {
  requireAccount(database, userId);
  const row = creatorProfileRowForUser(database, userId);
  if (existingProfile && !row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
  if (!existingProfile && row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_EXISTS);
  const view = requireCreatorEntitlement(database, configuration, userId, { now });
  if (row && row.status !== CREATOR_STATUS.ACTIVE) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "CREATOR_ACCESS_DENIED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { resourceKind: RESOURCE_KIND.CREATOR_PROFILE, profileStatus: row.status },
      occurredAt: nowIso(now),
    });
    throw new AccountApiError(ErrorCode.CREATOR_PROFILE_SUSPENDED);
  }
  return { row, view };
}

/**
 * Field validation, layered on the shared text rules so a creator field and a workspace field cannot diverge. Each
 * wrapper throws the generic typed invalid-request failure; the domain refusals (reserved handle, duplicate profile)
 * have their own codes and are raised by the operations below.
 */
function validateDisplayName(value) {
  return validateBoundedText(value, { ...CREATOR_FIELD_LIMITS.displayName });
}

function validateBio(value) {
  if (value === null || value === undefined) return "";
  return validateBoundedText(value, { ...CREATOR_FIELD_LIMITS.bio, allowNewlines: true, allowEmpty: true });
}

function validateCategory(value) {
  if (value === null || value === undefined) return null;
  if (typeof value !== "string" || !CREATOR_FIELD_LIMITS.category.values.includes(value)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  return value;
}

function validateAvatarReference(value) {
  return validateSafeReference(value, { maximum: CREATOR_FIELD_LIMITS.avatarReference.maximum });
}

function validateHandleInput(value) {
  return validateSlugInput(value, { reservedCode: ErrorCode.CREATOR_HANDLE_RESERVED });
}

function nowIso(nowMillis = Date.now()) {
  return new Date(nowMillis).toISOString();
}

export function creatorProfileRowForUser(database, userId) {
  return database.prepare("SELECT * FROM creator_profiles WHERE user_id = ?").get(userId) ?? null;
}

export function creatorProfileRowByHandle(database, handle) {
  return database.prepare("SELECT * FROM creator_profiles WHERE handle = ?").get(handle) ?? null;
}

export function creatorProfileRowById(database, creatorId) {
  return database.prepare("SELECT * FROM creator_profiles WHERE creator_id = ?").get(creatorId) ?? null;
}

/**
 * Append-only domain history. Membership transitions have their own table (Phase 22); this one records what happened to
 * a *profile* — a status move, a verification move — so a moderation decision can always be reconstructed without
 * touching the profile row's own timestamps.
 */
function recordCreatorHistory(database, {
  creatorId,
  userId,
  changeType,
  fromValue,
  toValue,
  reason,
  actorKind,
  actorDeveloperId = null,
  occurredAt,
}) {
  database.prepare(
    `INSERT INTO creator_status_history
       (history_id, creator_id, user_id, change_type, from_value, to_value, reason, actor_kind, actor_developer_id, occurred_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    newCreatorHistoryId(),
    creatorId,
    userId,
    changeType,
    fromValue ?? null,
    toValue,
    reason,
    actorKind,
    actorDeveloperId,
    occurredAt,
  );
}

/** Recorded for a creator *profile* status change. Kept beside the writes that use it so the naming cannot drift. */
const CREATOR_CHANGE = Object.freeze({ STATUS: "STATUS", VERIFICATION: "VERIFICATION" });

/**
 * Creates the one profile an account may own. The caller has already authenticated the account; the entitlement is
 * enforced here so a future caller cannot forget it, and the `UNIQUE(user_id)` constraint makes "one profile per
 * account" a database rule rather than a convention.
 */
export function createCreatorProfileInTransaction(database, configuration, {
  userId,
  handle,
  displayName,
  bio,
  category,
  avatarReference,
  now = Date.now(),
}) {
  const timestamp = nowIso(now);
  evaluateCreatorWrite(database, configuration, userId, { now });

  const normalizedHandle = validateHandleInput(handle);
  const name = validateDisplayName(displayName);
  const description = validateBio(bio);
  const primaryCategory = validateCategory(category);
  const avatar = validateAvatarReference(avatarReference);

  if (creatorProfileRowByHandle(database, normalizedHandle)) {
    throw new AccountApiError(ErrorCode.CREATOR_HANDLE_UNAVAILABLE);
  }

  const creatorId = newCreatorId();
  try {
    database.prepare(
      `INSERT INTO creator_profiles
         (creator_id, user_id, handle, display_name, bio, category, avatar_reference, status, verification_status,
          status_changed_at, verified_at, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)`,
    ).run(
      creatorId, userId, normalizedHandle, name, description, primaryCategory, avatar,
      CREATOR_STATUS.ACTIVE, CREATOR_VERIFICATION.UNVERIFIED, timestamp, timestamp, timestamp,
    );
  } catch (error) {
    // Two accounts may race for the same handle. The constraint decides; the loser gets the same typed refusal it would
    // have received from the check above, never a raw SQL error.
    if (/UNIQUE/i.test(String(error?.message ?? ""))) throw new AccountApiError(ErrorCode.CREATOR_HANDLE_UNAVAILABLE);
    throw error;
  }

  recordCreatorHistory(database, {
    creatorId, userId, changeType: CREATOR_CHANGE.STATUS, fromValue: null, toValue: CREATOR_STATUS.ACTIVE,
    reason: "Creator profile created", actorKind: AUDIT_ACTOR_KIND.SYSTEM, occurredAt: timestamp,
  });
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType: "CREATOR_PROFILE_CREATED",
    targetUserId: userId,
    outcome: "SUCCESS",
    metadata: { handle: normalizedHandle, category: primaryCategory, verification: CREATOR_VERIFICATION.UNVERIFIED },
    occurredAt: timestamp,
  });
  return creatorProfileRowForUser(database, userId);
}

/**
 * The owner's own update. Ownership is structural — the account id comes from the session, so there is no owner field
 * to forge — and the two operational checks are explicit: the Creator entitlement must still be held, and the profile
 * must still be ACTIVE. A suspended profile is refused with its own typed error, which tells the owner the profile is
 * suspended without telling them why (the reason lives in the audit log).
 */
export function updateCreatorProfileInTransaction(database, configuration, {
  userId,
  displayName,
  bio,
  category,
  avatarReference,
  now = Date.now(),
}) {
  const { row: existing } = evaluateCreatorWrite(database, configuration, userId, { now, existingProfile: true });

  // A patch: only the fields the caller actually supplied are revalidated and written, so an update cannot silently
  // clear a field the client never mentioned.
  const patch = {};
  if (displayName !== undefined) patch.display_name = validateDisplayName(displayName);
  if (bio !== undefined) patch.bio = validateBio(bio);
  if (category !== undefined) patch.category = validateCategory(category);
  if (avatarReference !== undefined) patch.avatar_reference = validateAvatarReference(avatarReference);
  if (Object.keys(patch).length === 0) throw new AccountApiError(ErrorCode.INVALID_REQUEST);

  const timestamp = nowIso(now);
  const assignments = Object.keys(patch).map((column) => `${column} = ?`).join(", ");
  database.prepare(`UPDATE creator_profiles SET ${assignments}, updated_at = ? WHERE creator_id = ?`)
    .run(...Object.values(patch), timestamp, existing.creator_id);
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType: "CREATOR_PROFILE_UPDATED",
    targetUserId: userId,
    outcome: "SUCCESS",
    metadata: { handle: existing.handle, fields: Object.keys(patch).sort() },
    occurredAt: timestamp,
  });
  return creatorProfileRowForUser(database, userId);
}

/**
 * Developer-controlled status change (suspend, restore, disable, or archive-equivalent). It is shared by the developer
 * tools and never reachable from an account route: no client request may change a creator's status, and the owner role
 * is not involved at all here.
 */
export function applyCreatorStatusInTransaction(database, configuration, {
  creatorId,
  status,
  reason,
  actorDeveloperId,
  now = Date.now(),
}) {
  if (!isCreatorStatus(status)) throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  const definition = creatorStatusDefinition(status);
  if (definition?.assignableByTool !== true) throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  const row = creatorProfileRowById(database, creatorId);
  if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
  if (row.status === status) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_CONFLICT, "That creator status is already in effect.");

  const timestamp = nowIso(now);
  database.prepare("UPDATE creator_profiles SET status = ?, status_changed_at = ?, updated_at = ? WHERE creator_id = ?")
    .run(status, timestamp, timestamp, creatorId);
  recordCreatorHistory(database, {
    creatorId, userId: row.user_id, changeType: CREATOR_CHANGE.STATUS, fromValue: row.status, toValue: status,
    reason, actorKind: AUDIT_ACTOR_KIND.DEVELOPER, actorDeveloperId: actorDeveloperId ?? null, occurredAt: timestamp,
  });
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.DEVELOPER,
    actorDeveloperId: actorDeveloperId ?? null,
    actionType: "CREATOR_STATUS_CHANGED",
    targetUserId: row.user_id,
    outcome: "SUCCESS",
    metadata: { from: row.status, to: status, reason },
    occurredAt: timestamp,
  });
  return creatorProfileRowById(database, creatorId);
}

/**
 * Developer-controlled verification marker. This is an internal CraftMind state — not identity, not payment, and not a
 * government or biometric check — and the only way it moves is this audited path.
 */
export function applyCreatorVerificationInTransaction(database, configuration, {
  creatorId,
  verification,
  reason,
  actorDeveloperId,
  now = Date.now(),
}) {
  if (!isCreatorVerification(verification) || verification === CREATOR_VERIFICATION.UNVERIFIED) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  const row = creatorProfileRowById(database, creatorId);
  if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
  if (row.verification_status === verification) {
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_CONFLICT, "That verification state is already in effect.");
  }
  const timestamp = nowIso(now);
  const verifiedAt = verification === CREATOR_VERIFICATION.VERIFIED ? timestamp : null;
  database.prepare("UPDATE creator_profiles SET verification_status = ?, verified_at = ?, updated_at = ? WHERE creator_id = ?")
    .run(verification, verifiedAt, timestamp, creatorId);
  recordCreatorHistory(database, {
    creatorId, userId: row.user_id, changeType: CREATOR_CHANGE.VERIFICATION, fromValue: row.verification_status,
    toValue: verification, reason, actorKind: AUDIT_ACTOR_KIND.DEVELOPER,
    actorDeveloperId: actorDeveloperId ?? null, occurredAt: timestamp,
  });
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.DEVELOPER,
    actorDeveloperId: actorDeveloperId ?? null,
    actionType: "CREATOR_VERIFICATION_CHANGED",
    targetUserId: row.user_id,
    outcome: "SUCCESS",
    metadata: { from: row.verification_status, to: verification, reason },
    occurredAt: timestamp,
  });
  return creatorProfileRowById(database, creatorId);
}

/** A bounded developer listing: newest first, filterable by status, never returning private account fields. */
export function listCreatorProfilesInTransaction(database, { limit = 25, status = null, verification = null } = {}) {
  if (status !== null && !isCreatorStatus(status)) throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  if (verification !== null && !isCreatorVerification(verification)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return database.prepare(
    `SELECT * FROM creator_profiles
      WHERE (? IS NULL OR status = ?) AND (? IS NULL OR verification_status = ?)
      ORDER BY created_at DESC, creator_id DESC LIMIT ?`,
  ).all(status, status, verification, verification, limit).map(toOwnerCreatorProfile);
}

/**
 * The capability projection: what this account may do through its creator identity, right now, and — when it may not —
 * the reason a handler or an interface is expected to show. A `FUTURE` capability is never reported as available.
 */
export function creatorCapabilitiesFor(entitlementView, profile) {
  const entitled = hasEntitlement(entitlementView, ENTITLEMENT.CREATOR_TOOLS);
  const status = profile?.status ?? null;
  const operational = status === CREATOR_STATUS.ACTIVE;
  return Object.freeze(CREATOR_CAPABILITY_REGISTRY.map((definition) => {
    if (definition.state === "FUTURE") {
      return Object.freeze({
        key: definition.key, label: definition.label, state: "FUTURE", available: false,
        reason: definition.unavailableReason,
      });
    }
    if (!entitled) {
      return Object.freeze({
        key: definition.key, label: definition.label, state: "AVAILABLE", available: false,
        reason: "The Creator entitlement is required. No plan is purchasable in this phase.",
      });
    }
    if (definition.key === "CREATOR_DASHBOARD") {
      return Object.freeze({ key: definition.key, label: definition.label, state: "AVAILABLE", available: true, reason: null });
    }
    if (!profile) {
      return Object.freeze({
        key: definition.key, label: definition.label, state: "AVAILABLE", available: false,
        reason: "No creator profile exists for this account yet.",
      });
    }
    if (!operational) {
      return Object.freeze({
        key: definition.key, label: definition.label, state: "AVAILABLE", available: false,
        reason: "The creator profile is not active, so protected creator operations are refused.",
      });
    }
    return Object.freeze({ key: definition.key, label: definition.label, state: "AVAILABLE", available: true, reason: null });
  }));
}

/**
 * The one eligibility read. It evaluates the account, the membership entitlement, the profile status, and the
 * verification marker — and returns the capability list rather than a bare boolean, so callers never re-derive a rule.
 * Callers must not scatter plan comparisons through route handlers; this is the function they call instead.
 */
export function creatorEligibilityInTransaction(database, configuration, userId, { now = Date.now() } = {}) {
  const user = requireAccount(database, userId);
  const entitlementView = getAccountEntitlementsInTransaction(database, configuration, userId, {
    now,
    includeCredits: false,
  });
  const profile = creatorProfileRowForUser(database, userId);
  const entitled = hasEntitlement(entitlementView, ENTITLEMENT.CREATOR_TOOLS);
  const capabilities = creatorCapabilitiesFor(entitlementView, profile);
  const statusDefinition = profile ? creatorStatusDefinition(profile.status) : null;
  const reasons = [];
  if (user.status !== "ACTIVE") reasons.push("The account is not active.");
  if (!entitled) reasons.push("The account does not hold the Creator entitlement.");
  if (profile && profile.status !== CREATOR_STATUS.ACTIVE) reasons.push("The creator profile is not active.");
  return Object.freeze({
    eligible: reasons.length === 0,
    reasons: Object.freeze(reasons),
    account: Object.freeze({ status: user.status }),
    membership: Object.freeze({
      plan: entitlementView.membership.plan,
      status: entitlementView.membership.status,
    }),
    entitlement: Object.freeze({ key: ENTITLEMENT.CREATOR_TOOLS, granted: entitled }),
    profile: Object.freeze({
      exists: Boolean(profile),
      status: profile?.status ?? null,
      statusLabel: statusDefinition?.label ?? null,
      verification: profile?.verification_status ?? null,
      verificationLabel: profile ? creatorVerificationDefinition(profile.verification_status)?.label ?? null : null,
    }),
    capabilities,
  });
}

/**
 * The public read. Only an ACTIVE profile is publicly visible, and the public projection carries no account, email,
 * membership, credit, or suspension information — a suspended profile is indistinguishable from one that never
 * existed, which is what stops a public read from becoming a moderation-status oracle.
 */
export function publicCreatorProfileInTransaction(database, handleInput) {
  const normalized = normalizeHandle(handleInput);
  if (!normalized.ok) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
  if (normalized.code === "RESERVED") throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
  const row = creatorProfileRowByHandle(database, normalized.handle);
  if (!row || !isPubliclyReadableCreatorStatus(row.status)) {
    throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
  }
  return toPublicCreatorProfile(row);
}

/** Atomic wrappers. The account surface uses these; the developer tools call the `…InTransaction` forms. */
export function createCreatorProfile(database, configuration, request) {
  return runTransaction(database, () => createCreatorProfileInTransaction(database, configuration, request));
}

export function updateCreatorProfile(database, configuration, request) {
  return runTransaction(database, () => updateCreatorProfileInTransaction(database, configuration, request));
}

export function creatorEligibility(database, configuration, userId, options = {}) {
  return runTransaction(database, () => creatorEligibilityInTransaction(database, configuration, userId, options));
}

export {
  requireCreatorEntitlement,
  validateAvatarReference,
  validateBio,
  validateCategory,
  validateDisplayName,
  validateHandleInput,
  CREATOR_CHANGE,
};

export { creatorCapabilityDefinition, isResourceOwner };
