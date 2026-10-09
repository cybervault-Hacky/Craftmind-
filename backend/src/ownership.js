/**
 * The reusable ownership boundary (Phase 23).
 *
 * Every account-owned resource in CraftMind — a creator profile now, a marketplace listing in the next phase, a server
 * workspace in this one — is authorized against a relationship the **server** holds, never against a field a client
 * sent. There is deliberately no function anywhere in the service that reads an owner from a request body: a caller
 * supplies the authenticated account id it already resolved from the session, and this module decides.
 *
 * The abstraction is intentionally small, because a large one would be re-implemented per resource. It answers exactly
 * two questions:
 *
 *   * `isResourceOwner(...)` — a pure predicate, for callers that need a boolean (capability checks, listings).
 *   * `authorizeResourceAccess(...)` — the enforcement point, which throws a typed `OWNERSHIP_REQUIRED` and writes the
 *     denial to the one audit log.
 *
 * A denial is an authorization failure, so it is auditable and observable: the resource kind is recorded, and the
 * metadata carries only the relationship that was missing — never the credential or the resource's private content.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";

/** The resource families the ownership boundary knows about. Keys are stable and reused by Phase 24. */
export const RESOURCE_KIND = Object.freeze({
  CREATOR_PROFILE: "CREATOR_PROFILE",
  SERVER_WORKSPACE: "SERVER_WORKSPACE",
  MARKETPLACE_LISTING: "MARKETPLACE_LISTING",
  /** Phase 26: buyer job requests and the proposals on them. */
  BUYER_JOB: "BUYER_JOB",
  JOB_PROPOSAL: "JOB_PROPOSAL",
  /** Phase 27: marketplace orders and their milestones. */
  ORDER: "ORDER",
  ORDER_MILESTONE: "ORDER_MILESTONE",
});

const RESOURCE_KINDS = new Set(Object.values(RESOURCE_KIND));

export function isResourceKind(kind) {
  return RESOURCE_KINDS.has(kind);
}

/**
 * Pure predicate. Both identifiers are opaque server-side values; an unknown, empty, or client-supplied owner value
 * simply is not equal to the authenticated account, so it can never authorize anything.
 */
export function isResourceOwner({ actorUserId, resourceOwnerUserId }) {
  return typeof actorUserId === "string" && actorUserId.length > 0 &&
    typeof resourceOwnerUserId === "string" && actorUserId === resourceOwnerUserId;
}

/**
 * Enforcement. Throws `OWNERSHIP_REQUIRED` (403) and audits the denial, then returns a decision object on success.
 *
 * `denialActionType` must be a registered audit action (`CREATOR_ACCESS_DENIED`, `SERVER_ACCESS_DENIED`, …), so the
 * failure lands in the same append-only log as the action it refused.
 */
export function authorizeResourceAccess(database, {
  resourceKind,
  resourceOwnerUserId,
  actorUserId,
  denialActionType,
  metadata = {},
  now = Date.now(),
}) {
  if (!isResourceKind(resourceKind)) throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  if (!isResourceOwner({ actorUserId, resourceOwnerUserId })) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: denialActionType,
      targetUserId: typeof actorUserId === "string" ? actorUserId : null,
      outcome: "DENIED",
      metadata: { resourceKind, ...metadata },
      occurredAt: new Date(now).toISOString(),
    });
    throw new AccountApiError(ErrorCode.OWNERSHIP_REQUIRED);
  }
  return Object.freeze({ authorized: true, resourceKind, relationship: "OWNER" });
}

/**
 * The boolean form for capability checks and list filters. It never throws and never audits: a list that hides other
 * accounts' resources is not an authorization failure, it is a correctly scoped query.
 */
export function canManageOwnedResource({ actorUserId, resourceOwnerUserId }) {
  return isResourceOwner({ actorUserId, resourceOwnerUserId });
}
