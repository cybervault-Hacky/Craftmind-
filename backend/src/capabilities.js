/**
 * The reusable capability boundary the marketplace phase will consume (Phase 23).
 *
 * Phase 24 builds listings, a catalog, and a purchase-flow foundation. When it does, it must not re-derive who may
 * create content, who may touch an existing resource, or what "publish" means. These four checks are that boundary, and
 * they are deliberately the *only* place the next phase needs to look:
 *
 *   * `canCreateCreatorContent` — may this account create creator-owned content at all? (membership → entitlement)
 *   * `canManageOwnedContent`   — may this account touch that specific owned resource? (ownership, server-side only)
 *   * `canPublishCreatorContent`— may this account publish? Currently a typed "not implemented" state, because
 *                                 marketplace publishing does not exist. It never returns a fake success.
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
 * "May this account publish creator content?"
 *
 * Publishing belongs to the marketplace phase. There is no publish endpoint, no listing table, and no catalog, so this
 * returns a typed not-implemented state. It never reports success, and it never silently performs a partial publish.
 */
export function canPublishCreatorContent(database, configuration, accountId) {
  return runTransaction(database, () => {
    const view = getAccountEntitlementsInTransaction(database, configuration, accountId, { includeCredits: false });
    const profile = creatorProfileRowForUser(database, accountId);
    const entitled = hasEntitlement(view, ENTITLEMENT.CREATOR_TOOLS);
    const capable = entitled && profile?.status === "ACTIVE";
    return decision(CAPABILITY_STATE.NOT_IMPLEMENTED, {
      reasons: ["Marketplace publishing is not implemented: there is no listing, catalog, or publish pipeline yet."],
      metadata: { wouldBeEligible: capable, entitlementGranted: entitled, plan: view.membership.plan },
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
