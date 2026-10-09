/**
 * Closed, server-authorized administrative action registry. Nothing here exposes a database handle to a caller or model.
 * State-changing actions are first stored as actor-bound, short-lived, single-use confirmation challenges.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { canonicalizeEmail, developerTokenDigest, digestsMatch, isWellFormedEmail, newGrantId, newToken } from "./ids.js";
import { ACCOUNT_STATUS } from "./db.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";
import { SECURITY_CENTER_TOOLS } from "./security-center-tools.js";
import { assignMembership, getAccountEntitlementsInTransaction } from "./entitlements.js";
import { planCatalog } from "./membership-plans.js";
import {
  applyCreatorStatusInTransaction,
  applyCreatorVerificationInTransaction,
  creatorCapabilitiesFor,
  creatorProfileRowById,
  creatorProfileRowByHandle,
  listCreatorProfilesInTransaction,
} from "./creator-profiles.js";
import {
  applyServerStatusInTransaction,
  listServerWorkspacesInTransaction,
  serverWorkspaceRowBySlug,
} from "./server-workspaces.js";
import { creatorStatusDefinition, creatorVerificationDefinition, normalizeHandle } from "./creator-catalog.js";
import { serverRoleDefinition, serverStatusDefinition, isServerStatus } from "./server-catalog.js";
import {
  creditBalanceInTransaction,
  grantCreditsInTransaction,
  grantPlanAllocation,
  listCreditTransactionsInTransaction,
  reverseCreditGrantInTransaction,
} from "./credits.js";

const GRANT_TYPES = new Set(["BETA_ACCESS", "PREVIEW_ACCESS", "PROMOTIONAL_ACCESS"]);
/** Phase 22 tools are OWNER/ADMIN only: a promotional grant of membership or credits is a high-impact action. */
const MEMBERSHIP_TOOL_ROLES = Object.freeze({
  READ: ["OWNER", "ADMIN", "DEVELOPER"],
  WRITE: ["OWNER", "ADMIN"],
});
const MAX_AUDIT_PAGE_SIZE = 100;
const SECURITY_CENTER_READ_TOOLS = Object.keys(SECURITY_CENTER_TOOLS);
const ALLOWED_ROLES = Object.freeze({
  OWNER: new Set([
    "overview", "inspectUser", "listUserSessions", "revokeUserSessions", "suspendUser", "restoreUser",
    "listEntitlements", "grantEntitlement", "revokeEntitlement", "listAuditLog", "configurationStatus",
    "inspectMembership", "grantMembership", "grantCredits", "reverseCreditGrant", "listCreditTransactions",
    "inspectCreator", "listCreatorProfiles", "verifyCreator", "revokeCreatorVerification", "suspendCreator",
    "restoreCreator", "inspectServer", "listServerWorkspaces", "suspendServer", "restoreServer", "archiveServer",
    ...SECURITY_CENTER_READ_TOOLS,
  ]),
  ADMIN: new Set([
    "overview", "inspectUser", "listUserSessions", "revokeUserSessions", "suspendUser", "restoreUser",
    "listEntitlements", "grantEntitlement", "revokeEntitlement", "listAuditLog",
    "inspectMembership", "grantMembership", "grantCredits", "reverseCreditGrant", "listCreditTransactions",
    "inspectCreator", "listCreatorProfiles", "verifyCreator", "revokeCreatorVerification", "suspendCreator",
    "restoreCreator", "inspectServer", "listServerWorkspaces", "suspendServer", "restoreServer", "archiveServer",
    ...SECURITY_CENTER_READ_TOOLS,
  ]),
  DEVELOPER: new Set([
    "overview", "inspectUser", "listUserSessions", "listEntitlements", "listAuditLog",
    "inspectMembership", "listCreditTransactions",
    "inspectCreator", "listCreatorProfiles", "inspectServer", "listServerWorkspaces",
    ...SECURITY_CENTER_READ_TOOLS,
  ]),
});

function nowIso() {
  return new Date().toISOString();
}

function isExpired(value) {
  const expiry = Date.parse(value);
  return !Number.isFinite(expiry) || expiry <= Date.now();
}

function runTransaction(database, operation) {
  database.exec("BEGIN IMMEDIATE");
  try {
    const result = operation();
    database.exec("COMMIT");
    return result;
  } catch (error) {
    database.exec("ROLLBACK");
    throw error;
  }
}

/**
 * Audit helper for AI-assisted developer turns. The human developer is always recorded as the acting identity; the
 * actor category marks that the produced analysis came from the AI boundary.
 */
export function recordDeveloperAiSecurityAudit(database, actor, outcome, metadata = {}) {
  return audited(database, actor, "developer_ai_security_summary", () => ({
    result: true,
    auditOutcome: outcome,
    auditMetadata: metadata,
    actorKind: AUDIT_ACTOR_KIND.AI,
  }));
}

function safeFailure(error) {
  if (error instanceof AccountApiError) return error;
  return new AccountApiError(ErrorCode.UNKNOWN_ERROR);
}

function requireCurrentActorSession(database, actor) {
  const sessionId = actor?.session?.session_id;
  const presentedDigest = actor?.session?.access_digest;
  if (typeof sessionId !== "string" || typeof presentedDigest !== "string") {
    throw new AccountApiError(ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED);
  }
  const row = database.prepare(
    `SELECT d.role, d.status, s.access_digest, s.access_expires_at, s.revoked_at
       FROM developer_sessions s
       JOIN developer_accounts d ON d.developer_id = s.developer_id
      WHERE s.session_id = ? AND s.developer_id = ?`,
  ).get(sessionId, actor.developer_id);
  const expiry = Date.parse(row?.access_expires_at);
  if (!row || !digestsMatch(row.access_digest, presentedDigest) || row.revoked_at !== null ||
      row.status !== "ACTIVE" || row.role !== actor.role || !Number.isFinite(expiry) || expiry <= Date.now()) {
    throw new AccountApiError(ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED);
  }
}

function audited(database, actor, actionType, operation, { onFailure, actorKind = AUDIT_ACTOR_KIND.DEVELOPER } = {}) {
  let result;
  let failure = null;
  let attemptedTargetUserId = null;
  database.exec("BEGIN IMMEDIATE");
  try {
    database.exec("SAVEPOINT developer_action");
    let operationResult;
    try {
      requireCurrentActorSession(database, actor);
      operationResult = operation((targetUserId) => { attemptedTargetUserId = targetUserId ?? null; });
      database.exec("RELEASE developer_action");
    } catch (error) {
      database.exec("ROLLBACK TO developer_action");
      database.exec("RELEASE developer_action");
      failure = safeFailure(error);
      if (typeof onFailure === "function") onFailure(failure);
    }
    const targetUserId = operationResult?.targetUserId ?? attemptedTargetUserId;
    const outcome = operationResult?.auditOutcome ?? (failure ? (
      failure.code === ErrorCode.DEVELOPER_ACCESS_DENIED ? "DENIED" : "FAILURE"
    ) : "SUCCESS");
    const metadata = failure ? { errorCode: failure.code } : (operationResult?.auditMetadata ?? {});
    appendAuditRecord(database, {
      actorKind: operationResult?.actorKind ?? actorKind,
      actorDeveloperId: actor.developer_id,
      actionType,
      targetUserId: targetUserId ?? null,
      outcome,
      metadata,
      occurredAt: nowIso(),
    });
    database.exec("COMMIT");
    result = operationResult?.result;
  } catch (error) {
    try { database.exec("ROLLBACK"); } catch {}
    // Fail closed: a privileged action is not acknowledged unless its audit write committed with it.
    throw safeFailure(error);
  }
  if (failure) throw failure;
  return result;
}

function strictObject(input, requiredKeys, optionalKeys = []) {
  if (input === null || typeof input !== "object" || Array.isArray(input)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  const keys = Object.keys(input);
  if (keys.some((key) => !requiredKeys.includes(key) && !optionalKeys.includes(key)) ||
      requiredKeys.some((key) => !Object.hasOwn(input, key))) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return input;
}

function emailInput(input) {
  strictObject(input, ["email"]);
  if (typeof input.email !== "string" || !isWellFormedEmail(input.email.trim())) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return canonicalizeEmail(input.email);
}

function getUserByEmail(database, email) {
  const user = database.prepare("SELECT * FROM users WHERE email_canonical = ?").get(email);
  if (!user) throw new AccountApiError(ErrorCode.ACCOUNT_NOT_FOUND);
  return user;
}

function getUserById(database, userId) {
  const user = database.prepare("SELECT * FROM users WHERE user_id = ?").get(userId);
  if (!user) throw new AccountApiError(ErrorCode.ACCOUNT_NOT_FOUND);
  return user;
}

function getGrant(database, grantId) {
  const grant = database.prepare("SELECT * FROM developer_access_grants WHERE grant_id = ?").get(grantId);
  if (!grant) throw new AccountApiError(ErrorCode.DEVELOPER_GRANT_NOT_FOUND);
  return grant;
}

function publicUser(user) {
  return {
    email: user.email,
    displayName: user.display_name,
    status: user.status,
    emailVerified: user.email_verified_at !== null,
    emailVerifiedAt: user.email_verified_at,
    createdAt: user.created_at,
    updatedAt: user.updated_at,
  };
}

function userSessions(database, userId) {
  const now = nowIso();
  return database.prepare(
    `SELECT issued_at, last_used_at, refresh_expires_at, device_label
       FROM sessions
      WHERE user_id = ? AND revoked_at IS NULL AND refresh_expires_at > ?
      ORDER BY issued_at DESC LIMIT 100`,
  ).all(userId, now).map((row) => ({
    createdAt: row.issued_at,
    lastUsedAt: row.last_used_at,
    expiresAt: row.refresh_expires_at,
    deviceLabel: row.device_label,
  }));
}

function validateExpiry(value) {
  if (typeof value !== "string" || value.length !== 24 ||
      !/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$/.test(value)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  const parsed = Date.parse(value);
  const now = Date.now();
  const maximum = now + 365 * 24 * 60 * 60 * 1000;
  if (!Number.isFinite(parsed) || new Date(parsed).toISOString() !== value || parsed <= now || parsed > maximum) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return new Date(parsed).toISOString();
}

function validateGrantKey(value) {
  if (typeof value !== "string" || !GRANT_TYPES.has(value)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return value;
}

function grantList(database, userId) {
  const now = nowIso();
  return database.prepare(
    `SELECT grant_id, entitlement_key, created_at, expires_at, revoked_at
       FROM developer_access_grants
      WHERE user_id = ? ORDER BY created_at DESC LIMIT 100`,
  ).all(userId).map((row) => ({
    grantId: row.grant_id,
    entitlementKey: row.entitlement_key,
    grantedAt: row.created_at,
    expiresAt: row.expires_at,
    revokedAt: row.revoked_at,
    active: row.revoked_at === null && row.expires_at > now,
  }));
}

function noArguments(input) {
  strictObject(input, []);
  return {};
}

/** A bounded, human-readable justification. It is mandatory: an unexplained credit movement is not auditable. */
function validateReason(value) {
  const reason = typeof value === "string" ? value.trim() : "";
  if (reason.length < 3 || reason.length > 200 || /[\u0000-\u001f\u007f]/.test(reason)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return reason;
}

function validateIdempotencyKey(value) {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string" || !/^[A-Za-z0-9._:-]{8,128}$/.test(value.trim())) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return value.trim();
}

function validateCreditAmount(value, maximum) {
  if (!Number.isSafeInteger(value) || value < 1 || value > maximum) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return value;
}

function validateDays(value, maximum) {
  if (!Number.isSafeInteger(value) || value < 1 || value > maximum) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return value;
}

function validatePlanId(value, catalog) {
  if (typeof value !== "string" || !catalog.byId[value] || !catalog.byId[value].grantable) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return value;
}

function validateTransactionReference(value) {
  if (typeof value !== "string" || !/^crd_[0-9a-f-]{36}$/.test(value)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return value;
}

/**
 * The optional developer-supplied idempotency key, or a generated one.
 *
 * The confirmation flow already makes a developer action execute at most once, so the ledger key is the second layer:
 * it protects a *client-side* retry of the confirmation, and it lets a developer deliberately repeat a grant intent
 * without creating a second ledger row.
 */
function creditOperationKey(input, developerId, purpose) {
  const supplied = validateIdempotencyKey(input);
  return supplied ?? `devtool-${purpose}-${developerId}-${newToken().slice(0, 32)}`;
}

/**
 * Creator and workspace lookups for the Phase 23 tools.
 *
 * A developer tool addresses a creator by handle and a workspace by slug — the public identifiers an operator sees in a
 * report — and both are normalized exactly as the account surface normalizes them, so a tool cannot reach a row the
 * public API would refuse to resolve.
 */
function getCreatorByHandle(database, value) {
  const normalized = normalizeHandle(value);
  if (!normalized.ok) throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  const row = creatorProfileRowByHandle(database, normalized.handle);
  if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
  return row;
}

function getServerBySlug(database, value) {
  const normalized = normalizeHandle(value);
  if (!normalized.ok) throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  const row = serverWorkspaceRowBySlug(database, normalized.handle);
  if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
  return row;
}

function validateVerificationTarget(value) {
  const state = value ?? "VERIFIED";
  if (state !== "PENDING" && state !== "VERIFIED") throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  return state;
}

function validateServerStatusTarget(value) {
  if (typeof value !== "string" || !isServerStatus(value) || value === "ARCHIVED") {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return value;
}

/** The owner-visible profile, as the control plane should see it: no token, no session, no internal account id. */
function developerCreatorView(database, configuration, row, { history = false } = {}) {
  const user = database.prepare("SELECT email, status FROM users WHERE user_id = ?").get(row.user_id);
  const entitlementView = getAccountEntitlementsInTransaction(database, configuration, row.user_id, { includeCredits: false });
  return {
    handle: row.handle,
    displayName: row.display_name,
    bio: row.bio ?? "",
    category: row.category ?? null,
    avatarReference: row.avatar_reference ?? null,
    status: row.status,
    statusLabel: creatorStatusDefinition(row.status)?.label ?? row.status,
    verification: row.verification_status,
    verificationLabel: creatorVerificationDefinition(row.verification_status)?.label ?? row.verification_status,
    verifiedAt: row.verified_at ?? null,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    owner: { email: user?.email ?? null, accountStatus: user?.status ?? null },
    membership: { plan: entitlementView.membership.plan, status: entitlementView.membership.status },
    capabilities: creatorCapabilitiesFor(entitlementView, row).map((entry) => ({
      key: entry.key, state: entry.state, available: entry.available, reason: entry.reason,
    })),
    history: history
      ? database.prepare(
        `SELECT change_type, from_value, to_value, reason, occurred_at FROM creator_status_history
          WHERE creator_id = ? ORDER BY occurred_at DESC, history_id DESC LIMIT 20`,
      ).all(row.creator_id)
      : null,
  };
}

function developerServerView(database, row, { members = true } = {}) {
  const owner = database.prepare("SELECT email, status FROM users WHERE user_id = ?").get(row.owner_user_id);
  return {
    serverId: row.server_id,
    slug: row.slug,
    displayName: row.display_name,
    description: row.description ?? "",
    status: row.status,
    statusLabel: serverStatusDefinition(row.status)?.label ?? row.status,
    operational: serverStatusDefinition(row.status)?.operational === true,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    owner: { email: owner?.email ?? null, accountStatus: owner?.status ?? null },
    members: members
      ? database.prepare("SELECT role, user_id, created_at FROM server_members WHERE server_id = ? ORDER BY created_at ASC")
        .all(row.server_id)
        .map((member) => ({
          role: member.role,
          roleLabel: serverRoleDefinition(member.role)?.label ?? member.role,
          email: database.prepare("SELECT email FROM users WHERE user_id = ?").get(member.user_id)?.email ?? null,
          since: member.created_at,
        }))
      : null,
  };
}

/** The entitlement view for one account, read inside the caller's transaction (never a second one). */
function membershipContext(database, configuration, userId, { history = false } = {}) {
  return getAccountEntitlementsInTransaction(database, configuration, userId, { includeHistory: history });
}

const ADMIN_TOOLS = Object.freeze({
  overview: {
    audit: "overview", mutating: false, roles: ["OWNER", "ADMIN", "DEVELOPER"],
    description: "Read aggregate account and session counts.", schema: { type: "object", properties: {}, additionalProperties: false },
    resolve: noArguments,
    execute(database) {
      const userCounts = database.prepare("SELECT status, COUNT(*) AS count FROM users GROUP BY status").all();
      const users = Object.fromEntries(userCounts.map((row) => [row.status, Number(row.count)]));
      const now = nowIso();
      const activeSessions = Number(database.prepare(
        "SELECT COUNT(*) AS count FROM sessions WHERE revoked_at IS NULL AND refresh_expires_at > ?",
      ).get(now).count);
      const activeDevelopers = Number(database.prepare(
        "SELECT COUNT(*) AS count FROM developer_accounts WHERE status = 'ACTIVE'",
      ).get().count);
      return {
        result: { users: { active: users.ACTIVE ?? 0, suspended: users.SUSPENDED ?? 0, deleted: users.DELETED ?? 0 }, activeUserSessions: activeSessions, activeDeveloperAccounts: activeDevelopers },
        auditMetadata: { activeUsers: users.ACTIVE ?? 0, suspendedUsers: users.SUSPENDED ?? 0, activeUserSessions: activeSessions },
      };
    },
  },
  inspectUser: {
    audit: "inspect_user", mutating: false, roles: ["OWNER", "ADMIN", "DEVELOPER"],
    description: "Inspect safe account metadata by exact email. Never returns a password hash or credential.",
    schema: { type: "object", properties: { email: { type: "string", format: "email" } }, required: ["email"], additionalProperties: false },
    resolve(input, database) { return { userId: getUserByEmail(database, emailInput(input)).user_id }; },
    execute(database, args) {
      const user = getUserById(database, args.userId);
      return { result: { user: publicUser(user) }, targetUserId: user.user_id, auditMetadata: { fields: ["email", "displayName", "status", "emailVerified", "createdAt", "updatedAt"] } };
    },
  },
  listUserSessions: {
    audit: "list_user_sessions", mutating: false, roles: ["OWNER", "ADMIN", "DEVELOPER"],
    description: "List safe metadata for currently active sessions belonging to an account.",
    schema: { type: "object", properties: { email: { type: "string", format: "email" } }, required: ["email"], additionalProperties: false },
    resolve(input, database) { return { userId: getUserByEmail(database, emailInput(input)).user_id }; },
    execute(database, args) {
      const user = getUserById(database, args.userId);
      const sessions = userSessions(database, user.user_id);
      return { result: { sessions }, targetUserId: user.user_id, auditMetadata: { sessionCount: sessions.length } };
    },
  },
  revokeUserSessions: {
    audit: "revoke_user_sessions", mutating: true, roles: ["OWNER", "ADMIN"],
    description: "Revoke all active sessions for one user; requires a separate explicit confirmation.",
    schema: { type: "object", properties: { email: { type: "string", format: "email" } }, required: ["email"], additionalProperties: false },
    resolve(input, database) { return { userId: getUserByEmail(database, emailInput(input)).user_id }; },
    validateResolved(database, args) { getUserById(database, args.userId); return args; },
    summary(database, args) { return `Revoke all active sessions for ${getUserById(database, args.userId).email}.`; },
    execute(database, args) {
      const user = getUserById(database, args.userId);
      const result = database.prepare("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")
        .run(nowIso(), user.user_id);
      const revokedSessions = Number(result.changes ?? 0);
      return { result: { revokedSessions }, targetUserId: user.user_id, auditMetadata: { revokedSessions } };
    },
  },
  suspendUser: {
    audit: "suspend_user", mutating: true, roles: ["OWNER", "ADMIN"],
    description: "Suspend one account and revoke all of its active sessions; requires confirmation.",
    schema: { type: "object", properties: { email: { type: "string", format: "email" } }, required: ["email"], additionalProperties: false },
    resolve(input, database) { return { userId: getUserByEmail(database, emailInput(input)).user_id }; },
    validateResolved(database, args) { getUserById(database, args.userId); return args; },
    summary(database, args) { return `Suspend ${getUserById(database, args.userId).email} and revoke all active sessions.`; },
    execute(database, args) {
      const user = getUserById(database, args.userId);
      if (user.status === ACCOUNT_STATUS.DELETED) throw new AccountApiError(ErrorCode.ACCOUNT_DELETED);
      const timestamp = nowIso();
      database.prepare("UPDATE users SET status = 'SUSPENDED', updated_at = ? WHERE user_id = ? AND status <> 'DELETED'")
        .run(timestamp, user.user_id);
      const revoked = database.prepare("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")
        .run(timestamp, user.user_id);
      return { result: { status: "SUSPENDED", revokedSessions: Number(revoked.changes ?? 0) }, targetUserId: user.user_id, auditMetadata: { status: "SUSPENDED", revokedSessions: Number(revoked.changes ?? 0) } };
    },
  },
  restoreUser: {
    audit: "restore_user", mutating: true, roles: ["OWNER", "ADMIN"],
    description: "Restore a suspended account. Previously revoked sessions remain revoked; the user must sign in again.",
    schema: { type: "object", properties: { email: { type: "string", format: "email" } }, required: ["email"], additionalProperties: false },
    resolve(input, database) { return { userId: getUserByEmail(database, emailInput(input)).user_id }; },
    validateResolved(database, args) { getUserById(database, args.userId); return args; },
    summary(database, args) { return `Restore ${getUserById(database, args.userId).email}; old sessions stay revoked.`; },
    execute(database, args) {
      const user = getUserById(database, args.userId);
      if (user.status === ACCOUNT_STATUS.DELETED) throw new AccountApiError(ErrorCode.ACCOUNT_DELETED);
      database.prepare("UPDATE users SET status = 'ACTIVE', updated_at = ? WHERE user_id = ? AND status = 'SUSPENDED'")
        .run(nowIso(), user.user_id);
      return { result: { status: "ACTIVE", existingSessionsRestored: false }, targetUserId: user.user_id, auditMetadata: { status: "ACTIVE", existingSessionsRestored: false } };
    },
  },
  listEntitlements: {
    audit: "list_entitlements", mutating: false, roles: ["OWNER", "ADMIN", "DEVELOPER"],
    description: "List the independent, time-limited administrative preview grants for one account.",
    schema: { type: "object", properties: { email: { type: "string", format: "email" } }, required: ["email"], additionalProperties: false },
    resolve(input, database) { return { userId: getUserByEmail(database, emailInput(input)).user_id }; },
    execute(database, args) {
      const user = getUserById(database, args.userId);
      const grants = grantList(database, user.user_id);
      return { result: { grants }, targetUserId: user.user_id, auditMetadata: { grantCount: grants.length } };
    },
  },
  grantEntitlement: {
    audit: "grant_entitlement", mutating: true, roles: ["OWNER", "ADMIN"],
    description: "Create a bounded beta, preview, or promotional access grant. This does not process payment or activate an app feature.",
    schema: {
      type: "object",
      properties: {
        email: { type: "string", format: "email" },
        entitlementKey: { type: "string", enum: [...GRANT_TYPES] },
        expiresAt: { type: "string", format: "date-time" },
      },
      required: ["email", "entitlementKey", "expiresAt"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["email", "entitlementKey", "expiresAt"]);
      const user = getUserByEmail(database, emailInput({ email: input.email }));
      const entitlementKey = validateGrantKey(input.entitlementKey);
      const expiresAt = validateExpiry(input.expiresAt);
      return { userId: user.user_id, entitlementKey, expiresAt };
    },
    validateResolved(database, args) {
      getUserById(database, args.userId);
      validateGrantKey(args.entitlementKey);
      return { ...args, expiresAt: validateExpiry(args.expiresAt) };
    },
    summary(database, args) { return `Grant ${args.entitlementKey} to ${getUserById(database, args.userId).email} until ${args.expiresAt}.`; },
    execute(database, args, actor) {
      const user = getUserById(database, args.userId);
      const existing = database.prepare(
        `SELECT grant_id FROM developer_access_grants
          WHERE user_id = ? AND entitlement_key = ? AND revoked_at IS NULL AND expires_at > ? LIMIT 1`,
      ).get(user.user_id, args.entitlementKey, nowIso());
      if (existing) throw new AccountApiError(ErrorCode.DEVELOPER_GRANT_ALREADY_ACTIVE);
      const grantId = newGrantId();
      const timestamp = nowIso();
      database.prepare(
        `INSERT INTO developer_access_grants
           (grant_id, user_id, entitlement_key, granted_by, created_at, expires_at, revoked_at, revoked_by)
         VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)`,
      ).run(grantId, user.user_id, args.entitlementKey, actor.developer_id, timestamp, args.expiresAt);
      return { result: { grantId, entitlementKey: args.entitlementKey, expiresAt: args.expiresAt }, targetUserId: user.user_id, auditMetadata: { grantId, entitlementKey: args.entitlementKey, expiresAt: args.expiresAt } };
    },
  },
  revokeEntitlement: {
    audit: "revoke_entitlement", mutating: true, roles: ["OWNER", "ADMIN"],
    description: "Revoke one administrative preview grant by its opaque grant reference; requires confirmation.",
    schema: { type: "object", properties: { grantId: { type: "string", pattern: "^grt_[0-9a-f-]{36}$" } }, required: ["grantId"], additionalProperties: false },
    resolve(input, database) {
      strictObject(input, ["grantId"]);
      if (typeof input.grantId !== "string" || !/^grt_[0-9a-f-]{36}$/.test(input.grantId)) {
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      const grant = getGrant(database, input.grantId);
      return { grantId: grant.grant_id, userId: grant.user_id };
    },
    validateResolved(database, args) {
      const grant = getGrant(database, args.grantId);
      if (grant.user_id !== args.userId) throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      return args;
    },
    summary(database, args) {
      const grant = getGrant(database, args.grantId);
      const user = getUserById(database, grant.user_id);
      return `Revoke ${grant.entitlement_key} for ${user.email}.`;
    },
    execute(database, args, actor) {
      const grant = getGrant(database, args.grantId);
      if (grant.revoked_at !== null) throw new AccountApiError(ErrorCode.DEVELOPER_GRANT_NOT_FOUND);
      const timestamp = nowIso();
      database.prepare("UPDATE developer_access_grants SET revoked_at = ?, revoked_by = ? WHERE grant_id = ? AND revoked_at IS NULL")
        .run(timestamp, actor.developer_id, grant.grant_id);
      return { result: { revoked: true, entitlementKey: grant.entitlement_key }, targetUserId: grant.user_id, auditMetadata: { grantId: grant.grant_id, entitlementKey: grant.entitlement_key } };
    },
  },
  listAuditLog: {
    audit: "list_audit_log", mutating: false, roles: ["OWNER", "ADMIN", "DEVELOPER"],
    description: "Read the newest append-only administrative audit events.",
    schema: { type: "object", properties: { limit: { type: "integer", minimum: 1, maximum: MAX_AUDIT_PAGE_SIZE } }, additionalProperties: false },
    resolve(input) {
      strictObject(input, [], ["limit"]);
      const limit = input.limit ?? 50;
      if (!Number.isInteger(limit) || limit < 1 || limit > MAX_AUDIT_PAGE_SIZE) throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      return { limit };
    },
    execute(database, args) {
      const rows = database.prepare(
        `SELECT l.action_type, l.actor_kind, l.incident_id, l.occurred_at, l.outcome, l.metadata_json,
                actor.email AS actor_email, target.email AS target_email
           FROM admin_audit_log l
           LEFT JOIN developer_accounts actor ON actor.developer_id = l.actor_developer_id
           LEFT JOIN users target ON target.user_id = l.target_user_id
          ORDER BY l.occurred_at DESC, l.audit_id DESC LIMIT ?`,
      ).all(args.limit);
      const events = rows.map((row) => ({
        action: row.action_type,
        actorKind: row.actor_kind,
        incidentId: row.incident_id,
        occurredAt: row.occurred_at,
        outcome: row.outcome,
        actorEmail: row.actor_email,
        targetEmail: row.target_email,
        metadata: JSON.parse(row.metadata_json),
      }));
      return { result: { events }, auditMetadata: { returned: events.length, limit: args.limit } };
    },
  },
  inspectMembership: {
    audit: "inspect_membership", mutating: false, roles: MEMBERSHIP_TOOL_ROLES.READ,
    description: "Inspect one account's membership state, entitlements, and credit balance summary by exact email.",
    schema: {
      type: "object",
      properties: { email: { type: "string", format: "email" }, includeHistory: { type: "boolean" } },
      required: ["email"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["email"], ["includeHistory"]);
      const user = getUserByEmail(database, emailInput({ email: input.email }));
      if (input.includeHistory !== undefined && typeof input.includeHistory !== "boolean") {
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      return { userId: user.user_id, includeHistory: input.includeHistory === true };
    },
    execute(database, args, actor, context) {
      const user = getUserById(database, args.userId);
      const view = membershipContext(database, context.configuration, user.user_id, { history: args.includeHistory });
      const balance = view.credits;
      const recent = listCreditTransactionsInTransaction(database, user.user_id, { limit: 5 });
      return {
        result: {
          email: user.email,
          membership: view.membership,
          plan: view.plan,
          entitlements: view.entitlements.map((entry) => entry.key),
          administrativeGrants: view.administrativeGrants,
          credits: balance
            ? { available: balance.available, expiring: balance.expiring, expired: balance.expired, expiringInDays: balance.expiringInDays }
            : null,
          recentTransactions: recent.map((entry) => ({
            transactionId: entry.transactionId, type: entry.type, amount: entry.amount, createdAt: entry.createdAt,
          })),
          history: view.history,
        },
        targetUserId: user.user_id,
        auditMetadata: {
          plan: view.membership.plan, status: view.membership.status,
          entitlements: view.entitlements.length, available: balance?.available ?? null,
        },
      };
    },
  },
  grantMembership: {
    audit: "grant_membership", mutating: true, roles: MEMBERSHIP_TOOL_ROLES.WRITE,
    description: "Assign a Pro, Creator, or Server membership for a bounded number of days, with its internal promotional credit allocation. No payment is processed and the plan stays non-purchasable.",
    schema: {
      type: "object",
      properties: {
        email: { type: "string", format: "email" },
        plan: { type: "string", enum: ["PRO", "CREATOR", "SERVER"] },
        days: { type: "integer", minimum: 1 },
        reason: { type: "string", minLength: 3, maxLength: 200 },
        idempotencyKey: { type: "string", minLength: 8, maxLength: 128 },
      },
      required: ["email", "plan", "days", "reason"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["email", "plan", "days", "reason"], ["idempotencyKey"]);
      const user = getUserByEmail(database, emailInput({ email: input.email }));
      const catalog = planCatalog();
      return {
        userId: user.user_id,
        plan: validatePlanId(input.plan, catalog),
        days: validateDays(input.days, catalog.maximumPlanGrantDays),
        reason: validateReason(input.reason),
        idempotencyKey: validateIdempotencyKey(input.idempotencyKey),
      };
    },
    validateResolved(database, args, context) {
      const user = getUserById(database, args.userId);
      const catalog = planCatalog(context?.configuration);
      return {
        ...args,
        plan: validatePlanId(args.plan, catalog),
        days: validateDays(args.days, catalog.maximumPlanGrantDays),
        reason: validateReason(args.reason),
        idempotencyKey: validateIdempotencyKey(args.idempotencyKey),
        email: user.email,
      };
    },
    summary(database, args) {
      const user = getUserById(database, args.userId);
      return `Grant the ${args.plan} plan to ${user.email} for ${args.days} day(s), with its promotional credit allocation. No payment is involved.`;
    },
    execute(database, args, actor, context) {
      const configuration = context.configuration;
      const user = getUserById(database, args.userId);
      const key = creditOperationKey(args.idempotencyKey, actor.developer_id, `membership-${args.plan}`);
      const granted = assignMembership(database, configuration, {
        userId: user.user_id,
        planId: args.plan,
        reason: args.reason,
        days: args.days,
        actorDeveloperId: actor.developer_id,
      });
      const allocation = grantPlanAllocation(database, configuration, {
        userId: user.user_id,
        planId: granted.plan,
        allocation: granted.promotionalCredits,
        expiresInDays: granted.expiresInDays,
        idempotencyKey: `${key}-allocation`,
        actorDeveloperId: actor.developer_id,
      });
      return {
        result: {
          email: user.email,
          plan: granted.plan,
          previousPlan: granted.previousPlan,
          status: granted.status,
          endsAt: granted.endsAt,
          promotionalCreditsGranted: allocation?.transaction?.amount ?? 0,
        },
        targetUserId: user.user_id,
        auditMetadata: {
          plan: granted.plan, days: args.days, endsAt: granted.endsAt,
          promotionalCredits: allocation?.transaction?.amount ?? 0, availability: "NOT_PURCHASABLE",
        },
      };
    },
  },
  grantCredits: {
    audit: "grant_credits", mutating: true, roles: MEMBERSHIP_TOOL_ROLES.WRITE,
    description: "Grant a bounded number of promotional build credits to one account, with a mandatory reason and an optional expiry. Credits are internal: no payment, purchase, or subscription is involved.",
    schema: {
      type: "object",
      properties: {
        email: { type: "string", format: "email" },
        amount: { type: "integer", minimum: 1 },
        reason: { type: "string", minLength: 3, maxLength: 200 },
        expiresInDays: { type: "integer", minimum: 1 },
        idempotencyKey: { type: "string", minLength: 8, maxLength: 128 },
      },
      required: ["email", "amount", "reason"], additionalProperties: false,
    },
    resolve(input, database, context) {
      strictObject(input, ["email", "amount", "reason"], ["expiresInDays", "idempotencyKey"]);
      const user = getUserByEmail(database, emailInput({ email: input.email }));
      const maximum = context?.configuration?.membership?.maximumGrantCredits ?? 10_000;
      const maximumDays = context?.configuration?.membership?.creditExpiryDays ?? 90;
      return {
        userId: user.user_id,
        amount: validateCreditAmount(input.amount, maximum),
        reason: validateReason(input.reason),
        expiresInDays: input.expiresInDays === undefined ? null : validateDays(input.expiresInDays, maximumDays),
        idempotencyKey: validateIdempotencyKey(input.idempotencyKey),
      };
    },
    validateResolved(database, args, context) {
      const user = getUserById(database, args.userId);
      const maximum = context?.configuration?.membership?.maximumGrantCredits ?? 10_000;
      const maximumDays = context?.configuration?.membership?.creditExpiryDays ?? 90;
      return {
        ...args,
        email: user.email,
        amount: validateCreditAmount(args.amount, maximum),
        reason: validateReason(args.reason),
        expiresInDays: args.expiresInDays === null || args.expiresInDays === undefined
          ? null
          : validateDays(args.expiresInDays, maximumDays),
        idempotencyKey: validateIdempotencyKey(args.idempotencyKey),
      };
    },
    summary(database, args) {
      const user = getUserById(database, args.userId);
      const expiry = args.expiresInDays === null ? "no expiry" : `expiring in ${args.expiresInDays} day(s)`;
      return `Grant ${args.amount} promotional build credits to ${user.email} (${expiry}). No payment is involved.`;
    },
    execute(database, args, actor, context) {
      const user = getUserById(database, args.userId);
      const granted = grantCreditsInTransaction(database, context.configuration, {
        userId: user.user_id,
        amount: args.amount,
        reason: args.reason,
        idempotencyKey: creditOperationKey(args.idempotencyKey, actor.developer_id, "grant"),
        expiresInDays: args.expiresInDays,
        actorDeveloperId: actor.developer_id,
      });
      return {
        result: {
          email: user.email,
          transactionId: granted.transaction.transactionId,
          amount: granted.transaction.amount,
          expiresAt: granted.transaction.expiresAt,
          availableAfter: granted.available,
        },
        targetUserId: user.user_id,
        auditMetadata: {
          amount: granted.transaction.amount, expiresAt: granted.transaction.expiresAt,
          availableAfter: granted.available,
        },
      };
    },
  },
  reverseCreditGrant: {
    audit: "reverse_credit_grant", mutating: true, roles: MEMBERSHIP_TOOL_ROLES.WRITE,
    description: "Reverse part or all of a still-standing credit grant by its transaction reference. The original ledger entry is never edited or deleted.",
    schema: {
      type: "object",
      properties: {
        email: { type: "string", format: "email" },
        transactionId: { type: "string", pattern: "^crd_[0-9a-f-]{36}$" },
        amount: { type: "integer", minimum: 1 },
        reason: { type: "string", minLength: 3, maxLength: 200 },
        idempotencyKey: { type: "string", minLength: 8, maxLength: 128 },
      },
      required: ["email", "transactionId", "reason"], additionalProperties: false,
    },
    resolve(input, database, context) {
      strictObject(input, ["email", "transactionId", "reason"], ["amount", "idempotencyKey"]);
      const user = getUserByEmail(database, emailInput({ email: input.email }));
      const maximum = context?.configuration?.membership?.maximumGrantCredits ?? 10_000;
      const transactionId = validateTransactionReference(input.transactionId);
      // Resolve first: a grant belonging to another account simply does not exist here, so the tool cannot be used to
      // probe another ledger.
      const row = database.prepare("SELECT user_id FROM credit_ledger WHERE transaction_id = ?").get(transactionId);
      if (!row || row.user_id !== user.user_id) throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      return {
        userId: user.user_id,
        transactionId,
        amount: input.amount === undefined ? null : validateCreditAmount(input.amount, maximum),
        reason: validateReason(input.reason),
        idempotencyKey: validateIdempotencyKey(input.idempotencyKey),
      };
    },
    validateResolved(database, args, context) {
      const user = getUserById(database, args.userId);
      const maximum = context?.configuration?.membership?.maximumGrantCredits ?? 10_000;
      const transactionId = validateTransactionReference(args.transactionId);
      const row = database.prepare("SELECT user_id FROM credit_ledger WHERE transaction_id = ?").get(transactionId);
      if (!row || row.user_id !== user.user_id) throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      return {
        ...args,
        email: user.email,
        transactionId,
        amount: args.amount === null || args.amount === undefined ? null : validateCreditAmount(args.amount, maximum),
        reason: validateReason(args.reason),
        idempotencyKey: validateIdempotencyKey(args.idempotencyKey),
      };
    },
    summary(database, args) {
      const user = getUserById(database, args.userId);
      const scope = args.amount === null ? "the remaining credits of" : `${args.amount} credits from`;
      return `Reverse ${scope} grant ${args.transactionId} for ${user.email}.`;
    },
    execute(database, args, actor, context) {
      const user = getUserById(database, args.userId);
      const reversed = reverseCreditGrantInTransaction(database, context.configuration, {
        userId: user.user_id,
        transactionId: args.transactionId,
        amount: args.amount,
        reason: args.reason,
        idempotencyKey: creditOperationKey(args.idempotencyKey, actor.developer_id, "reverse"),
        actorDeveloperId: actor.developer_id,
      });
      return {
        result: {
          email: user.email,
          transactionId: reversed.transaction.transactionId,
          originalTransactionId: args.transactionId,
          amount: reversed.transaction.amount,
          availableAfter: reversed.available,
        },
        targetUserId: user.user_id,
        auditMetadata: { amount: reversed.transaction.amount, originalTransactionId: args.transactionId, availableAfter: reversed.available },
      };
    },
  },
  listCreditTransactions: {
    audit: "list_credit_transactions", mutating: false, roles: MEMBERSHIP_TOOL_ROLES.READ,
    description: "Read the newest ledger entries for one account: grants, consumptions, expirations, and reversals.",
    schema: {
      type: "object",
      properties: {
        email: { type: "string", format: "email" },
        limit: { type: "integer", minimum: 1, maximum: MAX_AUDIT_PAGE_SIZE },
      },
      required: ["email"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["email"], ["limit"]);
      const user = getUserByEmail(database, emailInput({ email: input.email }));
      const limit = input.limit ?? 25;
      if (!Number.isInteger(limit) || limit < 1 || limit > MAX_AUDIT_PAGE_SIZE) {
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      return { userId: user.user_id, limit };
    },
    execute(database, args, actor, context) {
      const user = getUserById(database, args.userId);
      const transactions = listCreditTransactionsInTransaction(database, user.user_id, { limit: args.limit });
      const balance = creditBalanceInTransaction(database, context.configuration, user.user_id, { reconcile: false });
      return {
        result: {
          email: user.email,
          transactions: transactions.map((entry) => ({
            transactionId: entry.transactionId, type: entry.type, amount: entry.amount, reason: entry.reason,
            source: entry.source, referenceId: entry.referenceId, createdAt: entry.createdAt, expiresAt: entry.expiresAt,
          })),
          credits: { available: balance.available, expiring: balance.expiring, expired: balance.expired },
        },
        targetUserId: user.user_id,
        auditMetadata: { returned: transactions.length, available: balance.available },
      };
    },
  },
  configurationStatus: {
    audit: "configuration_status", mutating: false, roles: ["OWNER"],
    description: "Read non-secret service and provider-availability metadata.",
    schema: { type: "object", properties: {}, additionalProperties: false },
    resolve: noArguments,
    execute(database, args, actor, context) {
      const bootstrapUsed = Boolean(database.prepare("SELECT singleton_id FROM developer_bootstrap_state WHERE singleton_id = 1").get());
      const status = {
        service: "craftmind-auth",
        production: context.configuration.production,
        schemaVersion: context.schemaVersion,
        emailDeliveryMode: context.configuration.emailDeliveryMode,
        bootstrap: { configured: Boolean(context.configuration.developerBootstrapEmail && context.configuration.developerBootstrapSecret), consumed: bootstrapUsed },
        developerAi: {
          environmentConfigured: context.configuration.developerAi.configured,
          provider: context.configuration.developerAi.provider,
          model: context.configuration.developerAi.model,
          adapterAvailable: typeof context.developerAiProvider?.selectToolCall === "function",
        },
        rateLimitScope: "process-local",
        developerRole: actor.role,
      };
      return { result: status, auditMetadata: { production: status.production, aiConfigured: status.developerAi.environmentConfigured, aiAdapterAvailable: status.developerAi.adapterAvailable } };
    },
  },
  // ---- Phase 23 creator controls ---------------------------------------------------------------------------------
  inspectCreator: {
    audit: "inspect_creator", mutating: false, roles: MEMBERSHIP_TOOL_ROLES.READ,
    description: "Inspect one creator profile by handle: status, verification marker, capability state, and history.",
    schema: {
      type: "object",
      properties: { handle: { type: "string", minLength: 3, maxLength: 32 }, includeHistory: { type: "boolean" } },
      required: ["handle"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["handle"], ["includeHistory"]);
      if (input.includeHistory !== undefined && typeof input.includeHistory !== "boolean") {
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      const row = getCreatorByHandle(database, input.handle);
      return { creatorId: row.creator_id, handle: row.handle, includeHistory: input.includeHistory === true };
    },
    execute(database, args, actor, context) {
      const row = creatorProfileRowById(database, args.creatorId);
      if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
      const view = developerCreatorView(database, context.configuration, row, { history: args.includeHistory });
      return {
        result: view,
        targetUserId: row.user_id,
        auditMetadata: { handle: row.handle, status: row.status, verification: row.verification_status },
      };
    },
  },
  listCreatorProfiles: {
    audit: "list_creator_profiles", mutating: false, roles: MEMBERSHIP_TOOL_ROLES.READ,
    description: "List creator profiles, newest first, optionally filtered by status or verification state. Handles only: no account data in a listing.",
    schema: {
      type: "object",
      properties: {
        limit: { type: "integer", minimum: 1, maximum: MAX_AUDIT_PAGE_SIZE },
        status: { type: "string", enum: ["ACTIVE", "PENDING", "SUSPENDED", "DISABLED"] },
        verification: { type: "string", enum: ["UNVERIFIED", "PENDING", "VERIFIED", "REVOKED"] },
      },
      additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, [], ["limit", "status", "verification"]);
      const limit = input.limit ?? 25;
      if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_AUDIT_PAGE_SIZE) {
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      return { limit, status: input.status ?? null, verification: input.verification ?? null };
    },
    execute(database, args) {
      const profiles = listCreatorProfilesInTransaction(database, {
        limit: args.limit, status: args.status, verification: args.verification,
      }).map((profile) => ({
        handle: profile.handle, displayName: profile.displayName, status: profile.status,
        verification: profile.verification, createdAt: profile.createdAt,
      }));
      return {
        result: { profiles, returned: profiles.length, limit: args.limit },
        auditMetadata: { returned: profiles.length, status: args.status ?? "ANY", verification: args.verification ?? "ANY" },
      };
    },
  },
  verifyCreator: {
    audit: "verify_creator", mutating: true, roles: MEMBERSHIP_TOOL_ROLES.WRITE,
    description: "Record the internal CraftMind verification marker for a creator. This is not identity, government, document, biometric, or payment verification, and no such check is performed or implied.",
    schema: {
      type: "object",
      properties: {
        handle: { type: "string", minLength: 3, maxLength: 32 },
        state: { type: "string", enum: ["PENDING", "VERIFIED"] },
        reason: { type: "string", minLength: 3, maxLength: 200 },
      },
      required: ["handle", "reason"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["handle", "reason"], ["state"]);
      const row = getCreatorByHandle(database, input.handle);
      return {
        creatorId: row.creator_id, handle: row.handle,
        state: validateVerificationTarget(input.state), reason: validateReason(input.reason),
      };
    },
    validateResolved(database, args) {
      const row = creatorProfileRowById(database, args.creatorId);
      if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
      return { ...args, handle: row.handle, state: validateVerificationTarget(args.state), reason: validateReason(args.reason) };
    },
    summary(database, args) {
      const label = args.state === "VERIFIED" ? "Verified" : "Verification pending";
      return `Set the internal verification marker of ${args.handle} to "${label}". This is not identity verification.`;
    },
    execute(database, args, actor, context) {
      const row = creatorProfileRowById(database, args.creatorId);
      if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
      const updated = applyCreatorVerificationInTransaction(database, context.configuration, {
        creatorId: row.creator_id, verification: args.state, reason: args.reason,
        actorDeveloperId: actor.developer_id,
      });
      return {
        result: { handle: updated.handle, verification: updated.verification_status, verificationScope: "INTERNAL_MARKER_ONLY" },
        targetUserId: row.user_id,
        auditMetadata: { handle: updated.handle, verification: updated.verification_status, scope: "INTERNAL" },
      };
    },
  },
  revokeCreatorVerification: {
    audit: "revoke_creator_verification", mutating: true, roles: MEMBERSHIP_TOOL_ROLES.WRITE,
    description: "Withdraw a creator's internal verification marker. The profile keeps operating; the marker is removed.",
    schema: {
      type: "object",
      properties: { handle: { type: "string", minLength: 3, maxLength: 32 }, reason: { type: "string", minLength: 3, maxLength: 200 } },
      required: ["handle", "reason"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["handle", "reason"]);
      const row = getCreatorByHandle(database, input.handle);
      return { creatorId: row.creator_id, handle: row.handle, reason: validateReason(input.reason) };
    },
    validateResolved(database, args) {
      const row = creatorProfileRowById(database, args.creatorId);
      if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
      return { ...args, handle: row.handle, reason: validateReason(args.reason) };
    },
    summary(database, args) {
      return `Revoke the internal verification marker of ${args.handle}. The creator profile keeps operating.`;
    },
    execute(database, args, actor, context) {
      const row = creatorProfileRowById(database, args.creatorId);
      if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
      const updated = applyCreatorVerificationInTransaction(database, context.configuration, {
        creatorId: row.creator_id, verification: "REVOKED", reason: args.reason,
        actorDeveloperId: actor.developer_id,
      });
      return {
        result: { handle: updated.handle, verification: updated.verification_status },
        targetUserId: row.user_id,
        auditMetadata: { handle: updated.handle, verification: updated.verification_status },
      };
    },
  },
  suspendCreator: {
    audit: "suspend_creator", mutating: true, roles: MEMBERSHIP_TOOL_ROLES.WRITE,
    description: "Suspend a creator profile. Protected creator operations are refused and the profile leaves the public surface; every record is preserved and nothing is deleted.",
    schema: {
      type: "object",
      properties: { handle: { type: "string", minLength: 3, maxLength: 32 }, reason: { type: "string", minLength: 3, maxLength: 200 } },
      required: ["handle", "reason"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["handle", "reason"]);
      const row = getCreatorByHandle(database, input.handle);
      return { creatorId: row.creator_id, handle: row.handle, reason: validateReason(input.reason) };
    },
    validateResolved(database, args) {
      const row = creatorProfileRowById(database, args.creatorId);
      if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
      return { ...args, handle: row.handle, reason: validateReason(args.reason) };
    },
    summary(database, args) {
      return `Suspend the creator profile ${args.handle}. The profile, its history, and its account are preserved.`;
    },
    execute(database, args, actor, context) {
      const row = creatorProfileRowById(database, args.creatorId);
      if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
      const updated = applyCreatorStatusInTransaction(database, context.configuration, {
        creatorId: row.creator_id, status: "SUSPENDED", reason: args.reason,
        actorDeveloperId: actor.developer_id,
      });
      return {
        result: { handle: updated.handle, status: updated.status },
        targetUserId: row.user_id,
        auditMetadata: { handle: updated.handle, status: updated.status, reasonInAudit: true },
      };
    },
  },
  restoreCreator: {
    audit: "restore_creator", mutating: true, roles: MEMBERSHIP_TOOL_ROLES.WRITE,
    description: "Return a suspended or disabled creator profile to ACTIVE.",
    schema: {
      type: "object",
      properties: { handle: { type: "string", minLength: 3, maxLength: 32 }, reason: { type: "string", minLength: 3, maxLength: 200 } },
      required: ["handle", "reason"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["handle", "reason"]);
      const row = getCreatorByHandle(database, input.handle);
      return { creatorId: row.creator_id, handle: row.handle, reason: validateReason(input.reason) };
    },
    validateResolved(database, args) {
      const row = creatorProfileRowById(database, args.creatorId);
      if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
      return { ...args, handle: row.handle, reason: validateReason(args.reason) };
    },
    summary(database, args) {
      return `Restore the creator profile ${args.handle} to ACTIVE.`;
    },
    execute(database, args, actor, context) {
      const row = creatorProfileRowById(database, args.creatorId);
      if (!row) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
      const updated = applyCreatorStatusInTransaction(database, context.configuration, {
        creatorId: row.creator_id, status: "ACTIVE", reason: args.reason,
        actorDeveloperId: actor.developer_id,
      });
      return {
        result: { handle: updated.handle, status: updated.status },
        targetUserId: row.user_id,
        auditMetadata: { handle: updated.handle, status: updated.status },
      };
    },
  },

  // ---- Phase 23 server workspace controls -------------------------------------------------------------------------
  inspectServer: {
    audit: "inspect_server", mutating: false, roles: MEMBERSHIP_TOOL_ROLES.READ,
    description: "Inspect one server workspace by slug: status, owner, and member roles. No credential, pairing, or Minecraft connection data exists to return.",
    schema: {
      type: "object",
      properties: { slug: { type: "string", minLength: 3, maxLength: 32 } },
      required: ["slug"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["slug"]);
      const row = getServerBySlug(database, input.slug);
      return { serverId: row.server_id, slug: row.slug };
    },
    execute(database, args) {
      const row = database.prepare("SELECT * FROM server_workspaces WHERE server_id = ?").get(args.serverId);
      if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
      return {
        result: developerServerView(database, row),
        targetUserId: row.owner_user_id,
        auditMetadata: { slug: row.slug, status: row.status },
      };
    },
  },
  listServerWorkspaces: {
    audit: "list_server_workspaces", mutating: false, roles: MEMBERSHIP_TOOL_ROLES.READ,
    description: "List server workspaces, newest first, optionally filtered by status.",
    schema: {
      type: "object",
      properties: {
        limit: { type: "integer", minimum: 1, maximum: MAX_AUDIT_PAGE_SIZE },
        status: { type: "string", enum: ["ACTIVE", "SUSPENDED", "ARCHIVED"] },
      },
      additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, [], ["limit", "status"]);
      const limit = input.limit ?? 25;
      if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_AUDIT_PAGE_SIZE) {
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      if (input.status !== undefined && !isServerStatus(input.status)) {
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      return { limit, status: input.status ?? null };
    },
    execute(database, args) {
      const rows = database.prepare(
        `SELECT * FROM server_workspaces WHERE (? IS NULL OR status = ?)
          ORDER BY created_at DESC, server_id DESC LIMIT ?`,
      ).all(args.status, args.status, args.limit);
      const workspaces = rows.map((row) => ({
        slug: row.slug, displayName: row.display_name, status: row.status, createdAt: row.created_at,
      }));
      return {
        result: { workspaces, returned: workspaces.length, limit: args.limit },
        auditMetadata: { returned: workspaces.length, status: args.status ?? "ANY" },
      };
    },
  },
  suspendServer: {
    audit: "suspend_server", mutating: true, roles: MEMBERSHIP_TOOL_ROLES.WRITE,
    description: "Suspend a server workspace. Protected operations are refused; the workspace, its members, and its history are preserved.",
    schema: {
      type: "object",
      properties: { slug: { type: "string", minLength: 3, maxLength: 32 }, reason: { type: "string", minLength: 3, maxLength: 200 } },
      required: ["slug", "reason"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["slug", "reason"]);
      const row = getServerBySlug(database, input.slug);
      return { serverId: row.server_id, slug: row.slug, reason: validateReason(input.reason) };
    },
    validateResolved(database, args) {
      const row = database.prepare("SELECT * FROM server_workspaces WHERE server_id = ?").get(args.serverId);
      if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
      return { ...args, slug: row.slug, reason: validateReason(args.reason) };
    },
    summary(database, args) {
      return `Suspend the server workspace ${args.slug}. Nothing is deleted and the owner keeps the record.`;
    },
    execute(database, args, actor, context) {
      const row = database.prepare("SELECT * FROM server_workspaces WHERE server_id = ?").get(args.serverId);
      if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
      const updated = applyServerStatusInTransaction(database, context.configuration, {
        serverId: row.server_id, status: "SUSPENDED", reason: args.reason, actorDeveloperId: actor.developer_id,
      });
      return {
        result: { slug: updated.slug, status: updated.status },
        targetUserId: row.owner_user_id,
        auditMetadata: { slug: updated.slug, status: updated.status },
      };
    },
  },
  restoreServer: {
    audit: "restore_server", mutating: true, roles: MEMBERSHIP_TOOL_ROLES.WRITE,
    description: "Return a suspended workspace to ACTIVE.",
    schema: {
      type: "object",
      properties: { slug: { type: "string", minLength: 3, maxLength: 32 }, reason: { type: "string", minLength: 3, maxLength: 200 } },
      required: ["slug", "reason"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["slug", "reason"]);
      const row = getServerBySlug(database, input.slug);
      return { serverId: row.server_id, slug: row.slug, reason: validateReason(input.reason) };
    },
    validateResolved(database, args) {
      const row = database.prepare("SELECT * FROM server_workspaces WHERE server_id = ?").get(args.serverId);
      if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
      return { ...args, slug: row.slug, reason: validateReason(args.reason) };
    },
    summary(database, args) {
      return `Restore the server workspace ${args.slug} to ACTIVE.`;
    },
    execute(database, args, actor, context) {
      const row = database.prepare("SELECT * FROM server_workspaces WHERE server_id = ?").get(args.serverId);
      if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
      const updated = applyServerStatusInTransaction(database, context.configuration, {
        serverId: row.server_id, status: "ACTIVE", reason: args.reason, actorDeveloperId: actor.developer_id,
      });
      return {
        result: { slug: updated.slug, status: updated.status },
        targetUserId: row.owner_user_id,
        auditMetadata: { slug: updated.slug, status: updated.status },
      };
    },
  },
  archiveServer: {
    audit: "archive_server", mutating: true, roles: MEMBERSHIP_TOOL_ROLES.WRITE,
    description: "Archive a server workspace: closed to operation, history retained, nothing deleted.",
    schema: {
      type: "object",
      properties: { slug: { type: "string", minLength: 3, maxLength: 32 }, reason: { type: "string", minLength: 3, maxLength: 200 } },
      required: ["slug", "reason"], additionalProperties: false,
    },
    resolve(input, database) {
      strictObject(input, ["slug", "reason"]);
      const row = getServerBySlug(database, input.slug);
      return { serverId: row.server_id, slug: row.slug, reason: validateReason(input.reason) };
    },
    validateResolved(database, args) {
      const row = database.prepare("SELECT * FROM server_workspaces WHERE server_id = ?").get(args.serverId);
      if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
      return { ...args, slug: row.slug, reason: validateReason(args.reason) };
    },
    summary(database, args) {
      return `Archive the server workspace ${args.slug}. It stops operating and keeps every record.`;
    },
    execute(database, args, actor, context) {
      const row = database.prepare("SELECT * FROM server_workspaces WHERE server_id = ?").get(args.serverId);
      if (!row) throw new AccountApiError(ErrorCode.SERVER_NOT_FOUND);
      const updated = applyServerStatusInTransaction(database, context.configuration, {
        serverId: row.server_id, status: "ARCHIVED", reason: args.reason, actorDeveloperId: actor.developer_id,
      });
      return {
        result: { slug: updated.slug, status: updated.status },
        targetUserId: row.owner_user_id,
        auditMetadata: { slug: updated.slug, status: updated.status },
      };
    },
  },
});

/**
 * One registry, two families. Administrative tools keep the Phase 19 authority model (mutating actions stop at the
 * confirmation boundary); the Phase 20 Security Center adds read-only security tools. The automated security response
 * tools are a separate, policy-authorized registry the developer surface cannot reach.
 */
const TOOLS = Object.freeze({ ...ADMIN_TOOLS, ...SECURITY_CENTER_TOOLS });

function resolvedTargetUserId(args, database) {
  if (args?.userId) return args.userId;
  if (args?.grantId) return database.prepare("SELECT user_id FROM developer_access_grants WHERE grant_id = ?").get(args.grantId)?.user_id ?? null;
  return null;
}

function roleAllowed(actor, toolName) {
  return ALLOWED_ROLES[actor.role]?.has(toolName) ?? false;
}

function toolDefinition(toolName) {
  return Object.hasOwn(TOOLS, toolName) ? TOOLS[toolName] : null;
}

function auditDeniedUnknown(database, actor) {
  return audited(database, actor, "unknown_tool", () => {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_UNKNOWN);
  });
}

export function recordDeveloperToolRejection(database, actor, code = ErrorCode.DEVELOPER_TOOL_INPUT_INVALID) {
  return audited(database, actor, "unknown_tool", () => { throw new AccountApiError(code); });
}

export function developerToolSpecifications(actor) {
  return Object.entries(TOOLS)
    .filter(([name]) => roleAllowed(actor, name))
    .map(([name, tool]) => ({
      name,
      description: tool.description,
      inputSchema: structuredClone(tool.schema),
      requiresConfirmation: tool.mutating,
    }));
}

export function invokeDeveloperTool(database, configuration, actor, toolName, input, context = {}) {
  const tool = toolDefinition(toolName);
  if (!tool) return auditDeniedUnknown(database, actor);
  if (tool.mutating) return prepareDeveloperAction(database, configuration, actor, toolName, input);
  return audited(database, actor, tool.audit, (setTargetUserId) => {
    if (!tool.roles.includes(actor.role) || !roleAllowed(actor, toolName)) throw new AccountApiError(ErrorCode.DEVELOPER_ACCESS_DENIED);
    const args = tool.resolve(input ?? {}, database, context);
    const targetUserId = resolvedTargetUserId(args, database);
    setTargetUserId(targetUserId);
    const operation = tool.execute(database, args, actor, context);
    return { result: operation.result, targetUserId: operation.targetUserId ?? targetUserId, auditMetadata: operation.auditMetadata ?? {} };
  });
}

export function prepareDeveloperAction(database, configuration, actor, toolName, input) {
  const tool = toolDefinition(toolName);
  if (!tool) return auditDeniedUnknown(database, actor);
  if (!tool.mutating) {
    return audited(database, actor, tool.audit, () => {
      throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
    });
  }
  return audited(database, actor, tool.audit, (setTargetUserId) => {
    if (!tool.roles.includes(actor.role) || !roleAllowed(actor, toolName)) throw new AccountApiError(ErrorCode.DEVELOPER_ACCESS_DENIED);
    const args = tool.resolve(input ?? {}, database);
    const targetUserId = resolvedTargetUserId(args, database);
    setTargetUserId(targetUserId);
    if (typeof tool.validateResolved !== "function") throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
    const resolved = tool.validateResolved(database, args);
    const summary = tool.summary(database, resolved);
    const confirmationToken = newToken();
    const createdAt = nowIso();
    const expiresAt = new Date(Date.now() + configuration.developerConfirmationTtlSeconds * 1000).toISOString();
    database.prepare(
      `INSERT INTO developer_action_confirmations
         (confirmation_digest, developer_id, action_type, target_user_id, arguments_json, created_at, expires_at, consumed_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, NULL)`,
    ).run(
      developerTokenDigest(configuration.authSecret, "confirmation-v1", confirmationToken),
      actor.developer_id,
      tool.audit,
      targetUserId,
      JSON.stringify(resolved),
      createdAt,
      expiresAt,
    );
    return {
      result: { confirmationRequired: true, action: toolName, summary, confirmationToken, expiresAt },
      targetUserId,
      auditOutcome: "PREPARED",
      auditMetadata: { expiresAt },
    };
  });
}

function confirmationInput(body) {
  if (body === null || typeof body !== "object" || Array.isArray(body) || Object.keys(body).length !== 1 ||
      typeof body.confirmationToken !== "string" || body.confirmationToken.length < 32 || body.confirmationToken.length > 128) {
    throw new AccountApiError(ErrorCode.DEVELOPER_CONFIRMATION_INVALID);
  }
  return body.confirmationToken;
}

function auditConfirmationRejection(database, actor, code) {
  return audited(database, actor, "action_confirmation", () => { throw new AccountApiError(code); });
}

export function confirmDeveloperAction(database, configuration, actor, body) {
  let confirmationToken;
  try {
    confirmationToken = confirmationInput(body);
  } catch (error) {
    return auditConfirmationRejection(database, actor, safeFailure(error).code);
  }
  const digest = developerTokenDigest(configuration.authSecret, "confirmation-v1", confirmationToken);
  const confirmation = database.prepare(
    "SELECT * FROM developer_action_confirmations WHERE confirmation_digest = ? AND developer_id = ?",
  ).get(digest, actor.developer_id);
  if (!confirmation || !digestsMatch(confirmation?.confirmation_digest ?? "", digest) || confirmation.consumed_at !== null) {
    return auditConfirmationRejection(database, actor, ErrorCode.DEVELOPER_CONFIRMATION_INVALID);
  }
  if (isExpired(confirmation.expires_at)) {
    return audited(database, actor, confirmation.action_type, (setTargetUserId) => {
      setTargetUserId(confirmation.target_user_id);
      throw new AccountApiError(ErrorCode.DEVELOPER_CONFIRMATION_EXPIRED);
    }, {
      onFailure: () => database.prepare("UPDATE developer_action_confirmations SET consumed_at = ? WHERE confirmation_digest = ? AND consumed_at IS NULL")
        .run(nowIso(), digest),
    });
  }
  const tool = Object.values(TOOLS).find((candidate) => candidate.audit === confirmation.action_type && candidate.mutating);
  if (!tool) return auditConfirmationRejection(database, actor, ErrorCode.DEVELOPER_CONFIRMATION_INVALID);
  const toolName = Object.keys(TOOLS).find((name) => TOOLS[name] === tool);

  // The execution context is rebuilt here from the service configuration, so a confirmed action is validated and
  // executed against the same configured bounds as the original proposal.
  const context = { configuration };

  return audited(database, actor, confirmation.action_type, (setTargetUserId) => {
    setTargetUserId(confirmation.target_user_id);
    if (!tool.roles.includes(actor.role) || !roleAllowed(actor, toolName)) throw new AccountApiError(ErrorCode.DEVELOPER_ACCESS_DENIED);
    const latest = database.prepare(
      "SELECT * FROM developer_action_confirmations WHERE confirmation_digest = ? AND developer_id = ?",
    ).get(digest, actor.developer_id);
    if (!latest || latest.consumed_at !== null) throw new AccountApiError(ErrorCode.DEVELOPER_CONFIRMATION_INVALID);
    setTargetUserId(latest.target_user_id);
    if (isExpired(latest.expires_at)) {
      database.prepare("UPDATE developer_action_confirmations SET consumed_at = ? WHERE confirmation_digest = ?")
        .run(nowIso(), digest);
      throw new AccountApiError(ErrorCode.DEVELOPER_CONFIRMATION_EXPIRED);
    }
    let args;
    try { args = JSON.parse(latest.arguments_json); }
    catch { throw new AccountApiError(ErrorCode.DEVELOPER_CONFIRMATION_INVALID); }
    const validated = tool.validateResolved(database, args, context);
    const targetUserId = latest.target_user_id ?? resolvedTargetUserId(validated, database);
    database.prepare("UPDATE developer_action_confirmations SET consumed_at = ? WHERE confirmation_digest = ? AND consumed_at IS NULL")
      .run(nowIso(), digest);
    const operation = tool.execute(database, validated, actor, context);
    return { result: operation.result, targetUserId, auditMetadata: operation.auditMetadata ?? {} };
  }, {
    onFailure: () => database.prepare("UPDATE developer_action_confirmations SET consumed_at = ? WHERE confirmation_digest = ? AND developer_id = ? AND consumed_at IS NULL")
      .run(nowIso(), digest, actor.developer_id),
  });
}

export function cancelDeveloperAction(database, configuration, actor, body) {
  let confirmationToken;
  try { confirmationToken = confirmationInput(body); }
  catch (error) { return auditConfirmationRejection(database, actor, safeFailure(error).code); }
  const digest = developerTokenDigest(configuration.authSecret, "confirmation-v1", confirmationToken);
  const confirmation = database.prepare(
    "SELECT * FROM developer_action_confirmations WHERE confirmation_digest = ? AND developer_id = ?",
  ).get(digest, actor.developer_id);
  if (!confirmation || confirmation.consumed_at !== null || !digestsMatch(confirmation?.confirmation_digest ?? "", digest)) {
    return auditConfirmationRejection(database, actor, ErrorCode.DEVELOPER_CONFIRMATION_INVALID);
  }
  return audited(database, actor, confirmation.action_type, (setTargetUserId) => {
    setTargetUserId(confirmation.target_user_id);
    const changed = database.prepare(
      "UPDATE developer_action_confirmations SET consumed_at = ? WHERE confirmation_digest = ? AND developer_id = ? AND consumed_at IS NULL",
    ).run(nowIso(), digest, actor.developer_id);
    if (Number(changed.changes ?? 0) !== 1) throw new AccountApiError(ErrorCode.DEVELOPER_CONFIRMATION_INVALID);
    return { result: { cancelled: true }, targetUserId: confirmation.target_user_id, auditOutcome: "CANCELLED", auditMetadata: {} };
  });
}

export function recordDeveloperAiAudit(database, actor, outcome, metadata = {}) {
  return audited(database, actor, "developer_ai_turn", () => ({ result: true, auditOutcome: outcome, auditMetadata: metadata }));
}

export function developerAiToolCall(database, configuration, actor, proposedCall, context = {}) {
  if (!proposedCall || typeof proposedCall !== "object" || Array.isArray(proposedCall) ||
      Object.keys(proposedCall).length !== 2 || Object.keys(proposedCall).some((key) => !["name", "arguments"].includes(key)) ||
      !Object.hasOwn(proposedCall, "name") || typeof proposedCall.name !== "string" || !Object.hasOwn(proposedCall, "arguments")) {
    throw new AccountApiError(ErrorCode.DEVELOPER_AI_RESPONSE_INVALID);
  }
  return invokeDeveloperTool(database, configuration, actor, proposedCall.name, proposedCall.arguments, context);
}

export function entitlementKeys() {
  return [...GRANT_TYPES];
}

export { MAX_AUDIT_PAGE_SIZE, TOOLS as DEVELOPER_TOOLS };
