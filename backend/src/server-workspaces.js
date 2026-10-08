/**
 * The server workspace store (Phase 23).
 *
 * A workspace is an **account-owned administrative object** — a name, a slug, a description, and an owner — not a
 * Minecraft runtime connection. Nothing in this phase stores a Minecraft address, a bridge secret, a pairing token, or
 * any credential of any kind: pairing a CraftMind workspace with a Minecraft server remains the job of the existing
 * bridge architecture, and a workspace can exist with nothing attached to it at all.
 *
 * Ownership is structural and immutable. Creating a workspace writes exactly one member row, the owner's, in the same
 * transaction as the workspace itself; the database refuses to update or delete that row and refuses a second owner row
 * (see the migration's `server_members_*` triggers). There is deliberately **no ownership transfer**, no client-supplied
 * owner field, and no developer tool that silently reassigns one — a transfer system needs its own authorization design,
 * and inventing one here would be exactly the account-hijacking primitive this phase must not ship.
 *
 * Reads are owner-scoped. A workspace is not publicly listed: a non-member receives the same typed not-found failure as
 * a caller asking for a slug that never existed, so the endpoint cannot be used to enumerate other accounts'
 * infrastructure. There is no public server directory because none is needed yet, and creating one would publish
 * customer infrastructure names for no product reason.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { newServerId, newServerMemberId } from "./ids.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";
import {
  ENTITLEMENT,
  enforceEntitlement,
  getAccountEntitlementsInTransaction,
  hasEntitlement,
  requireAccount,
} from "./entitlements.js";
import {
  SERVER_CAPABILITY_REGISTRY,
  SERVER_FIELD_LIMITS,
  SERVER_LIMIT_STATE,
  SERVER_ROLE,
  SERVER_STATUS,
  isServerStatus,
  serverRoleDefinition,
  serverStatusDefinition,
  toOwnerServerWorkspace,
} from "./server-catalog.js";
import { RESOURCE_KIND } from "./ownership.js";
import { validateBoundedText, validateSlugInput } from "./text-fields.js";
import { runTransaction } from "./transactions.js";

function nowIso(nowMillis = Date.now()) {
  return new Date(nowMillis).toISOString();
}

function requireServerEntitlement(database, configuration, userId, { now = Date.now() } = {}) {
  const view = getAccountEntitlementsInTransaction(database, configuration, userId, { now, includeCredits: false });
  try {
    enforceEntitlement(database, view, ENTITLEMENT.SERVER_TOOLS, { now, targetUserId: userId });
  } catch (error) {
    if (error instanceof AccountApiError && error.code === ErrorCode.ENTITLEMENT_REQUIRED) {
      throw new AccountApiError(ErrorCode.SERVER_ENTITLEMENT_REQUIRED);
    }
    throw error;
  }
  return view;
}

export function serverWorkspaceRowBySlug(database, slug) {
  return database.prepare("SELECT * FROM server_workspaces WHERE slug = ?").get(slug) ?? null;
}

export function serverWorkspaceRowById(database, serverId) {
  return database.prepare("SELECT * FROM server_workspaces WHERE server_id = ?").get(serverId) ?? null;
}

export function serverMemberRow(database, serverId, userId) {
  return database
    .prepare("SELECT * FROM server_members WHERE server_id = ? AND user_id = ?")
    .get(serverId, userId) ?? null;
}

/** Every workspace the account belongs to, newest first, with the role that decided its access. */
export function listServerWorkspacesInTransaction(database, userId) {
  return database.prepare(
    `SELECT w.*, m.role, m.created_at AS member_since
       FROM server_members m
       JOIN server_workspaces w ON w.server_id = m.server_id
      WHERE m.user_id = ?
      ORDER BY w.created_at DESC, w.server_id DESC`,
  ).all(userId).map((row) => toOwnerServerWorkspace(row, { role: row.role, memberSince: row.member_since }));
}

export function countServerWorkspacesOwnedBy(database, userId) {
  return database
    .prepare("SELECT COUNT(*) AS count FROM server_workspaces WHERE owner_user_id = ?")
    .get(userId).count;
}

/**
 * Membership resolution: the reusable "which role does this account hold on this workspace?" question.
 *
 * Two different refusals, on purpose:
 *   * **not a member at all** → `SERVER_NOT_FOUND`, identical to a slug that does not exist, so a probe learns nothing;
 *   * **a member with an insufficient role** → `SERVER_ACCESS_DENIED`, because the caller demonstrably knows the
 *     workspace exists (they are on it) and needs to be told the operation is above their role.
 */
export function authorizeServerAccessInTransaction(database, {
  serverId,
  actorUserId,
  workspace = null,
  minimumRole = SERVER_ROLE.OWNER,
  auditDenial = true,
  now = Date.now(),
}) {
  const row = workspace ?? serverWorkspaceRowById(database, serverId);
  if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
  const member = serverMemberRow(database, row.server_id, actorUserId);
  if (!member) {
    if (auditDenial) {
      appendAuditRecord(database, {
        actorKind: AUDIT_ACTOR_KIND.SYSTEM,
        actionType: "SERVER_ACCESS_DENIED",
        targetUserId: typeof actorUserId === "string" ? actorUserId : null,
        outcome: "DENIED",
        metadata: { resourceKind: RESOURCE_KIND.SERVER_WORKSPACE, reason: "NOT_A_MEMBER" },
        occurredAt: nowIso(now),
      });
    }
    throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
  }
  const required = serverRoleDefinition(minimumRole)?.rank ?? 3;
  const held = serverRoleDefinition(member.role)?.rank ?? 0;
  if (held < required) {
    if (auditDenial) {
      appendAuditRecord(database, {
        actorKind: AUDIT_ACTOR_KIND.SYSTEM,
        actionType: "SERVER_ACCESS_DENIED",
        targetUserId: actorUserId,
        outcome: "DENIED",
        metadata: { resourceKind: RESOURCE_KIND.SERVER_WORKSPACE, role: member.role, requiredRole: minimumRole },
        occurredAt: nowIso(now),
      });
    }
    throw new AccountApiError(ErrorCode.SERVER_ACCESS_DENIED);
  }
  return Object.freeze({ workspace: row, member, role: member.role });
}

/**
 * Creates a workspace and its immutable owner row. The owner comes from the authenticated session — there is no owner
 * field in any request body — and the account's operational bound is enforced before the insert.
 */
export function createServerWorkspaceInTransaction(database, configuration, {
  userId,
  slug,
  displayName,
  description,
  now = Date.now(),
}) {
  const timestamp = nowIso(now);
  requireAccount(database, userId);
  requireServerEntitlement(database, configuration, userId, { now });

  const normalizedSlug = validateSlugInput(slug, { reservedCode: ErrorCode.SERVER_SLUG_RESERVED });
  const name = validateBoundedText(displayName, { ...SERVER_FIELD_LIMITS.displayName });
  const text = description === null || description === undefined
    ? ""
    : validateBoundedText(description, { ...SERVER_FIELD_LIMITS.description, allowNewlines: true, allowEmpty: true });

  const owned = countServerWorkspacesOwnedBy(database, userId);
  const maximum = configuration?.serverWorkspaces?.maximumPerAccount ?? 3;
  if (owned >= maximum) throw new AccountApiError(ErrorCode.SERVER_LIMIT_REACHED);
  if (serverWorkspaceRowBySlug(database, normalizedSlug)) throw new AccountApiError(ErrorCode.SERVER_SLUG_UNAVAILABLE);

  const serverId = newServerId();
  try {
    database.prepare(
      `INSERT INTO server_workspaces
         (server_id, owner_user_id, slug, display_name, description, status, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(serverId, userId, normalizedSlug, name, text, SERVER_STATUS.ACTIVE, timestamp, timestamp);
    database.prepare(
      `INSERT INTO server_members (member_id, server_id, user_id, role, created_at)
       VALUES (?, ?, ?, ?, ?)`,
    ).run(newServerMemberId(), serverId, userId, SERVER_ROLE.OWNER, timestamp);
  } catch (error) {
    if (/UNIQUE/i.test(String(error?.message ?? ""))) throw new AccountApiError(ErrorCode.SERVER_SLUG_UNAVAILABLE);
    throw error;
  }

  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType: "SERVER_CREATED",
    targetUserId: userId,
    outcome: "SUCCESS",
    metadata: { slug: normalizedSlug, role: SERVER_ROLE.OWNER },
    occurredAt: timestamp,
  });
  return toOwnerServerWorkspace(serverWorkspaceRowById(database, serverId), { role: SERVER_ROLE.OWNER, memberSince: timestamp });
}

/**
 * The owner's own update. The entitlement must still be held, the caller must hold the owner role, and the workspace
 * must still be operational — a suspended workspace refuses protected operations while keeping every record.
 */
export function updateServerWorkspaceInTransaction(database, configuration, {
  userId,
  slug,
  displayName,
  description,
  now = Date.now(),
}) {
  const normalizedSlug = validateSlugInput(slug, { reservedCode: ErrorCode.SERVER_SLUG_RESERVED });
  const row = serverWorkspaceRowBySlug(database, normalizedSlug);
  if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
  const access = authorizeServerAccessInTransaction(database, {
    serverId: row.server_id, actorUserId: userId, workspace: row, minimumRole: SERVER_ROLE.OWNER, now,
  });
  requireServerEntitlement(database, configuration, userId, { now });
  if (row.status !== SERVER_STATUS.ACTIVE) throw new AccountApiError(ErrorCode.SERVER_SUSPENDED);

  const patch = {};
  if (displayName !== undefined) patch.display_name = validateBoundedText(displayName, { ...SERVER_FIELD_LIMITS.displayName });
  if (description !== undefined) {
    patch.description = description === null
      ? ""
      : validateBoundedText(description, { ...SERVER_FIELD_LIMITS.description, allowNewlines: true, allowEmpty: true });
  }
  if (Object.keys(patch).length === 0) throw new AccountApiError(ErrorCode.INVALID_REQUEST);

  const timestamp = nowIso(now);
  const assignments = Object.keys(patch).map((column) => `${column} = ?`).join(", ");
  database.prepare(`UPDATE server_workspaces SET ${assignments}, updated_at = ? WHERE server_id = ?`)
    .run(...Object.values(patch), timestamp, row.server_id);
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType: "SERVER_UPDATED",
    targetUserId: userId,
    outcome: "SUCCESS",
    metadata: { slug: row.slug, fields: Object.keys(patch).sort(), role: access.role },
    occurredAt: timestamp,
  });
  return toOwnerServerWorkspace(serverWorkspaceRowById(database, row.server_id), {
    role: access.role, memberSince: access.member.created_at,
  });
}

/** Owner-scoped read. A non-member gets the not-found failure, whether or not the slug is taken. */
export function readServerWorkspaceInTransaction(database, { userId, slug, now = Date.now() }) {
  const normalizedSlug = validateSlugInput(slug, { reservedCode: ErrorCode.SERVER_SLUG_RESERVED });
  const row = serverWorkspaceRowBySlug(database, normalizedSlug);
  if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
  const access = authorizeServerAccessInTransaction(database, {
    serverId: row.server_id, actorUserId: userId, workspace: row, minimumRole: SERVER_ROLE.MEMBER, now,
  });
  return toOwnerServerWorkspace(row, { role: access.role, memberSince: access.member.created_at });
}

/** Developer-controlled status change: suspend, restore, or archive. Never reachable from an account route. */
export function applyServerStatusInTransaction(database, configuration, {
  serverId,
  status,
  reason,
  actorDeveloperId,
  now = Date.now(),
}) {
  if (!isServerStatus(status)) throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  const row = serverWorkspaceRowById(database, serverId);
  if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
  if (row.status === status) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_CONFLICT, "That workspace status is already in effect.");
  const timestamp = nowIso(now);
  database.prepare("UPDATE server_workspaces SET status = ?, updated_at = ? WHERE server_id = ?")
    .run(status, timestamp, serverId);
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.DEVELOPER,
    actorDeveloperId: actorDeveloperId ?? null,
    actionType: "SERVER_STATUS_CHANGED",
    targetUserId: row.owner_user_id,
    outcome: "SUCCESS",
    metadata: { from: row.status, to: status, reason },
    occurredAt: timestamp,
  });
  return toOwnerServerWorkspace(serverWorkspaceRowById(database, serverId), { role: SERVER_ROLE.OWNER });
}

/**
 * The capability projection for one account: what it may do with server workspaces right now. `FUTURE` capabilities
 * are never reported as available, so no interface can present build management, analytics, or seats as working.
 */
export function serverCapabilitiesFor(entitlementView, workspaces) {
  const entitled = hasEntitlement(entitlementView, ENTITLEMENT.SERVER_TOOLS);
  const operational = workspaces.some((workspace) => workspace.operational);
  return Object.freeze(SERVER_CAPABILITY_REGISTRY.map((definition) => {
    if (definition.state === "FUTURE") {
      return Object.freeze({
        key: definition.key, label: definition.label, state: "FUTURE", available: false,
        reason: definition.unavailableReason,
      });
    }
    if (!entitled) {
      return Object.freeze({
        key: definition.key, label: definition.label, state: "AVAILABLE", available: false,
        reason: "The Server entitlement is required. No plan is purchasable in this phase.",
      });
    }
    if (definition.key === "SERVER_WORKSPACE") {
      return Object.freeze({ key: definition.key, label: definition.label, state: "AVAILABLE", available: true, reason: null });
    }
    if (!operational) {
      return Object.freeze({
        key: definition.key, label: definition.label, state: "AVAILABLE", available: false,
        reason: workspaces.length === 0
          ? "This account owns no server workspace yet."
          : "No workspace is currently active, so management operations are refused.",
      });
    }
    return Object.freeze({ key: definition.key, label: definition.label, state: "AVAILABLE", available: true, reason: null });
  }));
}

export function serverEligibilityInTransaction(database, configuration, userId, { now = Date.now() } = {}) {
  const user = requireAccount(database, userId);
  const entitlementView = getAccountEntitlementsInTransaction(database, configuration, userId, {
    now,
    includeCredits: false,
  });
  const workspaces = listServerWorkspacesInTransaction(database, userId);
  const entitled = hasEntitlement(entitlementView, ENTITLEMENT.SERVER_TOOLS);
  const maximum = configuration?.serverWorkspaces?.maximumPerAccount ?? 3;
  const owned = countServerWorkspacesOwnedBy(database, userId);
  const reasons = [];
  if (user.status !== "ACTIVE") reasons.push("The account is not active.");
  if (!entitled) reasons.push("The account does not hold the Server entitlement.");
  return Object.freeze({
    eligible: reasons.length === 0,
    reasons: Object.freeze(reasons),
    account: Object.freeze({ status: user.status }),
    membership: Object.freeze({
      plan: entitlementView.membership.plan,
      status: entitlementView.membership.status,
    }),
    entitlement: Object.freeze({ key: ENTITLEMENT.SERVER_TOOLS, granted: entitled }),
    role: SERVER_ROLE.OWNER,
    ownerRole: serverRoleDefinition(SERVER_ROLE.OWNER),
    registry: Object.freeze({
      roles: Object.freeze([SERVER_ROLE.OWNER]),
      note: "Only the owner row exists in this phase: there is no invitation, seat, or transfer system.",
    }),
    workspaces: Object.freeze({
      count: owned,
      maximum,
      limitState: SERVER_LIMIT_STATE,
    }),
    capabilities: serverCapabilitiesFor(entitlementView, workspaces),
  });
}

/** Atomic wrappers for the account surface; developer tools call the `…InTransaction` forms. */
export function createServerWorkspace(database, configuration, request) {
  return runTransaction(database, () => createServerWorkspaceInTransaction(database, configuration, request));
}

export function updateServerWorkspace(database, configuration, request) {
  return runTransaction(database, () => updateServerWorkspaceInTransaction(database, configuration, request));
}

export function serverEligibility(database, configuration, userId, options = {}) {
  return runTransaction(database, () => serverEligibilityInTransaction(database, configuration, userId, options));
}

export { requireServerEntitlement, serverStatusDefinition };
