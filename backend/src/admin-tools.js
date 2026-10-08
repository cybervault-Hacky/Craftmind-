/**
 * Closed, server-authorized administrative action registry. Nothing here exposes a database handle to a caller or model.
 * State-changing actions are first stored as actor-bound, short-lived, single-use confirmation challenges.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { canonicalizeEmail, developerTokenDigest, digestsMatch, isWellFormedEmail, newAuditId, newGrantId, newToken } from "./ids.js";
import { ACCOUNT_STATUS } from "./db.js";

const GRANT_TYPES = new Set(["BETA_ACCESS", "PREVIEW_ACCESS", "PROMOTIONAL_ACCESS"]);
const MAX_AUDIT_PAGE_SIZE = 100;
const ALLOWED_ROLES = Object.freeze({
  OWNER: new Set([
    "overview", "inspectUser", "listUserSessions", "revokeUserSessions", "suspendUser", "restoreUser",
    "listEntitlements", "grantEntitlement", "revokeEntitlement", "listAuditLog", "configurationStatus",
  ]),
  ADMIN: new Set([
    "overview", "inspectUser", "listUserSessions", "revokeUserSessions", "suspendUser", "restoreUser",
    "listEntitlements", "grantEntitlement", "revokeEntitlement", "listAuditLog",
  ]),
  DEVELOPER: new Set(["overview", "inspectUser", "listUserSessions", "listEntitlements", "listAuditLog"]),
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

function appendAudit(database, actor, actionType, targetUserId, outcome, metadata = {}) {
  const encoded = JSON.stringify(metadata);
  if (encoded.length > 4096) throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  database.prepare(
    `INSERT INTO admin_audit_log
       (audit_id, actor_developer_id, action_type, target_user_id, occurred_at, outcome, metadata_json)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  ).run(newAuditId(), actor.developer_id, actionType, targetUserId ?? null, nowIso(), outcome, encoded);
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

function audited(database, actor, actionType, operation, { onFailure } = {}) {
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
    appendAudit(database, actor, actionType, targetUserId, outcome, metadata);
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

const TOOLS = Object.freeze({
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
        `SELECT l.action_type, l.occurred_at, l.outcome, l.metadata_json,
                actor.email AS actor_email, target.email AS target_email
           FROM admin_audit_log l
           JOIN developer_accounts actor ON actor.developer_id = l.actor_developer_id
           LEFT JOIN users target ON target.user_id = l.target_user_id
          ORDER BY l.occurred_at DESC, l.audit_id DESC LIMIT ?`,
      ).all(args.limit);
      const events = rows.map((row) => ({
        action: row.action_type,
        occurredAt: row.occurred_at,
        outcome: row.outcome,
        actorEmail: row.actor_email,
        targetEmail: row.target_email,
        metadata: JSON.parse(row.metadata_json),
      }));
      return { result: { events }, auditMetadata: { returned: events.length, limit: args.limit } };
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
});

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
    const validated = tool.validateResolved(database, args);
    const targetUserId = latest.target_user_id ?? resolvedTargetUserId(validated, database);
    database.prepare("UPDATE developer_action_confirmations SET consumed_at = ? WHERE confirmation_digest = ? AND consumed_at IS NULL")
      .run(nowIso(), digest);
    const operation = tool.execute(database, validated, actor);
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
