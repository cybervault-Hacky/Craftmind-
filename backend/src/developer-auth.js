/** Separate owner/developer identity, one-time bootstrap, and developer-only session lifecycle. */

import { createHmac, timingSafeEqual } from "node:crypto";
import { AccountApiError, ErrorCode } from "./errors.js";
import {
  canonicalizeEmail,
  developerTokenDigest,
  digestsMatch,
  isWellFormedEmail,
  newDeveloperId,
  newDeveloperSessionId,
  newAuditId,
  newToken,
} from "./ids.js";
import { hashPassword, performDummyVerification, validatePassword, verifyPassword } from "./passwords.js";

const DEVELOPER_ROLES = new Set(["OWNER", "ADMIN", "DEVELOPER"]);
const DEVELOPER_STATUSES = new Set(["ACTIVE", "SUSPENDED"]);
const SESSION_DEVICE_LABEL = "CraftMind developer dashboard";
const DUMMY_DEVELOPER_DIGEST = Buffer.alloc(32).toString("base64url");

function nowIso() {
  return new Date().toISOString();
}

function isoFromNow(seconds) {
  return new Date(Date.now() + seconds * 1000).toISOString();
}

function isPast(value) {
  const timestamp = Date.parse(value);
  return !Number.isFinite(timestamp) || timestamp <= Date.now();
}

function requireExactKeys(body, expected) {
  if (body === null || typeof body !== "object" || Array.isArray(body)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const keys = Object.keys(body).sort();
  const allowed = [...expected].sort();
  if (keys.length !== allowed.length || keys.some((key, index) => key !== allowed[index])) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
}

function requiredString(body, name, minimum = 1, maximum = 512) {
  const value = body?.[name];
  if (typeof value !== "string" || value.length < minimum || value.length > maximum) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  return value;
}

function publicDeveloper(row) {
  return {
    email: row.email,
    role: row.role,
    status: row.status,
    createdAt: row.created_at,
    lastLoginAt: row.last_login_at,
  };
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

function appendSessionAudit(database, developerId, scope, sessionId) {
  database.prepare(
    `INSERT INTO admin_audit_log
       (audit_id, actor_developer_id, action_type, target_user_id, occurred_at, outcome, metadata_json)
     VALUES (?, ?, 'developer_session_revoke', NULL, ?, 'SUCCESS', ?)`,
  ).run(newAuditId(), developerId, nowIso(), JSON.stringify({ scope, sessionId }));
}

function bootstrapSecretMatches(configuration, supplied) {
  const expectedDigest = createHmac("sha256", configuration.authSecret)
    .update("craftmind:developer-bootstrap:v1:", "utf8")
    .update(configuration.developerBootstrapSecret, "utf8")
    .digest();
  const suppliedDigest = createHmac("sha256", configuration.authSecret)
    .update("craftmind:developer-bootstrap:v1:", "utf8")
    .update(supplied, "utf8")
    .digest();
  return timingSafeEqual(expectedDigest, suppliedDigest);
}

/**
 * Creates the sole initial owner. The configured one-time secret is never stored, returned, or logged. The durable
 * consumed marker is written in the same transaction as the owner row so concurrent requests cannot create a second.
 */
export async function bootstrapDeveloper(database, configuration, body) {
  requireExactKeys(body, ["secret", "password"]);
  if (!configuration.developerBootstrapEmail || !configuration.developerBootstrapSecret) {
    throw new AccountApiError(ErrorCode.DEVELOPER_BOOTSTRAP_DISABLED);
  }
  const secret = requiredString(body, "secret", 1, 256);
  if (!bootstrapSecretMatches(configuration, secret)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_BOOTSTRAP_INVALID);
  }
  const password = requiredString(body, "password", 1, 256);
  if (!validatePassword(password).valid) throw new AccountApiError(ErrorCode.INVALID_PASSWORD);

  const passwordHash = await hashPassword(password);
  const developerId = newDeveloperId();
  const timestamp = nowIso();
  return runTransaction(database, () => {
    const consumed = database.prepare("SELECT singleton_id FROM developer_bootstrap_state WHERE singleton_id = 1").get();
    const developerCount = Number(database.prepare("SELECT COUNT(*) AS count FROM developer_accounts").get().count);
    if (consumed || developerCount !== 0) throw new AccountApiError(ErrorCode.DEVELOPER_BOOTSTRAP_CONSUMED);

    database.prepare(
      `INSERT INTO developer_accounts
         (developer_id, email, email_canonical, password_hash, role, status, created_at, updated_at, last_login_at)
       VALUES (?, ?, ?, ?, 'OWNER', 'ACTIVE', ?, ?, NULL)`,
    ).run(developerId, configuration.developerBootstrapEmail, canonicalizeEmail(configuration.developerBootstrapEmail), passwordHash, timestamp, timestamp);
    database.prepare(
      "INSERT INTO developer_bootstrap_state (singleton_id, consumed_at, developer_id) VALUES (1, ?, ?)",
    ).run(timestamp, developerId);
    const developer = database.prepare("SELECT * FROM developer_accounts WHERE developer_id = ?").get(developerId);
    return { developer: publicDeveloper(developer), bootstrapConsumed: true };
  });
}

function issueDeveloperSession(database, configuration, developerId) {
  const sessionId = newDeveloperSessionId();
  const accessToken = newToken();
  const refreshToken = newToken();
  const issuedAt = nowIso();
  const accessExpiresAt = isoFromNow(configuration.developerAccessTokenTtlSeconds);
  const refreshExpiresAt = isoFromNow(configuration.developerRefreshTokenTtlSeconds);
  database.prepare(
    `INSERT INTO developer_sessions
       (session_id, developer_id, access_digest, refresh_digest, issued_at, access_expires_at,
        refresh_expires_at, last_used_at, device_label, revoked_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)`,
  ).run(
    sessionId,
    developerId,
    developerTokenDigest(configuration.authSecret, "access-v1", accessToken),
    developerTokenDigest(configuration.authSecret, "refresh-v1", refreshToken),
    issuedAt,
    accessExpiresAt,
    refreshExpiresAt,
    issuedAt,
    SESSION_DEVICE_LABEL,
  );
  // Raw credentials are returned only by this authentication boundary and are never included in dashboard copy.
  return { accessToken, refreshToken, accessExpiresAt, refreshExpiresAt };
}

export async function loginDeveloper(database, configuration, body) {
  requireExactKeys(body, ["email", "password"]);
  const email = requiredString(body, "email", 1, 254).trim();
  const password = requiredString(body, "password", 1, 256);
  if (!isWellFormedEmail(email)) throw new AccountApiError(ErrorCode.INVALID_EMAIL);
  const developer = database.prepare("SELECT * FROM developer_accounts WHERE email_canonical = ?")
    .get(canonicalizeEmail(email));
  if (!developer) {
    await performDummyVerification(password);
    throw new AccountApiError(ErrorCode.DEVELOPER_INVALID_CREDENTIALS);
  }
  if (!(await verifyPassword(password, developer.password_hash)) || developer.status !== "ACTIVE") {
    throw new AccountApiError(ErrorCode.DEVELOPER_INVALID_CREDENTIALS);
  }

  const timestamp = nowIso();
  const session = runTransaction(database, () => {
    const updated = database.prepare(
      "UPDATE developer_accounts SET last_login_at = ?, updated_at = ? WHERE developer_id = ? AND status = 'ACTIVE' AND password_hash = ?",
    ).run(timestamp, timestamp, developer.developer_id, developer.password_hash);
    if (Number(updated.changes ?? 0) !== 1) throw new AccountApiError(ErrorCode.DEVELOPER_INVALID_CREDENTIALS);
    return issueDeveloperSession(database, configuration, developer.developer_id);
  });
  const current = database.prepare("SELECT * FROM developer_accounts WHERE developer_id = ?")
    .get(developer.developer_id);
  return { developer: publicDeveloper(current), session };
}

/** Resolves only a developer access digest; normal CraftMind user access tokens cannot match this table/namespace. */
export function authenticateDeveloper(database, configuration, accessToken) {
  if (typeof accessToken !== "string" || accessToken.length < 16 || accessToken.length > 512) {
    throw new AccountApiError(ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED);
  }
  const digest = developerTokenDigest(configuration.authSecret, "access-v1", accessToken);
  const session = database.prepare("SELECT * FROM developer_sessions WHERE access_digest = ?").get(digest);
  if (!session || !digestsMatch(session.access_digest ?? DUMMY_DEVELOPER_DIGEST, digest) || session.revoked_at !== null) {
    throw new AccountApiError(ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED);
  }
  if (isPast(session.access_expires_at)) throw new AccountApiError(ErrorCode.DEVELOPER_SESSION_EXPIRED);
  const developer = database.prepare("SELECT * FROM developer_accounts WHERE developer_id = ?").get(session.developer_id);
  if (!developer || developer.status !== "ACTIVE" || !DEVELOPER_ROLES.has(developer.role)) {
    database.prepare("UPDATE developer_sessions SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL")
      .run(nowIso(), session.session_id);
    throw new AccountApiError(ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED);
  }
  const lastUsedAt = nowIso();
  database.prepare("UPDATE developer_sessions SET last_used_at = ? WHERE session_id = ? AND revoked_at IS NULL")
    .run(lastUsedAt, session.session_id);
  session.last_used_at = lastUsedAt;
  return { developer, session };
}

export function currentDeveloper(database, configuration, accessToken) {
  const { developer, session } = authenticateDeveloper(database, configuration, accessToken);
  return {
    developer: publicDeveloper(developer),
    session: {
      createdAt: session.issued_at,
      lastUsedAt: session.last_used_at,
      expiresAt: session.access_expires_at,
      deviceLabel: session.device_label,
    },
  };
}

export function refreshDeveloperSession(database, configuration, body) {
  requireExactKeys(body, ["refreshToken"]);
  const refreshToken = requiredString(body, "refreshToken", 32, 128);
  const presentedDigest = developerTokenDigest(configuration.authSecret, "refresh-v1", refreshToken);
  const current = database.prepare("SELECT * FROM developer_sessions WHERE refresh_digest = ?").get(presentedDigest);
  if (!current || !digestsMatch(current.refresh_digest ?? DUMMY_DEVELOPER_DIGEST, presentedDigest) || current.revoked_at !== null) {
    throw new AccountApiError(ErrorCode.DEVELOPER_REFRESH_FAILED);
  }
  if (isPast(current.refresh_expires_at)) throw new AccountApiError(ErrorCode.DEVELOPER_REFRESH_FAILED);
  const developer = database.prepare("SELECT * FROM developer_accounts WHERE developer_id = ?").get(current.developer_id);
  if (!developer || developer.status !== "ACTIVE" || !DEVELOPER_ROLES.has(developer.role)) {
    database.prepare("UPDATE developer_sessions SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL")
      .run(nowIso(), current.session_id);
    throw new AccountApiError(ErrorCode.DEVELOPER_REFRESH_FAILED);
  }

  const accessToken = newToken();
  const nextRefreshToken = newToken();
  const timestamp = nowIso();
  const accessExpiresAt = isoFromNow(configuration.developerAccessTokenTtlSeconds);
  const refreshExpiresAt = isoFromNow(configuration.developerRefreshTokenTtlSeconds);
  const rotated = database.prepare(
    `UPDATE developer_sessions
        SET access_digest = ?, refresh_digest = ?, access_expires_at = ?, refresh_expires_at = ?, last_used_at = ?
      WHERE session_id = ? AND refresh_digest = ? AND revoked_at IS NULL AND refresh_expires_at > ?
        AND EXISTS (SELECT 1 FROM developer_accounts d WHERE d.developer_id = developer_sessions.developer_id
                    AND d.status = 'ACTIVE' AND d.role IN ('OWNER', 'ADMIN', 'DEVELOPER'))`,
  ).run(
    developerTokenDigest(configuration.authSecret, "access-v1", accessToken),
    developerTokenDigest(configuration.authSecret, "refresh-v1", nextRefreshToken),
    accessExpiresAt,
    refreshExpiresAt,
    timestamp,
    current.session_id,
    presentedDigest,
    timestamp,
  );
  if (Number(rotated.changes ?? 0) !== 1) throw new AccountApiError(ErrorCode.DEVELOPER_REFRESH_FAILED);
  return { developer: publicDeveloper(developer), session: { accessToken, refreshToken: nextRefreshToken, accessExpiresAt, refreshExpiresAt } };
}

export function logoutDeveloper(database, configuration, accessToken) {
  const { developer, session } = authenticateDeveloper(database, configuration, accessToken);
  return runTransaction(database, () => {
    const revoked = database.prepare("UPDATE developer_sessions SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL")
      .run(nowIso(), session.session_id);
    if (Number(revoked.changes ?? 0) !== 1) throw new AccountApiError(ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED);
    appendSessionAudit(database, developer.developer_id, "current_session", session.session_id);
    return { signedOut: true };
  });
}

export function listDeveloperSessions(database, configuration, accessToken) {
  const { developer, session: current } = authenticateDeveloper(database, configuration, accessToken);
  const timestamp = nowIso();
  const sessions = database.prepare(
    `SELECT session_id, issued_at, last_used_at, access_expires_at, refresh_expires_at, device_label
       FROM developer_sessions
      WHERE developer_id = ? AND revoked_at IS NULL AND refresh_expires_at > ?
      ORDER BY issued_at DESC`,
  ).all(developer.developer_id, timestamp);
  return {
    sessions: sessions.map((session) => ({
      sessionId: session.session_id,
      createdAt: session.issued_at,
      lastUsedAt: session.last_used_at,
      expiresAt: session.refresh_expires_at,
      deviceLabel: session.device_label,
      isCurrent: session.session_id === current.session_id,
    })),
  };
}

export function revokeDeveloperSession(database, configuration, accessToken, body) {
  requireExactKeys(body, ["sessionId"]);
  const sessionId = requiredString(body, "sessionId", 40, 48);
  if (!/^dvs_[0-9a-f-]{36}$/.test(sessionId)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const { developer, session: current } = authenticateDeveloper(database, configuration, accessToken);
  if (sessionId === current.session_id) throw new AccountApiError(ErrorCode.DEVELOPER_ACCESS_DENIED);
  const target = database.prepare("SELECT session_id, revoked_at FROM developer_sessions WHERE session_id = ? AND developer_id = ?")
    .get(sessionId, developer.developer_id);
  if (!target) throw new AccountApiError(ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED);
  if (target.revoked_at === null) {
    runTransaction(database, () => {
      const revoked = database.prepare("UPDATE developer_sessions SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL")
        .run(nowIso(), target.session_id);
      if (Number(revoked.changes ?? 0) !== 1) throw new AccountApiError(ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED);
      appendSessionAudit(database, developer.developer_id, "other_session", target.session_id);
    });
  }
  return { revoked: target.revoked_at === null };
}

export function cleanupExpiredDeveloperRecords(database, nowMillis = Date.now()) {
  const now = new Date(nowMillis).toISOString();
  const retentionCutoff = new Date(nowMillis - 30 * 24 * 60 * 60 * 1000).toISOString();
  const sessions = database.prepare(
    "DELETE FROM developer_sessions WHERE refresh_expires_at <= ? OR (revoked_at IS NOT NULL AND revoked_at <= ?)",
  ).run(now, retentionCutoff);
  const confirmations = database.prepare(
    "DELETE FROM developer_action_confirmations WHERE expires_at <= ?",
  ).run(retentionCutoff);
  return { sessions: Number(sessions.changes ?? 0), confirmations: Number(confirmations.changes ?? 0) };
}

export const DEVELOPER_ROLE_VALUES = Object.freeze([...DEVELOPER_ROLES]);
export const DEVELOPER_STATUS_VALUES = Object.freeze([...DEVELOPER_STATUSES]);
