/**
 * The server-workspace vocabulary (Phase 23).
 *
 * A CraftMind server workspace is an **account-owned administrative object**: a name, a slug, a description, an owner,
 * and a status. It is deliberately *not* a Minecraft runtime connection. Pairing a workspace with a Minecraft server
 * stays governed by the existing bridge architecture (Phase 4/5), which owns its own credentials, its own pairing flow,
 * and its own capability negotiation; nothing in this phase stores a Minecraft address, a bridge secret, or a pairing
 * token, and a workspace can exist with no Minecraft server attached at all.
 *
 * The same three boundaries as `creator-catalog.js` apply: status is not membership, a capability is not a service, and
 * a limit we have not decided on is recorded as `NOT_IMPLEMENTED` rather than invented.
 */

import { HANDLE_RULES, RESERVED_HANDLES, isReservedHandle, normalizeHandle } from "./creator-catalog.js";

export const SERVER_STATUS = Object.freeze({
  ACTIVE: "ACTIVE",
  SUSPENDED: "SUSPENDED",
  ARCHIVED: "ARCHIVED",
});

export const SERVER_STATUS_REGISTRY = Object.freeze([
  Object.freeze({
    status: SERVER_STATUS.ACTIVE,
    label: "Active",
    description: "The workspace is operable by its owner.",
    operational: true,
    assignableByTool: true,
  }),
  Object.freeze({
    status: SERVER_STATUS.SUSPENDED,
    label: "Suspended",
    description: "A developer suspended the workspace. Protected operations are refused; every record is preserved.",
    operational: false,
    assignableByTool: true,
  }),
  Object.freeze({
    status: SERVER_STATUS.ARCHIVED,
    label: "Archived",
    description: "The workspace is closed to operation but keeps its history. Nothing is deleted.",
    operational: false,
    assignableByTool: true,
  }),
]);

const SERVER_STATUS_BY_ID = new Map(SERVER_STATUS_REGISTRY.map((entry) => [entry.status, entry]));

export function serverStatusDefinition(status) {
  return SERVER_STATUS_BY_ID.get(status) ?? null;
}

export function isServerStatus(value) {
  return SERVER_STATUS_BY_ID.has(value);
}

export function isOperationalServerStatus(status) {
  return SERVER_STATUS_BY_ID.get(status)?.operational === true;
}

/**
 * Workspace roles.
 *
 * Only `OWNER` is reachable in this phase: creating a workspace writes exactly one member row, and there is no
 * invitation, transfer, or seat system (see the documentation). The wider role model is implemented as authorization
 * logic so a later phase can add members without redefining the boundary — and so no client can ever set a role.
 */
export const SERVER_ROLE = Object.freeze({
  OWNER: "OWNER",
  ADMIN: "ADMIN",
  MEMBER: "MEMBER",
});

export const SERVER_ROLE_REGISTRY = Object.freeze([
  Object.freeze({
    role: SERVER_ROLE.OWNER,
    label: "Owner",
    description: "The account that created the workspace. Immutable: no client request and no tool may change it.",
    rank: 3,
  }),
  Object.freeze({
    role: SERVER_ROLE.ADMIN,
    label: "Administrator",
    description: "Defined for a future team system. No invitation flow exists in this phase, so no such row can be created.",
    rank: 2,
  }),
  Object.freeze({
    role: SERVER_ROLE.MEMBER,
    label: "Member",
    description: "Defined for a future team system. No invitation flow exists in this phase, so no such row can be created.",
    rank: 1,
  }),
]);

const SERVER_ROLE_BY_ID = new Map(SERVER_ROLE_REGISTRY.map((entry) => [entry.role, entry]));

export function serverRoleDefinition(role) {
  return SERVER_ROLE_BY_ID.get(role) ?? null;
}

export function isServerRole(value) {
  return SERVER_ROLE_BY_ID.has(value);
}

/**
 * Typed server capabilities.
 *
 * `SERVER_WORKSPACE` and `SERVER_MANAGEMENT` describe what exists now: an owned workspace record and owner-checked
 * management of it. Build management, analytics, and team seats are `FUTURE` — a workspace has no build list, no
 * metrics source, and no way to add a member, so claiming any of them would be a fabrication.
 */
export const SERVER_CAPABILITY = Object.freeze({
  SERVER_WORKSPACE: "SERVER_WORKSPACE",
  SERVER_MANAGEMENT: "SERVER_MANAGEMENT",
  SERVER_BUILD_MANAGEMENT: "SERVER_BUILD_MANAGEMENT",
  SERVER_ANALYTICS: "SERVER_ANALYTICS",
  SERVER_TEAM: "SERVER_TEAM",
});

export const SERVER_CAPABILITY_REGISTRY = Object.freeze([
  Object.freeze({
    key: SERVER_CAPABILITY.SERVER_WORKSPACE,
    label: "Server workspace",
    state: "AVAILABLE",
    description: "Create and read the server workspaces owned by this account.",
  }),
  Object.freeze({
    key: SERVER_CAPABILITY.SERVER_MANAGEMENT,
    label: "Workspace management",
    state: "AVAILABLE",
    description: "Owner-checked updates to a workspace's own name, description, and archival state.",
  }),
  Object.freeze({
    key: SERVER_CAPABILITY.SERVER_BUILD_MANAGEMENT,
    label: "Workspace build management",
    state: "FUTURE",
    description: "Builds attached to a workspace.",
    unavailableReason: "Builds are not attached to workspaces yet: the build pipeline is account-scoped in this phase.",
  }),
  Object.freeze({
    key: SERVER_CAPABILITY.SERVER_ANALYTICS,
    label: "Workspace analytics",
    state: "FUTURE",
    description: "Usage and build analytics for a workspace.",
    unavailableReason: "No workspace analytics service exists yet, so nothing is collected or reported.",
  }),
  Object.freeze({
    key: SERVER_CAPABILITY.SERVER_TEAM,
    label: "Team seats",
    state: "FUTURE",
    description: "Inviting other accounts to a workspace.",
    unavailableReason: "There is no invitation flow and no seat billing; a workspace has exactly one member: its owner.",
  }),
]);

const SERVER_CAPABILITY_BY_KEY = new Map(SERVER_CAPABILITY_REGISTRY.map((entry) => [entry.key, entry]));

export function serverCapabilityDefinition(key) {
  return SERVER_CAPABILITY_BY_KEY.get(key) ?? null;
}

export function isServerCapability(key) {
  return SERVER_CAPABILITY_BY_KEY.has(key);
}

export const SERVER_FIELD_LIMITS = Object.freeze({
  displayName: Object.freeze({ minimum: 1, maximum: 60 }),
  description: Object.freeze({ minimum: 0, maximum: 600 }),
});

export const SERVER_SLUG_RULES = HANDLE_RULES;
export const RESERVED_SERVER_SLUGS = RESERVED_HANDLES;
export const normalizeServerSlug = normalizeHandle;
export const isReservedServerSlug = isReservedHandle;

/**
 * The marker every response carries beside the configured workspace bound, so an operator reading an API response can
 * see that the number is an operational limit rather than a plan tier.
 */
export const SERVER_LIMIT_STATE = Object.freeze({
  state: "CONFIGURED",
  note: "An operational anti-abuse bound, not a plan entitlement. It is configuration, not code.",
});

/**
 * Workspace limits, centralized.
 *
 * `workspacesPerAccount` is an operational anti-abuse bound, not a plan tier: it is configuration
 * (`SERVER_MAX_WORKSPACES_PER_ACCOUNT`) and it is documented as such. The remaining limits are the existing BuildPlan
 * limits (untouched) or explicitly not implemented — nothing here silently enforces a product policy.
 */
export const SERVER_LIMITS = Object.freeze({
  workspacesPerAccount: Object.freeze({
    state: "CONFIGURED",
    reason: "An operational bound against workspace spam, not a plan entitlement. Configurable per deployment.",
  }),
  membersPerWorkspace: Object.freeze({
    state: "NOT_IMPLEMENTED",
    reason: "Only the owner member row exists; there is no invitation flow, so no member limit is enforced.",
  }),
  buildLimits: Object.freeze({
    state: "DELEGATED",
    reason: "Builds keep the existing BuildPlan limits; a workspace does not add or remove a build limit.",
  }),
  analytics: Object.freeze({
    state: "NOT_IMPLEMENTED",
    reason: "No workspace analytics service exists, so there is no analytics limit to enforce.",
  }),
});

/** Owner view of a workspace row. No internal identifiers beyond the workspace's own public reference. */
export function toOwnerServerWorkspace(row, { role = SERVER_ROLE.OWNER, memberSince = null } = {}) {
  if (!row) return null;
  const definition = serverStatusDefinition(row.status);
  return Object.freeze({
    serverId: row.server_id,
    slug: row.slug,
    displayName: row.display_name,
    description: row.description ?? "",
    status: row.status,
    statusLabel: definition?.label ?? row.status,
    operational: definition?.operational === true,
    role,
    memberSince,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  });
}
