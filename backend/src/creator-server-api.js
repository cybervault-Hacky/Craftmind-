/**
 * The authenticated creator and server surface (Phase 23).
 *
 * This module is the only place the HTTP layer talks to. Like Phase 22's `account-membership.js`, every function takes
 * a **session token** and resolves the account from it — never from the request body — so there is no route that can
 * act on another account's creator profile or workspace. Request bodies supply content only (a name, a description, a
 * slug); they can never supply an owner, a status, a plan, a role, or a verification state, because no code path reads
 * those from input.
 *
 * Responses are narrow by construction: the owner view of a profile, the public view of a profile, a workspace summary,
 * and capability lists. No session identifier, token, email, membership record, credit balance, audit detail, or
 * internal account identifier appears in any of them.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { requireAccountIdForToken } from "./accounts.js";
import {
  creatorEligibilityInTransaction,
  creatorProfileRowForUser,
  createCreatorProfileInTransaction,
  publicCreatorProfileInTransaction,
  updateCreatorProfileInTransaction,
} from "./creator-profiles.js";
import { toOwnerCreatorProfile } from "./creator-catalog.js";
import {
  createServerWorkspaceInTransaction,
  listServerWorkspacesInTransaction,
  readServerWorkspaceInTransaction,
  updateServerWorkspaceInTransaction,
} from "./server-workspaces.js";
import { SERVER_LIMIT_STATE } from "./server-catalog.js";
import { runTransaction } from "./transactions.js";

/**
 * Strict body reading. Unknown keys are refused rather than ignored: a payload that mentions `ownerId`, `status`,
 * `role`, `plan`, or `verification` is a request whose author misunderstands the boundary, and the honest answer is a
 * typed refusal — not a silent success that ignores the field and then reports a different resource than they expected.
 */
export function strictBody(body, { required = [], optional = [] } = {}) {
  if (body === null || typeof body !== "object" || Array.isArray(body)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  const keys = Object.keys(body);
  if (keys.some((key) => !required.includes(key) && !optional.includes(key)) ||
      required.some((key) => !Object.hasOwn(body, key))) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  return body;
}

function creatorProfileResult(database, configuration, userId, profile) {
  return Object.freeze({
    profile: toOwnerCreatorProfile(profile),
    eligibility: creatorEligibilityInTransaction(database, configuration, userId),
  });
}

/** The account's own creator profile and capability state. Reading is never gated on the entitlement: a lapsed plan
 *  must still be able to see what it owns, and only *operating* the profile requires the entitlement. */
export function creatorProfileForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => creatorProfileResult(database, configuration, userId, creatorProfileRowForUser(database, userId)));
}

export function creatorEligibilityForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => Object.freeze({
    eligibility: creatorEligibilityInTransaction(database, configuration, userId),
  }));
}

export function createCreatorProfileForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, {
    required: ["handle", "displayName"],
    optional: ["bio", "category", "avatarReference"],
  });
  return runTransaction(database, () => {
    const profile = createCreatorProfileInTransaction(database, configuration, {
      userId,
      handle: payload.handle,
      displayName: payload.displayName,
      bio: payload.bio,
      category: payload.category,
      avatarReference: payload.avatarReference,
    });
    return creatorProfileResult(database, configuration, userId, profile);
  });
}

export function updateCreatorProfileForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { optional: ["displayName", "bio", "category", "avatarReference"] });
  if (Object.keys(payload).length === 0) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  return runTransaction(database, () => {
    const profile = updateCreatorProfileInTransaction(database, configuration, {
      userId,
      displayName: payload.displayName,
      bio: payload.bio,
      category: payload.category,
      avatarReference: payload.avatarReference,
    });
    return creatorProfileResult(database, configuration, userId, profile);
  });
}

/**
 * The public read. No session, no account data, and a uniform not-found failure for anything not publicly readable.
 *
 * `publicCreatorProfileInTransaction` already projects the row onto the public shape; projecting the *result* again
 * would read the first projection's camelCase keys as if they were columns, and the fields it missed would be dropped
 * by `JSON.stringify` — a silently thinner profile, including a verification badge that vanished.
 */
export function publicCreatorProfileForHandle(database, handle) {
  return runTransaction(database, () => Object.freeze({
    profile: publicCreatorProfileInTransaction(database, handle),
  }));
}

export function listServersForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => {
    const servers = listServerWorkspacesInTransaction(database, userId);
    return Object.freeze({
      servers,
      count: servers.length,
      maximum: configuration?.serverWorkspaces?.maximumPerAccount ?? 3,
      limit: SERVER_LIMIT_STATE,
    });
  });
}

export function createServerForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { required: ["slug", "displayName"], optional: ["description"] });
  return runTransaction(database, () => Object.freeze({
    server: createServerWorkspaceInTransaction(database, configuration, {
      userId,
      slug: payload.slug,
      displayName: payload.displayName,
      description: payload.description,
    }),
  }));
}

export function serverForSession(database, configuration, accessToken, slug) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => Object.freeze({
    server: readServerWorkspaceInTransaction(database, { userId, slug }),
  }));
}

export function updateServerForSession(database, configuration, accessToken, slug, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { optional: ["displayName", "description"] });
  if (Object.keys(payload).length === 0) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  return runTransaction(database, () => Object.freeze({
    server: updateServerWorkspaceInTransaction(database, configuration, {
      userId,
      slug,
      displayName: payload.displayName,
      description: payload.description,
    }),
  }));
}

/**
 * The account integration read (§24 of the phase brief).
 *
 * It summarizes the account's own capability state without duplicating Phase 22's source of truth: the membership
 * section carries the plan and status that Phase 22 already owns (the same two fields
 * `GET /account/entitlements` returns), and the creator and server sections carry identity and counts — never a second
 * copy of the entitlement list or the credit balance.
 */
export function accountCapabilitiesForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => {
    const eligibility = creatorEligibilityInTransaction(database, configuration, userId);
    const profile = creatorProfileRowForUser(database, userId);
    const servers = listServerWorkspacesInTransaction(database, userId);
    return Object.freeze({
      membership: Object.freeze({ plan: eligibility.membership.plan, status: eligibility.membership.status }),
      creator: Object.freeze({
        exists: eligibility.profile.exists,
        handle: profile?.handle ?? null,
        status: eligibility.profile.status,
        verification: eligibility.profile.verification,
        entitled: eligibility.entitlement.granted,
        eligible: eligibility.eligible,
      }),
      servers: Object.freeze({
        count: servers.length,
        maximum: configuration?.serverWorkspaces?.maximumPerAccount ?? 3,
      }),
      capabilities: Object.freeze({
        creator: eligibility.capabilities,
      }),
    });
  });
}
