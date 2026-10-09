/**
 * The reusable capability boundary the marketplace consumes (Phase 23, completed in Phase 25).
 *
 * The listing services must not re-derive who may create content, who may touch an existing resource, or what
 * "publish" means. These checks are that boundary, and they are deliberately the *only* place a caller needs to look:
 *
 *   * `canCreateCreatorContent` — may this account create creator-owned content at all? (membership → entitlement)
 *   * `canManageOwnedContent`   — may this account touch that specific owned resource? (ownership, server-side only)
 *   * `canPublishCreatorContent`— may this account publish a listing? Since Phase 25 this is the *real* gate: the
 *                                 Creator entitlement, an active creator profile, and the accepted creator agreement
 *                                 from seller onboarding. It reports DENIED with honest reasons — never a fake
 *                                 success, and no longer a typed placeholder, because the pipeline now exists.
 *   * `canManageServer`         — may this account manage that specific workspace? (workspace role + entitlement)
 *
 * Each check returns a decision object rather than a bare boolean, so a caller can tell *why* something is refused and
 * an interface can show the honest reason. Nothing here accepts an owner identifier from a request: the ownership
 * inputs are always values the server loaded from its own tables.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { ENTITLEMENT, getAccountEntitlementsInTransaction, hasEntitlement } from "./entitlements.js";
import {
  authorizeServerAccessInTransaction,
  countServerWorkspacesOwnedBy,
  serverWorkspaceRowById,
} from "./server-workspaces.js";
import { creatorCapabilitiesFor, creatorProfileRowForUser } from "./creator-profiles.js";
import { CREATOR_STATUS } from "./creator-catalog.js";
import { SERVER_ROLE, isOperationalServerStatus } from "./server-catalog.js";
import { runTransaction } from "./transactions.js";

export const CAPABILITY_STATE = Object.freeze({
  ALLOWED: "ALLOWED",
  DENIED: "DENIED",
  /** The capability is understood and authorized in principle, but the service behind it does not exist yet. */
  NOT_IMPLEMENTED: "NOT_IMPLEMENTED",
});

function decision(state, { reasons = [], metadata = {} } = {}) {
  return Object.freeze({
    allowed: state === CAPABILITY_STATE.ALLOWED,
    state,
    code: state === CAPABILITY_STATE.NOT_IMPLEMENTED ? ErrorCode.FEATURE_NOT_IMPLEMENTED : null,
    reasons: Object.freeze(reasons),
    ...metadata,
  });
}

/**
 * "May this account create creator-owned content?"
 *
 * Requires: an active account, the Creator entitlement, and an operational creator profile. The profile requirement is
 * what makes identity meaningful — content is created *as* a creator, not merely by a plan holder.
 */
export function canCreateCreatorContent(database, configuration, accountId) {
  return runTransaction(database, () => {
    const user = database.prepare("SELECT status FROM users WHERE user_id = ?").get(accountId);
    if (!user) return decision(CAPABILITY_STATE.DENIED, { reasons: ["No such account."] });
    const view = getAccountEntitlementsInTransaction(database, configuration, accountId, { includeCredits: false });
    const profile = creatorProfileRowForUser(database, accountId);
    const capabilities = creatorCapabilitiesFor(view, profile);
    const manage = capabilities.find((entry) => entry.key === "CREATOR_MANAGE_OWN_CONTENT");
    if (user.status !== "ACTIVE") return decision(CAPABILITY_STATE.DENIED, { reasons: ["The account is not active."] });
    if (!manage?.available) return decision(CAPABILITY_STATE.DENIED, { reasons: [manage?.reason ?? "Not eligible."] });
    return decision(CAPABILITY_STATE.ALLOWED, { metadata: { creatorProfileStatus: profile.status } });
  });
}

/**
 * "May this account manage that specific owned resource?"
 *
 * Ownership only: the resource's owner account was loaded by the server, never supplied by a caller. The account's live
 * entitlement is *reported*, not required — a creator whose plan lapsed keeps ownership of what they already have and
 * the next phase decides, per operation, whether writing requires a live plan.
 */
export function canManageOwnedContent(database, configuration, { actorAccountId, contentOwnerAccountId }) {
  return runTransaction(database, () => {
    const user = database.prepare("SELECT status FROM users WHERE user_id = ?").get(actorAccountId);
    if (!user) return decision(CAPABILITY_STATE.DENIED, { reasons: ["No such account."] });
    if (typeof contentOwnerAccountId !== "string" || contentOwnerAccountId !== actorAccountId) {
      return decision(CAPABILITY_STATE.DENIED, { reasons: ["This account does not own that resource."] });
    }
    const view = getAccountEntitlementsInTransaction(database, configuration, actorAccountId, { includeCredits: false });
    return decision(CAPABILITY_STATE.ALLOWED, {
      metadata: {
        relationship: "OWNER",
        entitlementGranted: hasEntitlement(view, ENTITLEMENT.CREATOR_TOOLS),
        requiresEntitlement: ENTITLEMENT.CREATOR_TOOLS,
        accountStatus: user.status,
      },
    });
  });
}

/**
 * The publish prerequisites as structured facts — the single source behind both `canPublishCreatorContent` and the
 * listing publish endpoint, so the capability read and the write can never disagree.
 *
 * Four independent conditions, each reported separately because they are NOT interchangeable: an active account, the
 * Creator entitlement, an operational creator profile, and the creator agreement recorded by seller onboarding.
 * Verification markers (email, creator verification, Trusted Seller) are deliberately absent: none of them is a
 * publishing requirement, and none is granted as a side effect of publishing.
 */
export function publishPrerequisitesInTransaction(database, configuration, accountId, { now = Date.now() } = {}) {
  const user = database.prepare("SELECT status FROM users WHERE user_id = ?").get(accountId);
  if (!user) {
    return Object.freeze({
      accountActive: false, entitled: false, profileExists: false, profile: null, operational: false,
      agreementRecorded: false, wouldBeEligible: false, publishEligible: false,
      reasons: Object.freeze(["No such account."]), plan: null,
    });
  }
  const view = getAccountEntitlementsInTransaction(database, configuration, accountId, { now, includeCredits: false });
  const profile = creatorProfileRowForUser(database, accountId);
  const entitled = hasEntitlement(view, ENTITLEMENT.CREATOR_TOOLS);
  const agreement = database.prepare("SELECT user_id FROM seller_onboarding WHERE user_id = ?").get(accountId);
  const accountActive = user.status === "ACTIVE";
  const operational = profile?.status === CREATOR_STATUS.ACTIVE;
  const reasons = [];
  if (!accountActive) reasons.push("The account is not active.");
  if (!entitled) reasons.push("The Creator entitlement is required. No plan is purchasable in this phase.");
  if (!profile) reasons.push("No creator profile exists for this account yet.");
  else if (!operational) reasons.push("The creator profile is not active, so protected creator operations are refused.");
  if (!agreement) reasons.push("The creator agreement from seller onboarding must be accepted before publishing.");
  return Object.freeze({
    accountActive,
    entitled,
    profileExists: Boolean(profile),
    profile: profile ? Object.freeze({ creatorId: profile.creator_id, status: profile.status }) : null,
    operational,
    agreementRecorded: Boolean(agreement),
    wouldBeEligible: entitled && operational,
    publishEligible: reasons.length === 0,
    reasons: Object.freeze(reasons),
    plan: view.membership.plan,
  });
}

/**
 * "May this account publish creator content?"
 *
 * Since Phase 25 this is the real gate, evaluated over the same four prerequisites the publish endpoint enforces.
 * A refusal is a DENIED decision with actionable reasons — never a fabricated success and never a blanket
 * "not implemented", because the listing pipeline now exists.
 */
export function canPublishCreatorContent(database, configuration, accountId) {
  return runTransaction(database, () => {
    const prerequisites = publishPrerequisitesInTransaction(database, configuration, accountId);
    return decision(prerequisites.publishEligible ? CAPABILITY_STATE.ALLOWED : CAPABILITY_STATE.DENIED, {
      reasons: prerequisites.reasons,
      metadata: {
        wouldBeEligible: prerequisites.wouldBeEligible,
        entitlementGranted: prerequisites.entitled,
        agreementRecorded: prerequisites.agreementRecorded,
        plan: prerequisites.plan,
      },
    });
  });
}

/**
 * "May this account manage that specific workspace?"
 *
 * Resolves the workspace from the server's own table by id, then applies the workspace role model: managing a workspace
 * requires the owner role, and a non-member receives the uniform not-found refusal (never a hint that the workspace
 * exists). The Server entitlement is reported alongside the role.
 */
export function canManageServer(database, configuration, { actorAccountId, serverId }) {
  return runTransaction(database, () => {
    const row = serverWorkspaceRowById(database, serverId);
    if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
    let access;
    try {
      access = authorizeServerAccessInTransaction(database, {
        serverId: row.server_id, actorUserId: actorAccountId, workspace: row,
        minimumRole: SERVER_ROLE.OWNER, auditDenial: false,
      });
    } catch (error) {
      if (error instanceof AccountApiError &&
          (error.code === ErrorCode.SERVER_ACCESS_DENIED || error.code === ErrorCode.SERVER_NOT_FOUND)) {
        return decision(CAPABILITY_STATE.DENIED, { reasons: ["This account does not hold the owner role on that workspace."] });
      }
      throw error;
    }
    const view = getAccountEntitlementsInTransaction(database, configuration, actorAccountId, { includeCredits: false });
    const entitled = hasEntitlement(view, ENTITLEMENT.SERVER_TOOLS);
    const operational = isOperationalServerStatus(row.status);
    if (!operational) {
      return decision(CAPABILITY_STATE.DENIED, {
        reasons: ["The workspace is not active, so management operations are refused while its records are preserved."],
        metadata: { role: access.role, workspaceStatus: row.status, entitlementGranted: entitled },
      });
    }
    if (!entitled) {
      return decision(CAPABILITY_STATE.DENIED, {
        reasons: ["The Server entitlement is required to manage a workspace."],
        metadata: { role: access.role, workspaceStatus: row.status },
      });
    }
    return decision(CAPABILITY_STATE.ALLOWED, {
      metadata: { role: access.role, workspaceStatus: row.status, ownedWorkspaces: countServerWorkspacesOwnedBy(database, actorAccountId) },
    });
  });
}
